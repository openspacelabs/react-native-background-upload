package ai.openspace.backgroundupload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyImportTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private fun v9(dir: File, eventId: String, uploadId: String, type: String, timestamp: Long) =
    File(dir, "$eventId.json").writeText(
      """{"eventId":"$eventId","uploadId":"$uploadId","type":"$type","timestamp":$timestamp,"responseCode":200}""",
    )

  @Test
  fun `each v9 id becomes one legacy row with its newest outcome`() {
    val v9Dir = tmp.newFolder()
    v9(v9Dir, "a", "up-1", "completed", 100)
    v9(v9Dir, "b", "up-2", "error", 200)
    v9(v9Dir, "c", "up-3", "cancelled", 300)
    v9(v9Dir, "d", "up-2", "completed", 250) // newer for up-2
    File(v9Dir, "bad.json").writeText("{not json")
    val store = QueueStore(tmp.newFolder(), RequestIndex())

    assertTrue(LegacyImport.import(v9Dir, store))

    val rows = store.all().associateBy { it.id }
    assertEquals(setOf("up-1", "up-2", "up-3"), rows.keys)
    assertEquals(EntryState.COMPLETED, rows["up-1"]!!.state)
    assertEquals(EntryState.COMPLETED, rows["up-2"]!!.state)
    assertEquals(EntryState.CANCELLED, rows["up-3"]!!.state)
    val row = rows["up-2"]!!
    assertEquals("legacy", row.key)
    assertEquals("null", row.varsJson)
    assertTrue(row.legacy)
    assertEquals(0, row.attempts)
    assertEquals(250, row.updatedAt)
    assertNull(row.descriptor)
    assertEquals(0, v9Dir.list()!!.size) // every v9 file is gone
  }

  @Test
  fun `a second run imports nothing`() {
    val v9Dir = tmp.newFolder()
    val store = QueueStore(tmp.newFolder(), RequestIndex())
    v9(v9Dir, "a", "up-1", "completed", 100)
    LegacyImport.import(v9Dir, store)
    store.remove("up-1")
    assertTrue(LegacyImport.import(v9Dir, store))
    assertEquals(emptyList<QueueEntry>(), store.all())
  }

  @Test
  fun `an id that a v10 entry owns is left alone`() {
    val v9Dir = tmp.newFolder()
    val store = QueueStore(tmp.newFolder(), RequestIndex())
    store.save(entry(id = "up-1"))
    v9(v9Dir, "a", "up-1", "error", 100)
    LegacyImport.import(v9Dir, store)
    assertEquals("note", store.load("up-1")!!.key)
  }

  @Test
  fun `a failed save keeps the v9 file and reports incomplete`() {
    val v9Dir = tmp.newFolder()
    v9(v9Dir, "a", "up-1", "error", 100)
    val broken = QueueStore(tmp.newFile(), RequestIndex()) // a file where the directory should be
    assertEquals(false, LegacyImport.import(v9Dir, broken))
    assertTrue(File(v9Dir, "a.json").exists())
  }

  @Test
  fun `an unknown type makes no row`() {
    assertNull(LegacyImport.legacyRow(LegacyImport.V9Entry("a", "up", "progress", 1)))
    assertNull(LegacyImport.legacyRow(LegacyImport.V9Entry("a", null, "completed", 1)))
  }
}
