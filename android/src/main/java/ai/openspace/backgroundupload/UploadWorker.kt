package ai.openspace.backgroundupload

import android.app.NotificationManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit

// Retry delay
private val RETRY_DELAY = TimeUnit.SECONDS.toMillis(10L)

// The retry budget for errors that count (see checkRetry). A connectivity gap
// or flaky-network IO resets the budget. The retry policy is internal to the
// library. It is not an option.
private const val MAX_RETRIES = 5

class UploadWorker(private val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  companion object {
    /**
     * Key for the serialized [Upload] in the worker's input data.
     *
     * A string literal on purpose. This key is persisted in WorkManager's
     * database, so the build that runs a job may not be the build that enqueued
     * it — a key derived from a symbol name (an enum constant, a property) breaks
     * the moment R8 renames it or someone refactors, and the failure looks like
     * "No Params" on a job that was queued perfectly well by the previous version.
     */
    const val PARAMS_KEY = "params"
  }

  private lateinit var upload: Upload
  // configure() saved this. The worker can read it when WorkManager relaunched
  // the worker with no JS. It is lazy, so the SharedPreferences read occurs on
  // the worker's IO dispatcher, not at construction.
  private val config by lazy { NotificationConfig.load(context) }
  private var retries = 0
  private var connectivity = Connectivity.Ok
  private val notificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    // Retrieve the upload. If this throws errors, error reporting won't work.
    // However, the only way it has errors is the implementation is incorrect,
    // which can be caught in development
    val paramsJson = inputData.getString(PARAMS_KEY) ?: throw Throwable("No Params")
    // normalized(): an older build can have enqueued this job, and its JSON
    // shape can make non-null fields null (Gson does not use the constructor).
    // See Upload.normalized.
    upload = Gson().fromJson(paramsJson, Upload::class.java).normalized()

    // initialization, errors thrown here won't be retried
    try {
      // An upload that suppresses its notification cannot enter foreground mode,
      // since the notification is the foreground service's own notification.
      if (upload.showsNotification) {
        // The foreground notification needs a channel to exist first, or posting
        // it silently fails and setForeground can crash on newer Android.
        ensureNotificationChannel(notificationManager, config)
        // `setForeground` is recommended for long-running workers.
        // Foreground mode helps prioritize the worker, reducing the risk
        // of it being killed during low memory or Doze/App Standby situations.
        // ⚠️ This should be called in the foreground
        setForeground(getForegroundInfo())
      }
    } catch (error: Throwable) {
      if (!isForegroundStartDenied(error)) {
        if (!checkAndHandleCancellation()) handleError(error)
        throw error
      }
      // The app is in the background on API 31+ (see isForegroundStartDenied).
      // Continue the upload without foreground priority. Do not fail an upload
      // that can run.
    }


    // Complex work, errors thrown below here trigger retry.
    // We don't let WorkManager manage retries and network constraints as it's very buggy.
    // i.e. we'd occasionally get BackgroundServiceStartNotAllowedException,
    // or ForegroundServiceStartNotAllowedException, or "isStopped" gets set to "true"
    // for no reason
    var isRetried = false
    while (true) {
      try {
        // - "delay" should be within the "try" block to account for worker cancellation,
        // which cancels the delay immediately and throws CancellationException.
        // - Linear backoff instead of exponential. One reason for this is we retry on
        // invalid connections. Exponential will take too long.
        // - We retry only transport failures here (no response). An HTTP
        // response, 4xx and 5xx included, is terminal at this layer.
        // handleResponse classifies it (a 2xx or an accept rule -> completed,
        // else an http error), and the worker returns without a retry. A
        // response-code retry policy is the JS queue's job. This matches the
        // iOS behavior.
        if (isRetried) delay(RETRY_DELAY)
        isRetried = true

        val response = upload() ?: continue
        handleResponse(response)
        return@withContext Result.success()
      } catch (error: Throwable) {
        if (checkAndHandleCancellation()) throw error
        if (checkRetry(error)) continue
        handleError(error)
        throw error
      }
    }

    // This should never happen. Only here to satisfy the type check
    return@withContext Result.failure()
  }

  private suspend fun upload(): UploadResponse? {
    val file = File(upload.path)
    val size = file.length()

    // Register progress asap so the total progress is accurate
    // This needs to happen before the semaphore wait
    UploadProgress.add(upload.id, size)

    // Don't bother to run on an invalid network
    if (!validateAndReportConnectivity()) return null

    // wait for its turn to run
    transferSemaphore.acquire()

    try {
      return okhttpUpload(uploadHttpClient, upload, file) { progress ->
        handleProgress(progress, size)
      }
    } catch (error: Throwable) {
      // reset progress on error
      UploadProgress.set(upload.id, 0L)
      // pass the error to upper layer for retry decision
      throw error
    } finally {
      transferSemaphore.release()
    }
  }

  private fun handleProgress(bytesSentTotal: Long, fileSize: Long) {
    UploadProgress.set(upload.id, bytesSentTotal)
    EventReporter.progress(upload.id, bytesSentTotal, fileSize)
    updateNotification()
  }

  // Redraws the progress notification. A no-op for a suppressed upload — the
  // worker never posted one, and `notify` would create it outside foreground mode.
  private fun updateNotification() {
    if (!upload.showsNotification) return
    notificationManager.notify(
      config.systemNotificationId,
      buildUploadNotification(context, config, connectivity),
    )
  }

  // An HTTP response came back. It is "completed" only for a 2xx or a matching
  // accept rule (axios validateStatus semantics: a 400 is an error, not a
  // completion). Every other response is a terminal http error that carries the
  // full response. In both cases the request finished, so the worker does not
  // retry.
  private fun handleResponse(response: UploadResponse) {
    UploadProgress.complete(upload.id)
    val accepted = UploadOutcome.isAccepted(response.code, response.body, upload.accept)
    val (body, truncated) = EventJournal.capBody(response.body)
    journalAndEmit(
      EventJournal.Entry(
        eventId = UUID.randomUUID().toString(),
        uploadId = upload.id,
        type = if (accepted) "completed" else "error",
        timestamp = System.currentTimeMillis(),
        responseCode = response.code,
        responseBody = body,
        responseBodyTruncated = truncated,
        responseHeaders = response.headers,
        errorKind = if (accepted) null else "http",
        error = if (accepted) null else "HTTP ${response.code}",
      )
    )
  }

  private fun handleError(error: Throwable) {
    UploadProgress.remove(upload.id)
    // Default fileExists=true so a failed existence probe reads as network, not file.
    val fileExists = runCatching { File(upload.path).exists() }.getOrDefault(true)
    journalAndEmit(
      EventJournal.Entry(
        eventId = UUID.randomUUID().toString(),
        uploadId = upload.id,
        type = "error",
        timestamp = System.currentTimeMillis(),
        error = error.message ?: "Unknown exception",
        errorKind = UploadOutcome.errorKind(error, fileExists),
      )
    )
  }

  // Check if cancelled by user or new worker with same ID
  // Worker won't rerun, perform teardown
  private fun checkAndHandleCancellation(): Boolean {
    if (!isStopped) return false

    UploadProgress.remove(upload.id)

    // Only a user cancel is terminal, so only a user cancel is journaled.
    //
    // WorkManager decides whether to reschedule BEFORE it stops the worker, and
    // it ignores the Result we return. cancelUniqueWork marks the row CANCELLED
    // first, so a user cancel is genuinely the end. A system stop — a
    // foreground-service timeout, quota, or memory pressure — leaves the row
    // RUNNING and WorkManager re-runs this same upload. Journaling a terminal
    // `cancelled` there would durably tell JS the upload was dead while it was in
    // fact about to be retried, so the consumer would settle the transfer and the
    // retry would land as a duplicate on the server.
    //
    // Emitting nothing is the honest answer for a system stop: the upload is
    // still in flight as far as anyone should be concerned. If WorkManager ever
    // declines to reschedule, `getAllUploads()` is how a consumer notices.
    if (!UserCancellations.consume(upload.id)) return true

    journalAndEmit(
      EventJournal.Entry(
        eventId = UUID.randomUUID().toString(),
        uploadId = upload.id,
        type = "cancelled",
        timestamp = System.currentTimeMillis(),
        cancelReason = "user",
      )
    )
    return true
  }

  private fun journalAndEmit(entry: EventJournal.Entry) =
    EventReporter.journalAndEmit(context, entry)

  /** @return whether to retry */
  private fun checkRetry(error: Throwable): Boolean {
    var unlimitedRetry = false

    // Error was thrown due to unmet network preferences.
    // Also happens every time you switch from one network to any other
    if (!validateAndReportConnectivity()) unlimitedRetry = true
    // Due to the flaky nature of networking, sometimes the network is
    // valid but the URL is still inaccessible, so keep waiting until
    // the URL is accessible
    else if (error is UnknownHostException) unlimitedRetry = true
    // There are many IOExceptions that only differ by messages,
    // so we can't check using class, but theoretically,
    // only the one caused by file not existing should stop the retry.
    // The rest should be related to flaky network or flaky file I/O,
    // where we can retry without limit.
    else if (error is IOException) {
      try {
        if (!File(upload.path).exists()) return false
        unlimitedRetry = true
      } catch (_: Throwable) {
        // read file error, can't do anything but retry
        unlimitedRetry = false
      }
    }

    retries = if (unlimitedRetry) 0 else retries + 1
    return retries <= MAX_RETRIES
  }

  // Checks connection and alerts connection issues
  private fun validateAndReportConnectivity(): Boolean {
    this.connectivity = validateConnectivity(context, upload.wifiOnly)
    // alert connectivity mode
    updateNotification()
    return this.connectivity == Connectivity.Ok
  }

  override suspend fun getForegroundInfo(): ForegroundInfo =
    uploadForegroundInfo(config, buildUploadNotification(context, config, connectivity))
}
