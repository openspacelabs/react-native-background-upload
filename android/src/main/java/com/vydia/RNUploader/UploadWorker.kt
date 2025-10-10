package com.vydia.RNUploader

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

// Retry delay
private val RETRY_DELAY = TimeUnit.SECONDS.toMillis(10L)

// Max total time for a single request to complete
// This is 24hrs so plenty of time for large uploads
// Worst case is the time maxes out and the upload gets restarted.
// Not using unlimited time to prevent unexpected behaviors.
private const val REQUEST_TIMEOUT = 24L
private val REQUEST_TIMEOUT_UNIT = TimeUnit.HOURS


// Use Okhttp as it provides the most standard behaviors even though it's not coroutine friendly
private val client = OkHttpClient.Builder()
  .callTimeout(REQUEST_TIMEOUT, REQUEST_TIMEOUT_UNIT)
  .build()

private enum class Connectivity { NoWifi, NoInternet, Ok }

class UploadWorker(private val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  private lateinit var upload: Upload
  private var retries = 0
  private var connectivity = Connectivity.Ok
  private var foreground = false

  val notificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    try {
      setForeground(getForegroundInfo())
      foreground = true
    } catch (error: Throwable) {
      // Should not block the worker if setting foreground fails
    }

    try {
      // Keep processing uploads until no more incomplete uploads
      while (!UploadQueue.isEmpty()) processUpload(UploadQueue.current())

      return@withContext Result.success()
    } finally {
      UploadQueue.clear()
    }
  }

  private suspend fun processUpload(upload: Upload) = withContext(Dispatchers.IO) {
    this@UploadWorker.upload = upload
    retries = 0
    connectivity = Connectivity.Ok

    // Complex work, errors thrown below here trigger retry.
    var isRetried = false
    while (true) {
      try {
        // - "delay" should be within the "try" block to account for worker cancellation,
        // which cancels the delay immediately and throws CancellationException.
        // - Linear backoff instead of exponential. One reason for this is we retry on
        // invalid connections. Exponential will take too long. If the server flakes and
        // returns 500s, we don't retry but consider the request successful.
        // This is consistent with iOS behavior. User gets notifications for
        // these server issues and can manually retry. Since 500s are currently rare,
        // it's likely ok. If they're too frequent, we can consider adding exponential
        // backoff for them.
        if (isRetried) delay(RETRY_DELAY)
        isRetried = true

        val file = File(upload.path)

        // Don't bother to run on an invalid network
        if (!validateAndReportConnectivity()) continue

        EventReporter.progress(upload.id)

        val response = okhttpUpload(client, upload, file) { bytesSentTotal ->
          UploadQueue.progress(upload.id, bytesSentTotal)
          EventReporter.progress(upload.id)
          updateNotification()
        }

        handleSuccess(upload, response)
        return@withContext
      } catch (error: Throwable) {
        if (checkAndHandleCancellation(upload)) throw error
        if (checkRetry(upload, error)) continue

        // Log error but continue with next upload
        Log.e("UploadWorker", "Upload ${upload.id} failed", error)
        UploadQueue.pop()
        EventReporter.error(upload.id, error)
        throw error
      }
    }
  }

  private fun handleSuccess(upload: Upload, response: UploadResponse) {
    // Mark as completed on success
    UploadQueue.complete()
    EventReporter.success(upload.id, response)
  }


  // Check if cancelled by user or new worker with same ID
  // Worker won't rerun, perform teardown
  private fun checkAndHandleCancellation(upload: Upload): Boolean {
    if (!isStopped) return false

    EventReporter.cancelled(upload.id)
    return true
  }

  /** @return whether to retry */
  private suspend fun checkRetry(upload: Upload, error: Throwable): Boolean {
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
    // Update notification instead of calling setForeground
    updateNotification()
    return this.connectivity == Connectivity.Ok
  }

  private fun updateNotification() {
    if (!foreground) return

    val (id, notification) = buildNotification()
    notificationManager.notify(id, notification)
  }

  private fun buildNotification(): Pair<Int, Notification> {
    val progress = UploadQueue.progressPercentage()
    val progress2Decimals = "%.2f".format(progress)
    val title = when (connectivity) {
      Connectivity.NoWifi -> NotificationConfigs.titleNoWifi
      Connectivity.NoInternet -> NotificationConfigs.titleNoInternet
      Connectivity.Ok -> NotificationConfigs.title
    }

    // Custom layout for progress notification.
    // The default hides the % text. This one shows it on the right,
    // like most examples in various docs.
    val content = RemoteViews(context.packageName, R.layout.notification)
    content.setTextViewText(R.id.notification_title, title)
    content.setTextViewText(R.id.notification_progress, "${progress2Decimals}%")
    content.setProgressBar(R.id.notification_progress_bar, 100, progress.toInt(), false)

    val notification = NotificationCompat.Builder(context, NotificationConfigs.channel).run {
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

    return Pair(NotificationConfigs.id, notification)
  }

  // builds the notification required to enable Foreground mode
  override suspend fun getForegroundInfo(): ForegroundInfo {
    val (id, notification) = buildNotification()

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
