package ai.openspace.backgroundupload

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class ChunkedEngineTest {

  // runBlocking is single-threaded, so the overlap is deterministic. Every
  // executor suspends at yield(). Thus all launchable siblings start before any
  // executor finishes.
  private class Tracker {
    var inFlight = 0
    var maxInFlight = 0
    val executions = ConcurrentHashMap<Int, Int>()

    suspend fun execute(index: Int) {
      executions.merge(index, 1, Int::plus)
      inFlight++
      maxInFlight = maxOf(maxInFlight, inFlight)
      yield()
      yield()
      inFlight--
    }
  }

  @Test
  fun `runs every part exactly once`() = runBlocking {
    val tracker = Tracker()
    ChunkedEngine.run((0 until 10).toList()) { tracker.execute(it) }
    assertEquals((0 until 10).associateWith { 1 }, tracker.executions.toMap())
  }

  @Test
  fun `never more than WINDOW parts in flight`() = runBlocking {
    val tracker = Tracker()
    ChunkedEngine.run((0 until 10).toList()) { tracker.execute(it) }
    assertEquals(ChunkedEngine.WINDOW, tracker.maxInFlight)
  }

  @Test
  fun `respects a smaller window`() = runBlocking {
    val tracker = Tracker()
    ChunkedEngine.run((0 until 5).toList(), window = 1) { tracker.execute(it) }
    assertEquals(1, tracker.maxInFlight)
  }

  @Test
  fun `an empty part list completes immediately`() = runBlocking {
    ChunkedEngine.run(emptyList()) { throw AssertionError("must not execute") }
  }

  @Test
  fun `a terminal part failure propagates and cancels the remaining parts`() {
    val tracker = Tracker()
    val thrown = assertThrows(IllegalStateException::class.java) {
      runBlocking {
        ChunkedEngine.run((0 until 10).toList()) { index ->
          if (index == 0) throw IllegalStateException("part rejected")
          tracker.execute(index)
        }
      }
    }
    assertEquals("part rejected", thrown.message)
    assertTrue(tracker.executions.size < 10)
  }

  @Test
  fun `backoff grows exponentially and caps`() {
    assertEquals(1_000, ChunkedEngine.backoffMs(1))
    assertEquals(2_000, ChunkedEngine.backoffMs(2))
    assertEquals(4_000, ChunkedEngine.backoffMs(3))
    assertEquals(60_000, ChunkedEngine.backoffMs(7))
    assertEquals(60_000, ChunkedEngine.backoffMs(100))
    // Defensive: a nonsense attempt number must not shift into a huge delay.
    assertEquals(1_000, ChunkedEngine.backoffMs(0))
  }

  // MARK: - startAction

  private fun manifest(vararg accepted: Boolean) = ChunkedManifest(
    id = "u1",
    sourcePath = "/data/blob",
    parts = accepted.mapIndexed { i, a ->
      ChunkedManifest.Part(
        url = "https://example.com/part?n=$i",
        headers = emptyMap(),
        start = i * 100L,
        end = (i + 1) * 100L,
        accepted = a,
      )
    },
    accept = emptyList(),
    expiresAt = 5_000,
    wifiOnly = false,
    noNotification = false,
    createdAt = 1_000,
  )

  @Test
  fun `no manifest at start is a silent success, never a journaled error`() {
    // A completed ack or removeUpload deleted the manifest while this run sat
    // in the queue. That is a legitimate end, already settled.
    assertEquals(ChunkedEngine.StartAction.NO_MANIFEST, ChunkedEngine.startAction(null))
  }

  @Test
  fun `an all-accepted manifest re-reports completion instead of running`() {
    assertEquals(
      ChunkedEngine.StartAction.ALREADY_COMPLETE,
      ChunkedEngine.startAction(manifest(true, true)),
    )
  }

  @Test
  fun `pending parts run the engine`() {
    assertEquals(ChunkedEngine.StartAction.RUN, ChunkedEngine.startAction(manifest(true, false)))
  }

  // MARK: - completionReport

  private fun completedEntry(uploadId: String) = EventJournal.Entry(
    eventId = "e-$uploadId",
    uploadId = uploadId,
    type = "completed",
    timestamp = 1,
  )

  @Test
  fun `an unacked completed entry is re-emitted, never minted twice`() {
    assertEquals(
      ChunkedEngine.CompletionReport.ReEmit(completedEntry("u1")),
      ChunkedEngine.completionReport(listOf(completedEntry("u1")), "u1", freshCompletion = false),
    )
    assertEquals(
      ChunkedEngine.CompletionReport.ReEmit(completedEntry("u1")),
      ChunkedEngine.completionReport(listOf(completedEntry("u1")), "u1", freshCompletion = true),
    )
  }

  @Test
  fun `a fresh completion with nothing journaled mints a new entry`() {
    assertEquals(
      ChunkedEngine.CompletionReport.Mint,
      ChunkedEngine.completionReport(emptyList(), "u1", freshCompletion = true),
    )
  }

  @Test
  fun `a trailing run over an acked completion reports nothing`() {
    // The trailing run raced ackEvents. The journal entry is already gone, but
    // the manifest still exists for a moment. An acknowledged completion means
    // that nobody is owed an event. A minted event would be a duplicate
    // 'completed' for an upload that the consumer already settled.
    assertEquals(
      ChunkedEngine.CompletionReport.None,
      ChunkedEngine.completionReport(emptyList(), "u1", freshCompletion = false),
    )
  }

  @Test
  fun `another upload's completed entry does not satisfy the lookup`() {
    assertEquals(
      ChunkedEngine.CompletionReport.None,
      ChunkedEngine.completionReport(listOf(completedEntry("other")), "u1", freshCompletion = false),
    )
  }

  @Test
  fun `only 5xx responses are transient`() {
    assertTrue(ChunkedEngine.isTransientHttp(500))
    assertTrue(ChunkedEngine.isTransientHttp(599))
    for (code in listOf(400, 401, 403, 404, 409, 429, 499, 600)) {
      assertEquals("code $code", false, ChunkedEngine.isTransientHttp(code))
    }
  }
}
