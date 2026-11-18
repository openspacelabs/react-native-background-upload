package ai.openspace.backgroundupload

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The WorkManager shell of one entry's run. The input data holds only the
 * entry id. The worker acquires the per-id gate, then [EntryRun] does the
 * run; this class is its [TransferHost]: OkHttp, the clock, progress, and
 * the notification.
 *
 * [UploadWorker] and [ChunkedUploadWorker] are the two class names
 * WorkManager knows; both run this same code. Every run returns success
 * (see [WorkManagerScheduler] for why), except a store failure before the
 * run could settle, which returns retry. A v9 row, which has no entry id,
 * exits at once in silence.
 */
open class EntryWorker(protected val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  companion object {
    private const val GATE_POLL_MS = 100L
  }

  private val store by lazy { QueueStore.get(context) }
  private val ops by lazy {
    WorkerOps(
      store,
      EventJournal.get(context),
      QueueSettingsStore.get(context),
      EventReporter,
      WorkManagerScheduler(context),
    )
  }
  private val config by lazy { NotificationConfig.load(context) }
  private val notificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  @Volatile
  private var connectivity = Connectivity.Ok

  @Volatile
  private var showsNotification = false

  /** setForeground succeeded in this run. WorkManager keeps the run in foreground mode until it ends. */
  @Volatile
  private var inForeground = false

  /** When this run began. WorkManager set the row RUNNING before it. */
  private var startedAt = 0L

  final override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    startedAt = System.currentTimeMillis()
    val id = inputData.getString(WorkManagerScheduler.ENTRY_ID_KEY) ?: return@withContext Result.success()
    // A headless run has no module, so it opens the log that its Diag calls write to.
    DiagnosticLog.get(context)
    // Acquire before the first store read: a cancel-then-enqueue can start
    // this run while the old one still winds down.
    while (!WorkerGate.tryAcquire(id, this@EntryWorker)) delay(GATE_POLL_MS)
    try {
      EntryRun(id, store, ops, host).run()
      Result.success()
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      // A store failure (disk full at start or during an attempt). Nothing
      // was settled; try the run again later.
      Diag.error("run of '$id' failed before it could settle; retrying", error)
      Result.retry()
    } finally {
      WorkerGate.release(id, this@EntryWorker)
    }
  }

  private val host = object : TransferHost {
    override fun now() = System.currentTimeMillis()

    override suspend fun sleep(ms: Long) = delay(ms)

    override suspend fun <T> withSlot(highPriority: Boolean, block: suspend () -> T): T =
      transferGate.withPermit(highPriority, block)

    override suspend fun send(request: TransferRequest, onProgress: (Long) -> Unit): UploadResponse =
      okhttpSend(uploadHttpClient, request, onProgress)

    override fun connectivity(wifiOnly: Boolean): Connectivity {
      connectivity = validateConnectivity(context, wifiOnly)
      updateNotification()
      return connectivity
    }

    override suspend fun foreground(entry: QueueEntry): Boolean {
      showsNotification = entry.descriptor?.noNotification == false
      if (startForeground()) inForeground = true
      return inForeground
    }

    override fun networkWakes() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override fun progressStarted(id: String, total: Long, sent: Long) {
      UploadProgress.add(id, total)
      UploadProgress.set(id, sent)
    }

    override fun progress(id: String, sent: Long, total: Long) {
      UploadProgress.set(id, sent)
      EventReporter.progress(id, sent, total)
      updateNotification()
    }

    override fun progressEnded(id: String, completed: Boolean) {
      if (completed) UploadProgress.complete(id) else UploadProgress.remove(id)
      EventReporter.flushProgress(id)
      EventReporter.dropProgress(id)
    }

    override fun attempt(event: AttemptEvent) = EventReporter.attempt(event)

    // getStopReason() needs API 31. Below that the reason is unknown, and
    // every stop takes the plain path: back to queued, no backoff step.
    override fun stopKind() =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) StopKind.of(stopReason) else StopKind.OTHER

    // runAttemptCount is the row's count before this start.
    override fun rowRestartsBy() = WorkManagerBackoff.restartBy(startedAt, runAttemptCount)
  }

  // MARK: - notification

  // v9 rules. A suppressed notification means no foreground mode. A denied
  // foreground start (API 31+, app in the background: the usual case for a
  // WorkManager relaunch) is not a failure; the transfer runs without
  // foreground priority, under JobScheduler's time limit (see
  // [EntryRun.releaseAfterTimeout]). Any other failure is logged and the run
  // goes on. Returns whether setForeground succeeded.
  private suspend fun startForeground(): Boolean {
    if (!showsNotification) return false
    return try {
      ensureNotificationChannel(notificationManager, config)
      setForeground(getForegroundInfo())
      true
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      if (!isForegroundStartDenied(error)) Diag.warn("foreground start failed; running without it", error)
      false
    }
  }

  private fun updateNotification() {
    if (!showsNotification) return
    runCatching {
      notificationManager.notify(
        config.systemNotificationId,
        buildUploadNotification(context, config, connectivity),
      )
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo =
    uploadForegroundInfo(config, buildUploadNotification(context, config, connectivity))
}
