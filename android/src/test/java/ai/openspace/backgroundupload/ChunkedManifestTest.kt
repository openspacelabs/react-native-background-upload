package ai.openspace.backgroundupload

import ai.openspace.backgroundupload.UploadOutcome.AcceptRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChunkedManifestTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private fun manifest(
    id: String = "u1",
    parts: List<ChunkedManifest.Part> = listOf(
      part(0, 100),
      part(100, 250),
    ),
    expiresAt: Long = 5_000,
  ) = ChunkedManifest(
    id = id,
    sourcePath = "/data/blob",
    parts = parts,
    accept = listOf(AcceptRule(409, "already completed")),
    expiresAt = expiresAt,
    wifiOnly = false,
    noNotification = false,
    createdAt = 1_000,
  )

  private fun part(start: Long, end: Long, accepted: Boolean = false) =
    ChunkedManifest.Part(
      url = "https://example.com/part?start=$start",
      headers = mapOf("Authorization" to "Bearer old"),
      start = start,
      end = end,
      accepted = accepted,
    )

  // MARK: - Model

  @Test
  fun `byte math is range-based`() {
    val m = manifest(parts = listOf(part(0, 100, accepted = true), part(100, 250)))
    assertEquals(250, m.totalBytes)
    assertEquals(100, m.acceptedBytes)
  }

  @Test
  fun `completed only when every part is accepted`() {
    val none = manifest()
    assertFalse(none.allAccepted)
    val partial = none.withPartAccepted(0)
    assertFalse(partial.allAccepted)
    val all = partial.withPartAccepted(1)
    assertTrue(all.allAccepted)
    assertEquals(emptyList<Int>(), all.pendingIndexes())
    assertEquals(listOf(1), partial.pendingIndexes())
  }

  @Test
  fun `expiry is inclusive of the deadline`() {
    val m = manifest(expiresAt = 5_000)
    assertFalse(m.isExpired(4_999))
    assertTrue(m.isExpired(5_000))
    assertTrue(m.isExpired(5_001))
  }

  // MARK: - Reconcile: resume (same parts array)

  private val blobSize = 250L

  @Test
  fun `resume replaces headers and deadline, keeps accepted parts and the moved source`() {
    val stored = manifest().withPartAccepted(0)
    val fresh = manifest(expiresAt = 99_000).copy(
      sourcePath = "/ignored/by/reconcile",
      createdAt = 42,
      accept = listOf(AcceptRule(208)),
      wifiOnly = true,
      parts = stored.parts.map { it.copy(headers = mapOf("Authorization" to "Bearer new"), accepted = false) },
    )

    val merged = stored.reconcile(fresh, running = false, blobSize = blobSize)

    assertEquals("Bearer new", merged.parts[0].headers["Authorization"])
    assertEquals(99_000, merged.expiresAt)
    assertEquals(listOf(AcceptRule(208)), merged.accept)
    assertTrue(merged.wifiOnly)
    // Accepted statuses, ownership, and identity survive from the stored copy.
    assertTrue(merged.parts[0].accepted)
    assertFalse(merged.parts[1].accepted)
    assertEquals("/data/blob", merged.sourcePath)
    assertEquals(1_000, merged.createdAt)
  }

  @Test
  fun `resume is allowed while the upload is running`() {
    // Fresh auth must reach a running worker's stalled parts.
    val stored = manifest().withPartAccepted(0)
    val fresh = manifest().copy(
      parts = stored.parts.map { it.copy(headers = mapOf("Authorization" to "Bearer new"), accepted = false) },
    )
    val merged = stored.reconcile(fresh, running = true, blobSize = blobSize)
    assertTrue(merged.parts[0].accepted)
    assertEquals("Bearer new", merged.parts[1].headers["Authorization"])
  }

  @Test
  fun `resume matches the same parts authored in a different order`() {
    // Identical tiles, reordered, are the SAME upload: a resume, never a
    // recreate (running = true would reject a recreate). Accepted flags follow
    // the range, not the array index.
    val stored = manifest().withPartAccepted(0)
    val fresh = manifest().copy(
      parts = listOf(stored.parts[1], stored.parts[0]).map {
        it.copy(headers = mapOf("Authorization" to "Bearer new"), accepted = false)
      },
    )
    val merged = stored.reconcile(fresh, running = true, blobSize = blobSize)
    assertTrue(merged.parts.first { it.start == 0L }.accepted)
    assertFalse(merged.parts.first { it.start == 100L }.accepted)
    assertEquals("Bearer new", merged.parts[0].headers["Authorization"])
  }

  // MARK: - Reconcile: recreate (different parts array)

  @Test
  fun `recreate from a stalled upload replaces parts and resets every status`() {
    // The consumer re-authored under a fresh server uploadId: new urls, a new
    // split, and fresh headers, accept, and expiresAt. The owned bytes stay.
    val stored = manifest().withPartAccepted(0)
    val fresh = manifest(
      parts = listOf(
        part(0, 120).copy(url = "https://example.com/v2?part=1"),
        part(120, 250).copy(url = "https://example.com/v2?part=2"),
      ),
      expiresAt = 99_000,
    ).copy(sourcePath = "/ignored/by/reconcile", createdAt = 42, accept = listOf(AcceptRule(208)))

    val recreated = stored.reconcile(fresh, running = false, blobSize = blobSize)

    assertTrue(recreated.parts.none { it.accepted })
    assertEquals(listOf("https://example.com/v2?part=1", "https://example.com/v2?part=2"), recreated.parts.map { it.url })
    assertEquals(99_000, recreated.expiresAt)
    assertEquals(listOf(AcceptRule(208)), recreated.accept)
    // Ownership survives. The blob is reused for the full re-upload.
    assertEquals("/data/blob", recreated.sourcePath)
    assertEquals(1_000, recreated.createdAt)
  }

  @Test
  fun `recreate with the same ranges but new urls also resets statuses`() {
    // New part urls embed a new server uploadId, even when the split is
    // identical. Nothing sent under the old id counts for the new one.
    val stored = manifest().withPartAccepted(0)
    val fresh = manifest(
      parts = listOf(
        part(0, 100).copy(url = "https://example.com/v2?part=1"),
        part(100, 250).copy(url = "https://example.com/v2?part=2"),
      ),
    )
    val recreated = stored.reconcile(fresh, running = false, blobSize = blobSize)
    assertTrue(recreated.parts.none { it.accepted })
  }

  @Test
  fun `recreate is rejected while the upload is running`() {
    val stored = manifest()
    val fresh = manifest(parts = listOf(part(0, 250).copy(url = "https://example.com/v2")))
    assertThrows(ChunkedManifest.ReconcileException::class.java) {
      stored.reconcile(fresh, running = true, blobSize = blobSize)
    }
  }

  @Test
  fun `recreate rejects parts that do not tile the blob exactly`() {
    val stored = manifest()
    for (
      bad in listOf(
        listOf(part(0, 100), part(150, 250)), // gap
        listOf(part(0, 150), part(100, 250)), // overlap
        listOf(part(50, 250)), // does not start at 0
        listOf(part(0, 200)), // short of the blob size
        listOf(part(0, 100), part(100, 251)), // past the blob size
      )
    ) {
      assertThrows(ChunkedManifest.ReconcileException::class.java) {
        stored.reconcile(manifest(parts = bad), running = false, blobSize = blobSize)
      }
    }
  }

  @Test
  fun `recreate accepts parts authored in any order`() {
    val stored = manifest()
    val fresh = manifest(parts = listOf(part(100, 250), part(0, 100)).map { it.copy(url = it.url + "&v=2") })
    val recreated = stored.reconcile(fresh, running = false, blobSize = blobSize)
    assertEquals(2, recreated.parts.size)
  }

  @Test
  fun `tilesExactly covers the edge shapes`() {
    assertTrue(ChunkedManifest.tilesExactly(listOf(part(0, 250)), 250))
    assertFalse(ChunkedManifest.tilesExactly(emptyList(), 0))
    assertFalse(ChunkedManifest.tilesExactly(listOf(part(0, 0)), 0)) // empty range
    assertFalse(ChunkedManifest.tilesExactly(listOf(part(0, 250)), 300))
  }

  // MARK: - Create validation

  @Test
  fun `create accepts parts that tile the blob exactly`() {
    val m = manifest()
    assertEquals(m, ChunkedManifest.validatedForCreate(m, blobSize))
  }

  @Test
  fun `create rejects parts that do not tile the blob`() {
    for (
      bad in listOf(
        listOf(part(0, 100), part(150, 250)), // gap
        listOf(part(0, 150), part(100, 250)), // overlap
        listOf(part(50, 250)), // does not start at 0
        listOf(part(0, 200)), // short of the blob size
        listOf(part(0, 100), part(100, 251)), // past the blob size
      )
    ) {
      assertThrows(ChunkedManifest.ReconcileException::class.java) {
        ChunkedManifest.validatedForCreate(manifest(parts = bad), blobSize)
      }
    }
  }

  @Test
  fun `a rejected create writes no manifest, leaving the blob adoptable`() {
    // startUpload validates AFTER takeOwnership moved the bytes. The throw
    // propagates out of compute before a save. Thus the blob sits ownerless at
    // its path. That is exactly what takeOwnership's orphan branch adopts on
    // the corrected retry.
    val store = ChunkedManifestStore(tmp.newFolder())
    store.blobFile("u1").apply { parentFile!!.mkdirs() }.writeText("owned bytes")
    assertThrows(ChunkedManifest.ReconcileException::class.java) {
      store.compute("u1") { ChunkedManifest.validatedForCreate(manifest(), 999L) }
    }
    assertNull(store.load("u1"))
    assertTrue(store.blobFile("u1").exists())
  }

  // MARK: - Store

  @Test
  fun `save then load round-trips, across store instances`() {
    val dir = tmp.newFolder()
    val m = manifest().withPartAccepted(1)
    ChunkedManifestStore(dir).save(m)
    // A new instance over the same dir is what a process relaunch looks like.
    assertEquals(m, ChunkedManifestStore(dir).load("u1"))
  }

  @Test
  fun `load returns null for an unknown id`() {
    assertNull(ChunkedManifestStore(tmp.newFolder()).load("nope"))
  }

  @Test
  fun `update persists the transformed manifest`() {
    val store = ChunkedManifestStore(tmp.newFolder())
    store.save(manifest())
    val updated = store.update("u1") { it.withPartAccepted(0) }
    assertTrue(updated!!.parts[0].accepted)
    assertTrue(store.load("u1")!!.parts[0].accepted)
  }

  @Test
  fun `update of a missing manifest returns null`() {
    assertNull(ChunkedManifestStore(tmp.newFolder()).update("nope") { it })
  }

  @Test
  fun `compute creates when no manifest exists`() {
    val store = ChunkedManifestStore(tmp.newFolder())
    val created = store.compute("u1") { existing ->
      assertNull(existing)
      manifest()
    }
    assertEquals(created, store.load("u1"))
  }

  @Test
  fun `compute holds the store lock across load, transform, and save`() {
    // The startUpload reconcile and a running worker's markAccepted race. If
    // the lock did not span all three steps, the update below could land
    // between compute's load and save, and it would be erased from disk. When
    // they are serialized, both effects must survive.
    val store = ChunkedManifestStore(tmp.newFolder())
    store.save(manifest())
    val inTransform = CountDownLatch(1)
    val computing = Thread {
      store.compute("u1") { existing ->
        inTransform.countDown()
        Thread.sleep(300) // hold the lock with load done and save not yet run
        existing!!.copy(expiresAt = 99_000)
      }
    }.apply { start() }
    assertTrue(inTransform.await(5, TimeUnit.SECONDS))
    val updating = Thread { store.update("u1") { it.withPartAccepted(0) } }.apply { start() }
    computing.join()
    updating.join()
    val final = store.load("u1")!!
    assertEquals(99_000, final.expiresAt)
    assertTrue(final.parts[0].accepted)
  }

  @Test
  fun `a throwing compute transform propagates and writes nothing`() {
    val store = ChunkedManifestStore(tmp.newFolder())
    store.save(manifest())
    assertThrows(ChunkedManifest.ReconcileException::class.java) {
      store.compute("u1") { throw ChunkedManifest.ReconcileException("rejected") }
    }
    assertEquals(manifest(), store.load("u1"))
  }

  @Test
  fun `contains tracks save and remove`() {
    val store = ChunkedManifestStore(tmp.newFolder())
    assertFalse(store.contains("u1"))
    store.save(manifest())
    assertTrue(store.contains("u1"))
    store.remove("u1")
    assertFalse(store.contains("u1"))
  }

  @Test
  fun `remove deletes the manifest and the blob`() {
    val store = ChunkedManifestStore(tmp.newFolder())
    store.save(manifest())
    store.blobFile("u1").writeText("bytes")
    store.remove("u1")
    assertNull(store.load("u1"))
    assertFalse(store.blobFile("u1").exists())
  }

  @Test
  fun `remove of an unknown id is a no-op`() {
    ChunkedManifestStore(tmp.newFolder()).remove("simple-upload-id")
  }

  @Test
  fun `ids with filesystem-hostile characters round-trip`() {
    val store = ChunkedManifestStore(tmp.newFolder())
    val id = "a/b:c dü..\\e"
    store.save(manifest(id = id))
    assertEquals(id, store.load(id)!!.id)
    store.remove(id)
    assertNull(store.load(id))
  }

  @Test
  fun `a corrupt manifest reads as absent, not fatal`() {
    val dir = tmp.newFolder()
    val store = ChunkedManifestStore(dir)
    store.save(manifest())
    File(File(dir, dir.list()!!.first()), "manifest.json").writeText("{not json")
    assertNull(store.load("u1"))
    assertEquals(emptyList<ChunkedManifest>(), store.all())
  }

  @Test
  fun `all lists every stored manifest`() {
    val store = ChunkedManifestStore(tmp.newFolder())
    store.save(manifest(id = "u1"))
    store.save(manifest(id = "u2").withPartAccepted(0))
    assertEquals(setOf("u1", "u2"), store.all().map { it.id }.toSet())
  }
}
