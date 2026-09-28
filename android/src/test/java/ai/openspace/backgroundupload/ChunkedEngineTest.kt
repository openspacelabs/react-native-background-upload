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
  // executor suspends at yield(), so all launchable siblings start before
  // any executor finishes.
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
    assertEquals(3, ChunkedEngine.WINDOW)
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
  fun `a part failure propagates and cancels the remaining parts`() {
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
  fun `a park from one part stops the siblings with the park itself`() {
    // The worker needs the ParkException back, not a CancellationException.
    val thrown = assertThrows(EntryRun.ParkException::class.java) {
      runBlocking {
        ChunkedEngine.run((0 until 6).toList()) { index ->
          yield()
          if (index == 1) throw EntryRun.ParkException(4)
          yield()
        }
      }
    }
    assertEquals(4, thrown.headerGeneration)
  }
}
