package ai.openspace.backgroundupload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CyclicBarrier

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
  private val listener = Any()
  private lateinit var journalDir: File

  @Before
  fun setUp() {
    val root = tmp.newFolder("queue")
    store = QueueStore(root, RequestIndex())
    journalDir = tmp.newFolder("journal")
    journal = EventJournal(journalDir, retryLater = { _, _ -> })
    settings = QueueSettingsStore(File(root, "settings.json"))
    ops = WorkerOps(store, journal, settings, events, scheduler) { now }
    controller = QueueController(store, journal, settings, events, scheduler, { false }, { now })
    journal.drain(listener) // JS is subscribed
  }

  private fun <T> withJournalReadOnly(block: () -> T): T {
    journalDir.setWritable(false)
    try {
      return block()
    } finally {
      journalDir.setWritable(true)
    }
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
  fun `begin applies a record of the entry's own generation and does not run it again`() {
    // The process died between the journal write and the store transition,
    // and WorkManager runs the entry again before any boot sweep.
    val own = "00000000-0000-0000-0000-000000000021"
    val older = "00000000-0000-0000-0000-000000000022"
    store.save(entry(state = EntryState.RUNNING, generation = 2))
    journal.append(record(older, generation = 2, at = 1))
    journal.append(record(own, generation = 2, at = 2))
    assertNull(ops.begin("e1"))
    val e = store.load("e1")!!
    assertEquals(EntryState.COMPLETED, e.state)
    assertEquals(own, e.settledEventId)
    assertEquals(listOf(own), journal.unacknowledged().map { it.eventId }) // the extra one is acked
    assertEquals(listOf("state:e1:completed"), events.log)
  }

  @Test
  fun `begin runs an entry whose only record is of an older generation`() {
    store.save(entry(state = EntryState.QUEUED, generation = 2))
    journal.append(record("00000000-0000-0000-0000-000000000023", generation = 1))
    assertEquals(EntryState.RUNNING, ops.begin("e1")!!.state)
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
  fun `a settle with no listener starts at 0 deliveries, so the first replay is 1`() {
    journal.stopListening(listener)
    store.save(entry(state = EntryState.RUNNING))
    assertTrue(ops.settle("e1", 1, Settlement.Completed(ok, "u", "POST")))
    assertEquals(0, journal.unacknowledged().single().deliveries)
    assertEquals(listOf("state:e1:completed"), events.log) // nothing to emit to
    assertEquals(1, controller.unacknowledged(listener).single().deliveries)
  }

  @Test
  fun `a settle whose journal can not write holds the record, settles, and emits`() {
    store.save(entry(state = EntryState.RUNNING))
    assertTrue(withJournalReadOnly { ops.settle("e1", 1, Settlement.Completed(ok, "u", "POST")) })
    val e = store.load("e1")!!
    assertEquals(EntryState.COMPLETED, e.state)
    assertTrue(journal.isHeld(e.settledEventId!!))
    assertEquals(listOf("settled:e1:completed", "state:e1:completed"), events.log)
    // The sweep does not forget a row whose record is held, and the ack does.
    controller.sweep()
    assertNotNull(store.load("e1"))
    controller.ack(listOf(e.settledEventId!!))
    assertNull(store.load("e1"))
  }

  @Test
  fun `a settle whose store write fails leaves the record for the next run to apply`() {
    store.save(entry(state = EntryState.RUNNING))
    val dir = store.entryDir("e1")
    dir.setWritable(false)
    val stood = try {
      ops.settle("e1", 1, Settlement.Completed(ok, "u", "POST"))
    } finally {
      dir.setWritable(true)
    }
    assertTrue(stood)
    assertEquals(EntryState.RUNNING, store.load("e1")!!.state)
    val record = journal.unacknowledged().single()
    assertEquals(listOf("settled:e1:completed"), events.log) // no state event: the row did not change
    // WorkManager runs it again: begin applies the record, no second send.
    assertNull(ops.begin("e1"))
    assertEquals(record.eventId, store.load("e1")!!.settledEventId)
  }

  @Test
  fun `a settle after a cancel whose save failed applies the cancel, not a second outcome`() {
    // cancel() journaled its record, then its entry save failed: the entry
    // is still running, and its request comes back.
    val cancelId = "00000000-0000-0000-0000-000000000041"
    store.save(entry(state = EntryState.RUNNING))
    journal.append(record(cancelId, kind = EventJournal.KIND_CANCELLED))
    assertFalse(ops.settle("e1", 1, Settlement.Completed(ok, "u", "POST")))
    val e = store.load("e1")!!
    assertEquals(EntryState.CANCELLED, e.state)
    assertEquals(cancelId, e.settledEventId)
    assertEquals(listOf(cancelId), journal.unacknowledged().map { it.eventId })
    assertEquals(listOf("state:e1:cancelled"), events.log) // no second outcome
  }

  @Test
  fun `a live settle goes to the listener that drained, not the newest module`() {
    store.save(entry(id = "a", state = EntryState.RUNNING))
    ops.settle("a", 1, Settlement.Completed(ok, "u", "POST"))
    // A reload: the next module's JS drains and takes over.
    val next = Any()
    controller.unacknowledged(next)
    store.save(entry(id = "b", state = EntryState.RUNNING))
    ops.settle("b", 1, Settlement.Completed(ok, "u", "POST"))
    assertEquals(2, events.listeners.size)
    assertTrue(events.listeners[0] === listener)
    assertTrue(events.listeners[1] === next)
  }

  @Test
  fun `a settle over the journal cap spares every record a row names`() {
    val small = EventJournal(tmp.newFolder("small"), maxEntries = 2)
    val smallOps = WorkerOps(store, small, settings, events, scheduler) { now }
    val named = "00000000-0000-0000-0000-000000000031"
    small.append(record(named, id = "done"))
    store.save(entry(id = "done", state = EntryState.ERROR, settledEventId = named))
    File(tmp.root, "small/$named.json").setLastModified(1_000)
    small.append(record("00000000-0000-0000-0000-000000000032", id = "orphan"))
    File(tmp.root, "small/00000000-0000-0000-0000-000000000032.json").setLastModified(2_000)
    store.save(entry(state = EntryState.RUNNING))
    smallOps.settle("e1", 1, Settlement.Completed(ok, "u", "POST"))
    val left = small.unacknowledged().map { it.eventId }
    assertTrue(left.contains(named))
    assertTrue(left.contains(store.load("e1")!!.settledEventId))
    assertEquals(2, left.size)
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
  fun `an accepted response that lands during pause still settles`() {
    store.save(entry(state = EntryState.PAUSED))
    assertTrue(ops.settle("e1", 1, Settlement.Completed(ok, "u", "POST")))
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)
  }

  @Test
  fun `a failure that lands during pause does not settle`() {
    store.save(entry(state = EntryState.PAUSED))
    assertFalse(ops.settle("e1", 1, Settlement.Failed("http", "HTTP 400", ok.copy(code = 400), null, "u", "POST")))
    assertEquals(EntryState.PAUSED, store.load("e1")!!.state)
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.unacknowledged())
    assertEquals(emptyList<String>(), events.log)
  }

  @Test
  fun `a failed simple settle keeps the live bytes, and a chunked one keeps its accepted bytes`() {
    store.save(entry(state = EntryState.RUNNING))
    ops.settle("e1", 1, Settlement.Failed("http", "HTTP 400", ok.copy(code = 400), null, "u", "POST", bytesSent = 5))
    assertEquals(5, store.load("e1")!!.bytesSent)
    assertEquals(5, journal.unacknowledged().single().bytesSent)
    store.save(entry(id = "c", state = EntryState.RUNNING).copy(bytesSent = 10))
    ops.settle("c", 1, Settlement.Failed("file", "gone", null, 1, "u", "PUT"))
    assertEquals(10, store.load("c")!!.bytesSent)
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
  fun `a settle racing the first drain reaches JS exactly once with deliveries 1`() {
    // The drain sets the listener and scans under one journal lock, and the
    // append decides 0 or 1 under it. Either the drain returns the record,
    // or the settle emits it live; never both, never neither.
    repeat(200) { i ->
      val id = "race-$i"
      val owner = Any()
      journal.stopListening(listener)
      journal.stopListening(owner)
      store.save(entry(id = id, state = EntryState.RUNNING))
      events.records.clear()
      val start = CyclicBarrier(2)
      var drained: List<EventJournal.SettledRecord> = emptyList()
      val drain = Thread { start.await(); drained = controller.unacknowledged(owner).filter { it.id == id } }
      drain.start()
      start.await()
      ops.settle(id, 1, Settlement.Completed(ok, "u", "POST"))
      drain.join()
      val live = events.records.filter { it.id == id }
      val seen = drained + live
      assertEquals("iteration $i", 1, seen.size)
      assertEquals("iteration $i", 1, seen.single().deliveries)
      journal.ack(listOf(seen.single().eventId))
    }
  }
}
