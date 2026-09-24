package ai.openspace.backgroundupload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class QueueStoreTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private fun store(dir: File = tmp.newFolder(), index: RequestIndex = RequestIndex()) = QueueStore(dir, index)

  @Test
  fun `save then load round-trips across store instances`() {
    val dir = tmp.newFolder()
    val e = entry(
      descriptor = desc(url = null, method = "PUT", file = "/f", parts = listOf(part(0, 10, accepted = true), part(10, 20))),
      body = StagedBody(StagedBody.CHUNKED, "blob", null, 20),
      nextAttemptAt = 5,
      parkedGeneration = 2,
    )
    store(dir).save(e)
    // A new instance over the same dir is what a process relaunch looks like.
    assertEquals(e, store(dir).load("e1"))
  }

  @Test
  fun `the state enum is stored by its wire string`() {
    val s = store()
    s.save(entry(state = EntryState.AWAITING_AUTH))
    assertTrue(File(s.entryDir("e1"), QueueStore.ENTRY_FILE).readText().contains("\"awaiting-auth\""))
  }

  @Test
  fun `ids with filesystem-hostile characters round-trip`() {
    val s = store()
    val id = "a/b:c dü..\\e"
    s.save(entry(id = id))
    assertEquals(id, s.load(id)!!.id)
    s.remove(id)
    assertNull(s.load(id))
  }

  @Test
  fun `a corrupt entry reads as absent`() {
    val s = store()
    s.save(entry())
    File(s.entryDir("e1"), QueueStore.ENTRY_FILE).writeText("{not json")
    assertNull(s.load("e1"))
    assertEquals(emptyList<QueueEntry>(), s.all())
  }

  @Test
  fun `an entry missing a required field reads as absent`() {
    val s = store()
    s.entryDir("e1").mkdirs()
    File(s.entryDir("e1"), QueueStore.ENTRY_FILE).writeText("""{"id":"e1","key":"k","state":"queued"}""")
    assertNull(s.load("e1")) // not legacy, and no descriptor or body
  }

  @Test
  fun `an older file gets safe defaults`() {
    val s = store()
    s.entryDir("e1").mkdirs()
    File(s.entryDir("e1"), QueueStore.ENTRY_FILE).writeText(
      """{"id":"e1","key":"k","state":"queued","descriptor":{"url":"https://x"},"body":{"kind":"none","totalBytes":0}}""",
    )
    val e = s.load("e1")!!
    assertEquals("null", e.varsJson)
    assertEquals("POST", e.descriptor!!.method)
    assertEquals(emptyMap<String, String>(), e.descriptor!!.headers)
    assertEquals(1, e.generation)
  }

  @Test
  fun `all skips v9-only directories and legacyManifest reads them`() {
    val s = store()
    val dir = s.entryDir("v9").apply { mkdirs() }
    File(dir, QueueStore.V9_MANIFEST_FILE).writeText(
      """{"id":"v9","sourcePath":"/x/blob","parts":[{"url":"https://p/1","headers":{},"start":0,"end":10,"accepted":true}],""" +
        """"accept":[{"status":409}],"expiresAt":99,"wifiOnly":false,"noNotification":true,"createdAt":1}""",
    )
    s.save(entry(id = "v10"))
    assertEquals(listOf("v10"), s.all().map { it.id })
    val m = s.legacyManifest("v9")!!
    assertEquals(listOf(Part("https://p/1", mapOf(), 0, 10, accepted = true)), m.parts)
    assertEquals(listOf(UploadOutcome.AcceptRule(409)), m.accept)
    assertNull(s.legacyManifest("v10"))
    assertNull(s.legacyManifest("nope"))
  }

  @Test
  fun `compute holds the lock across load, transform, and save`() {
    // A module transition and a worker's update race. If the lock did not
    // span all three steps, the update could land between load and save and
    // be erased. Serialized, both effects survive.
    val s = store()
    s.save(entry(descriptor = desc(url = null, file = "/f", parts = listOf(part(0, 10), part(10, 20)))))
    val inTransform = CountDownLatch(1)
    val computing = Thread {
      s.compute("e1") { e ->
        inTransform.countDown()
        Thread.sleep(300)
        e!!.copy(expiresAt = 99_000)
      }
    }.apply { start() }
    assertTrue(inTransform.await(5, TimeUnit.SECONDS))
    val updating = Thread {
      s.update("e1") { e -> e.copy(descriptor = e.descriptor!!.copy(parts = ChunkedParts.withAccepted(e.descriptor.parts!!, 0))) }
    }.apply { start() }
    computing.join()
    updating.join()
    val final = s.load("e1")!!
    assertEquals(99_000, final.expiresAt)
    assertTrue(final.descriptor!!.parts!![0].accepted)
  }

  @Test
  fun `a throwing transform writes nothing`() {
    val s = store()
    s.save(entry())
    assertThrows(IllegalStateException::class.java) { s.compute("e1") { throw IllegalStateException("no") } }
    assertEquals(entry(), s.load("e1"))
  }

  @Test
  fun `compute returning the same object or null writes nothing`() {
    val s = store()
    assertNull(s.compute("e1") { null })
    assertFalse(s.entryDir("e1").exists() && File(s.entryDir("e1"), QueueStore.ENTRY_FILE).exists())
    s.save(entry())
    val file = File(s.entryDir("e1"), QueueStore.ENTRY_FILE)
    file.setLastModified(1_000)
    s.compute("e1") { it }
    assertEquals(1_000, file.lastModified())
  }

  @Test
  fun `remove deletes the row and every staged byte`() {
    val index = RequestIndex()
    val s = store(index = index)
    s.save(entry())
    File(s.entryDir("e1"), "body-1.json").writeText("{}")
    File(s.entryDir("e1"), "blob").writeText("bytes")
    s.remove("e1")
    assertNull(s.load("e1"))
    assertFalse(s.entryDir("e1").exists())
    assertNull(index.get("e1"))
  }

  @Test
  fun `the index follows every save and remove, and loads on start`() {
    val dir = tmp.newFolder()
    val index = RequestIndex()
    val s = store(dir, index)
    s.save(entry(id = "a"))
    s.save(entry(id = "b", state = EntryState.ERROR))
    assertEquals(listOf("a", "b"), index.snapshot().map { it.id })
    s.remove("a")
    assertEquals(listOf("b"), index.snapshot().map { it.id })
    // A process relaunch: a fresh index loaded from disk.
    val fresh = RequestIndex()
    QueueStore(dir, fresh).loadIndex()
    assertEquals("error", fresh.get("b")!!.state)
  }

  @Test
  fun `pruneUnreferenced keeps only the entry file and its body`() {
    val s = store()
    val e = entry(body = StagedBody(StagedBody.JSON, "body-2.json", null, 2))
    s.save(e)
    val dir = s.entryDir("e1")
    listOf("body-1.json", "body-2.json", "blob", "manifest.json", "entry.json.tmp").forEach { File(dir, it).writeText("x") }
    s.pruneUnreferenced(e)
    assertEquals(setOf("entry.json", "body-2.json"), dir.list()!!.toSet())
  }

  // MARK: - crash mid-write

  @Test
  fun `crash mid-write (a) a partial entry tmp next to a valid entry`() {
    val s = store()
    s.save(entry(attempts = 1))
    // The process died while writing the next version: only the tmp is partial.
    File(s.entryDir("e1"), "entry.json.tmp").writeText("""{"id":"e1","key":"no""")
    assertEquals(1, s.load("e1")!!.attempts)
    s.save(entry(attempts = 2))
    assertEquals(2, s.load("e1")!!.attempts)
    assertFalse(File(s.entryDir("e1"), "entry.json.tmp").exists())
  }

  @Test
  fun `crash mid-write (b) a staged body with no entry is not a row`() {
    val s = store()
    val dir = s.entryDir("e1").apply { mkdirs() }
    File(dir, "body-1.json.tmp").writeText("{\"a\":")
    File(dir, "body-1.json").writeText("{\"a\":1}")
    assertEquals(emptyList<QueueEntry>(), s.all())
    assertNull(s.load("e1"))
    // The next create saves an entry and prunes what it does not use.
    val e = entry(body = StagedBody(StagedBody.JSON, "body-1.json", null, 7))
    s.save(e)
    s.pruneUnreferenced(e)
    assertEquals(setOf("entry.json", "body-1.json"), dir.list()!!.toSet())
  }

  @Test
  fun `crash mid-write (c) a write that throws leaves the old target intact`() {
    val target = File(tmp.newFolder(), "entry.json").apply { writeText("old") }
    assertThrows(IOException::class.java) {
      AtomicFiles.writeAtomically(target) { out ->
        out.write("new, half".toByteArray())
        throw IOException("disk full")
      }
    }
    assertEquals("old", target.readText())
    assertFalse(AtomicFiles.tmpFor(target).exists())
  }

  @Test
  fun `a save into an unwritable directory throws`() {
    val notADir = tmp.newFile()
    val s = QueueStore(notADir, RequestIndex())
    assertThrows(IOException::class.java) { s.save(entry()) }
  }

  @Test
  fun `update is best effort`() {
    val s = store()
    assertNull(s.update("nope") { it })
    s.save(entry())
    assertNotNull(s.update("e1") { it.copy(attempts = 3) })
    assertEquals(3, s.load("e1")!!.attempts)
  }
}
