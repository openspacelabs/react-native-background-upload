package ai.openspace.backgroundupload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class EventJournalTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private val id1 = "00000000-0000-0000-0000-000000000001"
  private val id2 = "00000000-0000-0000-0000-000000000002"

  private val listener = Any()

  @Test
  fun `append then read returns the record`() {
    val journal = EventJournal(tmp.newFolder())
    journal.drain(listener)
    assertEquals(record(id1), journal.append(record(id1)))
    assertEquals(listOf(record(id1)), journal.unacknowledged())
  }

  @Test
  fun `append starts at 1 delivery with a listener and at 0 without one`() {
    val journal = EventJournal(tmp.newFolder())
    assertFalse(journal.isListening())
    assertEquals(0, journal.append(record(id1)).deliveries)
    assertEquals(listOf(1), journal.drain(listener).map { it.deliveries }) // the drain delivers it
    assertTrue(journal.isListening())
    assertEquals(1, journal.append(record(id2)).deliveries)
  }

  @Test
  fun `a drain for a torn-down module sets no listener and counts nothing`() {
    val journal = EventJournal(tmp.newFolder())
    journal.append(record(id1))
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.drain(listener) { false })
    assertFalse(journal.isListening())
    assertEquals(0, journal.find(id1)!!.deliveries)
  }

  @Test
  fun `listener is the owner of the last drain`() {
    val journal = EventJournal(tmp.newFolder())
    assertNull(journal.listener())
    journal.drain(listener)
    assertTrue(journal.listener() === listener)
    val next = Any()
    journal.drain(next)
    assertTrue(journal.listener() === next)
  }

  @Test
  fun `stopListening clears only its own listener`() {
    val journal = EventJournal(tmp.newFolder())
    val next = Any()
    journal.drain(listener)
    journal.drain(next) // a reload: the next module drains before the old one is torn down
    journal.stopListening(listener)
    assertTrue(journal.isListening())
    journal.stopListening(next)
    assertFalse(journal.isListening())
  }

  @Test
  fun `redeliver counts a delivery only with a listener`() {
    val journal = EventJournal(tmp.newFolder())
    journal.append(record(id1))
    assertNull(journal.redeliver(id1))
    assertEquals(0, journal.find(id1)!!.deliveries)
    journal.drain(listener) // 1
    assertEquals(2, journal.redeliver(id1)!!.deliveries)
  }

  @Test
  fun `ack removes only the acked record and is idempotent`() {
    val journal = EventJournal(tmp.newFolder())
    journal.append(record(id1))
    journal.append(record(id2))
    journal.ack(listOf(id1, id1, "unknown"))
    assertEquals(listOf(id2), journal.unacknowledged().map { it.eventId })
  }

  @Test
  fun `ack ignores ids that are not event ids`() {
    val dir = tmp.newFolder()
    val outside = File(dir.parentFile, "precious.json").apply { writeText("x") }
    EventJournal(dir).ack(listOf("../precious"))
    assertTrue(outside.exists())
  }

  @Test
  fun `records survive a new journal instance`() {
    val dir = tmp.newFolder()
    EventJournal(dir).append(record(id1))
    assertEquals(1, EventJournal(dir).unacknowledged().size)
  }

  @Test
  fun `a body over 1 MB of UTF-8 is cut on a character and flagged`() {
    val journal = EventJournal(tmp.newFolder())
    // 2-byte characters, so a char cap would keep 2 MB.
    val big = "\u00e9".repeat(BodyCap.SETTLED_MAX_BYTES)
    journal.append(record(id1).copy(response = EventJournal.Response(200, null, big, false)))
    val read = journal.unacknowledged()[0].response!!
    assertTrue(read.bodyTruncated)
    assertEquals(BodyCap.SETTLED_MAX_BYTES, read.body!!.toByteArray(Charsets.UTF_8).size)
    assertEquals(BodyCap.SETTLED_MAX_BYTES / 2, read.body!!.length)
  }

  @Test
  fun `a response the stream cap cut stays flagged`() {
    val r = EventJournal.Response.of(UploadResponse(500, "partial", mapOf(), truncated = true))
    assertEquals("partial", r.body)
    assertTrue(r.bodyTruncated)
    assertFalse(EventJournal.Response.of(UploadResponse(500, "whole", mapOf())).bodyTruncated)
  }

  @Test
  fun `a corrupt file is skipped`() {
    val dir = tmp.newFolder()
    val journal = EventJournal(dir)
    journal.append(record(id1))
    File(dir, "garbage.json").writeText("{not json")
    File(dir, "partial.json").writeText("""{"eventId":"x"}""")
    assertEquals(listOf(id1), journal.unacknowledged().map { it.eventId })
  }

  @Test
  fun `records are ordered by time`() {
    val journal = EventJournal(tmp.newFolder())
    journal.append(record(id1, at = 2_000))
    journal.append(record(id2, at = 1_000))
    assertEquals(listOf(id2, id1), journal.unacknowledged().map { it.eventId })
  }

  @Test
  fun `append throws when it could not write and keeps nothing`() {
    val journal = EventJournal(tmp.newFile()) // a file where the directory should be
    assertThrows(IOException::class.java) { journal.append(record(id1)) }
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.unacknowledged())
  }

  @Test
  fun `appendOrHold holds a record it could not write, and every read and ack sees it`() {
    val tasks = mutableListOf<Pair<Long, () -> Unit>>()
    val dir = tmp.newFolder()
    val journal = EventJournal(dir, retryLater = { delay, task -> tasks += delay to task })
    dir.setWritable(false)
    try {
      val held = journal.appendOrHold(record(id1))
      assertTrue(journal.isHeld(id1))
      assertEquals(listOf(held), journal.unacknowledged())
      assertEquals(held, journal.find(id1))
      assertEquals(listOf(id1), journal.forEntry("e1").map { it.eventId })
      assertEquals(1, journal.drain(listener).single().deliveries)
      // The retry fails while the disk is full, and waits twice as long.
      tasks.removeAt(0).also { (delay, task) -> assertEquals(EventJournal.RETRY_MS, delay); task() }
      assertEquals(EventJournal.RETRY_MS * 2, tasks.single().first)
    } finally {
      dir.setWritable(true)
    }
    tasks.removeAt(0).second()
    assertFalse(journal.isHeld(id1))
    assertTrue(File(dir, "$id1.json").exists())
    assertEquals(1, EventJournal(dir).find(id1)!!.deliveries)
    assertEquals(emptyList<Pair<Long, () -> Unit>>(), tasks)
  }

  @Test
  fun `an ack removes a held record`() {
    val dir = tmp.newFolder()
    val journal = EventJournal(dir, retryLater = { _, _ -> })
    dir.setWritable(false)
    try {
      journal.appendOrHold(record(id1))
    } finally {
      dir.setWritable(true)
    }
    journal.ack(listOf(id1))
    assertFalse(journal.isHeld(id1))
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.unacknowledged())
  }

  @Test
  fun `ackEntry removes every record of one entry`() {
    val journal = EventJournal(tmp.newFolder())
    journal.append(record(id1, id = "a"))
    journal.append(record(id2, id = "a", generation = 2))
    journal.append(record("00000000-0000-0000-0000-000000000003", id = "b"))
    journal.ackEntry("a")
    assertEquals(listOf("b"), journal.unacknowledged().map { it.id })
  }

  @Test
  fun `prunes the oldest records beyond the cap`() {
    val dir = tmp.newFolder()
    val journal = EventJournal(dir, maxEntries = 3)
    val ids = (1..4).map { "00000000-0000-0000-0000-00000000000$it" }
    ids.take(3).forEachIndexed { i, id ->
      journal.append(record(id))
      File(dir, "$id.json").setLastModified(1_000L * (i + 1))
    }
    journal.append(record(ids[3]))
    val left = journal.unacknowledged().map { it.eventId }
    assertEquals(3, left.size)
    assertFalse(left.contains(ids[0]))
  }

  @Test
  fun `the prune never deletes a record a row names`() {
    val dir = tmp.newFolder()
    val journal = EventJournal(dir, maxEntries = 3)
    val ids = (1..4).map { "00000000-0000-0000-0000-00000000000$it" }
    ids.take(3).forEachIndexed { i, id ->
      journal.append(record(id))
      File(dir, "$id.json").setLastModified(1_000L * (i + 1))
    }
    // The oldest is named by a row; the next oldest goes instead.
    journal.append(record(ids[3])) { setOf(ids[0]) }
    assertEquals(setOf(ids[0], ids[2], ids[3]), journal.unacknowledged().map { it.eventId }.toSet())
  }

  @Test
  fun `incrementDeliveries persists`() {
    val dir = tmp.newFolder()
    val journal = EventJournal(dir)
    journal.drain(listener)
    journal.append(record(id1))
    assertEquals(2, journal.incrementDeliveries(id1)!!.deliveries)
    assertEquals(3, journal.incrementDeliveries(id1)!!.deliveries)
    assertEquals(3, EventJournal(dir).find(id1)!!.deliveries)
    assertNull(journal.incrementDeliveries(id2))
  }

  @Test
  fun `forEntry filters by entry id`() {
    val journal = EventJournal(tmp.newFolder())
    journal.append(record(id1, id = "a"))
    journal.append(record(id2, id = "b"))
    assertEquals(listOf(id2), journal.forEntry("b").map { it.eventId })
  }

  @Test
  fun `the completed shape is SettledEvent`() {
    val map = record(id1).toMap()
    assertEquals(
      listOf(
        "eventId", "id", "key", "vars", "at", "attempts", "requestId", "deliveries", "state",
        "bytesSent", "totalBytes", "url", "method", "kind", "response",
      ),
      map.keys.toList(),
    )
    assertEquals(mapOf("n" to 1.0), map["vars"])
    assertEquals(mapOf("status" to 200.0, "headers" to mapOf<String, String>(), "body" to "ok", "bodyTruncated" to false), map["response"])
  }

  @Test
  fun `a chunked completion has a response with no status`() {
    val map = record(id1).copy(response = null).toMap()
    assertEquals(mapOf("bodyTruncated" to false), map["response"])
  }

  @Test
  fun `the error shape nests errorKind, message, response, and partIndex`() {
    val map = record(id1, kind = EventJournal.KIND_ERROR).copy(
      partIndex = 2,
      response = EventJournal.Response(404, null, "gone", false),
    ).toMap()
    assertEquals(2.0, map["partIndex"])
    assertEquals(
      mapOf(
        "errorKind" to "http", "message" to "HTTP 400",
        "response" to mapOf("status" to 404.0, "body" to "gone", "bodyTruncated" to false),
        "partIndex" to 2.0,
      ),
      map["error"],
    )
    assertFalse(map.containsKey("response"))
  }

  @Test
  fun `the cancelled shape carries the reason`() {
    val map = record(id1, kind = EventJournal.KIND_CANCELLED).toMap()
    assertEquals("user", map["cancelReason"])
    assertFalse(map.containsKey("error"))
    assertFalse(map.containsKey("response"))
  }
}
