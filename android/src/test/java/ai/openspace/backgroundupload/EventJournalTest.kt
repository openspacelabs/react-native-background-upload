package ai.openspace.backgroundupload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EventJournalTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private val id1 = "00000000-0000-0000-0000-000000000001"
  private val id2 = "00000000-0000-0000-0000-000000000002"

  @Test
  fun `append then read returns the record`() {
    val journal = EventJournal(tmp.newFolder())
    assertTrue(journal.append(record(id1)))
    val events = journal.unacknowledged()
    assertEquals(listOf(record(id1)), events)
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
  fun `a body over 1 MB is cut and flagged`() {
    val journal = EventJournal(tmp.newFolder())
    val big = "x".repeat(EventJournal.MAX_BODY_CHARS + 100)
    journal.append(record(id1).copy(response = EventJournal.Response(200, null, big, false)))
    val read = journal.unacknowledged()[0].response!!
    assertTrue(read.bodyTruncated)
    assertEquals(EventJournal.MAX_BODY_CHARS, read.body!!.length)
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
  fun `append never throws, and says whether it wrote`() {
    val journal = EventJournal(tmp.newFile()) // a file where the directory should be
    assertFalse(journal.append(record(id1)))
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.unacknowledged())
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
  fun `incrementDeliveries persists`() {
    val dir = tmp.newFolder()
    val journal = EventJournal(dir)
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
