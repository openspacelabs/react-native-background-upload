package ai.openspace.backgroundupload

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo

// Builders and fakes shared by the JVM tests. No android.* here.

internal const val FAR_FUTURE = 4_000_000_000_000L

internal fun desc(
  url: String? = "https://example.com/items",
  method: String = "POST",
  headers: Map<String, String> = mapOf("Authorization" to "Bearer old"),
  dataJson: String? = null,
  form: List<FormPart>? = null,
  file: String? = null,
  parts: List<Part>? = null,
  accept: List<UploadOutcome.AcceptRule> = emptyList(),
  retry: RetryOverride? = null,
  noNotification: Boolean = false,
  wifiOnly: Boolean? = null,
  highPriority: Boolean = false,
) = Descriptor(url, method, headers, dataJson, form, file, parts, accept, retry, noNotification, wifiOnly, highPriority)

internal fun part(start: Long, end: Long, accepted: Boolean = false, url: String? = null) = Part(
  url = url ?: "https://example.com/part?start=$start",
  headers = mapOf("Content-Range" to "$start-${end - 1}"),
  start = start,
  end = end,
  accepted = accepted,
)

internal fun entry(
  id: String = "e1",
  state: EntryState = EntryState.QUEUED,
  descriptor: Descriptor? = desc(dataJson = """{"a":1}"""),
  body: StagedBody? = StagedBody(StagedBody.JSON, "body-1.json", null, 7),
  generation: Int = 1,
  attempts: Int = 0,
  settledEventId: String? = null,
  parkedGeneration: Int? = null,
  nextAttemptAt: Long? = null,
  expiresAt: Long = FAR_FUTURE,
  legacy: Boolean = false,
  key: String = "note",
  createdAt: Long = 1_000,
) = QueueEntry(
  id = id,
  key = key,
  varsJson = """{"n":1}""",
  descriptor = descriptor,
  body = body,
  state = state,
  attempts = attempts,
  bytesSent = 0,
  totalBytes = body?.totalBytes ?: 0,
  expiresAt = expiresAt,
  createdAt = createdAt,
  updatedAt = createdAt,
  nextAttemptAt = nextAttemptAt,
  parkedGeneration = parkedGeneration,
  generation = generation,
  settledEventId = settledEventId,
  legacy = legacy,
)

internal fun parsed(
  id: String = "e1",
  descriptor: Descriptor = desc(dataJson = """{"a":1}"""),
  varsJson: String = """{"n":1}""",
  expiresAt: Long = FAR_FUTURE,
  key: String = "note",
) = EntryParsing.Parsed(id, key, varsJson, descriptor, expiresAt)

internal fun record(
  eventId: String,
  id: String = "e1",
  kind: String = EventJournal.KIND_COMPLETED,
  generation: Int = 1,
  at: Long = 5_000,
  state: String = kind,
) = EventJournal.SettledRecord(
  eventId = eventId, id = id, key = "note", varsJson = """{"n":1}""", at = at, attempts = 1,
  requestId = "r1", deliveries = 1, state = state, bytesSent = 0, totalBytes = 0,
  url = "https://example.com/items", method = "POST", partIndex = null, kind = kind,
  response = if (kind == EventJournal.KIND_COMPLETED) EventJournal.Response(200, mapOf(), "ok", false) else null,
  errorKind = if (kind == EventJournal.KIND_ERROR) "http" else null,
  message = if (kind == EventJournal.KIND_ERROR) "HTTP 400" else null,
  cancelReason = if (kind == EventJournal.KIND_CANCELLED) "user" else null,
  generation = generation,
)

/** Records every event in order, as "state:<id>:<state>" and "settled:<id>:<kind>". */
internal class RecordingEvents : QueueEvents {
  val log = mutableListOf<String>()
  val rows = mutableListOf<RequestRow>()
  val records = mutableListOf<EventJournal.SettledRecord>()

  /** The listener each settled event went to, in order. */
  val listeners = mutableListOf<Any>()

  override fun state(row: RequestRow) {
    rows += row
    log += "state:${row.id}:${row.state}"
  }

  override fun settled(record: EventJournal.SettledRecord, listener: Any) {
    records += record
    listeners += listener
    log += "settled:${record.id}:${record.kind}"
  }
}

internal class FakeScheduler : WorkScheduler {
  val scheduled = mutableListOf<String>()
  val wakes = mutableListOf<Pair<String, Long>>()
  /** The replace argument of every scheduleWake, in order. REPLACE makes a fresh row. */
  val wakeReplaces = mutableListOf<Boolean>()
  /** How many of the next scheduleWake calls throw, as a failed WorkManager enqueue. */
  var wakeFailures = 0
  val cancelled = mutableListOf<String>()
  val cancelledWakes = mutableListOf<String>()
  /** What libraryRuns() returns. */
  val runs = mutableListOf<PendingRun>()
  /** What rowSummary() returns as startable. */
  val startable = mutableSetOf<String>()
  /** What rowSummary() returns as withWake. */
  val withWake = mutableSetOf<String>()
  /** How many rowSummary() calls were made. */
  var startableQueries = 0

  override fun schedule(entry: QueueEntry) {
    scheduled += entry.id
  }

  override fun scheduleWake(entry: QueueEntry, at: Long, replace: Boolean) {
    if (wakeFailures > 0) {
      wakeFailures--
      throw IllegalStateException("enqueue failed")
    }
    wakes += entry.id to at
    wakeReplaces += replace
  }

  override fun cancel(id: String) {
    cancelled += id
  }

  override fun cancelWake(id: String) {
    cancelledWakes += id
  }

  override fun libraryRuns(): List<PendingRun> = runs.toList()

  override fun rowSummary(): RowSummary {
    startableQueries++
    return RowSummary(startable.toSet(), withWake.toSet())
  }
}

/**
 * WorkManager 2.12.0's rules for unique work, as [RowScheduler] uses them,
 * with a manual clock. Read from the 2.12.0 classes:
 *
 * - A start sets RUNNING and adds 1 to runAttemptCount (WorkerWrapper.trySetRunning).
 * - A system stop sets the row back to ENQUEUED. The count and the last
 *   enqueue time stay (WorkerWrapper.resetWorkerStatus).
 * - An ENQUEUED row with runAttemptCount above 0 is backed off. It starts
 *   at lastEnqueueTime + min(30 s * 2^(count - 1), 5 h) and not before
 *   (WorkSpec.isBackedOff, WorkSpec.Companion.calculateNextRunTime, and the
 *   due check in WorkerWrapper.runWorker). Any other ENQUEUED row starts at
 *   lastEnqueueTime + initialDelay.
 * - When a row finishes, the rows blocked behind it become ENQUEUED with a
 *   new lastEnqueueTime.
 */
internal class FakeWorkManager(var now: Long = 10_000L) : WorkRows {
  class Row(
    val serial: Int,
    val name: String,
    val entryId: String,
    var state: WorkInfo.State,
    var runAttemptCount: Int,
    var lastEnqueueTime: Long,
    val initialDelay: Long,
  )

  val rows = mutableListOf<Row>()
  private var serial = 0

  fun nextScheduleAt(r: Row): Long = when {
    r.state != WorkInfo.State.ENQUEUED -> Long.MAX_VALUE
    r.runAttemptCount > 0 -> {
      val backoff = WorkManagerBackoff.DELAY_MS.toDouble() * Math.pow(2.0, (r.runAttemptCount - 1).toDouble())
      r.lastEnqueueTime + minOf(backoff, WorkManagerBackoff.MAX_MS.toDouble()).toLong()
    }
    else -> r.lastEnqueueTime + r.initialDelay
  }

  /** Adds a row as it stands, for a test's starting point. */
  fun add(
    name: String,
    state: WorkInfo.State = WorkInfo.State.ENQUEUED,
    runAttemptCount: Int = 0,
    lastEnqueueTime: Long = now,
    initialDelay: Long = 0,
  ): Row = Row(serial++, name, name.substringBefore("#"), state, runAttemptCount, lastEnqueueTime, initialDelay)
    .also { rows += it }

  fun open(name: String) = rows.filter { it.name == name && !it.state.isFinished }

  /** The rows WorkManager may start now. */
  fun startableNow(): List<Row> =
    rows.filter { it.state == WorkInfo.State.ENQUEUED && nextScheduleAt(it) <= now }

  override fun unfinished(name: String): List<WorkRow> {
    nameQueries++
    return open(name).map(::view)
  }

  /** How many library() queries were made, and the states each asked for. */
  val libraryQueries = mutableListOf<List<WorkInfo.State>>()

  /** How many unfinished(name) queries were made. */
  var nameQueries = 0

  override fun library(states: List<WorkInfo.State>): List<WorkRow> {
    libraryQueries += states
    return rows.filter { it.state in states }.map(::view)
  }

  override fun enqueue(name: String, policy: ExistingWorkPolicy, entry: QueueEntry, delayMs: Long) {
    val open = open(name)
    val state = when (policy) {
      ExistingWorkPolicy.KEEP -> if (open.isNotEmpty()) return else WorkInfo.State.ENQUEUED
      ExistingWorkPolicy.REPLACE -> {
        open.forEach { it.state = WorkInfo.State.CANCELLED }
        WorkInfo.State.ENQUEUED
      }
      // Behind an unfinished row a new one waits; with none it starts a fresh chain.
      ExistingWorkPolicy.APPEND_OR_REPLACE ->
        if (open.isNotEmpty()) WorkInfo.State.BLOCKED else WorkInfo.State.ENQUEUED
      else -> error("unused policy $policy")
    }
    rows += Row(serial++, name, entry.id, state, 0, now, delayMs)
  }

  override fun cancel(name: String) {
    open(name).forEach { it.state = WorkInfo.State.CANCELLED }
  }

  fun start(row: Row) {
    check(row in startableNow()) { "row ${row.serial} is not due" }
    row.state = WorkInfo.State.RUNNING
    row.runAttemptCount++
  }

  fun stop(row: Row) {
    check(row.state == WorkInfo.State.RUNNING)
    row.state = WorkInfo.State.ENQUEUED
  }

  private fun view(r: Row) = WorkRow(r.entryId, r.state, r.runAttemptCount, nextScheduleAt(r), r.initialDelay > 0)
}
