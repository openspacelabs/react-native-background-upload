package ai.openspace.backgroundupload

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkedWorkerGateTest {
  private val a = Any()
  private val b = Any()

  @After
  fun tearDown() {
    // The gate is a process-wide singleton. Leave nothing for other tests.
    ChunkedWorkerGate.release("u1", a)
    ChunkedWorkerGate.release("u1", b)
    ChunkedWorkerGate.release("u2", a)
  }

  @Test
  fun `a second worker for the same id must wait`() {
    assertTrue(ChunkedWorkerGate.tryAcquire("u1", a))
    // The replacement worker after a cancel-then-start: it must not run a part
    // PUT while the cancelled worker still holds the id.
    assertFalse(ChunkedWorkerGate.tryAcquire("u1", b))
    ChunkedWorkerGate.release("u1", a)
    assertTrue(ChunkedWorkerGate.tryAcquire("u1", b))
  }

  @Test
  fun `reacquiring with the same token is idempotent`() {
    assertTrue(ChunkedWorkerGate.tryAcquire("u1", a))
    assertTrue(ChunkedWorkerGate.tryAcquire("u1", a))
  }

  @Test
  fun `a stale release cannot evict a successor`() {
    assertTrue(ChunkedWorkerGate.tryAcquire("u1", a))
    ChunkedWorkerGate.release("u1", a)
    assertTrue(ChunkedWorkerGate.tryAcquire("u1", b))
    ChunkedWorkerGate.release("u1", a) // the old worker's finally, arriving late
    assertTrue(ChunkedWorkerGate.isRunning("u1"))
    assertFalse(ChunkedWorkerGate.tryAcquire("u1", a))
  }

  @Test
  fun `ids are independent and isRunning tracks the holder`() {
    assertFalse(ChunkedWorkerGate.isRunning("u1"))
    assertTrue(ChunkedWorkerGate.tryAcquire("u1", a))
    assertTrue(ChunkedWorkerGate.isRunning("u1"))
    assertFalse(ChunkedWorkerGate.isRunning("u2"))
    assertTrue(ChunkedWorkerGate.tryAcquire("u2", a))
    ChunkedWorkerGate.release("u1", a)
    assertFalse(ChunkedWorkerGate.isRunning("u1"))
    assertTrue(ChunkedWorkerGate.isRunning("u2"))
  }
}
