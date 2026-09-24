package ai.openspace.backgroundupload

import org.junit.Assert.assertArrayEquals
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

class QueueControllerTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private lateinit var root: File
  private lateinit var store: QueueStore
  private lateinit var journal: EventJournal
  private lateinit var settings: QueueSettingsStore
  private val events = RecordingEvents()
  private val scheduler = FakeScheduler()
  private val running = mutableSetOf<String>()
  private var now = 10_000L
  private lateinit var controller: QueueController

  @Before
  fun setUp() {
    root = tmp.newFolder("queue")
    store = QueueStore(root, RequestIndex())
    journal = EventJournal(tmp.newFolder("journal"))
    settings = QueueSettingsStore(File(root, "settings.json"))
    controller = QueueController(store, journal, settings, events, scheduler, { it in running }, { now })
  }

  private fun source(name: String, size: Int) = File(tmp.newFolder(), name).apply { writeBytes(ByteArray(size) { it.toByte() }) }

  private fun dirFiles(id: String = "e1") = store.entryDir(id).list()!!.toSet()

  // MARK: - enqueue

  @Test
  fun `create stages the json body, persists, schedules, then emits`() {
    assertEquals("e1", controller.enqueue(parsed()))
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(1, e.generation)
    assertEquals("""{"a":1}""", File(store.entryDir("e1"), e.body!!.fileName!!).readText())
    assertEquals("application/json", e.descriptor!!.headers["Content-Type"])
    assertEquals(setOf("entry.json", "body-1.json"), dirFiles())
    assertEquals(listOf("e1"), scheduler.scheduled)
    assertEquals(listOf("state:e1:queued"), events.log)
  }

  @Test
  fun `create while paused is paused and not scheduled`() {
    controller.pause()
    controller.enqueue(parsed())
    assertEquals(EntryState.PAUSED, store.load("e1")!!.state)
    assertEquals(emptyList<String>(), scheduler.scheduled)
  }

  @Test
  fun `a file body is copied, so the caller may delete its source`() {
    val src = source("photo.jpg", 100)
    controller.enqueue(parsed(descriptor = desc(file = src.path)))
    src.delete()
    val e = store.load("e1")!!
    assertEquals(100, File(store.entryDir("e1"), e.body!!.fileName!!).length())
    assertEquals(100, e.totalBytes)
  }

  @Test
  fun `a missing file rejects E_FILE_MISSING and persists nothing`() {
    val e = assertThrows(QueueException::class.java) { controller.enqueue(parsed(descriptor = desc(file = "/nope.bin"))) }
    assertEquals(QueueException.E_FILE_MISSING, e.code)
    assertNull(store.load("e1"))
    assertEquals(emptyList<String>(), events.log)
  }

  @Test
  fun `same body on a queued entry resumes with the new headers, vars, and expiry`() {
    controller.enqueue(parsed())
    val bodyFile = store.load("e1")!!.body!!.fileName
    controller.enqueue(parsed(descriptor = desc(dataJson = """{"a":1}""", headers = mapOf("Authorization" to "Bearer new")), varsJson = """{"n":2}""", expiresAt = 5))
    val e = store.load("e1")!!
    assertEquals("Bearer new", e.descriptor!!.headers["Authorization"])
    assertEquals("""{"n":2}""", e.varsJson)
    assertEquals(5, e.expiresAt)
    assertEquals(1, e.generation)
    assertEquals(bodyFile, e.body!!.fileName)
  }

  @Test
  fun `same body on a running entry stays running and is not scheduled again`() {
    store.save(entry(state = EntryState.RUNNING))
    controller.enqueue(parsed(descriptor = desc(dataJson = """{"a":1}""", headers = mapOf("Authorization" to "Bearer new"))))
    val e = store.load("e1")!!
    assertEquals(EntryState.RUNNING, e.state)
    assertEquals("Bearer new", e.descriptor!!.headers["Authorization"])
    assertEquals(emptyList<String>(), scheduler.scheduled)
  }

  @Test
  fun `a different body on a running entry rejects E_RUNNING and changes nothing`() {
    store.save(entry(state = EntryState.RUNNING))
    val e = assertThrows(QueueException::class.java) { controller.enqueue(parsed(descriptor = desc(dataJson = """{"a":2}"""))) }
    assertEquals(QueueException.E_RUNNING, e.code)
    assertEquals(entry(state = EntryState.RUNNING), store.load("e1"))
  }

  @Test
  fun `a different body on a queued entry with a worker sleeping out a short backoff is accepted`() {
    // The gate is held, but the entry is queued. The contract: queued is not running.
    controller.enqueue(parsed())
    running += "e1"
    controller.enqueue(parsed(descriptor = desc(dataJson = """{"a":2}""")))
    assertEquals("""{"a":2}""", store.load("e1")!!.descriptor!!.dataJson)
  }

  @Test
  fun `a different body on an error entry replaces it and reopens it`() {
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(state = EntryState.ERROR, attempts = 3, settledEventId = "ev"))
    controller.enqueue(parsed(descriptor = desc(dataJson = """{"a":2}""")))
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(2, e.generation)
    assertEquals(0, e.attempts)
    assertNull(e.settledEventId)
    assertEquals("""{"a":2}""", File(store.entryDir("e1"), "body-2.json").readText())
    assertEquals(setOf("entry.json", "body-2.json"), dirFiles()) // the old body is pruned
  }

  @Test
  fun `same body on a completed unacked entry re-emits with one more delivery and does not re-run`() {
    controller.enqueue(parsed())
    val eventId = "00000000-0000-0000-0000-00000000000a"
    journal.append(record(eventId))
    store.save(store.load("e1")!!.copy(state = EntryState.COMPLETED, settledEventId = eventId))
    scheduler.scheduled.clear()
    events.log.clear()
    controller.enqueue(parsed())
    assertEquals(listOf("settled:e1:completed"), events.log)
    assertEquals(2, events.records.single().deliveries)
    assertEquals(2, journal.find(eventId)!!.deliveries)
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)
    assertEquals(emptyList<String>(), scheduler.scheduled)
  }

  @Test
  fun `same body on a cancelled unacked entry gets a fresh generation, and the old ack forgets nothing`() {
    controller.enqueue(parsed())
    controller.cancel("e1")
    val cancelled = journal.unacknowledged().single()
    controller.enqueue(parsed())
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(2, e.generation)
    controller.ack(listOf(cancelled.eventId))
    assertNotNull(store.load("e1"))
  }

  @Test
  fun `same body on an error entry reopens it and keeps attempts`() {
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(state = EntryState.ERROR, attempts = 3, settledEventId = "ev"))
    controller.enqueue(parsed(expiresAt = FAR_FUTURE + 1))
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(2, e.generation)
    assertEquals(3, e.attempts)
  }

  @Test
  fun `enqueue over a legacy row replaces it`() {
    store.save(LegacyImport.legacyRow(LegacyImport.V9Entry("x", "e1", "completed", 5))!!)
    controller.enqueue(parsed())
    val e = store.load("e1")!!
    assertFalse(e.legacy)
    assertEquals(2, e.generation)
    assertEquals(EntryState.QUEUED, e.state)
  }

  // MARK: - chunked replace (a present file wins over the old blob)

  private fun chunkedErrorEntry(): File {
    val first = source("first.bin", 20)
    controller.enqueue(parsed(descriptor = desc(url = null, method = "PUT", file = first.path, parts = listOf(part(0, 10), part(10, 20)))))
    store.save(store.load("e1")!!.copy(state = EntryState.ERROR, settledEventId = "ev"))
    return File(store.entryDir("e1"), "blob")
  }

  @Test
  fun `a different-parts replace with a present file uploads that file, not the old blob`() {
    chunkedErrorEntry()
    val bytes = ByteArray(20) { (it * 3).toByte() }
    val second = File(tmp.newFolder(), "second.bin").apply { writeBytes(bytes) }
    controller.enqueue(parsed(descriptor = desc(url = null, method = "PUT", file = second.path, parts = listOf(part(0, 20)))))
    val e = store.load("e1")!!
    assertEquals(2, e.generation)
    assertEquals("blob-2", e.body!!.fileName)
    assertArrayEquals(bytes, store.bodyFile(e)!!.readBytes())
    assertEquals(setOf("entry.json", "blob-2"), dirFiles()) // the old blob is pruned
  }

  @Test
  fun `a different-parts replace with a present file of another size is accepted`() {
    chunkedErrorEntry()
    val second = source("second.bin", 30)
    controller.enqueue(parsed(descriptor = desc(url = null, method = "PUT", file = second.path, parts = listOf(part(0, 30)))))
    assertEquals(30, store.load("e1")!!.totalBytes)
  }

  @Test
  fun `a different-parts replace whose file was moved away runs over the old blob`() {
    chunkedErrorEntry()
    controller.enqueue(parsed(descriptor = desc(url = null, method = "PUT", file = "/moved.bin", parts = listOf(part(0, 20)))))
    val e = store.load("e1")!!
    assertEquals("blob", e.body!!.fileName)
    assertEquals(setOf("entry.json", "blob"), dirFiles())
  }

  @Test
  fun `a replace whose plan does not tile the present file changes nothing`() {
    val oldBlob = chunkedErrorEntry()
    val second = source("second.bin", 30)
    val e = assertThrows(QueueException::class.java) {
      controller.enqueue(parsed(descriptor = desc(url = null, method = "PUT", file = second.path, parts = listOf(part(0, 20)))))
    }
    assertEquals(QueueException.E_INVALID, e.code)
    assertTrue(second.exists())
    assertEquals(20, oldBlob.length())
    assertEquals(1, store.load("e1")!!.generation)
  }

  // MARK: - staging outside the lock

  @Test
  fun `a new file body is copied outside the store lock`() {
    val src = source("big.bin", 1000)
    var hookRan = false
    controller.afterPreStage = {
      hookRan = true
      assertFalse(Thread.holdsLock(store))
      assertTrue(File(store.entryDir("e1"), "file-1").exists())
    }
    controller.enqueue(parsed(descriptor = desc(file = src.path)))
    assertTrue(hookRan)
    assertEquals("file-1", store.load("e1")!!.body!!.fileName)
    assertEquals(setOf("entry.json", "file-1"), dirFiles())
  }

  @Test
  fun `a replace over a queued entry stages under the lock`() {
    controller.enqueue(parsed())
    var hookRan = false
    controller.afterPreStage = { hookRan = true }
    controller.enqueue(parsed(descriptor = desc(dataJson = """{"a":2}""")))
    assertFalse(hookRan)
    assertEquals(setOf("entry.json", "body-2.json"), dirFiles())
  }

  @Test
  fun `a pre-staged body that no longer fits is deleted and staged again under the lock`() {
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(state = EntryState.ERROR))
    // Between the staging and the lock, the entry moves on to another generation.
    controller.afterPreStage = { store.save(store.load("e1")!!.copy(generation = 5)) }
    controller.enqueue(parsed(descriptor = desc(dataJson = """{"a":2}""")))
    val e = store.load("e1")!!
    assertEquals(6, e.generation)
    assertEquals("body-6.json", e.body!!.fileName)
    assertEquals(setOf("entry.json", "body-6.json"), dirFiles()) // body-2.json is gone
  }

  @Test
  fun `a pre-staged body is deleted when the enqueue rejects`() {
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(state = EntryState.ERROR))
    controller.afterPreStage = { store.save(store.load("e1")!!.copy(state = EntryState.RUNNING)) }
    val e = assertThrows(QueueException::class.java) { controller.enqueue(parsed(descriptor = desc(dataJson = """{"a":2}"""))) }
    assertEquals(QueueException.E_RUNNING, e.code)
    assertEquals(setOf("entry.json", "body-1.json"), dirFiles())
  }

  // MARK: - v9 adoption

  private fun v9Dir(id: String, parts: String, blobSize: Int): File {
    val dir = store.entryDir(id).apply { mkdirs() }
    File(dir, "blob").writeBytes(ByteArray(blobSize))
    File(dir, "manifest.json").writeText(
      """{"id":"$id","sourcePath":"${File(dir, "blob").path}","parts":$parts,"accept":[],"expiresAt":1,"wifiOnly":false,"noNotification":false,"createdAt":1}""",
    )
    return dir
  }

  private val v9Parts = """[{"url":"https://example.com/part?start=0","headers":{},"start":0,"end":10,"accepted":true},""" +
    """{"url":"https://example.com/part?start=10","headers":{},"start":10,"end":20,"accepted":false}]"""

  @Test
  fun `a same-id enqueue with the same parts adopts the v9 blob and accepted parts`() {
    v9Dir("e1", v9Parts, 20)
    controller.enqueue(parsed(descriptor = desc(url = null, method = "PUT", file = "/gone.bin", parts = listOf(part(0, 10), part(10, 20)))))
    val e = store.load("e1")!!
    assertTrue(e.descriptor!!.parts!![0].accepted)
    assertFalse(e.descriptor!!.parts!![1].accepted)
    assertEquals(10, e.bytesSent)
    assertEquals(setOf("entry.json", "blob"), dirFiles())
  }

  @Test
  fun `a v9 adoption with the same parts keeps the v9 blob even when the caller's file is present`() {
    v9Dir("e1", v9Parts, 20)
    val present = source("again.bin", 20)
    controller.enqueue(parsed(descriptor = desc(url = null, method = "PUT", file = present.path, parts = listOf(part(0, 10), part(10, 20)))))
    assertTrue(present.exists())
    assertTrue(store.load("e1")!!.descriptor!!.parts!![0].accepted)
    assertArrayEquals(ByteArray(20), File(store.entryDir("e1"), "blob").readBytes())
  }

  @Test
  fun `a v9 adoption with different parts and a present file uploads that file`() {
    v9Dir("e1", v9Parts, 20)
    val present = source("new.bin", 30)
    controller.enqueue(parsed(descriptor = desc(url = null, method = "PUT", file = present.path, parts = listOf(part(0, 30)))))
    assertFalse(present.exists())
    val e = store.load("e1")!!
    assertFalse(e.descriptor!!.parts!![0].accepted)
    assertEquals(30, store.bodyFile(e)!!.length())
    assertEquals(setOf("entry.json", "blob"), dirFiles())
  }

  @Test
  fun `a v9 adoption with parts that do not tile rejects E_INVALID and keeps the v9 files`() {
    v9Dir("e1", v9Parts, 20)
    val e = assertThrows(QueueException::class.java) {
      controller.enqueue(parsed(descriptor = desc(url = null, method = "PUT", file = "/gone.bin", parts = listOf(part(0, 30)))))
    }
    assertEquals(QueueException.E_INVALID, e.code)
    assertEquals(setOf("manifest.json", "blob"), dirFiles())
  }

  // MARK: - cancel

  @Test
  fun `cancel of a live entry journals, settles cancelled, stops work, emits, and forgets after ack`() {
    controller.enqueue(parsed())
    events.log.clear()
    controller.cancel("e1")
    val record = journal.unacknowledged().single()
    assertEquals(EventJournal.KIND_CANCELLED, record.kind)
    assertEquals("user", record.cancelReason)
    assertEquals("https://example.com/items", record.url)
    val e = store.load("e1")!!
    assertEquals(EntryState.CANCELLED, e.state)
    assertEquals(record.eventId, e.settledEventId)
    assertEquals(listOf("e1"), scheduler.cancelled)
    assertEquals(listOf("settled:e1:cancelled", "state:e1:cancelled"), events.log)
    controller.ack(listOf(record.eventId))
    assertNull(store.load("e1"))
    assertFalse(store.entryDir("e1").exists())
  }

  @Test
  fun `cancel of a settled entry forgets it now and keeps its unacked record`() {
    controller.enqueue(parsed())
    val eventId = "00000000-0000-0000-0000-00000000000b"
    journal.append(record(eventId, kind = EventJournal.KIND_ERROR))
    store.save(store.load("e1")!!.copy(state = EntryState.ERROR, settledEventId = eventId))
    events.log.clear()
    controller.cancel("e1")
    assertNull(store.load("e1"))
    assertFalse(store.entryDir("e1").exists())
    assertEquals(emptyList<String>(), events.log)
    assertNotNull(journal.find(eventId))
  }

  @Test
  fun `cancel of an unknown id is a no-op`() {
    controller.cancel("nope")
    assertEquals(emptyList<String>(), events.log)
  }

  // MARK: - pause, resume, wifi, headers

  @Test
  fun `pause moves live rows to paused with no outcome, and resume brings them back`() {
    store.save(entry(id = "q", state = EntryState.QUEUED))
    store.save(entry(id = "r", state = EntryState.RUNNING))
    store.save(entry(id = "a", state = EntryState.AWAITING_AUTH, parkedGeneration = 0))
    store.save(entry(id = "s", state = EntryState.AWAITING_AUTH, parkedGeneration = 0))
    store.save(entry(id = "x", state = EntryState.ERROR))
    controller.pause()
    assertTrue(settings.load().paused)
    assertEquals(listOf("paused", "paused", "paused", "paused", "error"), listOf("q", "r", "a", "s", "x").map { store.load(it)!!.state.wire })
    assertEquals(setOf("q", "r", "a", "s"), scheduler.cancelled.toSet())
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.unacknowledged())

    // A header change while paused makes one parked entry's generation stale.
    store.save(store.load("s")!!.copy(parkedGeneration = -1))
    controller.resume()
    assertFalse(settings.load().paused)
    assertEquals(EntryState.QUEUED, store.load("q")!!.state)
    assertEquals(EntryState.QUEUED, store.load("r")!!.state)
    assertEquals(EntryState.AWAITING_AUTH, store.load("a")!!.state)
    assertEquals(EntryState.QUEUED, store.load("s")!!.state)
    assertTrue(scheduler.scheduled.containsAll(listOf("q", "r", "s")))
    assertEquals(listOf("a" to FAR_FUTURE), scheduler.wakes) // the parked one waits for its expiry
  }

  @Test
  fun `setWifiOnly persists`() {
    controller.setWifiOnly(true)
    assertTrue(QueueSettingsStore(File(root, "settings.json")).load().wifiOnly)
  }

  @Test
  fun `updateHeaders patches every entry, bumps the generation, and requeues the parked`() {
    store.save(entry(id = "p", state = EntryState.AWAITING_AUTH, parkedGeneration = 0))
    store.save(entry(id = "x", state = EntryState.ERROR))
    store.save(entry(id = "c", state = EntryState.QUEUED, descriptor = desc(url = null, file = "/f",
      parts = listOf(Part("https://p/1", mapOf("authorization" to "stale"), 0, 10)))))
    controller.updateHeaders(mapOf("Authorization" to "Bearer new"))
    assertEquals(1, settings.load().headerGeneration)
    val p = store.load("p")!!
    assertEquals(EntryState.QUEUED, p.state)
    assertNull(p.parkedGeneration)
    assertEquals("Bearer new", p.descriptor!!.headers["Authorization"])
    assertEquals(1, p.headerGeneration)
    assertEquals("Bearer new", store.load("x")!!.descriptor!!.headers["Authorization"])
    assertEquals(mapOf("Authorization" to "Bearer new"), store.load("c")!!.descriptor!!.parts!![0].headers)
    assertEquals(listOf("p"), scheduler.scheduled)
    assertEquals(listOf("state:p:queued"), events.log)
  }

  // MARK: - ack and replay

  @Test
  fun `ack forgets a completed entry of the current generation only`() {
    val current = "00000000-0000-0000-0000-00000000000c"
    val old = "00000000-0000-0000-0000-00000000000d"
    store.save(entry(state = EntryState.COMPLETED, settledEventId = current, generation = 2))
    journal.append(record(old, generation = 1))
    journal.append(record(current, generation = 2))
    controller.ack(listOf(old, "unknown", "../../x"))
    assertNotNull(store.load("e1"))
    controller.ack(listOf(current))
    assertNull(store.load("e1"))
    assertEquals(listOf("e1"), scheduler.cancelled)
    controller.ack(listOf(current)) // idempotent
  }

  @Test
  fun `ack of an error removes the record and keeps the row`() {
    val id = "00000000-0000-0000-0000-00000000000e"
    store.save(entry(state = EntryState.ERROR, settledEventId = id))
    journal.append(record(id, kind = EventJournal.KIND_ERROR))
    controller.ack(listOf(id))
    assertNull(journal.find(id))
    assertNotNull(store.load("e1"))
  }

  // A settle journaled and emitted its record, but the store write failed:
  // the entry is still live at the record's generation.

  @Test
  fun `ack of a completed record on a still-running entry settles and forgets it, so the sweep does not re-run it`() {
    val id = "00000000-0000-0000-0000-000000000011"
    store.save(entry(state = EntryState.RUNNING))
    journal.append(record(id))
    controller.ack(listOf(id))
    assertNull(store.load("e1"))
    assertNull(journal.find(id))
    controller.sweep()
    assertEquals(emptyList<String>(), scheduler.scheduled)
  }

  @Test
  fun `ack of an error record on a still-running entry settles it as error and keeps the row`() {
    val id = "00000000-0000-0000-0000-000000000012"
    store.save(entry(state = EntryState.RUNNING))
    journal.append(record(id, kind = EventJournal.KIND_ERROR))
    controller.ack(listOf(id))
    val e = store.load("e1")!!
    assertEquals(EntryState.ERROR, e.state)
    assertEquals(id, e.settledEventId)
    assertNull(journal.find(id))
    assertEquals(listOf("state:e1:error"), events.log)
    controller.sweep()
    assertEquals(emptyList<String>(), scheduler.scheduled)
  }

  @Test
  fun `ack of a record of an older generation does not settle the live entry`() {
    val id = "00000000-0000-0000-0000-000000000013"
    store.save(entry(state = EntryState.QUEUED, generation = 2))
    journal.append(record(id, generation = 1))
    controller.ack(listOf(id))
    assertEquals(EntryState.QUEUED, store.load("e1")!!.state)
    assertNull(journal.find(id))
  }

  @Test
  fun `when the ack repair can not save, the record stays for the sweep`() {
    val id = "00000000-0000-0000-0000-000000000014"
    store.save(entry(state = EntryState.RUNNING))
    journal.append(record(id))
    val dir = store.entryDir("e1")
    dir.setWritable(false)
    try {
      controller.ack(listOf(id))
    } finally {
      dir.setWritable(true)
    }
    assertNotNull(journal.find(id))
    assertEquals(EntryState.RUNNING, store.load("e1")!!.state)
    controller.sweep()
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)
  }

  @Test
  fun `each replay counts one more delivery`() {
    journal.append(record("00000000-0000-0000-0000-00000000000f"))
    assertEquals(2, controller.unacknowledged().single().deliveries)
    assertEquals(3, controller.unacknowledged().single().deliveries)
  }

  // MARK: - boot sweep

  @Test
  fun `sweep applies a record that the store transition missed`() {
    val id = "00000000-0000-0000-0000-000000000010"
    store.save(entry(state = EntryState.RUNNING))
    journal.append(record(id))
    controller.sweep()
    val e = store.load("e1")!!
    assertEquals(EntryState.COMPLETED, e.state)
    assertEquals(id, e.settledEventId)
    assertEquals(listOf("state:e1:completed"), events.log)
  }

  @Test
  fun `sweep applies a cancel whose store save was lost`() {
    val id = "00000000-0000-0000-0000-000000000011"
    store.save(entry(state = EntryState.QUEUED))
    journal.append(record(id, kind = EventJournal.KIND_CANCELLED))
    controller.sweep()
    assertEquals(EntryState.CANCELLED, store.load("e1")!!.state)
    assertEquals(emptyList<String>(), scheduler.scheduled)
  }

  @Test
  fun `sweep queues a running entry with no worker and schedules queued work`() {
    store.save(entry(id = "r", state = EntryState.RUNNING))
    store.save(entry(id = "q", state = EntryState.QUEUED))
    store.save(entry(id = "w", state = EntryState.QUEUED, nextAttemptAt = now + 3_600_000))
    store.save(entry(id = "p", state = EntryState.AWAITING_AUTH, expiresAt = now + 50))
    controller.sweep()
    assertEquals(EntryState.QUEUED, store.load("r")!!.state)
    assertEquals(setOf("r", "q"), scheduler.scheduled.toSet())
    assertEquals(setOf("w" to now + 3_600_000, "p" to now + 50), scheduler.wakes.toSet())
  }

  @Test
  fun `sweep skips an entry whose worker runs in this process`() {
    store.save(entry(state = EntryState.RUNNING))
    journal.append(record("00000000-0000-0000-0000-000000000012"))
    running += "e1"
    controller.sweep()
    assertEquals(EntryState.RUNNING, store.load("e1")!!.state)
  }

  @Test
  fun `sweep forgets a completed entry whose record was acked, and acks orphans`() {
    store.save(entry(id = "done", state = EntryState.COMPLETED, settledEventId = "gone"))
    val own = "00000000-0000-0000-0000-000000000013"
    val orphan = "00000000-0000-0000-0000-000000000014"
    store.save(entry(id = "c", state = EntryState.CANCELLED, settledEventId = own))
    journal.append(record(own, id = "c", kind = EventJournal.KIND_CANCELLED))
    journal.append(record(orphan, id = "c"))
    controller.sweep()
    assertNull(store.load("done"))
    assertEquals(listOf(own), journal.unacknowledged().map { it.eventId })
    assertNotNull(store.load("c"))
  }

  @Test
  fun `sweep leaves paused entries alone`() {
    controller.pause()
    store.save(entry(state = EntryState.PAUSED))
    controller.sweep()
    assertEquals(EntryState.PAUSED, store.load("e1")!!.state)
    assertEquals(emptyList<String>(), scheduler.scheduled)
  }

  @Test
  fun `sweep finishes a pause or resume that a process death cut short`() {
    // resume() saved the setting, then died before the rows.
    store.save(entry(id = "p", state = EntryState.PAUSED))
    controller.sweep()
    assertEquals(EntryState.QUEUED, store.load("p")!!.state)
    assertEquals(listOf("p"), scheduler.scheduled)

    // pause() saved the setting, then died before the rows.
    settings.update { it.copy(paused = true) }
    store.save(entry(id = "q", state = EntryState.QUEUED))
    store.save(entry(id = "r", state = EntryState.RUNNING))
    controller.sweep()
    assertEquals(EntryState.PAUSED, store.load("q")!!.state)
    assertEquals(EntryState.PAUSED, store.load("r")!!.state)
    assertEquals(listOf("p"), scheduler.scheduled)
  }
}
