package ai.openspace.backgroundupload

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * How far ahead a parked entry's expiry wake may be scheduled. A delayed
 * WorkManager row holds one of the app's JobScheduler slots (20 by default)
 * until it runs, and WorkManager's in-process scheduler reads only the 200
 * oldest enqueued rows. Wakes years ahead, from long-lived requests that
 * parked, filled both, so no other work started. An entry that expires past
 * this horizon gets its wake from a later boot sweep, and settles at the
 * first launch after it expires at the latest.
 */
const val EXPIRY_WAKE_HORIZON_MS = 24L * 60 * 60 * 1000

/** Whether an expiry wake at [expiresAt] is near enough to schedule now. */
fun expiryWakeIsNear(expiresAt: Long, now: Long): Boolean = expiresAt - now <= EXPIRY_WAKE_HORIZON_MS

/**
 * Starts and stops worker runs for entries. A run only carries the entry id;
 * the worker reads everything else from the store. So an extra run is always
 * harmless: it finds nothing to do and exits.
 */
interface WorkScheduler {
  /** A run as soon as possible, on the entry's main chain. */
  fun schedule(entry: QueueEntry)

  /**
   * A run at [at] under a second unique name. Used for long backoffs and for
   * the expiry of a parked entry. [replace] false keeps a wake that already
   * exists (the boot sweep).
   */
  fun scheduleWake(entry: QueueEntry, at: Long, replace: Boolean)

  /** Cancels the main chain and the wake. */
  fun cancel(id: String)

  /** Cancels the wake only. A wake that is no longer needed still holds a WorkManager slot. */
  fun cancelWake(id: String)

  /** Every unfinished WorkManager run of the library, main chains and wakes. For the boot snapshot. */
  fun libraryRuns(): List<PendingRun>
}

/**
 * One unfinished WorkManager run. [entryId] comes from the run's id tag.
 * [delayed] is a run enqueued with an initial delay, which only a wake has.
 * [nextScheduleAt] is null when WorkManager reports none.
 */
data class PendingRun(
  val entryId: String?,
  val state: String,
  val runAttemptCount: Int,
  val nextScheduleAt: Long?,
  val delayed: Boolean,
)

/**
 * WorkManager unique work per entry id, APPEND_OR_REPLACE, as v9.
 *
 * Why APPEND_OR_REPLACE: a worker settles before doWork returns, so a same-id
 * enqueue can arrive while the row is still RUNNING. KEEP would drop it and
 * REPLACE would kill the running worker. APPEND runs it after; OR_REPLACE
 * starts a fresh chain after a CANCELLED or FAILED one. Workers always return
 * success, because WorkManager fails the dependents of a FAILED row without a
 * run.
 *
 * Long waits do not go on the main chain. A delayed row there would hold
 * back every later "run now" appended behind it. They use the wake name
 * (`<id>#wake`) with an initial delay instead.
 *
 * No WorkManager Constraints: connectivity and wifi-only are checked inside
 * the worker, as v9, because the constraint path was unreliable.
 */
class WorkManagerScheduler(context: Context) : WorkScheduler {
  companion object {
    /** v9 rows carry the tag "RNFileUploader"; this one is new so the v9 cancel does not touch v10 work. */
    const val WORK_TAG = "RNFileUploader.v10"
    const val ID_TAG_PREFIX = "RNFileUploaderId:"
    /** A string literal, because WorkManager persists it across builds. */
    const val ENTRY_ID_KEY = "entryId"
    const val V9_WORK_TAG = "RNFileUploader"

    fun wakeName(id: String) = "$id#wake"

    /** Milliseconds from [now] until [at]; never negative. */
    fun initialDelayMs(at: Long?, now: Long): Long = if (at == null) 0L else (at - now).coerceAtLeast(0L)

    /** An unfinished row that is not RUNNING: a queued run that did not start yet. */
    fun hasQueuedSuccessor(states: List<WorkInfo.State>): Boolean =
      states.any { !it.isFinished && it != WorkInfo.State.RUNNING }
  }

  private val workManager = WorkManager.getInstance(context)

  override fun schedule(entry: QueueEntry) {
    // A queued successor already guarantees a run after the current one.
    val states = workManager.getWorkInfosForUniqueWork(entry.id).get().map { it.state }
    if (hasQueuedSuccessor(states)) return
    workManager
      .beginUniqueWork(entry.id, ExistingWorkPolicy.APPEND_OR_REPLACE, request(entry, 0L))
      .enqueue()
  }

  override fun scheduleWake(entry: QueueEntry, at: Long, replace: Boolean) {
    val delay = initialDelayMs(at, System.currentTimeMillis())
    workManager.enqueueUniqueWork(
      wakeName(entry.id),
      if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
      request(entry, delay),
    )
  }

  override fun cancel(id: String) {
    workManager.cancelUniqueWork(id)
    workManager.cancelUniqueWork(wakeName(id))
  }

  // A cancel reschedules all pending work even when the name has none, and
  // the boot sweep calls this for every queued entry.
  override fun cancelWake(id: String) {
    val states = workManager.getWorkInfosForUniqueWork(wakeName(id)).get().map { it.state }
    if (states.any { !it.isFinished }) workManager.cancelUniqueWork(wakeName(id))
  }

  // One query for every row, not one per entry: the boot sweep runs this on
  // the queue's only thread.
  override fun libraryRuns(): List<PendingRun> =
    workManager.getWorkInfosByTag(WORK_TAG).get()
      .filter { !it.state.isFinished }
      .map { info ->
        PendingRun(
          entryId = info.tags.firstOrNull { it.startsWith(ID_TAG_PREFIX) }?.removePrefix(ID_TAG_PREFIX),
          state = info.state.name,
          runAttemptCount = info.runAttemptCount,
          nextScheduleAt = info.nextScheduleTimeMillis.takeIf { it != Long.MAX_VALUE },
          delayed = info.initialDelayMillis > 0,
        )
      }

  /** First v10 launch: the v9 rows. Their workers are gone. */
  fun cancelV9Work() {
    workManager.cancelAllWorkByTag(V9_WORK_TAG)
  }

  private fun request(entry: QueueEntry, delayMs: Long): OneTimeWorkRequest {
    val builder = if (entry.body?.kind == StagedBody.CHUNKED) {
      OneTimeWorkRequestBuilder<ChunkedUploadWorker>()
    } else {
      OneTimeWorkRequestBuilder<UploadWorker>()
    }
    return builder
      .addTag(WORK_TAG)
      .addTag(ID_TAG_PREFIX + entry.id)
      .setInputData(workDataOf(ENTRY_ID_KEY to entry.id))
      .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
      .build()
  }
}
