package ai.openspace.backgroundupload

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class DiagnosticLogTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private fun log(file: File = File(tmp.newFolder(), "diagnostics.json")) = DiagnosticLog(file)

  private fun record(n: Int) = DiagnosticLog.Record(at = n.toLong(), level = DiagnosticLog.WARN, message = "m$n")

  @After
  fun tearDown() {
    Diag.log = null
  }

  @Test
  fun `drain returns the records oldest first and empties the log`() {
    val file = File(tmp.newFolder(), "diagnostics.json")
    val log = log(file)
    log.append(record(1))
    log.append(record(2))
    assertEquals(listOf("m1", "m2"), log.drain().map { it.message })
    assertFalse(file.exists())
    assertEquals(emptyList<DiagnosticLog.Record>(), log.drain())
  }

  @Test
  fun `the log keeps the newest records`() {
    val log = log()
    repeat(DiagnosticLog.MAX_RECORDS + 5) { log.append(record(it)) }
    val records = log.drain()
    assertEquals(DiagnosticLog.MAX_RECORDS, records.size)
    assertEquals("m5", records.first().message)
    assertEquals("m${DiagnosticLog.MAX_RECORDS + 4}", records.last().message)
  }

  @Test
  fun `a drained log stays drained for the next instance`() {
    val file = File(tmp.newFolder(), "diagnostics.json")
    val log = log(file)
    log.append(record(1))
    log.drain()
    log.append(record(2))
    assertEquals(listOf("m2"), log(file).drain().map { it.message })
  }

  @Test
  fun `records survive a new instance on the same file`() {
    val file = File(tmp.newFolder(), "diagnostics.json")
    log(file).append(record(1))
    assertEquals(listOf("m1"), log(file).drain().map { it.message })
  }

  @Test
  fun `a corrupt file reads as empty and is replaced by the next append`() {
    val file = File(tmp.newFolder(), "diagnostics.json").apply { writeText("not json") }
    val log = log(file)
    assertEquals(emptyList<DiagnosticLog.Record>(), log.drain())
    file.writeText("""[{"at":1},{"at":2,"level":"warn","message":"ok"}]""")
    assertEquals(listOf("ok"), log(file).drain().map { it.message })
  }

  @Test
  fun `Diag writes warnings, errors, and info to the log`() {
    val file = File(tmp.newFolder(), "diagnostics.json")
    Diag.log = log(file)
    Diag.warn("foreground start failed")
    Diag.error("run failed", IOException("No space left on device"))
    Diag.info("snapshot", mapOf("entries" to 2))
    Diag.warn("live event dropped", persist = false)
    // A new instance reads the file, so the numbers in data are doubles.
    val records = log(file).drain()
    assertEquals(listOf(DiagnosticLog.WARN, DiagnosticLog.ERROR, DiagnosticLog.INFO), records.map { it.level })
    assertEquals("java.io.IOException: No space left on device", records[1].error)
    assertEquals(mapOf("entries" to 2.0), records[2].data)
  }

  @Test
  fun `Diag without a log only logs`() {
    Diag.log = null
    Diag.error("nowhere to write")
  }
}
