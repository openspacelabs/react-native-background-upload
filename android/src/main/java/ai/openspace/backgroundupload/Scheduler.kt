package ai.openspace.backgroundupload

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
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

/**
 * The network a queued entry waits for, and the doorbell row that waits for
 * it. NONE: the entry does not wait for a network (it is not queued, or it
 * waits for a backoff). CONNECTED and WIFI need a validated network, as the
 * in-worker check does.
 */
enum class RequiredNetwork { NONE, CONNECTED, WIFI }

/**
 * The doorbell [entry] waits for when no run of it can start: WIFI when its
 * own wifiOnly, else the queue's setting, says so; else CONNECTED. NONE for
 * an entry that is not queued, and for one whose wait is longer than a
 * worker sleeps out: its wake row starts it ([RetryClassifier.IN_WORKER_BACKOFF_MAX_MS]).
 * A short wait, such as a chunked sibling's backoff pending at a hand-over,
 * does not change the rule.
 */
internal fun requiredNetwork(entry: QueueEntry, settings: QueueSettings, now: Long): RequiredNetwork {
  if (entry.legacy || entry.state != EntryState.QUEUED) return RequiredNetwork.NONE
  val at = entry.nextAttemptAt
  if (at != null && at - now > RetryClassifier.IN_WORKER_BACKOFF_MAX_MS) return RequiredNetwork.NONE
  return if (settings.wifiOnlyFor(entry)) RequiredNetwork.WIFI else RequiredNetwork.CONNECTED
}

/** What [RowScheduler.schedule] does to an entry's main chain. */
internal enum class MainChainStep { NONE, APPEND, REPLACE }

/**
 * The step for a "run now" on a main chain with [rows] (its unfinished
 * rows).
 *
 * - No row: a fresh chain (APPEND_OR_REPLACE starts one).
 * - A RUNNING row: a row BLOCKED behind it runs after it, so that is
 *   enough; with none, append one.
 * - No RUNNING row: a row WorkManager starts now is enough. When every
 *   queued row waits, REPLACE them with a fresh row. That is a row a stop
 *   left on WorkManager's backoff (see [WorkManagerBackoff]), and the rows
 *   blocked behind it. A worker of the entry that runs under the wake name
 *   does not change this: when it ends it may leave no row that can start.
 *   The fresh row then waits at the per-id gate until that worker ends.
 *
 * WorkManager does not start a row before its time, and a row due within
 * [START_MARGIN_MS] counts as starting now. That margin is the protection
 * against replacing a row that just started: the query, the enqueue and
 * the cancel run one after the other on WorkManager's executor, and the
 * enqueue is not awaited. It is a margin, not a guarantee.
 */
internal fun mainChainStep(rows: List<WorkRow>, now: Long): MainChainStep {
  val open = rows.filter { !it.state.isFinished }
  if (open.isEmpty()) return MainChainStep.APPEND
  if (open.any { it.state == WorkInfo.State.RUNNING }) {
    val after = open.any { it.state == WorkInfo.State.BLOCKED || it.startsBy(now) }
    return if (after) MainChainStep.NONE else MainChainStep.APPEND
  }
  if (open.any { it.startsBy(now) }) return MainChainStep.NONE
  return MainChainStep.REPLACE
}

/**
 * A row due this soon counts as one that starts now. 30 s is WorkManager's
 * shortest backoff, so the margin delays an entry by at most that much.
 */
internal const val START_MARGIN_MS = 30_000L

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
   * [schedule], and waits until WorkManager has stored the row it adds.
   * Throws when it did not.
   */
  fun scheduleAndWait(entry: QueueEntry) = schedule(entry)

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

  /**
   * Makes sure the doorbell row for [network] (CONNECTED or WIFI) exists: one
   * row for the whole queue, with that WorkManager network rule. When the
   * network is up, WorkManager runs it, and its worker ([DoorbellWorker])
   * starts the entries that wait for a network. It runs no upload. Returns
   * once WorkManager has stored the row; throws when it could not. [delayMs]
   * is the initial delay of a new row; a doorbell that is there is kept.
   */
  fun ensureDoorbell(network: RequiredNetwork, delayMs: Long = 0L)

  /** Every unfinished WorkManager run of the library, main chains and wakes. For the boot snapshot. */
  fun libraryRuns(): List<PendingRun>

  /** The library's unfinished rows by entry id. One query for the whole library. */
  fun rowSummary(): RowSummary
}

/**
 * [startable]: the ids with a run in progress, or a row WorkManager may
 * start now. [withWake]: the ids with an unfinished delayed row. Only a wake
 * has an initial delay; a main-chain row never does. [due]: the ids with an
 * ENQUEUED row WorkManager may start now; a RUNNING row does not count, so
 * a worker that is on its way out does not hide its entry.
 */
data class RowSummary(
  val startable: Set<String>,
  val withWake: Set<String>,
  val due: Set<String> = emptySet(),
)

/**
 * One unfinished WorkManager run. [entryId] comes from the run's id tag.
 * [delayed] is a run enqueued with an initial delay, which only a wake has.
 * [nextScheduleAt] is null when WorkManager reports none. [doorbell] is the
 * network of a doorbell row, which has no entry id; null for any other run.
 */
data class PendingRun(
  val entryId: String?,
  val state: String,
  val runAttemptCount: Int,
  val nextScheduleAt: Long?,
  val delayed: Boolean,
  val doorbell: RequiredNetwork? = null,
)

/**
 * One unfinished WorkManager row. [nextScheduleAt] is the earliest time
 * WorkManager starts it (`WorkInfo.nextScheduleTimeMillis`): Long.MAX_VALUE
 * for a row that is not ENQUEUED. WorkManager 2.12.0 leaves a network rule
 * out of that time (WorkSpec.WorkInfoPojo.calculateNextRunTimeMillis): a
 * doorbell reports its enqueue time, and starts once its network is up.
 * [doorbell]: the network of a doorbell row; null for an entry's row.
 */
data class WorkRow(
  val entryId: String?,
  val state: WorkInfo.State,
  val runAttemptCount: Int,
  val nextScheduleAt: Long,
  val delayed: Boolean,
  val doorbell: RequiredNetwork? = null,
)

/** The WorkManager calls [RowScheduler] makes. Tests run the same rules against a fake. */
interface WorkRows {
  /** The unfinished rows of one unique name. */
  fun unfinished(name: String): List<WorkRow>

  /** Every row of the library, main chains and wakes, in one of [states]. One query. */
  fun library(states: List<WorkInfo.State>): List<WorkRow>

  fun enqueue(name: String, policy: ExistingWorkPolicy, entry: QueueEntry, delayMs: Long)

  /** [enqueue], and waits until WorkManager has stored the row. Throws when it did not. */
  fun enqueueAndWait(name: String, policy: ExistingWorkPolicy, entry: QueueEntry, delayMs: Long) =
    enqueue(name, policy, entry, delayMs)

  /**
   * Enqueues the doorbell row [name] with [network] as its rule, and waits
   * until WorkManager has stored it. Throws when it did not.
   */
  fun enqueueDoorbell(name: String, policy: ExistingWorkPolicy, network: RequiredNetwork, delayMs: Long = 0L)

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
 * No row that runs an upload carries a WorkManager constraint. WorkManager
 * enforces a constraint while the worker runs: it stops the worker when the
 * network loses the rule, also in the middle of a request, and the stopped
 * row keeps its run count, so WorkManager then holds it back (see
 * [WorkManagerBackoff]). A constrained row also does not start while the app
 * is visible and offline, so it could not take foreground mode then. The
 * worker checks the network itself before every attempt.
 *
 * The network rule lives on two doorbell rows for the whole queue
 * ([ensureDoorbell]). A doorbell runs no upload: it only starts the entries
 * that wait for a network. Earlier versions used no constraint at all,
 * because the constraint path was unreliable. The WorkManager release notes
 * for 2.10.1 to 2.12.0 list fixes for network constraints that were not met
 * while the network was up, re-added capabilities, an identical second
 * constraint, and a blocked network (Android 15) that passed the
 * constraint. The library is on 2.12.0. Even so, no start depends on the
 * doorbell alone: the app's start, resume() and return to the foreground
 * start an entry whose network fits.
 */
open class RowScheduler(
  private val rows: WorkRows,
  private val clock: () -> Long = System::currentTimeMillis,
) : WorkScheduler {
  private companion object {
    val UNFINISHED = listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)

    /** The module's scheduler and a doorbell worker's are separate objects in one process. */
    val DOORBELL_LOCK = Any()
  }

  /**
   * Makes sure the entry has a row that starts now or after the run in
   * progress. See [mainChainStep].
   */
  override fun schedule(entry: QueueEntry) {
    when (mainChainStep(rows.unfinished(entry.id), clock())) {
      MainChainStep.NONE -> Unit
      MainChainStep.APPEND -> rows.enqueue(entry.id, ExistingWorkPolicy.APPEND_OR_REPLACE, entry, 0L)
      MainChainStep.REPLACE -> rows.enqueue(entry.id, ExistingWorkPolicy.REPLACE, entry, 0L)
    }
  }

  override fun scheduleAndWait(entry: QueueEntry) {
    when (mainChainStep(rows.unfinished(entry.id), clock())) {
      MainChainStep.NONE -> Unit
      MainChainStep.APPEND -> rows.enqueueAndWait(entry.id, ExistingWorkPolicy.APPEND_OR_REPLACE, entry, 0L)
      MainChainStep.REPLACE -> rows.enqueueAndWait(entry.id, ExistingWorkPolicy.REPLACE, entry, 0L)
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

  /**
   * The same rule as an entry's main chain ([mainChainStep]): a doorbell
   * that waits for its network, or a running one with one queued behind
   * it, is enough. A running doorbell with none behind it gets one appended,
   * so an entry that hands over while the doorbell answers is not missed.
   * A doorbell that WorkManager holds back after stops is replaced. One lock
   * for the process, and the enqueue is awaited, so two callers do not both
   * append.
   */
  override fun ensureDoorbell(network: RequiredNetwork, delayMs: Long) {
    require(network != RequiredNetwork.NONE) { "a doorbell needs a network" }
    val name = WorkManagerScheduler.doorbellName(network)
    synchronized(DOORBELL_LOCK) {
      when (mainChainStep(rows.unfinished(name), clock())) {
        MainChainStep.NONE -> Unit
        MainChainStep.APPEND -> rows.enqueueDoorbell(name, ExistingWorkPolicy.APPEND_OR_REPLACE, network, delayMs)
        MainChainStep.REPLACE -> rows.enqueueDoorbell(name, ExistingWorkPolicy.REPLACE, network, delayMs)
      }
    }
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
    rows.library(UNFINISHED).map { row ->
      PendingRun(
        entryId = row.entryId,
        state = row.state.name,
        runAttemptCount = row.runAttemptCount,
        nextScheduleAt = row.nextScheduleAt.takeIf { it != Long.MAX_VALUE },
        delayed = row.delayed,
        doorbell = row.doorbell,
      )
    }

  override fun rowSummary(): RowSummary {
    val now = clock()
    val open = rows.library(UNFINISHED)
    return RowSummary(
      startable = open.filter { it.state == WorkInfo.State.RUNNING || it.startsBy(now) }.mapNotNull { it.entryId }.toSet(),
      withWake = open.filter { it.delayed }.mapNotNull { it.entryId }.toSet(),
      due = open.filter { it.startsBy(now) }.mapNotNull { it.entryId }.toSet(),
    )
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

    /** The unique name of the doorbell for [network]. */
    fun doorbellName(network: RequiredNetwork) = "RNFileUploader.doorbell:${network.name}"

    const val DOORBELL_TAG_PREFIX = "RNFileUploaderDoorbell:"
    /** A string literal, because WorkManager persists it across builds. */
    const val DOORBELL_NETWORK_KEY = "network"

    /** How long a WorkManager query or an awaited enqueue may take before it counts as failed. */
    const val WORK_CALL_TIMEOUT_MS = 10_000L

    /**
     * The initial delay of the doorbell a doorbell queues behind itself when
     * WorkManager saw its network but the library's check did not. So such
     * a doorbell rings at most once in this time.
     */
    const val DOORBELL_REPEAT_MS = 30_000L

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
  // A timeout, so a WorkManager that does not answer does not hold the queue executor.
  override fun unfinished(name: String): List<WorkRow> =
    workManager.getWorkInfosForUniqueWork(name).get(WorkManagerScheduler.WORK_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .filter { !it.state.isFinished }.map(::row)

  // The states are filtered in the query, so finished rows are never loaded.
  override fun library(states: List<WorkInfo.State>): List<WorkRow> =
    workManager.getWorkInfos(
      WorkQuery.Builder.fromTags(listOf(WorkManagerScheduler.WORK_TAG)).addStates(states).build(),
    ).get(WorkManagerScheduler.WORK_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).map(::row)

  override fun enqueue(name: String, policy: ExistingWorkPolicy, entry: QueueEntry, delayMs: Long) {
    workManager.beginUniqueWork(name, policy, request(entry, delayMs)).enqueue()
  }

  override fun enqueueAndWait(name: String, policy: ExistingWorkPolicy, entry: QueueEntry, delayMs: Long) {
    workManager.beginUniqueWork(name, policy, request(entry, delayMs)).enqueue().result
      .get(WorkManagerScheduler.WORK_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  }

  override fun cancel(name: String) {
    workManager.cancelUniqueWork(name)
  }

  // Waits for the insert: an enqueue that fails later is otherwise not seen.
  // The callers run on the queue executor or a worker thread, never on the
  // main thread.
  override fun enqueueDoorbell(name: String, policy: ExistingWorkPolicy, network: RequiredNetwork, delayMs: Long) {
    val request = OneTimeWorkRequestBuilder<DoorbellWorker>()
      .addTag(WorkManagerScheduler.WORK_TAG)
      .addTag(WorkManagerScheduler.DOORBELL_TAG_PREFIX + network.name)
      .setInputData(workDataOf(WorkManagerScheduler.DOORBELL_NETWORK_KEY to network.name))
      .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
      .setConstraints(constraints(network))
      .build()
    workManager.beginUniqueWork(name, policy, request).enqueue().result
      .get(WorkManagerScheduler.WORK_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  }

  /**
   * CONNECTED: NetworkType.CONNECTED, which WorkManager 2.12.0 turns into a
   * request for INTERNET and VALIDATED (NetworkTypeCompatKt.toNetworkRequest).
   * WIFI: the in-worker check's rule, a validated Wi-Fi network. WorkManager
   * uses the request on API 28 and later; UNMETERED is its fallback below.
   * A new NetworkRequest.Builder also asks for NOT_VPN and NOT_RESTRICTED;
   * WorkManager removes both from its own CONNECTED request, and so does
   * this one, so a VPN over Wi-Fi counts.
   */
  private fun constraints(network: RequiredNetwork): Constraints = when (network) {
    RequiredNetwork.WIFI -> Constraints.Builder()
      .setRequiredNetworkRequest(
        NetworkRequest.Builder()
          .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
          .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
          .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
          .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
          .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
          .build(),
        NetworkType.UNMETERED,
      )
      .build()
    else -> Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
  }

  private fun row(info: WorkInfo) = WorkRow(
    entryId = info.tags.firstOrNull { it.startsWith(WorkManagerScheduler.ID_TAG_PREFIX) }
      ?.removePrefix(WorkManagerScheduler.ID_TAG_PREFIX),
    state = info.state,
    runAttemptCount = info.runAttemptCount,
    nextScheduleAt = info.nextScheduleTimeMillis,
    delayed = info.initialDelayMillis > 0,
    doorbell = info.tags.firstOrNull { it.startsWith(WorkManagerScheduler.DOORBELL_TAG_PREFIX) }
      ?.removePrefix(WorkManagerScheduler.DOORBELL_TAG_PREFIX)
      ?.let { name -> RequiredNetwork.values().firstOrNull { it.name == name } },
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
