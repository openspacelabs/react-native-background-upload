package ai.openspace.backgroundupload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// An acknowledged 'completed' is the one moment when a chunked upload's stored
// state may be released. But only the completed life's state may go. A recreate
// under the same id can run over the same bytes, and it must survive the old
// life's ack.
class AckReleaseTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private fun manifest(id: String, accepted: Boolean) = ChunkedManifest(
    id = id,
    sourcePath = "/data/blob",
    parts = listOf(
      ChunkedManifest.Part(
        url = "https://example.com/1",
        headers = emptyMap(),
        start = 0,
        end = 100,
        accepted = accepted,
      ),
    ),
    accept = emptyList(),
    expiresAt = 5_000,
    wifiOnly = false,
    noNotification = false,
    createdAt = 1_000,
  )

  @Test
  fun `releases a completed upload's manifest and cancels its trailing runs`() {
    val store = ChunkedManifestStore(tmp.newFolder())
    store.save(manifest("u1", accepted = true))
    val cancelled = mutableListOf<String>()
    releaseAckedCompletions(listOf("u1"), store) { cancelled.add(it) }
    assertNull(store.load("u1"))
    assertEquals(listOf("u1"), cancelled)
  }

  @Test
  fun `spares a recreate running under the same id`() {
    // The acknowledged completion belongs to the id's PREVIOUS life. The
    // manifest now holds a recreate's unaccepted parts, and a worker can be
    // mid-transfer. A work cancel or a blob delete here would destroy its
    // bytes.
    val store = ChunkedManifestStore(tmp.newFolder())
    store.save(manifest("u1", accepted = false))
    val cancelled = mutableListOf<String>()
    releaseAckedCompletions(listOf("u1"), store) { cancelled.add(it) }
    assertNotNull(store.load("u1"))
    assertTrue(cancelled.isEmpty())
  }

  @Test
  fun `ignores ids with no manifest (simple uploads)`() {
    val store = ChunkedManifestStore(tmp.newFolder())
    val cancelled = mutableListOf<String>()
    releaseAckedCompletions(listOf("raw-upload"), store) { cancelled.add(it) }
    assertTrue(cancelled.isEmpty())
  }
}
