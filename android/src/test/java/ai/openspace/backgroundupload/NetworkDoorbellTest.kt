package ai.openspace.backgroundupload

import androidx.work.WorkInfo.State.BLOCKED
import androidx.work.WorkInfo.State.CANCELLED
import androidx.work.WorkInfo.State.ENQUEUED
import androidx.work.WorkInfo.State.RUNNING
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * An entry with no network sleeps on a shared doorbell row, against a fake
 * that follows WorkManager 2.12.0 ([FakeWorkManager]). The questions: what
 * starts each entry, does any upload run on a constrained row, and how many
 * rows sleep.
 */
class NetworkDoorbellTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private val minute = 60_000L
  private val work = FakeWorkManager(now = 100 * 60_000L)
  private val running = mutableSetOf<String>()
  private lateinit var store: QueueStore
  private lateinit var settings: QueueSettingsStore
  private lateinit var journal: EventJournal
  private lateinit var scheduler: RowScheduler
  private lateinit var controller: QueueController
  private val events = RecordingEvents()
  private var fitsThrows = false
  private var fitsCalls = 0

  @Before
  fun setUp() {
    val root = tmp.newFolder("queue")
    store = QueueStore(root, RequestIndex())
    journal = EventJournal(tmp.newFolder("journal"), retryLater = { _, _ -> })
    settings = QueueSettingsStore(File(root, "settings.json"))
    scheduler = RowScheduler(work) { work.now }
    controller = newController()
  }

  @After
  fun noUploadOnAConstrainedRow() {
    // Every row that runs an upload has no network rule; only a doorbell has one.
    for (r in work.rows) {
      if (r.doorbell == null) assertEquals("row ${r.name}", RequiredNetwork.NONE, r.network)
      else assertEquals("row ${r.name}", r.doorbell, r.network)
    }
  }

  /** A controller as the module or a doorbell worker builds it, with the in-worker check on the fake's network. */
  private fun newController() = QueueController(
    store, journal, settings, events, scheduler, { it in running }, { work.now },
    networkFits = { wifiOnly ->
      fitsCalls++
      if (fitsThrows) throw SecurityException("Package android does not belong to 10123")
      work.network.ordinal >= (if (wifiOnly) RequiredNetwork.WIFI else RequiredNetwork.CONNECTED).ordinal
    },
  )

  private fun ops() = WorkerOps(store, journal, settings, events, scheduler) { work.now }

  private fun network(n: RequiredNetwork) = work.setNetwork(n)

  /** The unfinished rows that run an upload of [id]. */
  private fun uploadRows(id: String = "e1") = work.rows.filter { it.entryId == id && it.doorbell == null && !it.state.isFinished }

  private fun doorbellRows(n: RequiredNetwork? = null) =
    work.rows.filter { it.doorbell != null && (n == null || it.doorbell == n) && !it.state.isFinished }

  /** The rows WorkManager may start now that run an upload of [id]. */
  private fun startable(id: String = "e1") = work.startableNow().filter { it.entryId == id && it.doorbell == null }

  /** The rows that wait and are not running: what holds WorkManager's slots. */
  private fun sleeping() = work.rows.filter { it.state == ENQUEUED && it !in work.startableNow() }

  /**
   * A run of [id] on its main row found no network and handed over. Its row
   * then ends.
   */
  private fun handedOver(id: String = "e1", stored: QueueEntry = entry(id = id, state = EntryState.RUNNING)) {
    store.save(stored)
    val own = work.add(id, state = RUNNING, runAttemptCount = 1)
    running += id
    assertTrue(ops().awaitNetwork(id, stored.generation))
    running -= id
    work.finish(own)
  }

  /** WorkManager starts each doorbell that may start now; its worker answers with its own controller. */
  private fun ringDoorbells(): List<FakeWorkManager.Row> {
    val rung = work.startableNow().filter { it.doorbell != null }
    for (row in rung) {
      work.start(row)
      if (newController().answerDoorbell()) work.finish(row) else work.stop(row)
    }
    return rung
  }

  // MARK: - the fake follows WorkManager 2.12.0

  @Test
  fun `the fake, a doorbell reports its enqueue time, starts only on its network, and is stopped when the network goes`() {
    scheduler.ensureDoorbell(RequiredNetwork.WIFI)
    val bell = doorbellRows().single()
    assertEquals(work.now, work.nextScheduleAt(bell))
    network(RequiredNetwork.CONNECTED)
    assertEquals(emptyList<FakeWorkManager.Row>(), work.startableNow())
    network(RequiredNetwork.WIFI)
    work.start(bell)
    assertEquals(listOf(bell), network(RequiredNetwork.CONNECTED))
    assertEquals(ENQUEUED, bell.state)
    assertEquals(1, bell.runAttemptCount)
  }

  @Test
  fun `the fake, with the app process dead only the 20 oldest ENQUEUED rows can start`() {
    for (n in 1..25) work.add("x$n")
    assertEquals(20, work.startableWhileDead().size)
    assertEquals((1..20).map { "x$it" }, work.startableWhileDead().map { it.name })
  }

  // MARK: - a hand-over, and what starts the entry

  @Test
  fun `after a hand-over the entry has no row of its own, and the network doorbell starts it when the network returns`() {
    network(RequiredNetwork.NONE)
    handedOver()
    assertEquals(emptyList<FakeWorkManager.Row>(), uploadRows())
    assertEquals(1, doorbellRows(RequiredNetwork.CONNECTED).size)
    assertEquals(emptyList<FakeWorkManager.Row>(), ringDoorbells()) // offline: it does not ring
    network(RequiredNetwork.CONNECTED)
    assertEquals(1, ringDoorbells().size)
    assertEquals(1, startable().size)
    // The entry has a row now, so the doorbell ends with none behind it.
    assertEquals(emptyList<FakeWorkManager.Row>(), doorbellRows())
  }

  @Test
  fun `a Wi-Fi entry waits on the Wi-Fi doorbell through cellular, and starts on Wi-Fi`() {
    controller.setWifiOnly(true)
    network(RequiredNetwork.NONE)
    handedOver()
    assertEquals(1, doorbellRows(RequiredNetwork.WIFI).size)
    network(RequiredNetwork.CONNECTED)
    assertEquals(emptyList<FakeWorkManager.Row>(), ringDoorbells())
    controller.retryNow()
    assertEquals(emptyList<FakeWorkManager.Row>(), uploadRows())
    network(RequiredNetwork.WIFI)
    ringDoorbells()
    assertEquals(1, startable().size)
  }

  @Test
  fun `the network doorbell on cellular starts the entries that may use cellular and leaves the Wi-Fi ones on the Wi-Fi doorbell`() {
    network(RequiredNetwork.NONE)
    handedOver("cell")
    store.save(entry(id = "wifi", descriptor = desc(dataJson = """{"a":1}""", wifiOnly = true)))
    controller.sweep()
    assertEquals(1, doorbellRows(RequiredNetwork.CONNECTED).size)
    assertEquals(1, doorbellRows(RequiredNetwork.WIFI).size)
    network(RequiredNetwork.CONNECTED)
    ringDoorbells()
    assertEquals(1, startable("cell").size)
    assertEquals(emptyList<FakeWorkManager.Row>(), uploadRows("wifi"))
    assertEquals(1, doorbellRows(RequiredNetwork.WIFI).size)
    assertEquals(emptyList<FakeWorkManager.Row>(), doorbellRows(RequiredNetwork.CONNECTED))
  }

  @Test
  fun `a doorbell whose check finds no network after all gets one queued behind it, which rings later`() {
    network(RequiredNetwork.NONE)
    handedOver()
    network(RequiredNetwork.CONNECTED)
    val bell = doorbellRows().single()
    work.start(bell)
    // WorkManager saw the network; the in-worker check does not (not validated any more).
    fitsThrows = true
    assertTrue(newController().answerDoorbell())
    fitsThrows = false
    work.finish(bell)
    assertEquals(emptyList<FakeWorkManager.Row>(), uploadRows())
    val next = doorbellRows().single()
    assertTrue(next !== bell)
    ringDoorbells()
    assertEquals(1, startable().size)
  }

  @Test
  fun `a doorbell stopped by its own network rule runs again when the network returns`() {
    network(RequiredNetwork.NONE)
    handedOver()
    network(RequiredNetwork.CONNECTED)
    val bell = doorbellRows().single()
    work.start(bell)
    assertEquals(listOf(bell), network(RequiredNetwork.NONE)) // stopped before it answered
    network(RequiredNetwork.CONNECTED)
    work.now += 30_000 // WorkManager's backoff for a row started once
    assertEquals(listOf(bell), ringDoorbells())
    assertEquals(1, startable().size)
  }

  @Test
  fun `a doorbell that WorkManager holds back after many stops is replaced by the next hand-over or repair`() {
    network(RequiredNetwork.NONE)
    handedOver()
    val bell = doorbellRows().single()
    bell.runAttemptCount = 9 // stopped nine times: held for over two hours
    controller.sweep()
    assertEquals(CANCELLED, bell.state)
    val fresh = doorbellRows().single()
    assertEquals(0, fresh.runAttemptCount)
    network(RequiredNetwork.CONNECTED)
    assertEquals(listOf(fresh), ringDoorbells())
  }

  @Test
  fun `a hand-over while the doorbell runs gets one doorbell queued behind it, however many hand over`() {
    network(RequiredNetwork.NONE)
    handedOver("a")
    network(RequiredNetwork.CONNECTED)
    val bell = doorbellRows().single()
    work.start(bell)
    handedOver("b")
    handedOver("c")
    assertEquals(listOf(RUNNING, BLOCKED), doorbellRows().map { it.state })
  }

  @Test
  fun `the doorbell rings with the app process dead and its worker starts the entries`() {
    network(RequiredNetwork.NONE)
    for (n in 1..3) handedOver("e$n")
    network(RequiredNetwork.CONNECTED)
    // Only JobScheduler starts rows now; the doorbell is among the rows it holds.
    val bell = doorbellRows().single()
    assertTrue(bell in work.startableWhileDead())
    work.start(bell)
    assertTrue(newController().answerDoorbell()) // the worker's own controller, no module
    work.finish(bell)
    for (n in 1..3) assertTrue("e$n", startable("e$n").single() in work.startableWhileDead())
  }

  @Test
  fun `250 entries on the Wi-Fi doorbell do not starve an entry that may use cellular`() {
    controller.setWifiOnly(true)
    network(RequiredNetwork.CONNECTED)
    for (n in 1..250) store.save(entry(id = "w$n"))
    controller.sweep()
    // One sleeping row for all 250.
    assertEquals(1, sleeping().size)
    assertEquals(0, work.rows.count { it.doorbell == null })
    controller.enqueue(parsed(id = "cell", descriptor = desc(dataJson = """{"a":1}""", wifiOnly = false)))
    // The app process dies: only the 20 oldest ENQUEUED rows can start.
    assertEquals(1, startable("cell").size)
    assertTrue(startable("cell").single() in work.startableWhileDead())
    assertEquals(1, sleeping().size)
  }

  // MARK: - the doorbell is lost

  @Test
  fun `a doorbell insert that fails makes the run fail, so its own row runs again, and a repair makes the doorbell`() {
    network(RequiredNetwork.NONE)
    store.save(entry(state = EntryState.RUNNING))
    val own = work.add("e1", state = RUNNING, runAttemptCount = 1)
    work.doorbellInsertFailures = 1
    assertThrows(WorkerOps.WakeNotScheduledException::class.java) { ops().awaitNetwork("e1", 1) }
    assertEquals(EntryState.QUEUED, store.load("e1")!!.state)
    assertEquals(emptyList<FakeWorkManager.Row>(), doorbellRows())
    work.stop(own) // the worker returns retry
    // An app start offline makes the doorbell and adds no row.
    controller.sweep()
    assertEquals(1, doorbellRows(RequiredNetwork.CONNECTED).size)
    assertEquals(listOf(own), uploadRows())
  }

  @Test
  fun `a process death between the hand-over save and the doorbell leaves an entry that every repair finds`() {
    // The entry is queued with no row: the run's own row ended, the doorbell never landed.
    network(RequiredNetwork.NONE)
    store.save(entry(state = EntryState.QUEUED))
    controller.retryNow()
    assertEquals(1, doorbellRows(RequiredNetwork.CONNECTED).size)
    assertEquals(emptyList<FakeWorkManager.Row>(), uploadRows())
    // The same with the network up: a foreground starts it.
    work.rows.clear()
    network(RequiredNetwork.CONNECTED)
    controller.retryNow()
    assertEquals(1, startable().size)
    assertEquals(emptyList<FakeWorkManager.Row>(), doorbellRows())
  }

  @Test
  fun `a doorbell insert that fails at a repair gives the entry its main row`() {
    network(RequiredNetwork.NONE)
    store.save(entry(state = EntryState.QUEUED))
    work.doorbellInsertFailures = 1
    controller.retryNow()
    assertEquals(1, uploadRows().size)
    assertEquals(emptyList<FakeWorkManager.Row>(), doorbellRows())
  }

  // MARK: - one row per entry after each repair

  /** e1 was enqueued, ran offline and handed over. */
  private fun waitingOffline() {
    controller.enqueue(parsed())
    work.rows.clear()
    network(RequiredNetwork.NONE)
    handedOver(stored = store.load("e1")!!.copy(state = EntryState.RUNNING))
  }

  @Test
  fun `offline, the boot sweep, resume and a foreground add no row and keep one doorbell`() {
    waitingOffline()
    for (repair in listOf<() -> Unit>({ controller.sweep() }, { controller.resume() }, { controller.retryNow() }, { controller.enqueue(parsed()) })) {
      repair()
      assertEquals(emptyList<FakeWorkManager.Row>(), uploadRows())
      assertEquals(1, doorbellRows().size)
    }
  }

  @Test
  fun `online, the boot sweep adds one row for an entry that waited`() {
    waitingOffline()
    network(RequiredNetwork.CONNECTED)
    controller.sweep()
    controller.sweep()
    assertEquals(1, uploadRows().size)
    assertEquals(1, startable().size)
  }

  @Test
  fun `online, resume adds one row for an entry that waited`() {
    waitingOffline()
    network(RequiredNetwork.CONNECTED)
    controller.resume()
    controller.resume()
    assertEquals(1, uploadRows().size)
    assertEquals(1, startable().size)
  }

  @Test
  fun `online, a foreground adds one row for an entry that waited`() {
    waitingOffline()
    network(RequiredNetwork.CONNECTED)
    controller.retryNow()
    controller.retryNow()
    assertEquals(1, uploadRows().size)
    assertEquals(1, startable().size)
  }

  @Test
  fun `an offline app start starts no run`() {
    network(RequiredNetwork.NONE)
    for (n in 1..5) store.save(entry(id = "q$n"))
    store.save(entry(id = "r", state = EntryState.RUNNING)) // its process died mid-run
    controller.sweep()
    assertEquals(0, work.rows.count { it.doorbell == null })
    assertEquals(1, doorbellRows().size)
  }

  @Test
  fun `a foreground checks the network at most twice, whatever the number of entries`() {
    network(RequiredNetwork.NONE)
    for (n in 1..10) store.save(entry(id = "q$n", descriptor = desc(dataJson = """{"a":1}""", wifiOnly = n % 2 == 0)))
    fitsCalls = 0
    controller.retryNow()
    assertTrue("calls: $fitsCalls", fitsCalls <= 2)
  }

  @Test
  fun `a network check that throws reads as offline at a foreground and does not throw`() {
    waitingOffline()
    network(RequiredNetwork.CONNECTED)
    fitsThrows = true
    controller.retryNow()
    assertEquals(emptyList<FakeWorkManager.Row>(), uploadRows())
    assertEquals(1, doorbellRows().size)
  }

  // MARK: - an entry whose rule changes while it waits

  @Test
  fun `a paused entry is not started by the doorbell, and resume starts it`() {
    waitingOffline()
    controller.pause()
    network(RequiredNetwork.CONNECTED)
    ringDoorbells()
    assertEquals(emptyList<FakeWorkManager.Row>(), uploadRows())
    assertEquals(emptyList<FakeWorkManager.Row>(), doorbellRows())
    controller.resume()
    assertEquals(1, startable().size)
  }

  @Test
  fun `a parked entry is not started by the doorbell, and new headers start it`() {
    waitingOffline()
    store.save(store.load("e1")!!.copy(state = EntryState.AWAITING_AUTH, parkedGeneration = 0))
    network(RequiredNetwork.CONNECTED)
    ringDoorbells()
    assertEquals(emptyList<FakeWorkManager.Row>(), uploadRows())
    controller.updateHeaders(mapOf("Authorization" to "Bearer new"))
    assertEquals(1, startable().size)
  }

  @Test
  fun `an entry that gets a long backoff wait is started by its wake, which has no network rule`() {
    waitingOffline()
    val at = work.now + 60 * minute
    store.save(store.load("e1")!!.copy(nextAttemptAt = at, waitCause = WaitCause.RESPONSE, failedAt = work.now))
    controller.sweep()
    val wake = uploadRows().single()
    assertEquals("e1#wake", wake.name)
    network(RequiredNetwork.CONNECTED)
    ringDoorbells()
    assertEquals(listOf(wake), uploadRows())
    // The wake starts at its time whatever the network; offline its run hands over again.
    work.now = at
    network(RequiredNetwork.NONE)
    assertEquals(listOf(wake), startable())
  }

  @Test
  fun `setWifiOnly off and on moves a waiting entry between the doorbells`() {
    controller.setWifiOnly(true)
    network(RequiredNetwork.NONE)
    handedOver()
    assertEquals(1, doorbellRows(RequiredNetwork.WIFI).size)
    // Off while offline: it now waits for any network.
    controller.setWifiOnly(false)
    assertEquals(1, doorbellRows(RequiredNetwork.CONNECTED).size)
    assertEquals(emptyList<FakeWorkManager.Row>(), uploadRows())
    // On again: the CONNECTED doorbell rings on cellular and hands it back to the Wi-Fi doorbell.
    controller.setWifiOnly(true)
    network(RequiredNetwork.CONNECTED)
    ringDoorbells()
    assertEquals(emptyList<FakeWorkManager.Row>(), uploadRows())
    assertEquals(emptyList<FakeWorkManager.Row>(), doorbellRows(RequiredNetwork.CONNECTED))
    assertEquals(1, doorbellRows(RequiredNetwork.WIFI).size)
    // Off on cellular: it starts now; the Wi-Fi doorbell finds nothing and ends.
    controller.setWifiOnly(false)
    assertEquals(1, startable().size)
    network(RequiredNetwork.WIFI)
    ringDoorbells()
    assertEquals(emptyList<FakeWorkManager.Row>(), doorbellRows())
    assertEquals(1, uploadRows().size)
  }

  @Test
  fun `setWifiOnly(false) starts an entry whose short wait from a hand-over is long over`() {
    // Probe P1: a chunked hand-over kept a sibling part's short wait.
    controller.setWifiOnly(true)
    network(RequiredNetwork.CONNECTED)
    handedOver(
      stored = entry(state = EntryState.RUNNING, nextAttemptAt = work.now + 20_000)
        .copy(waitCause = WaitCause.RESPONSE, failedAt = work.now, backoffStreak = 1),
    )
    work.now += 10 * minute
    controller.setWifiOnly(false)
    assertEquals(1, startable().size)
  }

  // MARK: - what the old constrained rows did, and no longer do

  @Test
  fun `a network loss stops no upload row, and a due backoff wake starts with no network rule`() {
    // Probes P3 and P4: an upload row with a rule was stopped by WorkManager and held back.
    store.save(entry(state = EntryState.RUNNING))
    val main = work.add("e1", state = RUNNING, runAttemptCount = 1)
    assertEquals(emptyList<FakeWorkManager.Row>(), network(RequiredNetwork.NONE))
    assertEquals(RUNNING, main.state)
    work.rows.clear()
    store.save(entry(state = EntryState.QUEUED, nextAttemptAt = work.now + 60 * minute).copy(waitCause = WaitCause.RESPONSE, failedAt = work.now))
    controller.sweep()
    work.now += 60 * minute
    network(RequiredNetwork.CONNECTED)
    assertEquals(listOf("e1#wake"), startable().map { it.name })
  }

  @Test
  fun `the source sets a WorkManager constraint only on the doorbell`() {
    val source = File("src/main/java/ai/openspace/backgroundupload/Scheduler.kt").readText()
    assertEquals(1, Regex("""\.setConstraints\(""").findAll(source).count())
    val doorbell = source.substringAfter("override fun enqueueDoorbell(").substringBefore("\n  }\n")
    assertTrue(doorbell.contains(".setConstraints("))
    assertFalse(source.substringAfter("private fun request(").contains(".setConstraints("))
  }

  @Test
  fun `a doorbell that waits is enough, and a doorbell needs a network`() {
    scheduler.ensureDoorbell(RequiredNetwork.CONNECTED)
    scheduler.ensureDoorbell(RequiredNetwork.CONNECTED)
    assertEquals(1, doorbellRows().size)
    assertThrows(IllegalArgumentException::class.java) { scheduler.ensureDoorbell(RequiredNetwork.NONE) }
  }

  @Test
  fun `a doorbell row has no entry id, so the summary and the snapshot do not count it as an entry's row`() {
    scheduler.ensureDoorbell(RequiredNetwork.WIFI)
    val summary = scheduler.rowSummary()
    assertEquals(emptySet<String>(), summary.startable)
    assertEquals(emptySet<String>(), summary.withWake)
    assertEquals(listOf(RequiredNetwork.WIFI), scheduler.libraryRuns().map { it.doorbell })
    assertEquals(listOf<String?>(null), scheduler.libraryRuns().map { it.entryId })
  }
}
