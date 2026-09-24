package ai.openspace.backgroundupload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkerOpsTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private lateinit var store: QueueStore
  private lateinit var journal: EventJournal
  private lateinit var settings: QueueSettingsStore
  private val events = RecordingEvents()
  private val scheduler = FakeScheduler()
  private var now = 20_000L
  private lateinit var ops: WorkerOps
  private lateinit var controller: QueueController

  @Before
  fun setUp() {
    val root = tmp.newFolder("queue")
    store = QueueStore(root, RequestIndex())
    journal = EventJournal(tmp.newFolder("journal"))
    settings = QueueSettingsStore(File(root, "settings.json"))
    ops = WorkerOps(store, journal, settings, events, scheduler) { now }
    controller = QueueController(store, journal, settings, events, scheduler, { false }, { now })
  }

  private val ok = UploadResponse(200, """{"id":7}""", mapOf("x" to "y"))

  @Test
  fun `begin takes a queued entry and emits running`() {
    store.save(entry(nextAttemptAt = 5))
    val e = ops.begin("e1")!!
    assertEquals(EntryState.RUNNING, e.state)
    assertNull(e.nextAttemptAt)
    assertEquals(listOf("state:e1:running"), events.log)
  }

  @Test
  fun `begin does nothing for a paused, settled, or legacy entry`() {
    store.save(entry(id = "p", state = EntryState.PAUSED))
    store.save(entry(id = "x", state = EntryState.ERROR))
    store.save(entry(id = "l", state = EntryState.QUEUED, legacy = true, descriptor = null, body = null))
    assertNull(ops.begin("p"))
    assertNull(ops.begin("x"))
    assertNull(ops.begin("l"))
    assertNull(ops.begin("nope"))
  }

  @Test
  fun `recordAttempt persists attempts and the request id before the send`() {
    store.save(entry(state = EntryState.RUNNING, attempts = 2))
    val e = ops.recordAttempt("e1", 1, "req-3")
    assertEquals(3, e.attempts)
    assertEquals("req-3", store.load("e1")!!.lastRequestId)
    assertEquals(3, store.load("e1")!!.attempts)
  }

  @Test
  fun `recordAttempt refuses once the module took the entry`() {
    store.save(entry(state = EntryState.PAUSED))
    assertThrows(NotOwnedException::class.java) { ops.recordAttempt("e1", 1, "r") }
    store.save(entry(state = EntryState.RUNNING, generation = 2))
    assertThrows(NotOwnedException::class.java) { ops.recordAttempt("e1", 1, "r") }
  }

  @Test
  fun `settle journals, transitions, then emits settled before state`() {
    store.save(entry(state = EntryState.RUNNING, attempts = 1).copy(lastRequestId = "req-1"))
    assertTrue(ops.settle("e1", 1, Settlement.Completed(ok, "https://example.com/items", "POST")))
    val record = journal.unacknowledged().single()
    assertEquals(1, record.deliveries)
    assertEquals("req-1", record.requestId)
    assertEquals(200, record.response!!.status)
    assertEquals(1, record.generation)
    val e = store.load("e1")!!
    assertEquals(EntryState.COMPLETED, e.state)
    assertEquals(record.eventId, e.settledEventId)
    assertEquals(e.totalBytes, e.bytesSent)
    assertEquals(listOf("settled:e1:completed", "state:e1:completed"), events.log)
  }

  @Test
  fun `a settle with JS dead starts at 0 deliveries, so the first replay is 1`() {
    events.live = false
    store.save(entry(state = EntryState.RUNNING))
    assertTrue(ops.settle("e1", 1, Settlement.Completed(ok, "u", "POST")))
    assertEquals(0, journal.unacknowledged().single().deliveries)
    assertEquals(listOf("state:e1:completed"), events.log) // nothing to emit to
    assertEquals(1, controller.unacknowledged().single().deliveries)
  }

  @Test
  fun `a response that lands after cancel drops its record`() {
    controller.enqueue(parsed())
    ops.begin("e1")
    controller.cancel("e1")
    events.log.clear()
    assertFalse(ops.settle("e1", 1, Settlement.Completed(ok, "u", "POST")))
    val left = journal.unacknowledged().single()
    assertEquals(EventJournal.KIND_CANCELLED, left.kind)
    assertEquals(EntryState.CANCELLED, store.load("e1")!!.state)
    assertEquals(emptyList<String>(), events.log)
  }

  @Test
  fun `a settle from an older generation is dropped`() {
    store.save(entry(state = EntryState.QUEUED, generation = 2))
    assertFalse(ops.settle("e1", 1, Settlement.Completed(ok, "u", "POST")))
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.unacknowledged())
  }

  @Test
  fun `a response that lands during pause still settles`() {
    store.save(entry(state = EntryState.PAUSED))
    assertTrue(ops.settle("e1", 1, Settlement.Failed("http", "HTTP 400", ok.copy(code = 400), null, "u", "POST")))
    val e = store.load("e1")!!
    assertEquals(EntryState.ERROR, e.state)
    assertEquals("http", journal.unacknowledged().single().errorKind)
  }

  @Test
  fun `park sets awaiting-auth once and wakes at expiry`() {
    store.save(entry(state = EntryState.RUNNING, expiresAt = 90_000))
    assertEquals(WorkerOps.ParkResult.PARKED, ops.park("e1", 1, headerGeneration = 0))
    val e = store.load("e1")!!
    assertEquals(EntryState.AWAITING_AUTH, e.state)
    assertEquals(0, e.parkedGeneration)
    assertEquals(listOf("state:e1:awaiting-auth"), events.log)
    assertEquals(listOf("e1" to 90_000L), scheduler.wakes)
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.unacknowledged())
  }

  @Test
  fun `a 401 sent under older headers re-issues instead of parking`() {
    store.save(entry(state = EntryState.RUNNING))
    controller.updateHeaders(mapOf("Authorization" to "Bearer new"))
    assertEquals(WorkerOps.ParkResult.REISSUE, ops.park("e1", 1, headerGeneration = 0))
    assertEquals(EntryState.RUNNING, store.load("e1")!!.state)
  }

  @Test
  fun `updateHeaders after a park requeues it`() {
    store.save(entry(state = EntryState.RUNNING))
    ops.park("e1", 1, 0)
    controller.updateHeaders(mapOf("Authorization" to "Bearer new"))
    assertEquals(EntryState.QUEUED, store.load("e1")!!.state)
    assertEquals(listOf("e1"), scheduler.scheduled)
  }

  @Test
  fun `release queues the entry with its wake time and streak`() {
    store.save(entry(state = EntryState.RUNNING))
    assertTrue(ops.release("e1", 1, nextAttemptAt = 80_000, streak = 7))
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(80_000L, e.nextAttemptAt)
    assertEquals(7, e.backoffStreak)
    assertEquals(listOf("e1" to 80_000L), scheduler.wakes)
    assertEquals(80_000.0, events.rows.single().toMap()["nextAttemptAt"])
  }

  @Test
  fun `a system stop queues a running entry and leaves a paused one`() {
    store.save(entry(state = EntryState.RUNNING))
    ops.stopped("e1", 1)
    assertEquals(EntryState.QUEUED, store.load("e1")!!.state)
    store.save(entry(state = EntryState.PAUSED))
    ops.stopped("e1", 1)
    assertEquals(EntryState.PAUSED, store.load("e1")!!.state)
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.unacknowledged())
  }

  @Test
  fun `markAccepted persists the flag and the accepted bytes`() {
    store.save(entry(
      state = EntryState.RUNNING,
      descriptor = desc(url = null, file = "/f", parts = listOf(part(0, 10), part(10, 25))),
      body = StagedBody(StagedBody.CHUNKED, "blob", null, 25),
    ).copy(backoffStreak = 3))
    ops.markAccepted("e1", 1, 1)
    val e = store.load("e1")!!
    assertTrue(e.descriptor!!.parts!![1].accepted)
    assertEquals(15, e.bytesSent)
    assertEquals(0, e.backoffStreak)
  }

  // MARK: - header generation of an attempt

  @Test
  fun `an attempt carries the header generation of the headers it sends`() {
    store.save(entry(state = EntryState.RUNNING))
    controller.updateHeaders(mapOf("Authorization" to "Bearer new"))
    val e = ops.recordAttempt("e1", 1, "r1")
    assertEquals(1, e.headerGeneration)
    assertEquals("Bearer new", e.descriptor!!.headers["Authorization"])
  }

  @Test
  fun `a 401 between the settings bump and the entry patch parks, and the patch requeues it`() {
    store.save(entry(state = EntryState.RUNNING))
    // updateHeaders() has bumped the settings but not yet patched the entry.
    settings.update { it.copy(headerGeneration = it.headerGeneration + 1) }
    val sent = ops.recordAttempt("e1", 1, "r1")
    assertEquals(0, sent.headerGeneration)
    assertFalse(ops.hasNewerHeaders("e1", 1, sent.headerGeneration)) // no re-issue with the same old headers
    assertEquals(WorkerOps.ParkResult.PARKED, ops.park("e1", 1, sent.headerGeneration))
    // The rest of updateHeaders() finds it parked.
    controller.updateHeaders(mapOf("Authorization" to "Bearer new"))
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals("Bearer new", e.descriptor!!.headers["Authorization"])
  }

  @Test
  fun `a re-issue sends the patched headers, and a 401 on them parks with them`() {
    store.save(entry(state = EntryState.RUNNING))
    val first = ops.recordAttempt("e1", 1, "r1")
    controller.updateHeaders(mapOf("Authorization" to "Bearer new"))
    assertTrue(ops.hasNewerHeaders("e1", 1, first.headerGeneration))
    val second = ops.recordAttempt("e1", 1, "r2")
    assertEquals("Bearer new", second.descriptor!!.headers["Authorization"])
    assertFalse(ops.hasNewerHeaders("e1", 1, second.headerGeneration))
    assertEquals(WorkerOps.ParkResult.PARKED, ops.park("e1", 1, second.headerGeneration))
    assertEquals(1, store.load("e1")!!.parkedGeneration)
  }

  // MARK: - short backoff

  @Test
  fun `a short backoff shows nextAttemptAt on the running row until the next attempt`() {
    store.save(entry(state = EntryState.RUNNING))
    ops.backingOff("e1", 1, nextAttemptAt = 24_000)
    val waiting = store.load("e1")!!
    assertEquals(EntryState.RUNNING, waiting.state)
    assertEquals(24_000L, waiting.nextAttemptAt)
    assertEquals(24_000.0, events.rows.last().toMap()["nextAttemptAt"])
    ops.recordAttempt("e1", 1, "r2")
    assertNull(store.load("e1")!!.nextAttemptAt)
    assertEquals(listOf("state:e1:running", "state:e1:running"), events.log)
    assertFalse(events.rows.last().toMap().containsKey("nextAttemptAt"))
  }

  @Test
  fun `an attempt with no pending backoff emits no state event`() {
    store.save(entry(state = EntryState.RUNNING))
    ops.recordAttempt("e1", 1, "r1")
    assertEquals(emptyList<String>(), events.log)
  }

  @Test
  fun `a short backoff on an entry the run no longer owns changes nothing`() {
    store.save(entry(state = EntryState.PAUSED))
    ops.backingOff("e1", 1, nextAttemptAt = 24_000)
    assertNull(store.load("e1")!!.nextAttemptAt)
    assertEquals(emptyList<String>(), events.log)
  }

  // MARK: - deliveries

  @Test
  fun `JS that subscribes between the check and the append still gets the outcome live with deliveries 1`() {
    // canDeliver() is false at the record build and true right after the append.
    val answers = ArrayDeque(listOf(false, true))
    val flipping = object : QueueEvents by events {
      override fun canDeliver() = answers.removeFirstOrNull() ?: true
    }
    val flipOps = WorkerOps(store, journal, settings, flipping, scheduler) { now }
    store.save(entry(state = EntryState.RUNNING))
    assertTrue(flipOps.settle("e1", 1, Settlement.Completed(ok, "u", "POST")))
    assertEquals(1, journal.unacknowledged().single().deliveries)
    assertEquals(1, events.records.single().deliveries)
  }
}
