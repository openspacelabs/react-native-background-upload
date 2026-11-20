package ai.openspace.backgroundupload

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
  var timeout = false
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

  override fun stoppedByTimeout() = timeout
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
    respond(200)
    run()
    assertTrue(host.requests.single().highPriority)
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

  /** The stored entry as an earlier version saved it, with [expiresAt]. */
  private fun withExpiresAt(expiresAt: Long, id: String = "e1") =
    store.save(store.load(id)!!.copy(expiresAt = expiresAt))

  @Test
  fun `a 401 parks the entry with no WorkManager row, also with expiresAt near`() {
    controller.enqueue(parsed())
    withExpiresAt(host.clock + 80_000)
    val scheduledBefore = scheduler.scheduled.toList()
    respond(401)
    run()
    assertEquals(EntryState.AWAITING_AUTH, store.load("e1")!!.state)
    assertEquals(emptyList<Pair<String, Long>>(), scheduler.wakes)
    assertEquals(scheduledBefore, scheduler.scheduled)
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
  fun `a wake that finds a parked entry past expiresAt leaves it parked, then new headers send it`() {
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(state = EntryState.AWAITING_AUTH, parkedGeneration = 0, bytesSent = 3, expiresAt = 20_000))
    host.clock = 30_000
    run() // a wake an earlier version left
    val e = store.load("e1")!!
    assertEquals(EntryState.AWAITING_AUTH, e.state)
    assertEquals(3, e.bytesSent)
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.unacknowledged())
    assertEquals(emptyList<TransferRequest>(), host.requests)
    assertEquals(emptyList<Pair<String, Long>>(), scheduler.wakes)
    controller.updateHeaders(mapOf("Authorization" to "Bearer new"))
    run()
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)
    assertEquals("Bearer new", host.requests.single().headers["Authorization"])
  }

  @Test
  fun `an entry with expiresAt in the past is sent`() {
    controller.enqueue(parsed())
    withExpiresAt(5_000) // host.clock is 10 000
    run()
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)
    assertEquals(1, host.requests.size)
    assertEquals(EventJournal.KIND_COMPLETED, outcome().kind)
  }

  @Test
  fun `a network wait that passes expiresAt keeps polling, then sends`() {
    controller.enqueue(parsed())
    withExpiresAt(host.clock + 5_000)
    host.network = ArrayDeque(listOf(Connectivity.NoInternet, Connectivity.NoInternet))
    run()
    assertEquals(listOf(EntryRun.CONNECTIVITY_POLL_MS, EntryRun.CONNECTIVITY_POLL_MS), host.sleeps)
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)
  }

  @Test
  fun `a transient failure past expiresAt retries on the normal backoff`() {
    controller.enqueue(parsed())
    withExpiresAt(host.clock + 500)
    respond(503, 503, 200)
    run()
    // base 1 s, then 2 s: the full waits, and no settle at expiresAt.
    assertEquals(listOf(1_000L, 2_000L), host.sleeps)
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)
    assertEquals(3, host.requests.size)
  }

  @Test
  fun `a long backoff past expiresAt releases to the full wait`() {
    controller.configureRetry(RetryDefaults(baseMs = 60_000, jitter = 0.0))
    controller.enqueue(parsed())
    withExpiresAt(host.clock + 500)
    respond(503)
    run()
    assertEquals(host.clock + 60_000, store.load("e1")!!.nextAttemptAt)
    assertEquals(listOf("e1" to host.clock + 60_000), scheduler.wakes)
  }

  @Test
  fun `a timeout stop past expiresAt releases to the full next backoff step`() {
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(backoffStreak = 2, expiresAt = host.clock + 500))
    host.timeout = true
    host.handler = { _, _ -> throw CancellationException("timeout") }
    assertThrows(CancellationException::class.java) { run() }
    assertEquals(host.clock + 4_000, store.load("e1")!!.nextAttemptAt)
    assertEquals(listOf("e1" to host.clock + 4_000), scheduler.wakes)
  }

  @Test
  fun `a paused entry past expiresAt stays paused, and is sent at resume`() {
    controller.enqueue(parsed())
    withExpiresAt(20_000)
    controller.pause()
    host.clock = 30_000
    run() // a wake that fires during the pause
    assertEquals(EntryState.PAUSED, store.load("e1")!!.state)
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.unacknowledged())
    controller.resume()
    assertEquals(EntryState.QUEUED, store.load("e1")!!.state)
    run()
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)
    assertEquals(1, host.requests.size)
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

  /**
   * Runs e1 in its own job. [stopOn] decides, per request, whether the send
   * stops that job (as WorkManager stops a worker) and then still returns
   * its response: what awaitLanded does when a response landed before the stop.
   */
  private fun runStoppedAt(code: Int = 200, stopOn: (TransferRequest) -> Boolean) = runBlocking {
    var job: Job? = null
    host.handler = { r, _ ->
      if (stopOn(r)) job!!.cancel()
      UploadResponse(code, "ok", mapOf())
    }
    job = launch { EntryRun("e1", store, ops, host).run() }
    job.join()
  }

  @Test
  fun `a response that lands as the run is stopped still settles completed`() {
    // Pins that nothing suspends between the send's return and ops.settle.
    // A suspension there throws on a stopped job, and the entry would go
    // back to queued and be sent again.
    controller.enqueue(parsed())
    runStoppedAt { true }
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)
    assertEquals(EventJournal.KIND_COMPLETED, outcome().kind)
    assertEquals(listOf("completed"), host.attempts.map { it.outcome })
  }

  @Test
  fun `a 500 that lands at a stop is counted once and the entry goes back to queued`() {
    // The backoff sleep throws on the stopped job, as a real delay does.
    controller.enqueue(parsed())
    runStoppedAt(code = 500) { true }
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(1, e.attempts)
    assertEquals(1, host.requests.size)
    assertEquals(listOf("error"), host.attempts.map { it.outcome })
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.unacknowledged())
  }

  @Test
  fun `a part that lands as the run is stopped keeps its accepted flag`() {
    // The same pin for a part: nothing suspends between the send's return and markAccepted.
    chunked()
    runStoppedAt { it.url.endsWith("start=0") }
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(listOf(true, false, false), e.descriptor!!.parts!!.map { it.accepted })
    assertEquals(emptyList<EventJournal.SettledRecord>(), journal.unacknowledged())
  }

  @Test
  fun `a timeout stop takes one more backoff step instead of restarting at once`() {
    controller.enqueue(parsed())
    store.save(store.load("e1")!!.copy(backoffStreak = 2))
    host.timeout = true
    host.handler = { _, _ -> throw CancellationException("timeout") }
    assertThrows(CancellationException::class.java) { run() }
    val e = store.load("e1")!!
    assertEquals(EntryState.QUEUED, e.state)
    assertEquals(3, e.backoffStreak)
    assertEquals(host.clock + 4_000, e.nextAttemptAt) // base 1 s * 2^(3-1)
    assertEquals(listOf("e1" to host.clock + 4_000), scheduler.wakes)
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
    controller.enqueue(parsed(descriptor = desc(dataJson = """{"a":1}""", retry = after)))
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
  fun `a chunked entry with expiresAt in the past sends every part`() {
    chunked()
    withExpiresAt(5_000)
    run()
    assertEquals(EntryState.COMPLETED, store.load("e1")!!.state)
    assertEquals(3, host.requests.size)
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
