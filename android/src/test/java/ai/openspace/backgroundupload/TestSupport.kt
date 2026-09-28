package ai.openspace.backgroundupload

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
) = Descriptor(url, method, headers, dataJson, form, file, parts, accept, retry, noNotification)

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
  val cancelled = mutableListOf<String>()

  override fun schedule(entry: QueueEntry) {
    scheduled += entry.id
  }

  override fun scheduleWake(entry: QueueEntry, at: Long, replace: Boolean) {
    wakes += entry.id to at
  }

  override fun cancel(id: String) {
    cancelled += id
  }
}
