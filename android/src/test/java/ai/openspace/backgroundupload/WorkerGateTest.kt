package ai.openspace.backgroundupload

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerGateTest {
  private val a = Any()
  private val b = Any()

  @After
  fun tearDown() {
    // A process-wide singleton. Leave nothing for other tests.
    listOf("g1", "g2").forEach { id -> WorkerGate.release(id, a); WorkerGate.release(id, b) }
  }

  @Test
  fun `a second worker for the same id must wait`() {
    assertTrue(WorkerGate.tryAcquire("g1", a))
    assertFalse(WorkerGate.tryAcquire("g1", b))
    WorkerGate.release("g1", a)
    assertTrue(WorkerGate.tryAcquire("g1", b))
  }

  @Test
  fun `reacquiring with the same token is idempotent`() {
    assertTrue(WorkerGate.tryAcquire("g1", a))
    assertTrue(WorkerGate.tryAcquire("g1", a))
  }

  @Test
  fun `a stale release can not evict a successor`() {
    assertTrue(WorkerGate.tryAcquire("g1", a))
    WorkerGate.release("g1", a)
    assertTrue(WorkerGate.tryAcquire("g1", b))
    WorkerGate.release("g1", a)
    assertTrue(WorkerGate.isRunning("g1"))
    assertFalse(WorkerGate.tryAcquire("g1", a))
  }

  @Test
  fun `ids are independent`() {
    assertTrue(WorkerGate.tryAcquire("g1", a))
    assertFalse(WorkerGate.isRunning("g2"))
    assertTrue(WorkerGate.tryAcquire("g2", a))
    WorkerGate.release("g1", a)
    assertFalse(WorkerGate.isRunning("g1"))
    assertTrue(WorkerGate.isRunning("g2"))
  }
}
