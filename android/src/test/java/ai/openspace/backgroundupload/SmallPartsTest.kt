package ai.openspace.backgroundupload

import androidx.work.WorkInfo.State.BLOCKED
import androidx.work.WorkInfo.State.CANCELLED
import androidx.work.WorkInfo.State.ENQUEUED
import androidx.work.WorkInfo.State.FAILED
import androidx.work.WorkInfo.State.RUNNING
import androidx.work.WorkInfo.State.SUCCEEDED
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttemptEventTest {
  private val response = UploadResponse(401, "x".repeat(5_000), mapOf("a" to "b"))

  @Test
  fun `the body is cut at 4 KB of UTF-8 and flagged`() {
    val e = AttemptEvent.ofResponse(entry(attempts = 2), "r1", "https://x", null, response, accepted = false, at = 7)
    assertEquals(BodyCap.ATTEMPT_MAX_BYTES, e.responseBody!!.length)
    assertEquals(true, e.responseBodyTruncated)
    assertEquals(2, e.attempt)
    // 3-byte characters: the cut backs off to a whole character.
    val wide = AttemptEvent.ofResponse(entry(), "r1", "https://x", null, response.copy(body = "\u20ac".repeat(2_000)), false, 7)
    assertEquals(BodyCap.ATTEMPT_MAX_BYTES / 3, wide.responseBody!!.length)
  }

  @Test
  fun `a response the stream cap cut is flagged even under 4 KB`() {
    val e = AttemptEvent.ofResponse(entry(), "r1", "https://x", null, response.copy(body = "short", truncated = true), false, 7)
    assertEquals("short", e.responseBody)
    assertEquals(true, e.responseBodyTruncated)
  }

  @Test
  fun `outcome is completed only when accepted`() {
    val rejected = AttemptEvent.ofResponse(entry(), "r1", "https://x", null, response, accepted = false, at = 7)
    assertEquals("error", rejected.outcome)
    assertEquals(401, rejected.httpCode)
    assertEquals("http", rejected.errorKind)
    val ok = AttemptEvent.ofResponse(entry(), "r1", "https://x", 3, response.copy(code = 200, body = "ok"), accepted = true, at = 7)
    assertEquals("completed", ok.outcome)
    assertNull(ok.errorKind)
    assertEquals(3.0, ok.toMap()["partIndex"])
  }

  @Test
  fun `partIndex and response fields are optional in the map`() {
    val failure = AttemptEvent.ofFailure(entry(), "r1", "https://x", null, "network", "reset", 7).toMap()
    assertEquals(
      setOf("id", "key", "requestId", "attempt", "url", "method", "outcome", "errorKind", "errorMessage", "at"),
      failure.keys,
    )
  }
}

class ProgressThrottleTest {
  private var now = 0L
  private val emitted = mutableListOf<Long>()
  private val throttle = ProgressThrottle({ now }) { _, sent, _ -> emitted += sent }

  @Test
  fun `the first offer emits, then at most one per second in the foreground`() {
    throttle.offer("a", 1, 10, foreground = true)
    now = 500
    throttle.offer("a", 2, 10, foreground = true)
    assertEquals(listOf(1L), emitted)
    now = 1_000
    throttle.offer("a", 3, 10, foreground = true)
    assertEquals(listOf(1L, 3L), emitted)
  }

  @Test
  fun `the background interval is 10 minutes`() {
    throttle.offer("a", 1, 10, foreground = false)
    now = 599_999
    throttle.offer("a", 2, 10, foreground = false)
    assertEquals(listOf(1L), emitted)
    now = 600_000
    throttle.offer("a", 3, 10, foreground = false)
    assertEquals(listOf(1L, 3L), emitted)
  }

  @Test
  fun `flush sends the held value once`() {
    throttle.offer("a", 1, 10, foreground = true)
    throttle.offer("a", 2, 10, foreground = true)
    throttle.flush("a")
    throttle.flush("a")
    assertEquals(listOf(1L, 2L), emitted)
  }

  @Test
  fun `ids are independent, and drop forgets an id`() {
    throttle.offer("a", 1, 10, foreground = true)
    throttle.offer("b", 5, 10, foreground = true)
    assertEquals(listOf(1L, 5L), emitted)
    throttle.offer("a", 2, 10, foreground = true)
    throttle.drop("a")
    throttle.flush("a")
    assertEquals(listOf(1L, 5L), emitted)
  }
}

class RequestIndexTest {
  @Test
  fun `put, remove, and snapshot order`() {
    val index = RequestIndex()
    index.put(entry(id = "b", createdAt = 2).toRow())
    index.put(entry(id = "a", createdAt = 2).toRow())
    index.put(entry(id = "c", createdAt = 1).toRow())
    assertEquals(listOf("c", "a", "b"), index.snapshot().map { it.id })
    index.remove("a")
    assertEquals(listOf("c", "b"), index.snapshot().map { it.id })
  }

  @Test
  fun `setBytes on a missing id is a no-op`() {
    val index = RequestIndex()
    index.setBytes("nope", 5)
    assertEquals(emptyList<RequestRow>(), index.snapshot())
  }

  @Test
  fun `a save of a running entry does not move bytes backwards`() {
    val index = RequestIndex()
    val running = entry(state = EntryState.RUNNING, body = StagedBody(StagedBody.FILE, "f", null, 100))
    index.put(running.toRow())
    index.setBytes("e1", 60)
    index.put(running.copy(attempts = 2).toRow())
    assertEquals(60, index.snapshot().single().bytesSent)
    index.put(running.copy(state = EntryState.QUEUED).toRow())
    assertEquals(0, index.snapshot().single().bytesSent)
  }
}

class SchedulerTest {
  @Test
  fun `initialDelayMs is the time left, never negative`() {
    assertEquals(0, WorkManagerScheduler.initialDelayMs(null, 1_000))
    assertEquals(0, WorkManagerScheduler.initialDelayMs(500, 1_000))
    assertEquals(4_000, WorkManagerScheduler.initialDelayMs(5_000, 1_000))
  }

  @Test
  fun `a queued successor suppresses another append`() {
    assertTrue(WorkManagerScheduler.hasQueuedSuccessor(listOf(RUNNING, BLOCKED)))
    assertTrue(WorkManagerScheduler.hasQueuedSuccessor(listOf(ENQUEUED)))
    assertFalse(WorkManagerScheduler.hasQueuedSuccessor(listOf(RUNNING)))
    assertFalse(WorkManagerScheduler.hasQueuedSuccessor(listOf(SUCCEEDED, FAILED, CANCELLED)))
    assertFalse(WorkManagerScheduler.hasQueuedSuccessor(emptyList()))
  }

  @Test
  fun `the wake name differs from the main chain`() {
    assertEquals("e1#wake", WorkManagerScheduler.wakeName("e1"))
  }
}

class TransferSemaphoreTest {
  @Test
  fun `the global cap is 4 and a fifth request waits`() = runBlocking {
    assertEquals(4, MAX_TRANSFER_CONCURRENCY)
    repeat(4) { transferSemaphore.acquire() }
    try {
      assertFalse(transferSemaphore.tryAcquire()) // no fifth permit
      transferSemaphore.release()
      assertTrue(transferSemaphore.tryAcquire()) // one freed, one taken
    } finally {
      repeat(4) { transferSemaphore.release() }
    }
    assertEquals(4, transferSemaphore.availablePermits)
  }
}
