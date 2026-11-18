package ai.openspace.backgroundupload

import kotlinx.coroutines.CancellationException
import okhttp3.RequestBody
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * The run of one queue entry, and the one attempt step both transfers use.
 * No Android types: [EntryWorker] is the shell that holds the per-id gate
 * and is the real [TransferHost].
 *
 * The run: take the entry (queued → running), run the transfer, then
 * settle, park, or release. The body kind at run time picks the transfer,
 * [SimpleTransfer] or [ChunkedTransfer], so a kind change under a queued run
 * is safe. The entry is read from the store at start and again before every
 * attempt, so fresh headers and a new expiresAt reach a running run.
 */
internal class EntryRun(
  val entryId: String,
  val store: QueueStore,
  val ops: WorkerOps,
  val host: TransferHost,
) {
  /** A 401/403 under the headers of [headerGeneration] (the entry's value the attempt sent). */
  class ParkException(val headerGeneration: Int) : Exception("awaiting auth")

  /** A backoff too long to wait here. */
  class BackoffException(
    val nextAttemptAt: Long,
    val streak: Int,
    /** [WaitCause] of the failure. */
    val waitCause: String,
    /** The chunked part whose failure it was; null for a simple entry. */
    val partStart: Long?,
  ) : Exception("released for backoff")

  class ExpiredException : Exception("expired before completion")

  /** The entry was paused (whole queue or its key) between the module's pause and the work cancel reaching us. */
  class PausedException : Exception("paused")

  /**
   * A chunked run ended with [indexes] not accepted, and no part failed.
   * An IOException, so the run takes the store-failure path: back to queued
   * with no outcome, and the worker returns retry.
   */
  class PartsNotAcceptedException(val indexes: List<Int>) :
    IOException("chunked run ended with parts $indexes not accepted")

  /** How one attempt ended, after the retry table. */
  sealed class AttemptResult {
    data class Accepted(val response: UploadResponse) : AttemptResult()

    /** A 401/403. [reissue]: newer headers arrived while it was in flight, so send again now. */
    data class Auth(val headerGeneration: Int, val reissue: Boolean) : AttemptResult()

    object Transient : AttemptResult()

    data class Terminal(val errorKind: String, val message: String, val response: UploadResponse?) : AttemptResult()
  }

  /** One attempt: the entry it ran under, where it went, the retry policy, and how it ended. */
  class Attempt(
    val entry: QueueEntry,
    val url: String,
    val policy: RetryClassifier.Policy,
    val result: AttemptResult,
    /** [WaitCause]: whether the server answered. */
    val cause: String,
  ) {
    val method: String get() = entry.descriptor!!.method
  }

  companion object {
    /** The poll while the network is unusable (offline, or waiting for wifi). */
    const val CONNECTIVITY_POLL_MS = 10_000L

    /** The poll while a short backoff remainder runs out before the run starts. */
    private const val WAIT_POLL_MS = 10_000L

    /** The descriptor's headers, the part's over them, and X-Request-Id over all. */
    fun headersFor(d: Descriptor, part: Part?, requestId: String): Map<String, String> {
      val withPart = if (part == null) d.headers else HeaderMap.merge(d.headers, part.headers)
      return HeaderMap.merge(withPart, mapOf("X-Request-Id" to requestId))
    }
  }

  var generation = 0
    private set

  /** The bytes of the current attempt, as last reported. A failed simple entry settles with them. */
  @Volatile
  var liveBytes = 0L
    private set

  /** A cancel reached a request after its write-ahead. Chunked parts set it from their own coroutines. */
  @Volatile
  private var stopCutRequest = false

  /**
   * Returns when the run is over. Throws a CancellationException after it
   * handled a stop, or an IOException after a store write failed (the
   * caller returns retry; nothing settled).
   */
  suspend fun run() {
    val initial = store.load(entryId) ?: return // forgotten while queued
    if (initial.legacy) return
    if (initial.state == EntryState.AWAITING_AUTH) {
      // The expiry wake of a parked entry. No attempt ran here, so the
      // stored bytes stand.
      if (RetryClassifier.isExpired(host.now(), initial.expiresAt)) {
        ops.settle(entryId, initial.generation, expired(initial).copy(bytesSent = null))
      }
      return
    }
    if (initial.state != EntryState.QUEUED && initial.state != EntryState.RUNNING) return
    if (ops.settings().isPaused(initial.key)) return
    if (!waitUntilDue(initial)) return
    val entry = ops.begin(entryId) ?: return
    generation = entry.generation

    var current = entry
    var first = true
    while (true) {
      try {
        // A part cancelled by a sibling's park in the last pass does not count.
        stopCutRequest = false
        if (!first) current = ops.latest(entryId, generation)
        first = false
        host.foreground(current)
        val settlement = transfer(current)
        endProgress(completed = settlement is Settlement.Completed)
        ops.settle(entryId, generation, settlement)
        return
      } catch (park: ParkException) {
        endProgress(completed = false)
        // REISSUE: updateHeaders() landed while this attempt was in flight.
        if (ops.park(entryId, generation, park.headerGeneration) != WorkerOps.ParkResult.REISSUE) return
      } catch (backoff: BackoffException) {
        endProgress(completed = false)
        ops.release(entryId, generation, backoff.nextAttemptAt, backoff.streak, cause = backoff.waitCause, partStart = backoff.partStart)
        return
      } catch (error: ExpiredException) {
        endProgress(completed = false)
        ops.settle(entryId, generation, expired(current))
        return
      } catch (error: NotOwnedException) {
        endProgress(completed = false)
        return
      } catch (error: PausedException) {
        endProgress(completed = false)
        return
      } catch (error: CancellationException) {
        // A system stop moves a running entry back to queued. A pause or a
        // cancel already moved it; then this does nothing.
        endProgress(completed = false)
        afterStop(host.stopKind())
        throw error
      } catch (error: IOException) {
        // A store write failed (disk full, directory briefly unwritable), or
        // a chunked run ended with a part not accepted. The attempt step
        // classifies every network IOException itself, so one that lands here
        // is not a network failure: transient, no outcome. Back to queued;
        // the caller returns retry.
        endProgress(completed = false)
        ops.stopped(entryId, generation)
        throw error
      } catch (error: Throwable) {
        endProgress(completed = false)
        val d = current.descriptor
        ops.settle(
          entryId,
          generation,
          Settlement.Failed(
            errorKind = "unknown",
            message = error.message ?: error.javaClass.simpleName,
            response = null,
            partIndex = null,
            url = d?.reportUrl ?: "",
            method = d?.method ?: "POST",
            bytesSent = liveBytesOf(current),
          ),
        )
        return
      }
    }
  }

  private suspend fun transfer(entry: QueueEntry): Settlement =
    if (entry.body?.kind == StagedBody.CHUNKED) ChunkedTransfer(this).run(entry)
    else SimpleTransfer(this).run(entry)

  private fun liveBytesOf(entry: QueueEntry): Long? =
    if (entry.body?.kind == StagedBody.CHUNKED) null else liveBytes

  private fun expired(entry: QueueEntry) = Settlement.Failed(
    errorKind = "expired",
    message = "expired before completion",
    response = null,
    partIndex = null,
    url = entry.descriptor?.reportUrl ?: "",
    method = entry.descriptor?.method ?: "POST",
    bytesSent = liveBytesOf(entry),
  )

  /**
   * A system stop. Only a time-limit stop that cut a request is a failed
   * attempt. A run that only waited (for the network, a slot, or a short
   * backoff) sent nothing, so it goes back to queued with no backoff step.
   *
   * After a time-limit stop a fresh wake row starts the entry again, unless
   * WorkManager starts the stopped row again at once. WorkManager runs the
   * stopped row again after its own backoff, which grows with every start
   * of that row ([WorkManagerBackoff]). For the first few starts that wait
   * is already over at the stop; a wake then would be a second row for the
   * entry, and one of the two would wait at the per-id gate for the whole
   * upload. The other stop reasons say nothing about the server, and the
   * system decides when their row runs again.
   *
   * The state change is saved first, then the diagnostic record.
   */
  private fun afterStop(kind: StopKind) {
    when {
      kind != StopKind.TIME_LIMIT -> ops.stopped(entryId, generation)
      stopCutRequest -> releaseAfterTimeout(wake = !stoppedRowRestartsNow())
      else -> ops.stopped(entryId, generation, wake = !stoppedRowRestartsNow())
    }
    if (kind.logged) Diag.countStop(kind.name, entryId)
  }

  /** WorkManager starts the stopped row again at once. False when unknown. */
  private fun stoppedRowRestartsNow(): Boolean {
    val by = host.rowRestartsBy() ?: return false
    return by <= host.now()
  }

  /**
   * The system stopped the run at its time limit while a request was on its
   * way. That happens to a run with no foreground mode after about 10
   * minutes, or to a foreground run whose service type used up its time.
   * Starting again at once would send the body from byte 0 on every run, so
   * this is one more transient failure: the next backoff step, then a wake.
   * With [wake] false the stopped row, which WorkManager starts again at
   * once, sleeps out a short wait or makes the wake itself ([waitUntilDue]).
   */
  private fun releaseAfterTimeout(wake: Boolean) {
    runCatching {
      val e = store.load(entryId)
      if (!EntryTransitions.isOwnedRun(e, generation)) return
      val streak = e!!.backoffStreak + 1
      val now = host.now()
      val backoff = RetryClassifier.backoffMs(policy(e), streak)
      // A cut request has no response. Which part it cut is not known.
      ops.release(
        entryId, generation, RetryClassifier.nextAttemptAt(now, backoff, e.expiresAt), streak, wake,
        cause = WaitCause.NO_RESPONSE, partStart = null,
      )
    }.onFailure {
      if (it is WorkerOps.WakeNotScheduledException) {
        Diag.error("released '$entryId' after a timeout stop, but could not schedule its wake", it.cause)
      } else {
        Diag.error("could not release '$entryId' after a timeout stop", it)
      }
    }
  }

  /**
   * Sleeps out a short backoff remainder. False when the wait is long (the
   * wake run comes back for it) or the entry is no longer queued. For a long
   * wait it first makes sure a wake is pending, so a lost wake does not
   * leave the entry waiting for the next boot sweep.
   */
  private suspend fun waitUntilDue(initial: QueueEntry): Boolean {
    var e = initial
    while (true) {
      val at = e.nextAttemptAt ?: return true
      val remaining = at - host.now()
      if (remaining <= 0) return true
      if (remaining > RetryClassifier.IN_WORKER_BACKOFF_MAX_MS) {
        if (e.state == EntryState.QUEUED) ops.ensureWake(e, at)
        return false
      }
      host.sleep(min(remaining, WAIT_POLL_MS))
      e = store.load(entryId) ?: return false
      if (e.state != EntryState.QUEUED && e.state != EntryState.RUNNING) return false
    }
  }

  // MARK: - helpers for the transfers

  /**
   * Waits until the network fits the entry's Wi-Fi rule: its own wifiOnly,
   * else the queue's setting. Re-reads the settings and the entry at every
   * poll, so a setWifiOnly() toggle reaches an entry that follows it.
   */
  suspend fun waitForNetwork() {
    while (true) {
      val s = ops.settings()
      val entry = ops.latest(entryId, generation)
      if (s.isPaused(entry.key)) throw PausedException()
      if (RetryClassifier.isExpired(host.now(), entry.expiresAt)) throw ExpiredException()
      if (host.connectivity(s.wifiOnlyFor(entry)) == Connectivity.Ok) return
      host.sleep(CONNECTIVITY_POLL_MS)
    }
  }

  fun policy(entry: QueueEntry) =
    RetryClassifier.policy(ops.settings().retry, entry.descriptor?.retry)

  /**
   * One HTTP attempt, the step both transfers share: take a transmit slot,
   * write the attempt ahead (attempts + 1, its X-Request-Id), send, emit the
   * attempt event, and classify. [partIndex] is null for a simple entry.
   * [body] builds the request body from the entry the attempt runs under.
   * [fileExists] tells a missing payload from a network failure.
   *
   * The write-ahead happens inside the slot. A run that only waits for a
   * slot sends nothing, so it counts no attempt, and the request takes the
   * headers that are current when it starts to send.
   */
  suspend fun attempt(
    partIndex: Int?,
    body: (Descriptor, Part?) -> RequestBody?,
    onProgress: (Long) -> Unit,
    fileExists: () -> Boolean,
  ): Attempt {
    val requestId = UUID.randomUUID().toString()
    val highPriority = ops.latest(entryId, generation).descriptor!!.highPriority
    var written: QueueEntry? = null
    var failure: Throwable? = null
    val response = try {
      host.withSlot(highPriority) {
        val e = ops.recordAttempt(entryId, generation, requestId)
        written = e
        val d = e.descriptor!!
        val part = partIndex?.let { d.parts!![it] }
        host.send(TransferRequest(part?.url ?: d.url!!, d.method, headersFor(d, part, requestId), body(d, part)), onProgress)
      }
    } catch (error: CancellationException) {
      // A stop after the write-ahead cut a request that was on its way.
      if (written != null) stopCutRequest = true
      throw error
    } catch (error: Throwable) {
      // With no write-ahead, the store write failed: not a network attempt.
      if (written == null) throw error
      failure = error
      null
    }

    val entry = written!!
    // The generation of the headers this attempt sends: both come from the same entry.
    val headerGeneration = entry.headerGeneration
    val d = entry.descriptor!!
    val part = partIndex?.let { d.parts!![it] }
    val url = part?.url ?: d.url!!
    val policy = policy(entry)

    if (response == null) {
      val error = failure!!
      // A failed probe reads as present, so a flaky check is a retryable
      // network error and not a terminal "file gone".
      val verdict = RetryClassifier.classifyFailure(error, runCatching(fileExists).getOrDefault(true))
      host.attempt(
        AttemptEvent.ofFailure(
          entry, requestId, url, partIndex, RetryClassifier.failureKind(verdict),
          error.message ?: error.javaClass.simpleName, host.now(),
        ),
      )
      val result = if (verdict is RetryClassifier.Verdict.Terminal) {
        AttemptResult.Terminal(verdict.errorKind, verdict.message, null)
      } else AttemptResult.Transient
      // The attempt started on a validated network (waitForNetwork). A call
      // whose every try failed while it connected waits at most 15 minutes;
      // any other failure keeps the entry's maximum.
      val waitPolicy = if (RetryClassifier.reachedNoServer(error)) RetryClassifier.transportFailurePolicy(policy) else policy
      return Attempt(entry, url, waitPolicy, result, WaitCause.NO_RESPONSE)
    }

    // A part counts on its own, so each part gets a rule's full maxRetries.
    val hits = (if (part != null) part.transientHitsByRule else entry.transientHitsByRule) ?: emptyMap()
    var verdict = RetryClassifier.classifyResponse(
      response.code, response.body, d.accept, policy.exempt, policy.transient, hits,
    )
    val rule = (verdict as? RetryClassifier.Verdict.Transient)?.rule
    if (rule != null) {
      // The snapshot above can be stale; the store decides the cap exactly.
      if (!ops.recordTransientHit(entryId, generation, part?.start, rule)) {
        verdict = RetryClassifier.Verdict.Terminal("http", "HTTP ${response.code}")
      }
    }
    host.attempt(
      AttemptEvent.ofResponse(
        entry, requestId, url, partIndex, response, verdict == RetryClassifier.Verdict.Accepted, host.now(),
      ),
    )
    val result = when (verdict) {
      RetryClassifier.Verdict.Accepted -> AttemptResult.Accepted(response)
      RetryClassifier.Verdict.Auth ->
        AttemptResult.Auth(headerGeneration, ops.hasNewerHeaders(entryId, generation, headerGeneration))
      is RetryClassifier.Verdict.Transient -> AttemptResult.Transient
      is RetryClassifier.Verdict.Terminal -> AttemptResult.Terminal("http", verdict.message, response)
    }
    return Attempt(entry, url, policy, result, WaitCause.RESPONSE)
  }

  /**
   * A short backoff waits here, with the row still running and showing
   * nextAttemptAt; a long one throws [BackoffException] to release the run.
   */
  suspend fun backoffOrRelease(
    policy: RetryClassifier.Policy,
    streak: Int,
    expiresAt: Long,
    cause: String,
    partStart: Long? = null,
  ) {
    val backoff = RetryClassifier.backoffMs(policy, streak)
    val now = host.now()
    if (backoff <= RetryClassifier.IN_WORKER_BACKOFF_MAX_MS) {
      val wait = min(backoff, max(0L, expiresAt - now))
      ops.backingOff(entryId, generation, now + wait, streak, cause, partStart)
      host.sleep(wait)
      return
    }
    throw BackoffException(RetryClassifier.nextAttemptAt(now, backoff, expiresAt), streak, cause, partStart)
  }

  fun progressStarted(total: Long, sent: Long) {
    liveBytes = sent
    host.progressStarted(entryId, total, sent)
  }

  fun reportProgress(sent: Long, total: Long) {
    liveBytes = sent
    host.progress(entryId, sent, total)
  }

  private fun endProgress(completed: Boolean) = host.progressEnded(entryId, completed)
}
