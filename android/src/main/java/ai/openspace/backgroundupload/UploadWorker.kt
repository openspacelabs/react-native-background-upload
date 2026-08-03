package ai.openspace.backgroundupload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit

// All workers will start `doWork` immediately but only 1 request is active at a time.
private const val MAX_CONCURRENCY = 1

// Retry delay
private val RETRY_DELAY = TimeUnit.SECONDS.toMillis(10L)

// Max total time for a single request to complete
// This is 24hrs so plenty of time for large uploads
// Worst case is the time maxes out and the upload gets restarted.
// Not using unlimited time to prevent unexpected behaviors.
private const val REQUEST_TIMEOUT = 24L
private val REQUEST_TIMEOUT_UNIT = TimeUnit.HOURS

// Control max concurrent requests using semaphore to instead of using
// `maxConnectionsCount` in HttpClient as the latter introduces a delay between requests
private val semaphore = Semaphore(MAX_CONCURRENCY)

// Use Okhttp as it provides the most standard behaviors even though it's not coroutine friendly
private val client = OkHttpClient.Builder()
  .callTimeout(REQUEST_TIMEOUT, REQUEST_TIMEOUT_UNIT)
  .build()

private enum class Connectivity { NoWifi, NoInternet, Ok }

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
  private var retries = 0
  private var connectivity = Connectivity.Ok
  private val notificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    // Retrieve the upload. If this throws errors, error reporting won't work.
    // However, the only way it has errors is the implementation is incorrect,
    // which can be caught in development
    val paramsJson = inputData.getString(PARAMS_KEY) ?: throw Throwable("No Params")
    upload = Gson().fromJson(paramsJson, Upload::class.java)

    // initialization, errors thrown here won't be retried
    try {
      // The foreground notification needs a channel to exist first, or posting
      // it silently fails and setForeground can crash on newer Android.
      ensureNotificationChannel()
      // `setForeground` is recommended for long-running workers.
      // Foreground mode helps prioritize the worker, reducing the risk
      // of it being killed during low memory or Doze/App Standby situations.
      // ⚠️ This should be called in the foreground
      setForeground(getForegroundInfo())
    } catch (error: Throwable) {
      if (!checkAndHandleCancellation()) handleError(error)
      throw error
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
        // - We only retry transport failures here (no response). Any HTTP response,
        // including 4xx/5xx, is terminal at this layer: handleResponse classifies it
        // (2xx/acceptStatus -> completed, else http error) and the worker returns
        // without retrying. Response-code-based retry policy is the JS queue's job.
        // This is consistent with iOS behavior.
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
    semaphore.acquire()

    try {
      return okhttpUpload(client, upload, file) { progress ->
        handleProgress(progress, size)
      }
    } catch (error: Throwable) {
      // reset progress on error
      UploadProgress.set(upload.id, 0L)
      // pass the error to upper layer for retry decision
      throw error
    } finally {
      semaphore.release()
    }
  }

  private fun handleProgress(bytesSentTotal: Long, fileSize: Long) {
    UploadProgress.set(upload.id, bytesSentTotal)
    EventReporter.progress(upload.id, bytesSentTotal, fileSize)
    notificationManager.notify(upload.notificationId, buildNotification())
  }

  // An HTTP response came back. "completed" only for 2xx or a per-request
  // acceptStatus code (axios validateStatus semantics — a 400 is an error, not a
  // completion); anything else is a terminal http error carrying the full
  // response. Either way the request finished, so the worker does not retry.
  private fun handleResponse(response: UploadResponse) {
    UploadProgress.complete(upload.id)
    val accepted = UploadOutcome.isAccepted(response.code, upload.acceptStatus)
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

  // Journal before emitting: the journal is the durable record (survives JS being
  // dead); the live emit is best-effort. Both carry the identical payload, so a
  // consumer can ack a live event by its eventId.
  private fun journalAndEmit(entry: EventJournal.Entry) {
    EventJournal.get(context).append(entry)
    EventReporter.emit(entry)
  }

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
    return retries <= upload.maxRetries
  }

  // Checks connection and alerts connection issues
  private fun validateAndReportConnectivity(): Boolean {
    this.connectivity = validateConnectivity(context, upload.wifiOnly)
    // alert connectivity mode
    notificationManager.notify(upload.notificationId, buildNotification())
    return this.connectivity == Connectivity.Ok
  }

  // Ensures the channel used by the foreground notification exists. Only creates
  // it when absent, so a channel the consumer registered themselves (with their
  // own name/importance) always wins; when they pass nothing we fall back to a
  // default LOW-importance channel and no notifee setup is required.
  private fun ensureNotificationChannel() {
    // minSdk is 29, so NotificationChannel (API 26) is always available.
    if (notificationManager.getNotificationChannel(upload.notificationChannel) != null) return
    val channel = NotificationChannel(
      upload.notificationChannel,
      "Uploads",
      NotificationManager.IMPORTANCE_LOW,
    )
    notificationManager.createNotificationChannel(channel)
  }

  // builds the notification required to enable Foreground mode
  fun buildNotification(): Notification {
    val channel = upload.notificationChannel
    val progress = UploadProgress.total()
    val progress2Decimals = "%.2f".format(progress)
    val title = when (connectivity) {
      Connectivity.NoWifi -> upload.notificationTitleNoWifi
      Connectivity.NoInternet -> upload.notificationTitleNoInternet
      Connectivity.Ok -> upload.notificationTitle
    }

    // Custom layout for progress notification.
    // The default hides the % text. This one shows it on the right,
    // like most examples in various docs.
    val content = RemoteViews(context.packageName, R.layout.notification)
    content.setTextViewText(R.id.notification_title, title)
    content.setTextViewText(R.id.notification_progress, "${progress2Decimals}%")
    content.setProgressBar(R.id.notification_progress_bar, 100, progress.toInt(), false)

    return NotificationCompat.Builder(context, channel).run {
      // Starting Android 12, the notification shows up with a confusing delay of 10s.
      // This fixes that delay.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        foregroundServiceBehavior = Notification.FOREGROUND_SERVICE_IMMEDIATE

      // Required by android. Here we use the system's default upload icon
      setSmallIcon(android.R.drawable.stat_sys_upload)
      // These prevent the notification from being force-dismissed or dismissed when pressed
      setOngoing(true)
      setAutoCancel(false)
      // These help show the same custom content when the notification collapses and expands
      setCustomContentView(content)
      setCustomBigContentView(content)
      // opens the app when the notification is pressed
      setContentIntent(openAppIntent(context))
      build()
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo {
    val notification = buildNotification()
    val id = upload.notificationId
    // Starting Android 14, FOREGROUND_SERVICE_TYPE_DATA_SYNC is mandatory, otherwise app will crash
    return if (Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU)
      ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    else
      ForegroundInfo(id, notification)
  }
}

// This is outside and synchronized to ensure consistent status across workers
@Synchronized
private fun validateConnectivity(context: Context, wifiOnly: Boolean): Connectivity {
  val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  val network = manager.activeNetwork
  val capabilities = manager.getNetworkCapabilities(network)

  val hasInternet = capabilities?.hasCapability(NET_CAPABILITY_VALIDATED) == true

  // not wifiOnly, return early
  if (!wifiOnly) return if (hasInternet) Connectivity.Ok else Connectivity.NoInternet

  // handle wifiOnly
  return if (hasInternet && capabilities?.hasTransport(TRANSPORT_WIFI) == true)
    Connectivity.Ok
  else
    Connectivity.NoWifi // don't return NoInternet here, more direct to request to join wifi
}


private fun openAppIntent(context: Context): PendingIntent? {
  val intent = Intent(context, NotificationReceiver::class.java)
  val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  return PendingIntent.getBroadcast(context, "RNFileUpload-notification".hashCode(), intent, flags)
}
