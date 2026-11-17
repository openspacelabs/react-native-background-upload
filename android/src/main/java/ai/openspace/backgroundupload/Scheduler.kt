package ai.openspace.backgroundupload

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlin.math.min

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
 * When WorkManager 2.12.0 starts a row again after a system stop. Read from
 * the 2.12.0 classes: a stop sets the row back to ENQUEUED and keeps its
 * runAttemptCount and its last enqueue time (WorkerWrapper.resetWorkerStatus,
 * for a row with no backoff for system interruptions, which the library
 * never sets). An ENQUEUED row with a count above 0 then starts at
 * lastEnqueueTime + min(30 s * 2^(count - 1), 5 h), and not before
 * (WorkSpec.calculateNextRunTime, and the due check in WorkerWrapper). The
 * library's rows use the default backoff: 30 s, exponential.
 */
internal object WorkManagerBackoff {
  const val DELAY_MS = 30_000L
  const val MAX_MS = 18_000_000L

  /**
   * The latest time WorkManager starts a row again after a stop of the run
   * that began at [startedAt]. [runAttemptCountAtStart] is the worker's
   * runAttemptCount: the starts before this one. The row's last enqueue time
   * is at or before [startedAt], so the real time is at or before this one.
   */
  fun restartBy(startedAt: Long, runAttemptCountAtStart: Int): Long {
    val exponent = runAttemptCountAtStart.coerceIn(0, 40)
    return startedAt + min(DELAY_MS.toDouble() * Math.pow(2.0, exponent.toDouble()), MAX_MS.toDouble()).toLong()
  }
}

/** What [RowScheduler.schedule] does to an entry's main chain. */
internal enum class MainChainStep { NONE, APPEND, REPLACE }

/**
 * The step for a "run now" on a main chain with [rows] (its unfinished
 * rows). [workerRunning]: a worker of the entry runs in this process.
 *
 * - No row: a fresh chain (APPEND_OR_REPLACE starts one).
 * - A run in progress (a RUNNING row, or a worker in this process): a
 *   queued row after it is enough; with none, append one.
 * - No run in progress: a row WorkManager starts now is enough. When every
 *   queued row waits, REPLACE them with a fresh row. That is a row a stop
 *   left on WorkManager's backoff (see [WorkManagerBackoff]), and the rows
 *   blocked behind it. Nothing runs, so the replace cuts no request.
 *   WorkManager does not start a row before its time, and a row due within
 *   [START_MARGIN_MS] counts as starting now, so no row starts between the
 *   read and the replace.
 */
internal fun mainChainStep(rows: List<WorkRow>, workerRunning: Boolean, now: Long): MainChainStep {
  val open = rows.filter { !it.state.isFinished }
  if (open.isEmpty()) return MainChainStep.APPEND
  if (workerRunning || open.any { it.state == WorkInfo.State.RUNNING }) {
    return if (WorkManagerScheduler.hasQueuedSuccessor(open.map { it.state })) MainChainStep.NONE else MainChainStep.APPEND
  }
  if (open.any { it.startsBy(now) }) return MainChainStep.NONE
  return MainChainStep.REPLACE
}

/** A row due this soon counts as one that starts now. */
internal const val START_MARGIN_MS = 5_000L

/** An ENQUEUED row that WorkManager may start now, or within [START_MARGIN_MS]. */
internal fun WorkRow.startsBy(now: Long) = state == WorkInfo.State.ENQUEUED && nextScheduleAt <= now + START_MARGIN_MS

/**
 * Starts and stops worker runs for entries. A run only carries the entry id;
 * the worker reads everything else from the store. So an extra run is always
 * harmless: it finds nothing to do and exits.
 */
interface WorkScheduler {
  /**
   * A run as soon as possible, on the entry's main chain. A row that
   * WorkManager holds back after a stop is replaced, so the entry can start
   * now.
   */
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

  /**
   * The entry ids that have a run in progress, or a row WorkManager may
   * start now. One query for the whole library.
   */
  fun startableIds(): Set<String>
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
 * One unfinished WorkManager row. [nextScheduleAt] is the earliest time
 * WorkManager starts it (`WorkInfo.nextScheduleTimeMillis`): Long.MAX_VALUE
 * for a row that is not ENQUEUED.
 */
data class WorkRow(
  val entryId: String?,
  val state: WorkInfo.State,
  val runAttemptCount: Int,
  val nextScheduleAt: Long,
  val delayed: Boolean,
)

/** The WorkManager calls [RowScheduler] makes. Tests run the same rules against a fake. */
interface WorkRows {
  /** The unfinished rows of one unique name. */
  fun unfinished(name: String): List<WorkRow>

  /** Every unfinished row of the library, main chains and wakes. */
  fun library(): List<WorkRow>

  fun enqueue(name: String, policy: ExistingWorkPolicy, entry: QueueEntry, delayMs: Long)

  fun cancel(name: String)
}

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
open class RowScheduler(
  private val rows: WorkRows,
  private val isWorkerRunning: (id: String) -> Boolean = WorkerGate::isRunning,
  private val clock: () -> Long = System::currentTimeMillis,
) : WorkScheduler {

  /**
   * Makes sure the entry has a row that starts now or after the run in
   * progress. See [mainChainStep].
   */
  override fun schedule(entry: QueueEntry) {
    when (mainChainStep(rows.unfinished(entry.id), isWorkerRunning(entry.id), clock())) {
      MainChainStep.NONE -> Unit
      MainChainStep.APPEND -> rows.enqueue(entry.id, ExistingWorkPolicy.APPEND_OR_REPLACE, entry, 0L)
      MainChainStep.REPLACE -> rows.enqueue(entry.id, ExistingWorkPolicy.REPLACE, entry, 0L)
    }
  }

  override fun scheduleWake(entry: QueueEntry, at: Long, replace: Boolean) {
    val delay = WorkManagerScheduler.initialDelayMs(at, clock())
    rows.enqueue(
      WorkManagerScheduler.wakeName(entry.id),
      if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
      entry,
      delay,
    )
  }

  override fun cancel(id: String) {
    rows.cancel(id)
    rows.cancel(WorkManagerScheduler.wakeName(id))
  }

  // A cancel reschedules all pending work even when the name has none, and
  // the boot sweep calls this for every queued entry.
  override fun cancelWake(id: String) {
    val name = WorkManagerScheduler.wakeName(id)
    if (rows.unfinished(name).isNotEmpty()) rows.cancel(name)
  }

  // One query for every row, not one per entry: the boot sweep runs this on
  // the queue's only thread.
  override fun libraryRuns(): List<PendingRun> =
    rows.library().map { row ->
      PendingRun(
        entryId = row.entryId,
        state = row.state.name,
        runAttemptCount = row.runAttemptCount,
        nextScheduleAt = row.nextScheduleAt.takeIf { it != Long.MAX_VALUE },
        delayed = row.delayed,
      )
    }

  override fun startableIds(): Set<String> {
    val now = clock()
    return rows.library()
      .filter { it.state == WorkInfo.State.RUNNING || it.startsBy(now) }
      .mapNotNull { it.entryId }
      .toSet()
  }
}

/** [RowScheduler] on the app's WorkManager. */
class WorkManagerScheduler private constructor(private val workManager: WorkManager) :
  RowScheduler(WorkManagerRows(workManager)) {

  constructor(context: Context) : this(WorkManager.getInstance(context))

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

  /** First v10 launch: the v9 rows. Their workers are gone. */
  fun cancelV9Work() {
    workManager.cancelAllWorkByTag(V9_WORK_TAG)
  }
}

/** [WorkRows] on WorkManager. */
private class WorkManagerRows(private val workManager: WorkManager) : WorkRows {
  override fun unfinished(name: String): List<WorkRow> =
    workManager.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }.map(::row)

  override fun library(): List<WorkRow> =
    workManager.getWorkInfosByTag(WorkManagerScheduler.WORK_TAG).get().filter { !it.state.isFinished }.map(::row)

  override fun enqueue(name: String, policy: ExistingWorkPolicy, entry: QueueEntry, delayMs: Long) {
    workManager.beginUniqueWork(name, policy, request(entry, delayMs)).enqueue()
  }

  override fun cancel(name: String) {
    workManager.cancelUniqueWork(name)
  }

  private fun row(info: WorkInfo) = WorkRow(
    entryId = info.tags.firstOrNull { it.startsWith(WorkManagerScheduler.ID_TAG_PREFIX) }
      ?.removePrefix(WorkManagerScheduler.ID_TAG_PREFIX),
    state = info.state,
    runAttemptCount = info.runAttemptCount,
    nextScheduleAt = info.nextScheduleTimeMillis,
    delayed = info.initialDelayMillis > 0,
  )

  private fun request(entry: QueueEntry, delayMs: Long): OneTimeWorkRequest {
    val builder = if (entry.body?.kind == StagedBody.CHUNKED) {
      OneTimeWorkRequestBuilder<ChunkedUploadWorker>()
    } else {
      OneTimeWorkRequestBuilder<UploadWorker>()
    }
    // No setBackoffCriteria and no backoff for system interruptions: a
    // stopped row keeps WorkManager's default (30 s, exponential, from its
    // last enqueue time). [WorkManagerBackoff] relies on that.
    return builder
      .addTag(WorkManagerScheduler.WORK_TAG)
      .addTag(WorkManagerScheduler.ID_TAG_PREFIX + entry.id)
      .setInputData(workDataOf(WorkManagerScheduler.ENTRY_ID_KEY to entry.id))
      .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
      .build()
  }
}
