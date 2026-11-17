package ai.openspace.backgroundupload

import androidx.work.WorkInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** A scripted [TransferHost]: a manual clock, a send handler, and a log of what the run did. */
internal class FakeHost(var clock: Long = 10_000L) : TransferHost {
  val requests = mutableListOf<TransferRequest>()
  val sleeps = mutableListOf<Long>()
  val attempts = mutableListOf<AttemptEvent>()
  val progress = mutableListOf<String>()
  var handler: suspend (TransferRequest, (Long) -> Unit) -> UploadResponse = { _, _ -> UploadResponse(200, "ok", mapOf()) }
  var onSleep: () -> Unit = {}
  var network = ArrayDeque<Connectivity>()
  /** What stopKind() answers after a cancel. */
  var stop = StopKind.OTHER
  /** Runs while a request waits for a transmit slot, before it holds one. */
  var beforeSlot: suspend () -> Unit = {}
  /** The highPriority argument of every slot request, in order. */
  val slots = mutableListOf<Boolean>()
  var foregroundError: Throwable? = null
  /** The wifiOnly value of every connectivity check, in order. */
  val wifiChecks = mutableListOf<Boolean>()

  override fun now() = clock

  override suspend fun sleep(ms: Long) {
    sleeps += ms
    clock += ms
    onSleep()
    // A real delay throws here when the run's job was cancelled.
    currentCoroutineContext().ensureActive()
  }

  override suspend fun <T> withSlot(highPriority: Boolean, block: suspend () -> T): T {
    slots += highPriority
    beforeSlot()
    return block()
  }

  override suspend fun send(request: TransferRequest, onProgress: (Long) -> Unit): UploadResponse {
    requests += request
    return handler(request, onProgress)
  }

  override fun connectivity(wifiOnly: Boolean): Connectivity {
    wifiChecks += wifiOnly
    return network.removeFirstOrNull() ?: Connectivity.Ok
  }

  override suspend fun foreground(entry: QueueEntry) {
    foregroundError?.let { throw it }
  }

  override fun progressStarted(id: String, total: Long, sent: Long) {
    progress += "start:$total:$sent"
  }

  override fun progress(id: String, sent: Long, total: Long) {
    progress += "$sent"
  }

  override fun progressEnded(id: String, completed: Boolean) {
    progress += "end:$completed"
  }

  override fun attempt(event: AttemptEvent) {
    attempts += event
  }

  override fun stopKind() = stop

  /** What rowRestartsBy() answers. Null: unknown. */
  var restartsBy: Long? = null

  override fun rowRestartsBy() = restartsBy
}

class EntryRunTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private lateinit var root: File
  private lateinit var store: QueueStore
  private lateinit var journal: EventJournal
  private lateinit var settings: QueueSettingsStore
  private val events = RecordingEvents()
  private val scheduler = FakeScheduler()
  private val host = FakeHost()
  private lateinit var ops: WorkerOps
  private lateinit var controller: QueueController

  @Before
  fun setUp() {
    root = tmp.newFolder("queue")
    store = QueueStore(root, RequestIndex())
    journal = EventJournal(tmp.newFolder("journal"), retryLater = { _, _ -> })
    settings = QueueSettingsStore(File(root, "settings.json"))
    ops = WorkerOps(store, journal, settings, events, scheduler) { host.clock }
    controller = QueueController(store, journal, settings, events, scheduler, { false }, { host.clock })
    controller.configureRetry(RetryDefaults(baseMs = 1_000, jitter = 0.0))
    journal.drain(Any())
    Diag.log = null
  }

  @After
  fun tearDown() {
    Diag.log = null
    Diag.clock = System::currentTimeMillis
  }

  private fun run(id: String = "e1") = runBlocking { EntryRun(id, store, ops, host).run() }

  private fun respond(vararg codes: Int) {
    val queue = ArrayDeque(codes.toList())
    host.handler = { _, onProgress ->
      onProgress(7)
      UploadResponse(queue.removeFirst(), "body", mapOf())
    }
  }

  private fun outcome() = journal.unacknowledged().single()

  // MARK: - simple

  @Test
  fun `an accepted response settles completed after one write-ahead attempt`() {
    controller.enqueue(parsed())
    respond(200)
    run()
    val e = store.load("e1")!!
    assertEquals(EntryState.COMPLETED, e.state)
    assertEquals(1, e.attempts)
    val request = host.requests.single()
    assertEquals(e.lastRequestId, request.headers["X-Request-Id"])
    assertEquals("Bearer old", request.headers["Authorization"])
    assertEquals("application/json", request.headers["Content-Type"])
    assertEquals(7L, request.body!!.contentLength())
    assertEquals(listOf("completed"), host.attempts.map { it.outcome })
    assertEquals(EventJournal.KIND_COMPLETED, outcome().kind)
    assertEquals(200, outcome().response!!.status)
    assertEquals(listOf("start:7:0", "7", "end:true"), host.progress)
  }

  @Test
  fun `a high-priority entry sends its request as high priority`() {
    controller.enqueue(parsed(descriptor = desc(dataJson = """{"a":1}""", highPriority = true)))
    controller.enqueue(parsed(id = "normal"))
    respond(200, 200)
    run()
    run("normal")
    assertEquals(listOf(true, false), host.slots)
  }

  @Test
  fun `a transient response waits a short backoff in place, with nextAttemptAt on the running row`() {
    controller.enqueue(parsed())
    respond(503, 200)
    run()
    assertEquals(listOf(1_000L), host.sleeps)
    assertEquals(listOf("error", "completed"), host.attempts.map { it.outcome })
    assertEquals("http", host.attempts[0].errorKind)
    assertEquals(503, host.attempts[0].httpCode)
    val waiting = events.rows.first { it.toMap().containsKey("nextAttemptAt") }
    assertEquals("running", waiting.state)
    assertEquals(11_000.0, waiting.toMap()["nextAttemptAt"])
    assertEquals(2, store.load("e1")!!.attempts)
    assertEquals(listOf("start:7:0", "7", "0", "7", "end:true"), host.progress) // bytes reset between attempts
  }

  @Test
  fun `a backoff longer than 30 s releases the run back to queued with a wake`() {
    controller.configureRetry(RetryDefaults(baseMs = 60_000, jitter = 0.0))
    controller.enqueue(parsed())
    respond(503)
    run()
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(host.clock + 60_000, e.nextAttemptAt)
    assertEquals(1, e.backoffStreak)
    assertEquals(listOf("e1" to host.clock + 60_000), scheduler.wakes)
    assertEquals(emptyList<Long>(), host.sleeps)
    assertEquals("end:false", host.progress.last())
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.unacknowledged())
  }

  @Test
  fun `a transport failure is a network attempt and retries`() {
    controller.enqueue(parsed())
    var calls = 0
    host.handler = { _, _ -> if (calls++ == 0) throw IOException("reset") else UploadResponse(200, "", mapOf()) }
    run()
    assertEquals(listOf("network", null), host.attempts.map { it.errorKind })
    assertEquals("reset", host.attempts[0].errorMessage)
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)
  }

  @Test
  fun `a connect failure waits at most 15 minutes, an HTTP failure keeps the 2 hour maximum`() {
    controller.configureRetry(RetryDefaults(baseMs = 1_000, maxMs = 7_200_000, jitter = 0.0))
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(backoffStreak = 12))
    host.handler = { _, _ -> throw java.net.ConnectException("Failed to connect to example.com/1.2.3.4:443") }
    run()
    // Streak 13 is 2^12 s, about 68 minutes, capped at 15.
    val e = store.load("e1")!!
    assertEquals(host.clock + RetryClassifier.TRANSPORT_FAILURE_MAX_MS, e.nextAttemptAt)
    assertEquals(13, e.backoffStreak)
    assertEquals(WaitCause.NO_RESPONSE, e.waitCause)
    assertEquals(host.clock, e.failedAt)

    controller.enqueue(parsed(id = "e2"))
    store.save(store.load("e2")!!.copy(backoffStreak = 12))
    respond(503)
    run("e2")
    assertEquals(host.clock + 4_096_000, store.load("e2")!!.nextAttemptAt)
    assertEquals(WaitCause.RESPONSE, store.load("e2")!!.waitCause)
    store.save(store.load("e2")!!.copy(nextAttemptAt = null, backoffStreak = 13))
    respond(503)
    run("e2")
    assertEquals(host.clock + 7_200_000, store.load("e2")!!.nextAttemptAt)
  }

  @Test
  fun `a failure after the request may have gone out keeps the 2 hour maximum`() {
    // A read timeout, or a reset after the body left: the server may have
    // the request, so a sooner repeat would make a duplicate likelier.
    for ((n, error) in listOf(java.net.SocketTimeoutException("timeout"), IOException("connection reset")).withIndex()) {
      controller.enqueue(parsed(id = "t$n"))
      store.save(store.load("t$n")!!.copy(backoffStreak = 13))
      host.handler = { _, _ -> throw error }
      run("t$n")
      assertEquals(error.message, host.clock + 7_200_000, store.load("t$n")!!.nextAttemptAt)
      assertEquals(WaitCause.NO_RESPONSE, store.load("t$n")!!.waitCause)
    }
  }

  @Test
  fun `a connect failure keeps a maximum below 15 minutes`() {
    controller.configureRetry(RetryDefaults(baseMs = 1_000, maxMs = 60_000, jitter = 0.0))
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(backoffStreak = 12))
    host.handler = { _, _ -> throw java.net.UnknownHostException("example.com") }
    run()
    assertEquals(host.clock + 60_000, store.load("e1")!!.nextAttemptAt)
  }

  // MARK: - the app clears a wait (review probes P1 and P2)

  @Test
  fun `repeated returns to the foreground do not send again after a 503`() {
    // Probe P1: before, 10 foregrounds gave 10 requests with 0 ms between.
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(backoffStreak = 13))
    host.handler = { _, _ -> UploadResponse(503, "down", mapOf()) }
    repeat(10) {
      controller.retryNow()
      run()
    }
    assertEquals(1, host.requests.size)
    assertEquals(WaitCause.RESPONSE, store.load("e1")!!.waitCause)
  }

  @Test
  fun `repeated returns to the foreground after a connect failure send at most once per 30 s`() {
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(backoffStreak = 13))
    val sentAt = mutableListOf<Long>()
    host.handler = { _, _ ->
      sentAt += host.clock
      throw java.net.ConnectException("Failed to connect")
    }
    repeat(10) {
      controller.retryNow()
      run()
      host.clock += 10_000
    }
    // The first clear comes 30 s after the failure (its own wait was 15
    // minutes). Later requests come from a clear or from the entry's own
    // backoff, and never less than 30 s apart.
    assertEquals(sentAt.toString(), sentAt[0] + RetryClassifier.CLEAR_MIN_AGE_MS, sentAt[1])
    assertTrue(sentAt.toString(), sentAt.zipWithNext().all { (a, b) -> b - a >= RetryClassifier.CLEAR_MIN_AGE_MS })
    assertTrue(sentAt.toString(), sentAt.size < 10)
  }

  @Test
  fun `returns to the foreground do not spend a transient rule's retries`() {
    // Probe P2: before, six foregrounds used all 5 retries and the entry
    // settled error with no time between.
    controller.configureRetry(
      RetryDefaults(baseMs = 60_000, jitter = 0.0, transient = listOf(UploadOutcome.TransientRule(409, maxRetries = 5))),
    )
    controller.enqueue(parsed())
    host.handler = { _, _ -> UploadResponse(409, "not yet", mapOf()) }
    repeat(20) {
      controller.retryNow()
      run()
    }
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(1, host.requests.size)
    assertEquals(1, e.transientHitsByRule!!.values.sum())
  }

  @Test
  fun `a terminal response settles error http with the response and the live bytes`() {
    controller.enqueue(parsed())
    respond(400)
    run()
    val r = outcome()
    assertEquals(EventJournal.KIND_ERROR, r.kind)
    assertEquals("http", r.errorKind)
    assertEquals(400, r.response!!.status)
    assertEquals(7, r.bytesSent)
    assertEquals(7, store.load("e1")!!.bytesSent)
    assertEquals("end:false", host.progress.last())
  }

  @Test
  fun `a staged body gone before the run settles error file with no request`() {
    controller.enqueue(parsed())
    store.bodyFile(store.load("e1")!!)!!.delete()
    run()
    assertEquals("file", outcome().errorKind)
    assertEquals(emptyList<TransferRequest>(), host.requests)
  }

  @Test
  fun `a body that vanishes mid-send settles error file, and the attempt says file`() {
    controller.enqueue(parsed())
    host.handler = { _, _ ->
      store.bodyFile(store.load("e1")!!)!!.delete()
      throw IOException("ENOENT")
    }
    run()
    assertEquals("file", outcome().errorKind)
    assertEquals(listOf("file"), host.attempts.map { it.errorKind })
  }

  @Test
  fun `a 401 parks the entry and wakes it at expiry`() {
    controller.enqueue(parsed(expiresAt = 90_000))
    respond(401)
    run()
    assertEquals(EntryState.AWAITING_AUTH, store.load("e1")!!.state)
    assertEquals(listOf("e1" to 90_000L), scheduler.wakes)
    assertEquals(listOf("error"), host.attempts.map { it.outcome })
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.unacknowledged())
  }

  @Test
  fun `a 401 with newer headers from updateHeaders mid-flight re-issues at once with them`() {
    controller.enqueue(parsed())
    var calls = 0
    host.handler = { _, _ ->
      if (calls++ == 0) {
        controller.updateHeaders(mapOf("Authorization" to "Bearer new"))
        UploadResponse(401, "", mapOf())
      } else UploadResponse(200, "", mapOf())
    }
    run()
    assertEquals(listOf("Bearer old", "Bearer new"), host.requests.map { it.headers["Authorization"] })
    assertEquals(emptyList<Long>(), host.sleeps)
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)
  }

  @Test
  fun `the expiry wake of a parked entry settles expired and keeps the stored bytes`() {
    controller.enqueue(parsed(expiresAt = 20_000))
    store.save(store.load("e1")!!.copy(state = EntryState.AWAITING_AUTH, bytesSent = 3))
    run()
    assertEquals(EntryState.AWAITING_AUTH, store.load("e1")!!.state) // not yet expired
    host.clock = 20_000
    run()
    assertEquals("expired", outcome().errorKind)
    assertEquals(3, outcome().bytesSent)
    assertEquals(emptyList<TransferRequest>(), host.requests)
  }

  @Test
  fun `an entry past expiresAt settles error expired`() {
    controller.enqueue(parsed(expiresAt = 5_000))
    run()
    assertEquals("expired", outcome().errorKind)
    assertEquals(emptyList<TransferRequest>(), host.requests)
  }

  @Test
  fun `a paused entry past expiresAt stays paused, and settles expired at resume`() {
    controller.enqueue(parsed(expiresAt = 20_000))
    controller.pause()
    host.clock = 30_000
    run() // a wake that fires during the pause
    assertEquals(EntryState.PAUSED, store.load("e1")!!.state)
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.unacknowledged())
    controller.resume()
    assertEquals(EntryState.QUEUED, store.load("e1")!!.state)
    run()
    assertEquals("expired", outcome().errorKind)
    assertEquals(EntryState.ERROR, store.load("e1")!!.state)
  }

  @Test
  fun `the connectivity check uses the entry's wifiOnly, else the queue setting at each poll`() {
    controller.setWifiOnly(true)
    controller.enqueue(parsed(id = "cell", descriptor = desc(dataJson = """{"a":1}""", wifiOnly = false)))
    respond(200)
    run("cell")
    assertEquals(listOf(false), host.wifiChecks) // its own false beats the queue's true

    host.wifiChecks.clear()
    controller.enqueue(parsed(id = "follow"))
    host.network = ArrayDeque(listOf(Connectivity.NoWifi))
    host.onSleep = { controller.setWifiOnly(false) } // a toggle while it waits
    respond(200)
    run("follow")
    assertEquals(listOf(true, false), host.wifiChecks)
    assertEquals(EntryState.COMPLETED, store.load("follow")!!.state)
  }

  @Test
  fun `a run under a key pause does not start, and other keys run`() {
    controller.enqueue(parsed(id = "c", key = "capture"))
    controller.enqueue(parsed(id = "n", key = "note"))
    controller.pause(listOf("capture"))
    respond(200)
    run("c")
    run("n")
    assertEquals(EntryState.PAUSED, store.load("c")!!.state)
    assertEquals(EntryState.COMPLETED, store.load("n")!!.state)
    assertEquals(1, host.requests.size)
  }

  @Test
  fun `a key pause while waiting for the network stops the run with no outcome`() {
    controller.enqueue(parsed(id = "c", key = "capture"))
    host.network = ArrayDeque(listOf(Connectivity.NoInternet))
    host.onSleep = { controller.pause(listOf("capture")) }
    run("c")
    assertEquals(EntryState.PAUSED, store.load("c")!!.state)
    assertEquals(emptyList<TransferRequest>(), host.requests)
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.unacknowledged())
  }

  @Test
  fun `a failure that lands after pause is not an outcome`() {
    controller.enqueue(parsed())
    host.handler = { _, _ ->
      controller.pause()
      UploadResponse(400, "", mapOf())
    }
    run()
    assertEquals(EntryState.PAUSED, store.load("e1")!!.state)
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.unacknowledged())
  }

  @Test
  fun `an accepted response that lands after pause settles completed`() {
    controller.enqueue(parsed())
    host.handler = { _, _ ->
      controller.pause()
      UploadResponse(200, "", mapOf())
    }
    run()
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)
  }

  @Test
  fun `a transient response after cancel stops the run with only the cancel outcome`() {
    controller.enqueue(parsed())
    host.handler = { _, _ ->
      controller.cancel("e1")
      UploadResponse(503, "", mapOf())
    }
    run()
    assertEquals(EventJournal.KIND_CANCELLED, outcome().kind)
    assertEquals("end:false", host.progress.last())
  }

  @Test
  fun `a system stop moves the entry back to queued with no outcome and no attempt event`() {
    controller.enqueue(parsed())
    host.handler = { _, _ -> throw CancellationException("stopped") }
    assertThrows(CancellationException::class.java) { run() }
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertNull(e.nextAttemptAt)
    assertEquals(emptyList<AttemptEvent>(), host.attempts)
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.unacknowledged())
    assertEquals("end:false", host.progress.last())
  }

  @Test
  fun `a timeout stop takes one more backoff step instead of restarting at once`() {
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(backoffStreak = 2))
    host.stop = StopKind.TIME_LIMIT
    host.handler = { _, _ -> throw CancellationException("timeout") } // the stop cuts the request
    assertThrows(CancellationException::class.java) { run() }
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(3, e.backoffStreak)
    assertEquals(1, e.attempts)
    assertEquals(host.clock + 4_000, e.nextAttemptAt) // base 1 s * 2^(3-1)
    // A fresh wake row, even for a wait a worker could sleep out in place.
    assertEquals(listOf("e1" to host.clock + 4_000), scheduler.wakes)
    assertEquals(listOf(true), scheduler.wakeReplaces)
  }

  @Test
  fun `a foreground-service timeout that cut a request takes one backoff step`() {
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(backoffStreak = 6))
    host.stop = StopKind.of(WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT)
    host.handler = { _, _ -> throw CancellationException("fgs timeout") }
    assertThrows(CancellationException::class.java) { run() }
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(7, e.backoffStreak)
    assertEquals(listOf("e1" to host.clock + 64_000), scheduler.wakes) // base 1 s * 2^(7-1)
  }

  @Test
  fun `a timeout stop while it waits for the network is not a failed attempt, and a fresh row runs it at once`() {
    controller.enqueue(parsed())
    host.stop = StopKind.TIME_LIMIT
    host.network = ArrayDeque(listOf(Connectivity.NoInternet))
    host.onSleep = { throw CancellationException("timeout") }
    assertThrows(CancellationException::class.java) { run() }
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(0, e.backoffStreak)
    assertNull(e.nextAttemptAt)
    assertEquals(0, e.attempts)
    assertEquals(emptyList<TransferRequest>(), host.requests)
    assertEquals(listOf("e1" to host.clock), scheduler.wakes)
    assertEquals(listOf(true), scheduler.wakeReplaces)
  }

  @Test
  fun `an idle timeout stop adds no fresh row when WorkManager starts the stopped row again at once`() {
    controller.enqueue(parsed())
    host.stop = StopKind.TIME_LIMIT
    host.restartsBy = host.clock // the stopped row is due now
    host.network = ArrayDeque(listOf(Connectivity.NoInternet))
    host.onSleep = { throw CancellationException("timeout") }
    assertThrows(CancellationException::class.java) { run() }
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertNull(e.nextAttemptAt)
    assertEquals(emptyList<Pair<String, Long>>(), scheduler.wakes)
  }

  @Test
  fun `an idle timeout stop adds a fresh row when WorkManager holds the stopped row back`() {
    controller.enqueue(parsed())
    host.stop = StopKind.TIME_LIMIT
    host.network = ArrayDeque(listOf(Connectivity.NoInternet))
    host.onSleep = {
      host.restartsBy = host.clock + 1 // due a moment after the stop
      throw CancellationException("timeout")
    }
    assertThrows(CancellationException::class.java) { run() }
    assertEquals(listOf("e1" to host.clock), scheduler.wakes)
  }

  @Test
  fun `a timeout stop that cut a request adds no wake when the stopped row starts again at once`() {
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(backoffStreak = 6))
    host.stop = StopKind.TIME_LIMIT
    host.restartsBy = host.clock - 1
    host.handler = { _, _ -> throw CancellationException("timeout") }
    assertThrows(CancellationException::class.java) { run() }
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(7, e.backoffStreak)
    val at = host.clock + 64_000
    assertEquals(at, e.nextAttemptAt)
    assertEquals(emptyList<Pair<String, Long>>(), scheduler.wakes)

    // The stopped row runs again, sees the long wait and makes the wake itself.
    host.restartsBy = null
    run()
    assertEquals(listOf("e1" to at), scheduler.wakes)
  }

  @Test
  fun `a timeout stop while it waits for a transmit slot counts no attempt`() {
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(backoffStreak = 2))
    host.stop = StopKind.TIME_LIMIT
    host.beforeSlot = { throw CancellationException("timeout") }
    assertThrows(CancellationException::class.java) { run() }
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(0, e.attempts)
    assertNull(e.lastRequestId)
    assertEquals(2, e.backoffStreak)
    assertNull(e.nextAttemptAt)
    assertEquals(listOf("e1" to host.clock), scheduler.wakes)
    assertEquals(emptyList<AttemptEvent>(), host.attempts)
  }

  @Test
  fun `the attempt is written ahead inside the slot`() {
    controller.enqueue(parsed())
    var attemptsWhileWaiting = -1
    host.beforeSlot = { attemptsWhileWaiting = store.load("e1")!!.attempts }
    respond(200)
    run()
    assertEquals(0, attemptsWhileWaiting)
    assertEquals(1, store.load("e1")!!.attempts)
  }

  @Test
  fun `a timeout stop during a short backoff keeps the failure's step and wakes at the due time`() {
    controller.enqueue(parsed())
    respond(503)
    host.stop = StopKind.TIME_LIMIT
    val start = host.clock
    // The stop lands 600 ms into the 1 s wait after the 503.
    host.onSleep = {
      host.clock -= 400
      throw CancellationException("timeout")
    }
    assertThrows(CancellationException::class.java) { run() }
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(1, e.backoffStreak) // the 503's step, as if no stop had landed
    assertEquals(1, e.attempts)
    assertEquals(start + 1_000, e.nextAttemptAt)
    assertEquals(listOf("e1" to start + 1_000), scheduler.wakes)
  }

  @Test
  fun `a stop after a short backoff ran out clears the past nextAttemptAt`() {
    controller.enqueue(parsed())
    respond(503)
    host.onSleep = { throw CancellationException("preempted") } // after the whole 1 s wait
    assertThrows(CancellationException::class.java) { run() }
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertNull(e.nextAttemptAt)
    assertFalse(events.rows.last().toMap().containsKey("nextAttemptAt"))
    assertEquals(1, e.backoffStreak)
    assertEquals(emptyList<Pair<String, Long>>(), scheduler.wakes) // not a time-limit stop
  }

  @Test
  fun `a quota stop goes back to queued with no backoff step and no wake, and writes a counted warning`() {
    val file = File(tmp.newFolder(), "diagnostics.json")
    Diag.log = DiagnosticLog(file)
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(backoffStreak = 2))
    host.stop = StopKind.QUOTA
    host.handler = { _, _ -> throw CancellationException("quota") } // even when it cut a request
    assertThrows(CancellationException::class.java) { run() }
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(2, e.backoffStreak)
    assertNull(e.nextAttemptAt)
    assertEquals(emptyList<Pair<String, Long>>(), scheduler.wakes)
    val warning = DiagnosticLog(file).drain().single()
    assertEquals(DiagnosticLog.WARN, warning.level)
    assertEquals("run stopped by the system: QUOTA", warning.message)
    assertEquals(mapOf("entryId" to "e1", "count" to 1.0), warning.data)
  }

  @Test
  fun `50 stops keep one counted record per kind, and an older error survives`() {
    val file = File(tmp.newFolder(), "diagnostics.json")
    Diag.log = DiagnosticLog(file)
    Diag.error("an older error")
    host.handler = { _, _ -> throw CancellationException("stopped") }
    for (n in 1..50) {
      controller.enqueue(parsed(id = "e$n"))
      host.stop = if (n % 2 == 0) StopKind.QUOTA else StopKind.APP_STANDBY
      assertThrows(CancellationException::class.java) { run("e$n") }
    }
    val records = DiagnosticLog(file).drain()
    assertEquals(listOf("an older error", "run stopped by the system: APP_STANDBY", "run stopped by the system: QUOTA"), records.map { it.message })
    assertEquals(mapOf("entryId" to "e49", "count" to 25.0), records[1].data)
    assertEquals(mapOf("entryId" to "e50", "count" to 25.0), records[2].data)
  }

  @Test
  fun `the stop is saved before the warning is written`() {
    val file = File(tmp.newFolder(), "diagnostics.json")
    Diag.log = DiagnosticLog(file)
    controller.enqueue(parsed())
    var stateAtWarning: EntryState? = null
    Diag.clock = {
      stateAtWarning = store.load("e1")!!.state
      host.clock
    }
    host.stop = StopKind.DEVICE_STATE
    host.handler = { _, _ -> throw CancellationException("doze") }
    assertThrows(CancellationException::class.java) { run() }
    assertEquals(EntryState.QUEUED, stateAtWarning)
  }

  @Test
  fun `a network-constraint stop is never a failure and writes no warning`() {
    val file = File(tmp.newFolder(), "diagnostics.json")
    Diag.log = DiagnosticLog(file)
    controller.enqueue(parsed())
    host.stop = StopKind.NETWORK
    host.handler = { _, _ -> throw CancellationException("network lost") }
    assertThrows(CancellationException::class.java) { run() }
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(0, e.backoffStreak)
    assertNull(e.nextAttemptAt)
    assertEquals(emptyList<Pair<String, Long>>(), scheduler.wakes)
    assertEquals(emptyList<DiagnosticLog.Record>(), DiagnosticLog(file).drain())
  }

  @Test
  fun `a run that finds a long wait makes its wake again, and keeps a wake that exists`() {
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(nextAttemptAt = host.clock + 60_000))
    scheduler.wakes.clear()
    scheduler.wakeReplaces.clear()
    run()
    assertEquals(emptyList<TransferRequest>(), host.requests)
    assertEquals(EntryState.QUEUED, store.load("e1")!!.state)
    assertEquals(listOf("e1" to host.clock + 60_000), scheduler.wakes)
    assertEquals(listOf(false), scheduler.wakeReplaces) // KEEP: a pending wake stays as it is
  }

  @Test
  fun `a wake lost after a timeout release is made again by the stopped row's re-run`() {
    val file = File(tmp.newFolder(), "diagnostics.json")
    Diag.log = DiagnosticLog(file)
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(backoffStreak = 6))
    host.stop = StopKind.TIME_LIMIT
    host.handler = { _, _ -> throw CancellationException("timeout") }
    scheduler.wakeFailures = 1
    assertThrows(CancellationException::class.java) { run() }
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(7, e.backoffStreak)
    val at = host.clock + 64_000
    assertEquals(at, e.nextAttemptAt)
    assertEquals(emptyList<Pair<String, Long>>(), scheduler.wakes)
    val error = DiagnosticLog(file).drain().single()
    assertEquals(DiagnosticLog.ERROR, error.level)
    assertEquals("released 'e1' after a timeout stop, but could not schedule its wake", error.message)

    // WorkManager runs the stopped row again. It sees the long wait and makes the wake.
    run()
    assertEquals(1, host.requests.size)
    assertEquals(listOf("e1" to at), scheduler.wakes)
    assertEquals(listOf(false), scheduler.wakeReplaces)
  }

  @Test
  fun `a wake that fails after an idle timeout stop leaves the entry queued and writes one error`() {
    val file = File(tmp.newFolder(), "diagnostics.json")
    Diag.log = DiagnosticLog(file)
    controller.enqueue(parsed())
    host.stop = StopKind.TIME_LIMIT
    host.network = ArrayDeque(listOf(Connectivity.NoInternet))
    host.onSleep = { throw CancellationException("timeout") }
    scheduler.wakeFailures = 1
    val thrown = assertThrows(CancellationException::class.java) { run() }
    assertEquals("timeout", thrown.message) // the stop ends as a stop, not with the enqueue error
    assertEquals(EntryState.QUEUED, store.load("e1")!!.state)
    val error = DiagnosticLog(file).drain().single()
    assertEquals(DiagnosticLog.ERROR, error.level)
    assertEquals("could not schedule a wake for 'e1' after a stop", error.message)
  }

  @Test
  fun `a store write that fails mid-run throws with no outcome`() {
    controller.enqueue(parsed())
    respond(503, 200)
    val dir = store.entryDir("e1")
    host.onSleep = { dir.setWritable(false) } // the next attempt's write-ahead fails
    try {
      assertThrows(IOException::class.java) { run() }
    } finally {
      dir.setWritable(true)
    }
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.unacknowledged())
    assertEquals(1, host.requests.size)
  }

  @Test
  fun `an unexpected error settles error unknown`() {
    controller.enqueue(parsed())
    host.foregroundError = IllegalStateException("boom")
    run()
    assertEquals("unknown", outcome().errorKind)
    assertEquals("boom", outcome().message)
    assertEquals(listOf("end:false"), host.progress)
  }

  @Test
  fun `a re-run after a lost settle write applies the record and sends nothing`() {
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(state = EntryState.RUNNING))
    journal.append(record("00000000-0000-0000-0000-000000000041"))
    run()
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)
    assertEquals(emptyList<TransferRequest>(), host.requests)
  }

  @Test
  fun `a short remaining backoff is slept out before the run, a long one is left to the wake`() {
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(nextAttemptAt = host.clock + 5_000))
    run()
    assertEquals(listOf(5_000L), host.sleeps)
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)

    controller.enqueue(parsed(id = "later"))
    store.save(store.load("later")!!.copy(nextAttemptAt = host.clock + 60_000))
    run("later")
    assertEquals(EntryState.QUEUED, store.load("later")!!.state)
    assertEquals(1, host.requests.size)
  }

  @Test
  fun `no usable network polls until it returns`() {
    controller.enqueue(parsed())
    host.network = ArrayDeque(listOf(Connectivity.NoWifi, Connectivity.NoInternet))
    run()
    assertEquals(listOf(EntryRun.CONNECTIVITY_POLL_MS, EntryRun.CONNECTIVITY_POLL_MS), host.sleeps)
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)
  }

  // MARK: - transient rules

  private val bodyStopped = UploadOutcome.TransientRule(400, "Bad Request", maxRetries = 2)

  /** Answers [codes] in order; a 400 carries the server's body-stopped shape. */
  private fun respondShaped(vararg codes: Int) {
    val queue = ArrayDeque(codes.toList())
    host.handler = { _, onProgress ->
      onProgress(7)
      val code = queue.removeFirst()
      UploadResponse(code, if (code == 400) """{"status":400,"error":"Bad Request"}""" else "body", mapOf())
    }
  }

  @Test
  fun `a transient rule retries a matching 400 and counts the hit on the entry`() {
    controller.configureRetry(RetryDefaults(baseMs = 1_000, jitter = 0.0, transient = listOf(bodyStopped)))
    controller.enqueue(parsed())
    respondShaped(400, 400, 200)
    run()
    val e = store.load("e1")!!
    assertEquals(EntryState.COMPLETED, e.state)
    assertEquals(3, e.attempts)
    assertEquals(mapOf("400|Bad Request" to 2), e.transientHitsByRule)
    assertEquals(listOf(1_000L, 2_000L), host.sleeps)
    assertEquals(listOf("error", "error", "completed"), host.attempts.map { it.outcome })
  }

  @Test
  fun `the response after maxRetries settles error http with the response`() {
    controller.configureRetry(RetryDefaults(baseMs = 1_000, jitter = 0.0, transient = listOf(bodyStopped)))
    controller.enqueue(parsed())
    respondShaped(400, 400, 400)
    run()
    val r = outcome()
    assertEquals(EventJournal.KIND_ERROR, r.kind)
    assertEquals("http", r.errorKind)
    assertEquals(400, r.response!!.status)
    assertEquals(3, store.load("e1")!!.attempts)
    assertEquals(mapOf("400|Bad Request" to 2), store.load("e1")!!.transientHitsByRule)
  }

  @Test
  fun `a 400 whose body does not match the rule is terminal at once`() {
    controller.configureRetry(RetryDefaults(baseMs = 1_000, jitter = 0.0, transient = listOf(bodyStopped)))
    controller.enqueue(parsed())
    respond(400) // body "body"
    run()
    assertEquals("http", outcome().errorKind)
    assertEquals(1, store.load("e1")!!.attempts)
    assertNull(store.load("e1")!!.transientHitsByRule)
  }

  @Test
  fun `exempt alone retries without counting`() {
    controller.enqueue(parsed()) // default exempt [404]
    respond(404, 200)
    run()
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)
    assertNull(store.load("e1")!!.transientHitsByRule)
  }

  @Test
  fun `a matched rule past its cap settles even when exempt lists the status`() {
    controller.configureRetry(RetryDefaults(baseMs = 1_000, jitter = 0.0, transient = listOf(UploadOutcome.TransientRule(404, maxRetries = 1))))
    controller.enqueue(parsed())
    respond(404, 404)
    run()
    assertEquals("http", outcome().errorKind)
    assertEquals(404, outcome().response!!.status)
    assertEquals(mapOf("404" to 1), store.load("e1")!!.transientHitsByRule)
  }

  @Test
  fun `hits persist across runs and the descriptor's own rules override the defaults`() {
    controller.configureRetry(RetryDefaults(baseMs = 60_000, jitter = 0.0))
    val own = RetryOverride(null, null, null, null, transient = listOf(bodyStopped))
    controller.enqueue(parsed(descriptor = desc(dataJson = """{"a":1}""", retry = own)))
    respondShaped(400, 400, 400)
    run()
    assertEquals(mapOf("400|Bad Request" to 1), store.load("e1")!!.transientHitsByRule) // released to a 60 s wake
    assertEquals(EntryState.QUEUED, store.load("e1")!!.state)
    host.clock += 60_000
    run()
    assertEquals(mapOf("400|Bad Request" to 2), store.load("e1")!!.transientHitsByRule)
    host.clock += 120_000
    run()
    assertEquals("http", outcome().errorKind)
    assertEquals(3, store.load("e1")!!.attempts)
  }

  /** A new process: a fresh store, settings, ops and controller over the same directories. */
  private fun restart() {
    store = QueueStore(root, RequestIndex())
    settings = QueueSettingsStore(File(root, "settings.json"))
    ops = WorkerOps(store, journal, settings, events, scheduler) { host.clock }
    controller = QueueController(store, journal, settings, events, scheduler, { false }, { host.clock })
  }

  private val notFound48 = UploadOutcome.TransientRule(404, maxRetries = 48)

  @Test
  fun `an app update that puts a rule in front keeps each rule's count`() {
    controller.configureRetry(RetryDefaults(baseMs = 1_000, jitter = 0.0, transient = listOf(notFound48)))
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(transientHitsByRule = mapOf("404" to 40)))
    controller.configureRetry(RetryDefaults(baseMs = 1_000, jitter = 0.0, transient = listOf(bodyStopped.copy(maxRetries = 3), notFound48)))
    respondShaped(400, 404, 200)
    run()
    val e = store.load("e1")!!
    assertEquals(EntryState.COMPLETED, e.state)
    assertEquals(mapOf("404" to 41, "400|Bad Request" to 1), e.transientHitsByRule)
  }

  @Test
  fun `a live same-id mutate that reorders its rules keeps each rule's count`() {
    val before = RetryOverride(null, null, null, null, transient = listOf(notFound48))
    controller.enqueue(parsed(descriptor = desc(dataJson = """{"a":1}""", retry = before)))
    store.save(store.load("e1")!!.copy(transientHitsByRule = mapOf("404" to 40)))
    val after = RetryOverride(null, null, null, null, transient = listOf(bodyStopped.copy(maxRetries = 3), notFound48))
    controller.enqueue(parsed(descriptor = desc(dataJson = """{"a":1}""", retry = after), expiresAt = FAR_FUTURE + 1))
    assertEquals(mapOf("404" to 40), store.load("e1")!!.transientHitsByRule)
    respondShaped(400, 400, 200)
    run()
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)
    assertEquals(mapOf("404" to 40, "400|Bad Request" to 2), store.load("e1")!!.transientHitsByRule)
  }

  @Test
  fun `a raised cap lets a capped count retry again, a lowered one settles it`() {
    controller.configureRetry(RetryDefaults(baseMs = 1_000, jitter = 0.0, transient = listOf(UploadOutcome.TransientRule(404, maxRetries = 5))))
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(transientHitsByRule = mapOf("404" to 2)))
    respond(404, 200)
    run()
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)
    assertEquals(mapOf("404" to 3), store.load("e1")!!.transientHitsByRule)

    controller.enqueue(parsed(id = "e2"))
    store.save(store.load("e2")!!.copy(transientHitsByRule = mapOf("404" to 3)))
    controller.configureRetry(RetryDefaults(baseMs = 1_000, jitter = 0.0, transient = listOf(UploadOutcome.TransientRule(404, maxRetries = 2))))
    respond(404)
    run("e2")
    val r = journal.unacknowledged().single { it.id == "e2" }
    assertEquals("http", r.errorKind)
    assertEquals(404, r.response!!.status)
  }

  @Test
  fun `a simple entry's count survives a process restart`() {
    controller.configureRetry(RetryDefaults(baseMs = 60_000, jitter = 0.0, transient = listOf(bodyStopped)))
    controller.enqueue(parsed())
    respondShaped(400, 400, 400)
    run()
    restart()
    assertEquals(mapOf("400|Bad Request" to 1), store.load("e1")!!.transientHitsByRule)
    host.clock += 60_000
    run()
    restart()
    assertEquals(mapOf("400|Bad Request" to 2), store.load("e1")!!.transientHitsByRule)
    host.clock += 120_000
    run()
    assertEquals("http", outcome().errorKind)
  }

  // MARK: - chunked

  private fun chunked(size: Int = 30) {
    val src = File(tmp.newFolder(), "video.bin").apply { writeBytes(ByteArray(size) { it.toByte() }) }
    controller.enqueue(parsed(descriptor = desc(url = null, method = "PUT", file = src.path,
      parts = listOf(part(0, 10), part(10, 20), part(20, 30)))))
  }

  @Test
  fun `every part accepted settles completed with no status, one attempt per part`() {
    chunked()
    run()
    val e = store.load("e1")!!
    assertEquals(EntryState.COMPLETED, e.state)
    assertEquals(3, e.attempts)
    assertTrue(e.descriptor!!.parts!!.all { it.accepted })
    assertNull(outcome().response!!.status)
    val byUrl = host.requests.associateBy { it.url }
    assertEquals("20-29", byUrl["https://example.com/part?start=20"]!!.headers["Content-Range"])
    assertEquals(10L, byUrl["https://example.com/part?start=20"]!!.body!!.contentLength())
    assertEquals(listOf(0, 1, 2), host.attempts.map { it.partIndex }.sortedBy { it })
  }

  @Test
  fun `a part that fails terminally settles error with its index, and the other parts stop`() {
    chunked()
    host.handler = { r, _ ->
      UploadResponse(if (r.url.endsWith("start=10")) 400 else 200, "", mapOf())
    }
    run()
    val r = outcome()
    assertEquals("http", r.errorKind)
    assertEquals(1, r.partIndex)
    assertEquals("HTTP 400 on part 1", r.message)
    assertEquals("https://example.com/part?start=10", r.url)
    val accepted = store.load("e1")!!.descriptor!!.parts!!.map { it.accepted }
    assertFalse(accepted[1])
  }

  @Test
  fun `a part 503 backs off in that part while the others go on`() {
    chunked()
    var failed = false
    host.handler = { r, _ ->
      if (r.url.endsWith("start=0") && !failed) {
        failed = true
        UploadResponse(503, "", mapOf())
      } else UploadResponse(200, "", mapOf())
    }
    run()
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)
    assertEquals(4, host.requests.size)
    assertEquals(listOf(1_000L), host.sleeps)
  }

  @Test
  fun `a chunked entry stored at a long streak completes after 200, 503, 200 with no wake`() {
    val src = File(tmp.newFolder(), "video.bin").apply { writeBytes(ByteArray(20) { it.toByte() }) }
    controller.enqueue(parsed(descriptor = desc(url = null, method = "PUT", file = src.path,
      parts = listOf(part(0, 10), part(10, 20)))))
    store.save(store.load("e1")!!.copy(backoffStreak = 9))
    respond(200, 503, 200)
    run()
    val e = store.load("e1")!!
    assertEquals(EntryState.COMPLETED, e.state)
    assertEquals(3, host.requests.size)
    // The 503 came after a part was accepted, so it waits 1 s in place, not 2^9 s.
    assertEquals(listOf(1_000L), host.sleeps)
    assertEquals(emptyList<Pair<String, Long>>(), scheduler.wakes)
  }

  @Test
  fun `a chunked part keeps the stored streak until a part is accepted in this run`() {
    chunked()
    store.save(store.load("e1")!!.copy(backoffStreak = 9))
    respond(503)
    run()
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(10, e.backoffStreak)
    assertEquals(listOf("e1" to host.clock + 512_000), scheduler.wakes) // base 1 s * 2^(10-1)
  }

  @Test
  fun `the part whose failures set the streak goes on from it, the others start from 0`() {
    chunked()
    // Part 1 (start 10) set streak 9 in an earlier run.
    store.save(store.load("e1")!!.copy(backoffStreak = 9, backoffPartStart = 10))
    host.handler = { r, _ ->
      UploadResponse(if (r.url.endsWith("start=10")) 503 else 200, "", mapOf())
    }
    run()
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    // Part 1's next failure is step 10: 2^9 s. The accepted siblings did not reset it.
    assertEquals(10, e.backoffStreak)
    assertEquals(10L, e.backoffPartStart)
    assertEquals(listOf("e1" to host.clock + 512_000), scheduler.wakes)
    assertTrue(e.descriptor!!.parts!![0].accepted)
    assertFalse(e.descriptor!!.parts!![1].accepted)
  }

  @Test
  fun `a part other than the one that set the streak counts its own failures from 0`() {
    chunked()
    store.save(store.load("e1")!!.copy(backoffStreak = 9, backoffPartStart = 10))
    var failed = false
    host.handler = { r, _ ->
      if (r.url.endsWith("start=0") && !failed) {
        failed = true
        UploadResponse(503, "", mapOf())
      } else UploadResponse(200, "", mapOf())
    }
    run()
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)
    assertEquals(listOf(1_000L), host.sleeps) // step 1, not step 10
  }

  @Test
  fun `a part that always fails backs off further in each run while its siblings are accepted`() {
    // Probe P6: before, part 0 started from step 1 in every run: 48
    // attempts in 8 runs, a 32 s wait every time.
    val n = 40
    val src = File(tmp.newFolder(), "video.bin").apply { writeBytes(ByteArray(n * 10) { it.toByte() }) }
    controller.enqueue(
      parsed(descriptor = desc(url = null, method = "PUT", file = src.path, parts = (0 until n).map { part(it * 10L, it * 10L + 10) })),
    )
    var part0 = 0
    var acceptedThisRun = 0
    host.handler = { r, _ ->
      val index = r.url.substringAfter("start=").toInt() / 10
      if (index == 0) {
        kotlinx.coroutines.yield()
        part0++
        UploadResponse(503, "", mapOf())
      } else if (acceptedThisRun++ == 0) {
        UploadResponse(200, "", mapOf())
      } else {
        awaitCancellation()
      }
    }
    val streaks = mutableListOf<Int>()
    repeat(8) {
      acceptedThisRun = 0
      run()
      val e = store.load("e1")!!
      streaks += e.backoffStreak
      host.clock = e.nextAttemptAt ?: host.clock
    }
    // Six attempts in the first run (1, 2, 4, 8, 16 s in place, then 32 s),
    // then one per run, each wait twice the one before.
    assertEquals(6 + 7, part0)
    assertEquals((6..13).toList(), streaks)
  }

  /** Four 10-byte parts. [code] answers each request by its part index and how many times that part was sent. */
  private fun fourParts(code: (part: Int, sent: Int) -> Int) {
    val src = File(tmp.newFolder(), "video.bin").apply { writeBytes(ByteArray(40) { it.toByte() }) }
    controller.enqueue(parsed(descriptor = desc(url = null, method = "PUT", file = src.path,
      parts = listOf(part(0, 10), part(10, 20), part(20, 30), part(30, 40)))))
    val sends = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    host.handler = { r, _ ->
      val index = r.url.substringAfter("start=").toInt() / 10
      UploadResponse(code(index, sends.merge(index, 1, Int::plus)!!), "", mapOf())
    }
  }

  @Test
  fun `each part of a chunked entry gets the rule's full maxRetries`() {
    controller.configureRetry(RetryDefaults(baseMs = 1_000, jitter = 0.0, transient = listOf(UploadOutcome.TransientRule(400, maxRetries = 3))))
    // Every part answers 400 three times, then 200. One shared count would settle at the fourth 400.
    fourParts { _, sent -> if (sent <= 3) 400 else 200 }
    run()
    val e = store.load("e1")!!
    assertEquals(EntryState.COMPLETED, e.state)
    assertEquals(12, host.attempts.count { it.httpCode == 400 })
    assertEquals(List(4) { mapOf("400" to 3) }, e.descriptor!!.parts!!.map { it.transientHitsByRule })
    assertNull(e.transientHitsByRule) // a chunked entry counts on its parts
  }

  @Test
  fun `one part past the cap settles the entry error http with that part's response`() {
    controller.configureRetry(RetryDefaults(baseMs = 1_000, jitter = 0.0, transient = listOf(UploadOutcome.TransientRule(400, maxRetries = 3))))
    fourParts { part, sent -> if (part == 2 || sent <= 3) 400 else 200 }
    run()
    val r = outcome()
    assertEquals(EventJournal.KIND_ERROR, r.kind)
    assertEquals("http", r.errorKind)
    assertEquals(2, r.partIndex)
    assertEquals(400, r.response!!.status)
    assertEquals("HTTP 400 on part 2", r.message)
    assertEquals(4, host.attempts.count { it.partIndex == 2 }) // three excused, the fourth settles
    assertEquals(mapOf("400" to 3), store.load("e1")!!.descriptor!!.parts!![2].transientHitsByRule)
  }

  @Test
  fun `a part's count survives a process restart`() {
    controller.configureRetry(RetryDefaults(baseMs = 60_000, jitter = 0.0, transient = listOf(UploadOutcome.TransientRule(400, maxRetries = 2))))
    fourParts { part, sent -> if (part == 1 && sent <= 2) 400 else 200 }
    run() // part 1's first 400 releases the run to a 60 s wake
    assertEquals(mapOf("400" to 1), store.load("e1")!!.descriptor!!.parts!![1].transientHitsByRule)
    restart()
    host.clock += 60_000
    run()
    assertEquals(mapOf("400" to 2), store.load("e1")!!.descriptor!!.parts!![1].transientHitsByRule)
    restart()
    host.clock += 120_000
    run()
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)
  }

  @Test
  fun `a part cut by a sibling's park does not make a later idle stop a failure`() {
    chunked()
    lateinit var job: Job
    host.stop = StopKind.TIME_LIMIT
    host.handler = { r, _ ->
      if (r.url.endsWith("start=0")) {
        try {
          awaitCancellation() // in flight when part 1 parks the entry
        } finally {
          // New headers land before the park, so the run goes on for a second pass.
          controller.updateHeaders(mapOf("Authorization" to "Bearer new"))
          // In that pass the network is gone and the system stops the run at its time limit.
          host.network = ArrayDeque(listOf(Connectivity.NoInternet))
          host.onSleep = { job.cancel(CancellationException("timeout")) }
        }
      } else UploadResponse(401, "", mapOf())
    }
    runBlocking {
      job = launch { EntryRun("e1", store, ops, host).run() }
      job.join()
    }
    assertEquals(listOf(EntryRun.CONNECTIVITY_POLL_MS), host.sleeps) // the second pass waited for the network
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(0, e.backoffStreak)
    assertNull(e.nextAttemptAt)
    assertEquals(listOf("e1" to host.clock), scheduler.wakes) // a fresh row, at once
  }

  @Test
  fun `a chunked part cut by a timeout stop takes one backoff step and keeps the accepted parts`() {
    chunked()
    store.save(store.load("e1")!!.copy(backoffStreak = 2))
    lateinit var job: Job
    var cutRequestId: String? = null
    host.stop = StopKind.TIME_LIMIT
    host.handler = { r, _ ->
      if (r.url.endsWith("start=20")) {
        cutRequestId = r.headers["X-Request-Id"]
        job.cancel(CancellationException("timeout")) // the stop lands while part 2 is on its way
        awaitCancellation()
      } else UploadResponse(200, "", mapOf())
    }
    runBlocking {
      job = launch { EntryRun("e1", store, ops, host).run() }
      job.join()
    }
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(listOf(true, true, false), e.descriptor!!.parts!!.map { it.accepted })
    // markAccepted reset the streak to 0, so the cut part's step makes it 1.
    assertEquals(1, e.backoffStreak)
    assertEquals(3, e.attempts)
    assertEquals(cutRequestId, e.lastRequestId)
    assertEquals(listOf("e1" to host.clock + 1_000), scheduler.wakes)
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.unacknowledged())
  }

  @Test
  fun `a part that ends with a cancel of its own does not let the entry complete`() {
    chunked()
    host.handler = { r, _ ->
      // Not the run's cancel: only this part's coroutine ends.
      if (r.url.endsWith("start=10")) throw CancellationException("part only")
      UploadResponse(200, "", mapOf())
    }
    assertThrows(EntryRun.PartsNotAcceptedException::class.java) { run() }
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(listOf(true, false, true), e.descriptor!!.parts!!.map { it.accepted })
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.unacknowledged())
  }

  @Test
  fun `the entry keeps the highest backoff step of its concurrent parts`() {
    chunked()
    lateinit var job: Job
    var sleeps = 0
    var part0Calls = 0
    host.stop = StopKind.TIME_LIMIT
    host.handler = { r, _ ->
      when {
        // Part 0: two 503s (streak 2), then its third request is on its way at the stop.
        r.url.endsWith("start=0") -> if (++part0Calls <= 2) UploadResponse(503, "", mapOf()) else awaitCancellation()
        // Part 1: one 503 (streak 1), saved after part 0's streak 2.
        r.url.endsWith("start=10") -> UploadResponse(503, "", mapOf())
        else -> UploadResponse(200, "", mapOf())
      }
    }
    // The stop lands in part 1's wait, the third wait of the run.
    host.onSleep = { if (++sleeps == 3) job.cancel(CancellationException("timeout")) }
    runBlocking {
      job = launch { EntryRun("e1", store, ops, host).run() }
      job.join()
    }
    assertEquals(listOf(1_000L, 2_000L, 1_000L), host.sleeps)
    // Part 0's request was cut, so one step on top of the highest saved streak: 2 + 1.
    assertEquals(3, store.load("e1")!!.backoffStreak)
  }

  @Test
  fun `a part 401 parks the whole entry and keeps the accepted parts`() {
    chunked()
    host.handler = { r, _ ->
      UploadResponse(if (r.url.endsWith("start=20")) 401 else 200, "", mapOf())
    }
    run()
    val e = store.load("e1")!!
    assertEquals(EntryState.AWAITING_AUTH, e.state)
    assertEquals(listOf(true, true, false), e.descriptor!!.parts!!.map { it.accepted })
  }

  @Test
  fun `a failed chunked settle keeps the accepted bytes, not the in-flight ones`() {
    chunked()
    host.handler = { r, onProgress ->
      onProgress(5)
      UploadResponse(if (r.url.endsWith("start=20")) 400 else 200, "", mapOf())
    }
    run()
    assertEquals(20, outcome().bytesSent)
  }
}
