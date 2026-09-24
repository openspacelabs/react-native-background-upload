package ai.openspace.backgroundupload

import android.app.NotificationManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/**
 * Runs one queue entry. The input data holds only the entry id; the worker
 * reads the entry from the store at start and again before every attempt,
 * so fresh headers and a new expiresAt reach a running worker with no
 * restart.
 *
 * The run: acquire the per-id gate, take the entry (queued → running), run
 * the transfer, then settle, park, or release. The body kind picks the
 * transfer: [SimpleTransfer] or [ChunkedTransfer]. [UploadWorker] and
 * [ChunkedUploadWorker] are the two class names WorkManager knows; both run
 * this same code, so a kind change under a queued run is safe.
 *
 * Every run returns success (see [WorkManagerScheduler] for why). A v9 row,
 * which has no entry id, exits at once in silence.
 */
open class EntryWorker(protected val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  companion object {
    private const val GATE_POLL_MS = 100L
    /** The poll while the network is unusable (offline, or waiting for wifi). */
    const val CONNECTIVITY_POLL_MS = 10_000L
    /** The poll while a short backoff remainder runs out before the run starts. */
    private const val WAIT_POLL_MS = 10_000L
  }

  /** A 401/403 under the headers of [headerGeneration] (the entry's value the attempt sent). */
  class ParkException(val headerGeneration: Int) : Exception("awaiting auth")

  /** A backoff too long to wait here. */
  class BackoffException(val nextAttemptAt: Long, val streak: Int) : Exception("released for backoff")

  class ExpiredException : Exception("expired before completion")

  /** The queue was paused between the module's pause and the work cancel reaching us. */
  class PausedException : Exception("queue paused")

  internal lateinit var entryId: String
  internal var generation = 0
  internal val store by lazy { QueueStore.get(context) }
  internal val ops by lazy {
    WorkerOps(
      store,
      EventJournal.get(context),
      QueueSettingsStore.get(context),
      EventReporter,
      WorkManagerScheduler(context),
    )
  }
  private val config by lazy { NotificationConfig.load(context) }
  private val notificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  @Volatile
  private var connectivity = Connectivity.Ok

  @Volatile
  private var showsNotification = false

  final override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val id = inputData.getString(WorkManagerScheduler.ENTRY_ID_KEY) ?: return@withContext Result.success()
    entryId = id
    // Acquire before the first store read: a cancel-then-enqueue can start
    // this run while the old one still winds down.
    while (!WorkerGate.tryAcquire(id, this@EntryWorker)) delay(GATE_POLL_MS)
    try {
      runEntry()
      Result.success()
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      // A store failure (disk full at start or during an attempt). Nothing
      // was settled; try the run again later.
      Diag.error("run of '$id' failed before it could settle; retrying", error)
      Result.retry()
    } finally {
      WorkerGate.release(id, this@EntryWorker)
    }
  }

  private suspend fun runEntry() {
    val initial = store.load(entryId) ?: return // forgotten while queued
    if (initial.legacy) return
    if (initial.state == EntryState.AWAITING_AUTH) {
      // The expiry wake of a parked entry.
      if (RetryClassifier.isExpired(now(), initial.expiresAt)) {
        ops.settle(entryId, initial.generation, expired(initial))
      }
      return
    }
    if (initial.state != EntryState.QUEUED && initial.state != EntryState.RUNNING) return
    if (ops.settings().paused) return
    if (!waitUntilDue(initial)) return
    val entry = ops.begin(entryId) ?: return
    generation = entry.generation
    showsNotification = entry.descriptor?.noNotification == false

    var current = entry
    var first = true
    while (true) {
      try {
        if (!first) current = ops.latest(entryId, generation)
        first = false
        startForeground()
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
        ops.stopped(entryId, generation)
        throw error
      } catch (error: IOException) {
        // A store write failed (disk full, directory briefly unwritable).
        // The transfers classify every network IOException themselves, so
        // one that lands here is storage: transient, no outcome. Back to
        // queued; doWork returns retry.
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
          ),
        )
        return
      }
    }
  }

  private suspend fun transfer(entry: QueueEntry): Settlement =
    if (entry.body?.kind == StagedBody.CHUNKED) ChunkedTransfer(this).run(entry)
    else SimpleTransfer(this).run(entry)

  private fun expired(entry: QueueEntry) = Settlement.Failed(
    errorKind = "expired",
    message = "expired before completion",
    response = null,
    partIndex = null,
    url = entry.descriptor?.reportUrl ?: "",
    method = entry.descriptor?.method ?: "POST",
  )

  /**
   * Sleeps out a short backoff remainder. False when the wait is long (the
   * wake run comes back for it) or the entry is no longer queued.
   */
  private suspend fun waitUntilDue(initial: QueueEntry): Boolean {
    var e = initial
    while (true) {
      val at = e.nextAttemptAt ?: return true
      val remaining = at - now()
      if (remaining <= 0) return true
      if (remaining > RetryClassifier.IN_WORKER_BACKOFF_MAX_MS) return false
      delay(min(remaining, WAIT_POLL_MS))
      e = store.load(entryId) ?: return false
      if (e.state != EntryState.QUEUED && e.state != EntryState.RUNNING) return false
    }
  }

  // MARK: - helpers for the transfers

  internal fun now() = System.currentTimeMillis()

  /**
   * Waits until the network fits the queue's wifi-only setting. Re-reads
   * the settings and the entry at every poll.
   */
  internal suspend fun waitForNetwork() {
    while (true) {
      val s = ops.settings()
      if (s.paused) throw PausedException()
      val entry = ops.latest(entryId, generation)
      if (RetryClassifier.isExpired(now(), entry.expiresAt)) throw ExpiredException()
      connectivity = validateConnectivity(context, s.wifiOnly)
      updateNotification()
      if (connectivity == Connectivity.Ok) return
      delay(CONNECTIVITY_POLL_MS)
    }
  }

  /** The descriptor's headers, the part's over them, and X-Request-Id over all. */
  internal fun headersFor(d: Descriptor, part: Part?, requestId: String): Map<String, String> {
    val withPart = if (part == null) d.headers else HeaderMap.merge(d.headers, part.headers)
    return HeaderMap.merge(withPart, mapOf("X-Request-Id" to requestId))
  }

  internal fun policy(entry: QueueEntry) =
    RetryClassifier.policy(ops.settings().retry, entry.descriptor?.retry)

  /**
   * A short backoff waits here, with the row still running and showing
   * nextAttemptAt; a long one throws [BackoffException] to release the worker.
   */
  internal suspend fun backoffOrRelease(policy: RetryClassifier.Policy, streak: Int, expiresAt: Long) {
    val backoff = RetryClassifier.backoffMs(policy, streak)
    val now = now()
    if (backoff <= RetryClassifier.IN_WORKER_BACKOFF_MAX_MS) {
      val wait = min(backoff, max(0L, expiresAt - now))
      ops.backingOff(entryId, generation, now + wait)
      delay(wait)
      return
    }
    throw BackoffException(RetryClassifier.nextAttemptAt(now, backoff, expiresAt), streak)
  }

  internal fun reportProgress(sent: Long, total: Long) {
    UploadProgress.set(entryId, sent)
    EventReporter.progress(entryId, sent, total)
    updateNotification()
  }

  internal fun bodyFile(entry: QueueEntry): File? = store.bodyFile(entry)

  private fun endProgress(completed: Boolean) {
    if (completed) UploadProgress.complete(entryId) else UploadProgress.remove(entryId)
    EventReporter.flushProgress(entryId)
    EventReporter.dropProgress(entryId)
  }

  // MARK: - notification

  // v9 rules. A suppressed notification means no foreground mode. A denied
  // foreground start (API 31+, app in the background: the usual case for a
  // WorkManager relaunch) is not a failure; the transfer runs without
  // foreground priority. Any other failure is logged and the run goes on.
  private suspend fun startForeground() {
    if (!showsNotification) return
    try {
      ensureNotificationChannel(notificationManager, config)
      setForeground(getForegroundInfo())
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      if (!isForegroundStartDenied(error)) Diag.warn("foreground start failed; running without it", error)
    }
  }

  private fun updateNotification() {
    if (!showsNotification) return
    runCatching {
      notificationManager.notify(
        config.systemNotificationId,
        buildUploadNotification(context, config, connectivity),
      )
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo =
    uploadForegroundInfo(config, buildUploadNotification(context, config, connectivity))
}
