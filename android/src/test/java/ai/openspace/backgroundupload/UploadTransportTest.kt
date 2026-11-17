package ai.openspace.backgroundupload

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class UploadHttpClientTest {
  @Test
  fun `the client sets connect 15 s, read 60 s, write 60 s, and keeps the 24 h call cap`() {
    assertEquals(15_000, uploadHttpClient.connectTimeoutMillis)
    assertEquals(60_000, uploadHttpClient.readTimeoutMillis)
    assertEquals(60_000, uploadHttpClient.writeTimeoutMillis)
    assertEquals(TimeUnit.HOURS.toMillis(24).toInt(), uploadHttpClient.callTimeoutMillis)
  }
}

class AwaitLandedTest {
  private val response = UploadResponse(201, "created", mapOf())

  /** A fake call: the test delivers the result by hand and sees whether the call was cancelled. */
  private class FakeCall {
    var onResult: ((Result<UploadResponse>) -> Unit)? = null
    var cancels = 0

    suspend fun await(): UploadResponse =
      awaitLanded(register = { onResult = it }, cancel = { cancels++ })
  }

  @Test
  fun `a result with no stop is returned`() = runBlocking {
    val call = FakeCall()
    var got: UploadResponse? = null
    val job = launch(start = CoroutineStart.UNDISPATCHED) { got = call.await() }
    call.onResult!!(Result.success(response))
    job.join()
    assertSame(response, got)
    assertEquals(0, call.cancels)
  }

  @Test
  fun `a failure with no stop is thrown`() = runBlocking {
    val call = FakeCall()
    var thrown: Throwable? = null
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      try { call.await() } catch (e: IOException) { thrown = e }
    }
    call.onResult!!(Result.failure(IOException("reset")))
    job.join()
    assertEquals("reset", thrown!!.message)
  }

  @Test
  fun `a result that lands before a stop is returned`() = runBlocking {
    val call = FakeCall()
    var got: UploadResponse? = null
    val job = launch(start = CoroutineStart.UNDISPATCHED) { got = call.await() }
    // The response lands, and the stop arrives before the coroutine runs again.
    // This is the window where a plain suspendCancellableCoroutine drops the value.
    call.onResult!!(Result.success(response))
    job.cancel()
    job.join()
    assertSame(response, got)
  }

  @Test
  fun `a stop with no result cancels the call and throws`() = runBlocking {
    val call = FakeCall()
    var thrown: Throwable? = null
    var got: UploadResponse? = null
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      try { got = call.await() } catch (e: CancellationException) { thrown = e; throw e }
    }
    job.cancel()
    job.join()
    assertEquals(1, call.cancels)
    assertTrue(thrown is CancellationException)
    assertNull(got)
  }

  @Test
  fun `a stop before the call starts cancels it at once`() = runBlocking {
    val call = FakeCall()
    var thrown: Throwable? = null
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      currentCoroutineContext()[Job]!!.cancel()
      try { call.await() } catch (e: CancellationException) { thrown = e; throw e }
    }
    job.join()
    assertNotNull(thrown)
    assertEquals(1, call.cancels)
  }

  @Test
  fun `a failure that lands before a stop is thrown as it is`() = runBlocking {
    // Unchanged behavior: kotlinx delivers an exception that landed first.
    // The attempt then counts a transport failure, and its backoff stops the run.
    val call = FakeCall()
    var thrown: Throwable? = null
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      try { call.await() } catch (e: IOException) { thrown = e }
    }
    call.onResult!!(Result.failure(IOException("reset")))
    job.cancel()
    job.join()
    assertEquals("reset", thrown!!.message)
    assertFalse(job.isActive)
  }
}

class TransferGateStopTest {
  @Test
  fun `the gate returns the block's value on a stopped job, in both lanes`() = runBlocking {
    // EntryWorker sends inside transferGate.withPermit. A response that
    // landed as the run stopped must come out of the gate: nothing in it may
    // suspend after the block.
    for (high in listOf(false, true)) {
      val gate = TransferGate(total = 4, reserved = 1)
      var got: String? = null
      val job = launch {
        got = gate.withPermit(high) {
          currentCoroutineContext()[Job]!!.cancel()
          "landed"
        }
      }
      job.join()
      assertEquals("landed", got)
      assertEquals(4, gate.availableSlots)
    }
  }
}
