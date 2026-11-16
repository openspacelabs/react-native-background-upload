package ai.openspace.backgroundupload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class QueueSettingsTest {
  @get:Rule
  val tmp = TemporaryFolder()

  @Test
  fun `defaults when there is no file`() {
    val s = QueueSettingsStore(File(tmp.newFolder(), "settings.json")).load()
    assertEquals(QueueSettings(), s)
    assertEquals(RetryDefaults(1_000, 7_200_000, 0.2, listOf(404)), s.retry)
  }

  @Test
  fun `update persists and reloads in a new instance`() {
    val file = File(tmp.newFolder(), "settings.json")
    QueueSettingsStore(file).update { it.copy(wifiOnly = true, paused = true) }
    val reloaded = QueueSettingsStore(file).load()
    assertTrue(reloaded.wifiOnly)
    assertTrue(reloaded.paused)
  }

  @Test
  fun `paused keys persist and reload in a new instance`() {
    val file = File(tmp.newFolder(), "settings.json")
    QueueSettingsStore(file).update { it.copy(pausedKeys = setOf("capture", "video")) }
    assertEquals(setOf("capture", "video"), QueueSettingsStore(file).load().pausedKeys)
  }

  @Test
  fun `a null paused keys field reads an empty set`() {
    val file = File(tmp.newFolder(), "settings.json").apply { writeText("""{"paused":true,"pausedKeys":null}""") }
    val s = QueueSettingsStore(file).load()
    assertTrue(s.paused)
    assertEquals(emptySet<String>(), s.pausedKeys)
  }

  @Test
  fun `an entry is paused under the gate or its key`() {
    assertFalse(QueueSettings().isPaused("note"))
    assertTrue(QueueSettings(paused = true).isPaused("note"))
    val keyed = QueueSettings(pausedKeys = setOf("capture"))
    assertTrue(keyed.isPaused("capture"))
    assertFalse(keyed.isPaused("note"))
  }

  @Test
  fun `the entry's own wifiOnly wins, and absent follows the queue setting`() {
    val on = QueueSettings(wifiOnly = true)
    val off = QueueSettings(wifiOnly = false)
    assertTrue(on.wifiOnlyFor(entry(descriptor = desc())))
    assertFalse(off.wifiOnlyFor(entry(descriptor = desc())))
    assertFalse(on.wifiOnlyFor(entry(descriptor = desc(wifiOnly = false))))
    assertTrue(off.wifiOnlyFor(entry(descriptor = desc(wifiOnly = true))))
  }

  @Test
  fun `a corrupt file reads as the defaults`() {
    val file = File(tmp.newFolder(), "settings.json").apply { writeText("{not json") }
    assertEquals(QueueSettings(), QueueSettingsStore(file).load())
  }

  @Test
  fun `a file missing fields keeps the defaults for them`() {
    val file = File(tmp.newFolder(), "settings.json").apply { writeText("""{"wifiOnly":true}""") }
    val s = QueueSettingsStore(file).load()
    assertTrue(s.wifiOnly)
    assertFalse(s.paused)
    assertEquals(emptySet<String>(), s.pausedKeys)
    assertEquals(RetryDefaults(), s.retry)
  }

  @Test
  fun `header generation increments`() {
    val store = QueueSettingsStore(File(tmp.newFolder(), "settings.json"))
    assertEquals(1, store.update { it.copy(headerGeneration = it.headerGeneration + 1) }.headerGeneration)
    assertEquals(2, store.update { it.copy(headerGeneration = it.headerGeneration + 1) }.headerGeneration)
  }

  @Test
  fun `a failed write keeps the new value in memory and the old one on disk`() {
    val dir = tmp.newFolder()
    val file = File(dir, "settings.json")
    val store = QueueSettingsStore(file)
    dir.setWritable(false)
    try {
      assertTrue(store.update { it.copy(paused = true) }.paused)
    } finally {
      dir.setWritable(true)
    }
    assertTrue(store.load().paused)
    assertTrue(store.isBehind)
    assertFalse(QueueSettingsStore(file).load().paused)
  }

  @Test
  fun `a failed write records a warning with the cause`() {
    val dir = tmp.newFolder()
    Diag.log = DiagnosticLog(File(tmp.newFolder(), "diagnostics.json"))
    val store = QueueSettingsStore(File(dir, "settings.json"))
    dir.setWritable(false)
    try {
      store.update { it.copy(wifiOnly = true) }
    } finally {
      dir.setWritable(true)
      val records = Diag.log!!.drain()
      Diag.log = null
      assertEquals(listOf(DiagnosticLog.WARN), records.map { it.level })
      val message = records.single().message
      assertTrue(message, message.startsWith("could not save the queue settings; this session uses the new value"))
      assertTrue(message, message.contains(" (cause: java.io.FileNotFoundException"))
    }
  }

  @Test
  fun `a second failure in a row is not persisted to the diagnostic log`() {
    val dir = tmp.newFolder()
    Diag.log = DiagnosticLog(File(tmp.newFolder(), "diagnostics.json"))
    val store = QueueSettingsStore(File(dir, "settings.json"))
    dir.setWritable(false)
    try {
      store.update { it.copy(wifiOnly = true) }
      store.update { it.copy(paused = true) }
      store.saveIfBehind()
    } finally {
      dir.setWritable(true)
      val records = Diag.log!!.drain()
      Diag.log = null
      assertEquals("only the first failure", 1, records.size)
    }
    // After a write lands, the next failure is persisted again.
    store.saveIfBehind()
    Diag.log = DiagnosticLog(File(tmp.newFolder(), "diagnostics.json"))
    dir.setWritable(false)
    try {
      store.update { it.copy(paused = false) }
    } finally {
      dir.setWritable(true)
      val records = Diag.log!!.drain()
      Diag.log = null
      assertEquals(1, records.size)
    }
  }

  @Test
  fun `the next update saves a value that is behind, even when it changes nothing`() {
    val dir = tmp.newFolder()
    val file = File(dir, "settings.json")
    val store = QueueSettingsStore(file)
    dir.setWritable(false)
    try {
      store.update { it.copy(wifiOnly = true) }
    } finally {
      dir.setWritable(true)
    }
    store.update { it.copy(wifiOnly = true) } // the same value
    assertTrue(QueueSettingsStore(file).load().wifiOnly)
    assertFalse(store.isBehind)
  }

  @Test
  fun `saveIfBehind writes only after a failed save`() {
    val dir = tmp.newFolder()
    val file = File(dir, "settings.json")
    val store = QueueSettingsStore(file)
    store.update { it.copy(paused = true) }
    file.delete()
    store.saveIfBehind()
    assertFalse("nothing was behind, so nothing is written", file.exists())

    dir.setWritable(false)
    try {
      store.update { it.copy(paused = false) }
      store.saveIfBehind() // fails again and keeps the value
    } finally {
      dir.setWritable(true)
    }
    assertFalse(store.load().paused)
    assertTrue(store.isBehind)
    store.saveIfBehind()
    assertFalse(store.isBehind)
    assertFalse(QueueSettingsStore(file).load().paused)
  }

  @Test
  fun `configure retry fills absent fields with the library defaults`() {
    val d = QueueSettingsStore.retryDefaults(mapOf("backoff" to mapOf("baseMs" to 500.0), "terminalHttp" to mapOf("exempt" to listOf<Any>())))
    assertEquals(RetryDefaults(baseMs = 500, exempt = emptyList()), d)
    assertEquals(RetryDefaults(), QueueSettingsStore.retryDefaults(null))
    val withRules = QueueSettingsStore.retryDefaults(
      mapOf("transient" to listOf(mapOf("status" to 400.0, "bodyIncludes" to "x", "maxRetries" to 3.0), mapOf("status" to 404.0), "junk")),
    )
    assertEquals(listOf(UploadOutcome.TransientRule(400, "x", 3), UploadOutcome.TransientRule(404)), withRules.transient)
  }

  @Test
  fun `a settings file written before transient rules reads them as empty`() {
    val file = File(tmp.newFolder(), "settings.json")
    file.writeText("""{"wifiOnly":true,"paused":false,"pausedKeys":[],"headerGeneration":2,"retry":{"baseMs":1000,"maxMs":7200000,"jitter":0.2,"exempt":[404]}}""")
    val s = QueueSettingsStore(file).load()
    assertEquals(emptyList<UploadOutcome.TransientRule>(), s.retry.transient)
    assertEquals(listOf(404), s.retry.exempt)
  }
}
