package com.vydia.RNUploader

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

// All workers will start `doWork` immediately but only 1 request is active at a time.
private const val MAX_CONCURRENCY = 1

// Retry delay
private val RETRY_DELAY = TimeUnit.SECONDS.toMillis(10L)

// Control max concurrent requests using semaphore to instead of using
// `maxConnectionsCount` in HttpClient as the latter introduces a delay between requests
private val semaphore = Semaphore(MAX_CONCURRENCY)

// Use Okhttp as it provides the most standard behaviors even though it's not coroutine friendly
private val client = OkHttpClient()

private enum class Connectivity { NoWifi, NoInternet, Ok }

class UploadWorker(private val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  enum class Input { Params }

  private lateinit var upload: Upload
  private var retries = 0
  private var connectivity = Connectivity.Ok

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    // Retrieve the upload. If this throws errors, error reporting won't work.
    // However, the only way it has errors is the implementation is incorrect,
    // which can be caught in development
    val paramsJson = inputData.getString(Input.Params.name) ?: throw Throwable("No Params")
    upload = Gson().fromJson(paramsJson, Upload::class.java)

    // initialization, errors thrown here won't be retried
    try {
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
        // Delay if it's an actual retry.
        // Should be within the try block to account for worker cancellation,
        // which resumes immediately and throws CancellationException.
        if (isRetried) delay(RETRY_DELAY)
        isRetried = true

        val response = upload() ?: continue
        handleSuccess(response)
        return@withContext Result.success()
      } catch (error: Throwable) {
        if (checkAndHandleCancellation()) throw error
        if (checkRetry()) continue
        handleError(error)
        throw error
      }
    }

    // This should never happen. Only here to satisfy the type check
    return@withContext Result.failure()
  }

  private suspend fun upload(): Response? = withContext(Dispatchers.IO) {
    val file = File(upload.path)
    val size = file.length()

    // Register progress asap so the total progress is accurate
    // This needs to happen before the semaphore wait
    handleProgress(0, size)

    // Don't bother to run on an invalid network
    if (!validateAndReportConnectivity()) return@withContext null

    // wait for its turn to run
    semaphore.acquire()

    try {
      val response = okhttpUpload(client, upload, file) { progress ->
        launch { handleProgress(progress, size) }
      }

      handleProgress(size, size)
      return@withContext response
    }
    // don't catch, propagate error up
    finally {
      semaphore.release()
    }
  }

  private suspend fun handleProgress(bytesSentTotal: Long, fileSize: Long) {
    UploadProgress.set(context, upload.id, bytesSentTotal, fileSize)
    EventReporter.progress(upload.id, bytesSentTotal, fileSize)
    setForeground(getForegroundInfo())
  }

  private fun handleSuccess(response: Response) {
    UploadProgress.scheduleClearing(context)
    EventReporter.success(upload.id, response)
  }

  private fun handleError(error: Throwable) {
    UploadProgress.remove(context, upload.id)
    UploadProgress.scheduleClearing(context)
    EventReporter.error(upload.id, error)
  }

  // Check if cancelled by user or new worker with same ID
  // Worker won't rerun, perform teardown
  private fun checkAndHandleCancellation(): Boolean {
    if (!isStopped) return false

    UploadProgress.remove(context, upload.id)
    UploadProgress.scheduleClearing(context)
    EventReporter.cancelled(upload.id)
    return true
  }

  private suspend fun checkRetry(): Boolean {
    // Clearing state not needed. Retrying for free.
    // Error was thrown due to unmet network preferences.
    // Also happens every time you switch from one network to any other,
    // so no need to implement restarting when switching networks.
    if (!validateAndReportConnectivity()) return true

    // Retrying while counting toward maxRetries
    if (++retries <= upload.maxRetries) return true

    return false
  }

  // Checks connection and alerts connection issues
  private suspend fun validateAndReportConnectivity(): Boolean {
    this.connectivity = validateConnectivity(context, upload.wifiOnly)
    // alert connectivity mode
    setForeground(getForegroundInfo())
    return this.connectivity == Connectivity.Ok
  }

  // builds the notification required to enable Foreground mode
  override suspend fun getForegroundInfo(): ForegroundInfo {
    // All workers share the same notification that shows the total progress
    val id = upload.notificationId.hashCode()
    val channel = upload.notificationChannel
    val progress = UploadProgress.total(context)
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

    val notification = NotificationCompat.Builder(context, channel).run {
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

    return ForegroundInfo(id, notification)
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
