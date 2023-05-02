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
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.cio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

// All workers will start `doWork` immediately but only 1 request is active at a time.
const val MAX_CONCURRENCY = 1

// Throttling interval of progress reports
const val PROGRESS_INTERVAL = 500

// Retry delay
val RETRY_DELAY = TimeUnit.SECONDS.toMillis(10L)

// Max total time for a single request to complete
// This is 24hrs so plenty of time for large uploads
// Worst case is the time maxes out and the upload gets restarted.
val REQUEST_TIMEOUT_MILLIS = TimeUnit.HOURS.toMillis(24L)

// Control max concurrent requests using semaphore to instead of using
// `maxConnectionsCount` in HttpClient as the latter introduces a delay between requests
val semaphore = Semaphore(MAX_CONCURRENCY)
private val client = HttpClient(CIO) {
  install(HttpTimeout) {
    requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
  }
}

class UploadWorker(private val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  enum class Input { Params }
  private enum class Connectivity { NoWifi, NoInternet, Ok }

  private lateinit var upload: Upload
  private var retries = 0
  private var firstRun = true
  private var connectivity = Connectivity.Ok
  private var semaphoreAcquired = false

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    // Retrieve the upload. If it fails, even error reporting won't work.
    // In the future, pass the upload ID separately to reduce burden.
    // Currently however, the likelihood of failure is super low.
    val paramsJson = inputData.getString(Input.Params.name) ?: throw Throwable("No Params")
    upload = Gson().fromJson(paramsJson, Upload::class.java)

    // initialization, errors thrown here won't be retried
    val httpMethod: HttpMethod
    try {
      httpMethod = HttpMethod.parse(upload.method)
      // `setForeground` is recommended for long-running workers.
      // Foreground mode helps prioritize the worker, reducing the risk
      // of it being killed during low memory or Doze/App Standby situations.
      // ⚠️ This should be called in the foreground
      setForeground(getForegroundInfo())
    } catch (error: Throwable) {
      handleError(error, retry = false)
      return@withContext Result.failure()
    }


    // Complex work, errors thrown below here trigger retry.
    // We don't let WorkManager manage retries and network constraints as it's very buggy.
    // i.e. we'd occasionally get BackgroundServiceStartNotAllowedException,
    // or ForegroundServiceStartNotAllowedException, or "isStopped" gets set to "true"
    // for no reason
    while (true) {
      try {
        // Delay if it's an actual retry, not the first run.
        // Should be within the try block to account for worker cancellation,
        // which resumes immediately and throws CancellationException.
        if (!firstRun) delay(RETRY_DELAY)
        firstRun = false

        val file = File(upload.path)
        val size = file.length()

        // Register progress asap so the total progress is accurate
        // This needs to happen before the semaphore wait
        handleProgress(0, size)

        // Don't bother to run on an invalid network
        if (!validateConnection(context, upload.wifiOnly)) continue

        // wait for its turn to run
        semaphore.acquire()
        semaphoreAcquired = true

        // Use ktor instead of okhttp. Ktor request is coroutine friendly and
        // will get cancelled when the coroutine gets cancelled
        var lastProgressReport = 0L
        val body = file.readChannel()
        val response = client.request(upload.url) {
          method = httpMethod
          setBody(body)
          upload.headers.forEach { (key, value) -> headers.append(key, value) }
          onUpload { bytesSentTotal, _ ->
            // throttle progress report
            val now = System.currentTimeMillis()
            if (now - lastProgressReport >= PROGRESS_INTERVAL) {
              lastProgressReport = now
              handleProgress(bytesSentTotal, size)
            }
          }
        }

        return@withContext handleSuccess(response, size)
      } catch (error: Throwable) {
        handleError(error, retry = true)
      } finally {
        // yield to the next worker
        if (semaphoreAcquired) semaphore.release()
        semaphoreAcquired = false
      }
    }

    // This should never happen. Only here to satisfy the type check
    return@withContext Result.failure()
  }

  private suspend fun handleProgress(bytesSentTotal: Long, fileSize: Long) {
    UploadProgress.set(context, upload.id, bytesSentTotal, fileSize)
    EventReporter.progress(upload.id, bytesSentTotal, fileSize)
    setForeground(getForegroundInfo())
  }

  private fun handleSuccess(response: HttpResponse, fileSize: Long): Result {
    UploadProgress.set(context, upload.id, fileSize, fileSize)
    UploadProgress.scheduleClearing(context)
    EventReporter.success(upload.id, response)
    return Result.success()
  }

  // Rethrows to teardown the worker or suppresses error for retrying
  // Alerts status
  private suspend fun handleError(error: Throwable, retry: Boolean) {
    // Cancelled by user or new worker with same ID
    // Worker won't rerun, perform teardown
    if (isStopped) {
      UploadProgress.remove(context, upload.id)
      UploadProgress.scheduleClearing(context)
      EventReporter.cancelled(upload.id)
      throw error
    }

    if (retry) {
      // Error thrown due to unmet network constraints. Clearing state not needed.
      // Retrying for free
      if (!validateConnection(context, upload.wifiOnly)) return

      // Retrying while counting toward maxRetries
      retries++
      if (retries <= upload.maxRetries) return
    }


    // no more retrying
    UploadProgress.remove(context, upload.id)
    UploadProgress.scheduleClearing(context)
    EventReporter.error(upload.id, error)
    throw error
  }

  // Checks connection and alerts connection issues
  private suspend fun validateConnection(context: Context, wifiOnly: Boolean): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = manager.activeNetwork
    val capabilities = manager.getNetworkCapabilities(network)

    this.connectivity =
      if (capabilities?.hasCapability(NET_CAPABILITY_VALIDATED) != true)
        Connectivity.NoInternet
      else if (wifiOnly && !capabilities.hasTransport(TRANSPORT_WIFI))
        Connectivity.NoWifi
      else
        Connectivity.Ok


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
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) foregroundServiceBehavior =
        Notification.FOREGROUND_SERVICE_IMMEDIATE

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

private fun openAppIntent(context: Context): PendingIntent? {
  val intent = Intent(context, NotificationReceiver::class.java)
  val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  return PendingIntent.getBroadcast(context, "RNFileUpload-notification".hashCode(), intent, flags)
}


