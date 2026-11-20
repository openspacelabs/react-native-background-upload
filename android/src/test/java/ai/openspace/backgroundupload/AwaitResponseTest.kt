package ai.openspace.backgroundupload

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

/**
 * A plain HTTP/1.1 server on a local socket. It reads one request at a time
 * on a connection, waits [delayMs], and answers 200 "ok". With [truncate] it
 * promises 10 body bytes, sends 2, and closes.
 */
private class TinyServer(private val delayMs: Long = 0, private val truncate: Boolean = false) : AutoCloseable {
  private val server = ServerSocket(0)
  val port: Int get() = server.localPort
  val connections = AtomicInteger(0)
  val requests = AtomicInteger(0)

  @Volatile
  var answered = CountDownLatch(1)

  init {
    thread(isDaemon = true) {
      while (true) {
        val s = try { server.accept() } catch (e: IOException) { return@thread }
        connections.incrementAndGet()
        thread(isDaemon = true) { serve(s) }
      }
    }
  }

  private fun serve(s: Socket) {
    try {
      val input = s.getInputStream()
      val out = s.getOutputStream()
      while (true) {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
          val b = input.read()
          if (b < 0) return
          head.append(b.toChar())
        }
        var left = Regex("(?i)content-length: (\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
        val buf = ByteArray(8192)
        while (left > 0) {
          val n = input.read(buf, 0, minOf(left, buf.size))
          if (n < 0) return
          left -= n
        }
        requests.incrementAndGet()
        if (delayMs > 0) Thread.sleep(delayMs)
        val length = if (truncate) 10 else 2
        out.write("HTTP/1.1 200 OK\r\nContent-Length: $length\r\nContent-Type: text/plain\r\n\r\nok".toByteArray())
        out.flush()
        answered.countDown()
        if (truncate) return
      }
    } catch (e: IOException) {
      // The client went away.
    } finally {
      runCatching { s.close() }
    }
  }

  override fun close() = server.close()
}

/** A dispatcher that only queues. The test runs the tasks by hand, so it decides when the coroutine runs again. */
private class ManualDispatcher : CoroutineDispatcher() {
  private val queue = ConcurrentLinkedQueue<Runnable>()

  override fun dispatch(context: CoroutineContext, block: Runnable) {
    queue += block
  }

  fun pump() {
    while (true) (queue.poll() ?: return).run()
  }

  /** Waits until another thread queued a task: the OkHttp callback resumed the coroutine. */
  fun awaitTask(ms: Long = 10_000) {
    val end = System.currentTimeMillis() + ms
    while (queue.isEmpty()) {
      check(System.currentTimeMillis() < end) { "no task queued" }
      Thread.sleep(5)
    }
  }
}

/** awaitResponse (through okhttpSend) against real OkHttp and a local socket server. */
class AwaitResponseTest {
  // The library's client with its own dispatcher and pool, so the counts here are this test's own.
  private val client: OkHttpClient = uploadHttpClient.newBuilder()
    .dispatcher(Dispatcher())
    .connectionPool(ConnectionPool())
    .build()

  @After
  fun tearDown() {
    client.dispatcher.cancelAll()
    client.connectionPool.evictAll()
  }

  private fun request(port: Int) = TransferRequest(
    "http://127.0.0.1:$port/items", "POST", mapOf("Content-Type" to "application/json"),
    """{"a":1}""".toRequestBody(null),
  )

  @Test
  fun `a landed response survives a stop, and the connection is reused`() {
    TinyServer().use { server ->
      val dispatcher = ManualDispatcher()
      var got: UploadResponse? = null
      val job = CoroutineScope(dispatcher + Job()).launch {
        got = okhttpSend(client, request(server.port)) {}
      }
      dispatcher.pump() // runs to the suspension in awaitLanded; the call is enqueued
      assertTrue(server.answered.await(10, TimeUnit.SECONDS))
      dispatcher.awaitTask() // OkHttp read the response and resumed; the coroutine waits to run
      job.cancel() // the stop, before the coroutine runs again
      dispatcher.pump()
      assertEquals(200, got?.code)
      assertEquals("ok", got?.body)
      assertEquals(1, server.requests.get())

      // The call.cancel() from the stop ran after the response was read and
      // closed, so the pooled connection still works.
      val again = runBlocking { okhttpSend(client, request(server.port)) {} }
      assertEquals(200, again.code)
      assertEquals(2, server.requests.get())
      assertEquals(1, server.connections.get())
    }
  }

  @Test
  fun `a stop with no response cancels the call and throws the stop`() {
    TinyServer(delayMs = 30_000).use { server ->
      var thrown: Throwable? = null
      runBlocking {
        val job = launch(Dispatchers.IO) {
          try { okhttpSend(client, request(server.port)) {} } catch (e: Throwable) { thrown = e; throw e }
        }
        withContext(Dispatchers.IO) { while (server.requests.get() == 0) Thread.sleep(10) }
        job.cancel()
        withTimeout(5_000) { job.join() }
      }
      assertTrue(thrown is CancellationException)
      // call.cancel() ended the call: OkHttp runs nothing for it.
      val end = System.currentTimeMillis() + 5_000
      while (client.dispatcher.runningCallsCount() > 0 && System.currentTimeMillis() < end) Thread.sleep(10)
      assertEquals(0, client.dispatcher.runningCallsCount())
    }
  }

  @Test
  fun `a stop before the call starts sends nothing`() {
    TinyServer().use { server ->
      var thrown: Throwable? = null
      runBlocking {
        launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
          currentCoroutineContext()[Job]!!.cancel()
          try { okhttpSend(client, request(server.port)) {} } catch (e: Throwable) { thrown = e; throw e }
        }.join()
      }
      Thread.sleep(300)
      assertTrue(thrown is CancellationException)
      assertEquals(0, server.requests.get())
      assertEquals(0, server.connections.get())
    }
  }

  @Test
  fun `a body that fails to read is not saved as landed`() {
    TinyServer(truncate = true).use { server ->
      val dispatcher = ManualDispatcher()
      var got: UploadResponse? = null
      var thrown: Throwable? = null
      val job = CoroutineScope(dispatcher + Job()).launch {
        try { got = okhttpSend(client, request(server.port)) {} } catch (e: Throwable) { thrown = e }
      }
      dispatcher.pump()
      assertTrue(server.answered.await(10, TimeUnit.SECONDS))
      dispatcher.awaitTask() // the read failed and resumed the coroutine with it
      job.cancel()
      dispatcher.pump()
      // The failure comes through as a transport failure, not as a response.
      assertNull(got)
      assertTrue("expected an IOException, got $thrown", thrown is IOException)
    }
  }
}
