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
  class BackoffException(val nextAttemptAt: Long, val streak: Int) : Exception("released for backoff")

  class ExpiredException : Exception("expired before completion")

  /** The queue was paused between the module's pause and the work cancel reaching us. */
  class PausedException : Exception("queue paused")

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
    if (ops.settings().paused) return
    if (!waitUntilDue(initial)) return
    val entry = ops.begin(entryId) ?: return
    generation = entry.generation

    var current = entry
    var first = true
    while (true) {
      try {
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
        ops.release(entryId, generation, backoff.nextAttemptAt, backoff.streak)
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
        if (host.stoppedByTimeout()) releaseAfterTimeout() else ops.stopped(entryId, generation)
        throw error
      } catch (error: IOException) {
        // A store write failed (disk full, directory briefly unwritable).
        // The attempt step classifies every network IOException itself, so
        // one that lands here is storage: transient, no outcome. Back to
        // queued; the caller returns retry.
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
   * The system stopped the run at its time limit. That happens to a
   * headless run whose foreground start was denied (API 31+), after about
   * 10 minutes. Starting again at once would send the body from byte 0 on
   * every run, so this is one more transient failure: the next backoff
   * step, then a wake.
   */
  private fun releaseAfterTimeout() {
    runCatching {
      val e = store.load(entryId)
      if (!EntryTransitions.isOwnedRun(e, generation)) return
      val streak = e!!.backoffStreak + 1
      val now = host.now()
      val backoff = RetryClassifier.backoffMs(policy(e), streak)
      ops.release(entryId, generation, RetryClassifier.nextAttemptAt(now, backoff, e.expiresAt), streak)
    }.onFailure { Diag.error("could not release '$entryId' after a timeout stop", it) }
  }

  /**
   * Sleeps out a short backoff remainder. False when the wait is long (the
   * wake run comes back for it) or the entry is no longer queued.
   */
  private suspend fun waitUntilDue(initial: QueueEntry): Boolean {
    var e = initial
    while (true) {
      val at = e.nextAttemptAt ?: return true
      val remaining = at - host.now()
      if (remaining <= 0) return true
      if (remaining > RetryClassifier.IN_WORKER_BACKOFF_MAX_MS) return false
      host.sleep(min(remaining, WAIT_POLL_MS))
      e = store.load(entryId) ?: return false
      if (e.state != EntryState.QUEUED && e.state != EntryState.RUNNING) return false
    }
  }

  // MARK: - helpers for the transfers

  /**
   * Waits until the network fits the queue's wifi-only setting. Re-reads
   * the settings and the entry at every poll.
   */
  suspend fun waitForNetwork() {
    while (true) {
      val s = ops.settings()
      if (s.paused) throw PausedException()
      val entry = ops.latest(entryId, generation)
      if (RetryClassifier.isExpired(host.now(), entry.expiresAt)) throw ExpiredException()
      if (host.connectivity(s.wifiOnly) == Connectivity.Ok) return
      host.sleep(CONNECTIVITY_POLL_MS)
    }
  }

  fun policy(entry: QueueEntry) =
    RetryClassifier.policy(ops.settings().retry, entry.descriptor?.retry)

  /**
   * One HTTP attempt, the step both transfers share: write the attempt
   * ahead (attempts + 1, its X-Request-Id), send, emit the attempt event,
   * and classify. [partIndex] is null for a simple entry. [body] builds the
   * request body from the entry the attempt runs under. [fileExists] tells a
   * missing payload from a network failure.
   */
  suspend fun attempt(
    partIndex: Int?,
    body: (Descriptor, Part?) -> RequestBody?,
    onProgress: (Long) -> Unit,
    fileExists: () -> Boolean,
  ): Attempt {
    val requestId = UUID.randomUUID().toString()
    val entry = ops.recordAttempt(entryId, generation, requestId)
    // The generation of the headers this attempt sends: both come from the same entry.
    val headerGeneration = entry.headerGeneration
    val d = entry.descriptor!!
    val part = partIndex?.let { d.parts!![it] }
    val url = part?.url ?: d.url!!
    val policy = policy(entry)

    val response = try {
      host.send(TransferRequest(url, d.method, headersFor(d, part, requestId), body(d, part)), onProgress)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
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
      return Attempt(entry, url, policy, result)
    }

    val verdict = RetryClassifier.classifyResponse(response.code, response.body, d.accept, policy.exempt)
    host.attempt(
      AttemptEvent.ofResponse(
        entry, requestId, url, partIndex, response, verdict == RetryClassifier.Verdict.Accepted, host.now(),
      ),
    )
    val result = when (verdict) {
      RetryClassifier.Verdict.Accepted -> AttemptResult.Accepted(response)
      RetryClassifier.Verdict.Auth ->
        AttemptResult.Auth(headerGeneration, ops.hasNewerHeaders(entryId, generation, headerGeneration))
      RetryClassifier.Verdict.Transient -> AttemptResult.Transient
      is RetryClassifier.Verdict.Terminal -> AttemptResult.Terminal("http", verdict.message, response)
    }
    return Attempt(entry, url, policy, result)
  }

  /**
   * A short backoff waits here, with the row still running and showing
   * nextAttemptAt; a long one throws [BackoffException] to release the run.
   */
  suspend fun backoffOrRelease(policy: RetryClassifier.Policy, streak: Int, expiresAt: Long) {
    val backoff = RetryClassifier.backoffMs(policy, streak)
    val now = host.now()
    if (backoff <= RetryClassifier.IN_WORKER_BACKOFF_MAX_MS) {
      val wait = min(backoff, max(0L, expiresAt - now))
      ops.backingOff(entryId, generation, now + wait)
      host.sleep(wait)
      return
    }
    throw BackoffException(RetryClassifier.nextAttemptAt(now, backoff, expiresAt), streak)
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
