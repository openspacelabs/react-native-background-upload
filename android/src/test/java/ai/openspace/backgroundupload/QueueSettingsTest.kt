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
  fun `a failed write keeps the old value`() {
    // A regular file where the directory should be: the write fails.
    val notADir = tmp.newFile()
    val store = QueueSettingsStore(File(notADir, "settings.json"))
    runCatching { store.update { it.copy(paused = true) } }
    assertFalse(store.load().paused)
  }

  @Test
  fun `configure retry fills absent fields with the library defaults`() {
    val d = QueueSettingsStore.retryDefaults(mapOf("backoff" to mapOf("baseMs" to 500.0), "terminalHttp" to mapOf("exempt" to listOf<Any>())))
    assertEquals(RetryDefaults(baseMs = 500, exempt = emptyList()), d)
    assertEquals(RetryDefaults(), QueueSettingsStore.retryDefaults(null))
  }
}
