package ai.openspace.backgroundupload

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo.State.BLOCKED
import androidx.work.WorkInfo.State.CANCELLED
import androidx.work.WorkInfo.State.ENQUEUED
import androidx.work.WorkInfo.State.RUNNING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The library's scheduling rules ([RowScheduler]) against a fake that
 * follows WorkManager 2.12.0 ([FakeWorkManager]). The question each test
 * asks: after the app acts, does the entry have a row WorkManager may start
 * now?
 */
class MainChainTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private val minute = 60_000L
  private val work = FakeWorkManager(now = 100 * 60_000L)
  private val running = mutableSetOf<String>()
  private lateinit var store: QueueStore
  private lateinit var settings: QueueSettingsStore
  private lateinit var controller: QueueController
  private lateinit var scheduler: RowScheduler
  private lateinit var journal: EventJournal
  private val events = RecordingEvents()

  /** WorkManager calls in order, and an enqueue that throws, as a process death before it lands. */
  private inner class LoggedRows : WorkRows by work {
    val log = mutableListOf<String>()
    var enqueueFails = false

    override fun enqueue(name: String, policy: ExistingWorkPolicy, entry: QueueEntry, delayMs: Long) {
      if (enqueueFails) throw IllegalStateException("the process died before the enqueue")
      log += "enqueue:$name:$policy"
      work.enqueue(name, policy, entry, delayMs)
    }

    override fun cancel(name: String) {
      log += "cancel:$name"
      work.cancel(name)
    }
  }

  private val rows = LoggedRows()

  @Before
  fun setUp() {
    val root = tmp.newFolder("queue")
    store = QueueStore(root, RequestIndex())
    journal = EventJournal(tmp.newFolder("journal"), retryLater = { _, _ -> })
    settings = QueueSettingsStore(File(root, "settings.json"))
    scheduler = RowScheduler(rows) { work.now }
    controller = QueueController(store, journal, settings, events, scheduler, { it in running }, { work.now })
  }

  /** A queued entry that waits after a transport failure 10 minutes ago. */
  private fun transportWait(id: String = "e1", streak: Int = 12) = entry(id = id, state = EntryState.QUEUED, nextAttemptAt = work.now + 60 * minute)
    .copy(backoffStreak = streak, waitCause = WaitCause.NO_RESPONSE, failedAt = work.now - 10 * minute)

  /** The rows of [id] WorkManager may start now, main chain and wake. */
  private fun startable(id: String = "e1") = work.startableNow().filter { it.entryId == id }

  /**
   * The L2 case: a main-chain row stopped at the job time limit at its 8th
   * start, and the fresh wake row the stop scheduled. WorkManager holds the
   * stopped row for 64 minutes from its last enqueue time.
   */
  private fun stoppedAtEighthStartWithWake() {
    store.save(entry(state = EntryState.QUEUED))
    work.add("e1", runAttemptCount = 8, lastEnqueueTime = work.now - 10 * minute)
    work.add("e1#wake")
  }

  // MARK: - the fake follows WorkManager 2.12.0

  @Test
  fun `a stopped row waits 30 s doubled per start from its last enqueue, at most 5 hours`() {
    val enqueued = work.now
    fun waitAfter(starts: Int) = work.nextScheduleAt(work.add("x$starts", runAttemptCount = starts, lastEnqueueTime = enqueued)) - enqueued
    assertEquals(30_000L, waitAfter(1))
    assertEquals(64 * minute, waitAfter(8))
    assertEquals(128 * minute, waitAfter(9))
    assertEquals(5 * 60 * minute, waitAfter(20))
    assertEquals(enqueued + 5_000, work.nextScheduleAt(work.add("delayed", initialDelay = 5_000)))
  }

  // MARK: - the app acts, and the entry can start now

  @Test
  fun `the boot sweep leaves a row that can start now when the stopped main row waits`() {
    stoppedAtEighthStartWithWake()
    controller.sweep()
    assertEquals(1, startable().size)
    // One row for the entry, not two: the held-back row is gone.
    assertEquals(listOf("e1"), work.rows.filter { !it.state.isFinished }.map { it.name })
  }

  @Test
  fun `resume leaves a row that can start now when the stopped main row waits`() {
    stoppedAtEighthStartWithWake()
    controller.resume()
    assertEquals(1, startable().size)
  }

  @Test
  fun `a same-id enqueue leaves a row that can start now when the stopped main row waits`() {
    controller.enqueue(parsed())
    work.rows.clear()
    work.add("e1", runAttemptCount = 9, lastEnqueueTime = work.now - minute)
    work.add("e1#wake")
    controller.enqueue(parsed())
    assertEquals(1, startable().size)
  }

  @Test
  fun `a return to the foreground starts an entry whose row waits after a quota stop`() {
    // A quota stop schedules no wake; WorkManager holds the stopped row.
    store.save(entry(state = EntryState.QUEUED))
    work.add("e1", runAttemptCount = 7, lastEnqueueTime = work.now - minute)
    controller.retryNow()
    assertEquals(1, startable().size)
  }

  @Test
  fun `a return to the foreground clears a long backoff and starts the entry now`() {
    store.save(entry(state = EntryState.QUEUED, nextAttemptAt = work.now + 2 * 60 * minute).copy(backoffStreak = 13))
    work.add("e1#wake", initialDelay = 2 * 60 * minute)
    controller.retryNow()
    assertEquals(1, startable().size)
    assertEquals(listOf("e1"), work.rows.filter { !it.state.isFinished }.map { it.name })
  }

  @Test
  fun `the rows blocked behind a held-back row go with it`() {
    store.save(entry(state = EntryState.QUEUED))
    work.add("e1", runAttemptCount = 8, lastEnqueueTime = work.now - minute)
    work.add("e1", state = BLOCKED)
    controller.resume()
    assertEquals(1, startable().size)
    assertEquals(listOf(CANCELLED, CANCELLED, ENQUEUED), work.rows.map { it.state })
  }

  // MARK: - what stays as it is

  @Test
  fun `a stopped row that WorkManager starts now is kept`() {
    store.save(entry(state = EntryState.QUEUED))
    val row = work.add("e1", runAttemptCount = 1, lastEnqueueTime = work.now - 10 * minute)
    controller.resume()
    assertEquals(listOf(row), work.rows)
    assertEquals(listOf(row), startable())
  }

  @Test
  fun `a stopped row due within the margin is kept, one due later is replaced`() {
    store.save(entry(state = EntryState.QUEUED))
    // Started once, so WorkManager holds it 30 s from its last enqueue.
    val soon = work.add("e1", runAttemptCount = 1, lastEnqueueTime = work.now - 30_000 + START_MARGIN_MS)
    controller.resume()
    assertEquals(listOf(soon), work.rows)
    soon.lastEnqueueTime += 1
    controller.resume()
    assertEquals(CANCELLED, soon.state)
    assertEquals(1, startable().size)
  }

  @Test
  fun `a running row is never replaced`() {
    store.save(entry(state = EntryState.QUEUED))
    val row = work.add("e1", state = RUNNING, runAttemptCount = 3)
    controller.resume()
    assertEquals(RUNNING, row.state)
    // A run after the current one, as before.
    assertEquals(listOf(RUNNING, BLOCKED), work.rows.map { it.state })
  }

  @Test
  fun `a held-back main row is replaced while a wake worker of the entry runs`() {
    // Probe P5. The wake's worker released the entry and still holds the
    // gate. Then it schedules its wake at the old time and ends.
    store.save(transportWait())
    val held = work.add("e1", runAttemptCount = 8, lastEnqueueTime = work.now - minute)
    work.add("e1#wake", state = RUNNING, runAttemptCount = 1)
    running += "e1"
    controller.retryNow()
    scheduler.scheduleWake(store.load("e1")!!, work.now + 60 * minute, replace = true)
    running -= "e1"
    assertEquals(CANCELLED, held.state)
    assertNull(store.load("e1")!!.nextAttemptAt)
    assertEquals(listOf("e1"), startable().map { it.name })
  }

  @Test
  fun `startableIds lists the entries with a run in progress or a row due now`() {
    work.add("due")
    work.add("held", runAttemptCount = 8, lastEnqueueTime = work.now - minute)
    work.add("run", state = RUNNING, runAttemptCount = 1)
    work.add("later#wake", initialDelay = minute)
    work.add("blocked", state = BLOCKED)
    work.add("gone", state = CANCELLED)
    assertEquals(setOf("due", "run"), scheduler.startableIds())
    // One query, and it asks only for the states that can start.
    assertEquals(listOf(listOf(ENQUEUED, RUNNING)), work.libraryQueries)
  }

  @Test
  fun `restartBy is the latest time WorkManager starts a stopped row again`() {
    // The worker's runAttemptCount counts the starts before this one.
    val startedAt = work.now
    assertEquals(startedAt + 30_000, WorkManagerBackoff.restartBy(startedAt, 0))
    assertEquals(startedAt + 8 * minute, WorkManagerBackoff.restartBy(startedAt, 4))
    assertEquals(startedAt + 64 * minute, WorkManagerBackoff.restartBy(startedAt, 7))
    assertEquals(startedAt + 5 * 60 * minute, WorkManagerBackoff.restartBy(startedAt, 40))
    // The same rule as the fake, one start later.
    val row = work.add("e1", runAttemptCount = 8, lastEnqueueTime = startedAt)
    assertEquals(work.nextScheduleAt(row), WorkManagerBackoff.restartBy(startedAt, 7))
    assertTrue(WorkManagerBackoff.restartBy(startedAt, 4) < startedAt + 10 * minute)
  }

  // MARK: - order, and cost

  @Test
  fun `the main row is made before the wake is cancelled`() {
    store.save(transportWait())
    work.add("e1#wake", initialDelay = 60 * minute)
    controller.retryNow()
    assertEquals(listOf("enqueue:e1:APPEND_OR_REPLACE", "cancel:e1#wake"), rows.log)
  }

  @Test
  fun `a process death before the main row lands keeps the wake`() {
    // Probe P3: with the wake cancelled first, the entry had no row at all.
    store.save(transportWait())
    work.add("e1#wake", initialDelay = 60 * minute)
    rows.enqueueFails = true
    controller.retryNow()
    assertEquals(listOf("e1#wake"), work.rows.filter { !it.state.isFinished }.map { it.name })
  }

  @Test
  fun `the boot sweep then an immediate return to the foreground clear a wait once`() {
    store.save(transportWait())
    work.add("e1#wake", initialDelay = 60 * minute)
    controller.sweep()
    controller.retryNow()
    assertEquals(listOf("state:e1:queued"), events.log)
    assertEquals(listOf("enqueue:e1:APPEND_OR_REPLACE", "cancel:e1#wake"), rows.log)
    assertEquals(1, startable().size)
  }

  @Test
  fun `retryNow costs no save and no WorkManager query for entries whose wait stays`() {
    for (n in 1..3) {
      store.save(transportWait("r$n").copy(waitCause = WaitCause.RESPONSE))
    }
    controller.retryNow()
    assertEquals(0, work.nameQueries)
    assertEquals(emptyList<List<androidx.work.WorkInfo.State>>(), work.libraryQueries)
    assertEquals(emptyList<String>(), rows.log)
    assertEquals(emptyList<String>(), events.log) // no save changed a row
  }

  @Test
  fun `retryNow costs one save and two name queries per cleared entry, and one query for the idle ones`() {
    for (n in 1..3) store.save(transportWait("t$n"))
    for (n in 1..2) store.save(entry(id = "i$n", state = EntryState.QUEUED))
    work.add("i1")
    controller.retryNow()
    assertEquals(3, events.log.size) // the three saves
    assertEquals(1, work.libraryQueries.size)
    // Cleared: schedule() and cancelWake() read one name each. Idle "i2"
    // has no row that can start, so it is scheduled too; "i1" has one.
    assertEquals(2 * 4, work.nameQueries)
  }
}
