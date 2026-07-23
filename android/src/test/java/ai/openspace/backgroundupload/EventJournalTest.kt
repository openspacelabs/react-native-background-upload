package ai.openspace.backgroundupload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EventJournalTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private fun entry(id: String, uploadId: String = "u1") = EventJournal.Entry(
    eventId = id,
    uploadId = uploadId,
    type = "completed",
    timestamp = System.currentTimeMillis(),
    responseCode = 200,
    responseBody = "ok",
    responseHeaders = mapOf("x-a" to "b"),
  )

  @Test
  fun `append then read returns the entry`() {
    val journal = EventJournal(tmp.newFolder())
    journal.append(entry("e1"))
    val events = journal.unacknowledged()
    assertEquals(1, events.size)
    assertEquals("e1", events[0].eventId)
    assertEquals(200, events[0].responseCode)
    assertEquals("ok", events[0].responseBody)
  }

  @Test
  fun `ack removes only the acked entry`() {
    val journal = EventJournal(tmp.newFolder())
    journal.append(entry("e1"))
    journal.append(entry("e2"))
    journal.ack(listOf("e1"))
    assertEquals(listOf("e2"), journal.unacknowledged().map { it.eventId })
  }

  @Test
  fun `entries survive a new journal instance over the same dir`() {
    val dir = tmp.newFolder()
    EventJournal(dir).append(entry("e1"))
    assertEquals(1, EventJournal(dir).unacknowledged().size)
  }

  @Test
  fun `oversized body is truncated and flagged`() {
    val journal = EventJournal(tmp.newFolder())
    val big = "x".repeat(EventJournal.MAX_BODY_CHARS + 100)
    journal.append(entry("e1").copy(responseBody = big))
    val read = journal.unacknowledged()[0]
    assertTrue(read.responseBodyTruncated)
    assertTrue(read.responseBody!!.length <= EventJournal.MAX_BODY_CHARS)
  }

  @Test
  fun `corrupt file is skipped, not fatal`() {
    val dir = tmp.newFolder()
    val journal = EventJournal(dir)
    journal.append(entry("e1"))
    java.io.File(dir, "garbage.json").writeText("{not json")
    assertEquals(1, journal.unacknowledged().size)
  }

  @Test
  fun `entries are ordered by timestamp`() {
    val journal = EventJournal(tmp.newFolder())
    journal.append(entry("late").copy(timestamp = 2000))
    journal.append(entry("early").copy(timestamp = 1000))
    assertEquals(listOf("early", "late"), journal.unacknowledged().map { it.eventId })
  }

  @Test
  fun `append does not throw when the directory is unwritable`() {
    // A regular file where a directory is expected: mkdirs() and every write fail.
    val notADir = tmp.newFile()
    val journal = EventJournal(notADir)
    journal.append(entry("e1")) // must not throw
    assertEquals(emptyList<String>(), journal.unacknowledged().map { it.eventId })
  }

  @Test
  fun `prunes the oldest entries beyond the cap`() {
    val dir = tmp.newFolder()
    val journal = EventJournal(dir, maxEntries = 3)
    // Stamp increasing mtimes so pruning order is deterministic. Each mtime is
    // set before the next append, which is when pruning reads it.
    journal.append(entry("e1")); File(dir, "e1.json").setLastModified(1000)
    journal.append(entry("e2")); File(dir, "e2.json").setLastModified(2000)
    journal.append(entry("e3")); File(dir, "e3.json").setLastModified(3000)
    journal.append(entry("e4")) // 4th write trips the cap; oldest (e1) is dropped

    val ids = journal.unacknowledged().map { it.eventId }
    assertEquals(3, ids.size)
    assertFalse(ids.contains("e1"))
    assertTrue(ids.contains("e4"))
  }
}
