package com.vydia.RNUploader

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit


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

class UploadWorker(private val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    try {
      // `setForeground` is recommended for long-running workers.
      // Foreground mode helps prioritize the worker, reducing the risk
      // of it being killed during low memory or Doze/App Standby situations.
      // ⚠️ This should be called in the foreground
      setForeground(getForegroundInfo())
    } catch (error: Throwable) {
      // If we fail to start foreground service, the worker will be stopped shortly after,
      // which makes it impossible to report errors for all uploads in the queue.
      // If we report errors here, the client can decide when to retry.
      handleUnableToStartForeground(error)
      throw error
    }

    // Update notification periodically
    var notificationJob: Job? = null
    try {
      notificationJob = startNotificationUpdateJob()
    } catch (_: Throwable) {
      // Should not block the worker if notification job setup fails
      EventReporter.globalError(
        "UploadWorker.startNotificationUpdateJob",
        Error("Failed to start notification job")
      )
    }

    try {
      // Keep processing uploads until the queue is empty
      while (UploadQueue.current() != null) uploadCurrent()

      return@withContext Result.success()
    } finally {
      notificationJob?.cancel() // Cancel the notification updates when work is done
    }
  }

  private suspend fun uploadCurrent() {
    val upload = UploadQueue.current() ?: return

    // We don't let WorkManager manage retries and network constraints as it's very buggy.
    // i.e. we'd occasionally get BackgroundServiceStartNotAllowedException,
    // or ForegroundServiceStartNotAllowedException, or workers getting cancelled for no reason.
    var retries = 0
    while (true) {
      try {
        // even this delay needs to be part of the try block
        if (retries > 0) delay(5_000L)

        // If there's no internet, wait until there is
        val connection = waitForInternet()

        // If upload requires wifi and we're not on wifi, try to switch to a non-wifi upload
        if (!connection.wifi && upload.wifiOnly) {
          if (UploadQueue.skipWifiOnly()) return
          continue
        }

        // Start the upload
        val response = okhttpUpload(
          client, upload,
          onProgress = { bytesSentTotal ->
            UploadQueue.progress(bytesSentTotal)
            EventReporter.progress(upload.id)
          },
          isCancelled = {
            if (UploadQueue.current() != upload) true
            else if (upload.wifiOnly && !checkConnection().wifi) true
            else false
          }
        )

        // Mark upload as completed
        UploadQueue.complete()
        EventReporter.success(upload.id, response)
        return
      } catch (error: Throwable) {
        try {
          // If the current upload has changed, it means it was cancelled externally
          if (UploadQueue.current() != upload) return

          // Worker stopped externally. This is unexpected so we need to report errors
          if (isStopped) return handleUnexpectedStop(error)

          // High chance error was thrown due to network connection issue
          // There's a bunch of different errors that can be thrown here,
          // so just check if the network is connected.
          if (!checkConnection().connected) continue

          // Due to the flaky nature of networking, sometimes the network is
          // valid but the URL is still inaccessible, so keep waiting until
          // the URL is accessible
          if (error is UnknownHostException) continue

          // There are many errors here that come from non-existent files
          // so we can't check using class, so we just check if the file exists
          // If the file doesn't exist, no point retrying
          if (!File(upload.path).exists())
            return handleError(upload, IOException("File at path ${upload.path} does not exist"))

          // Only penalize retries for other types of errors
          retries++

          // If we've retried too many times, give up
          if (retries > upload.maxRetries) return handleError(upload, error)
        } catch (_: Throwable) {
          continue
        }
      }
    }
  }

  private suspend fun waitForInternet(): Connection {
    while (true) {
      if (UploadQueue.isEmpty()) throw CancellationException()
      val connectivity = checkConnection()
      if (connectivity.connected) return connectivity
      delay(1000L)
    }
  }

  private fun handleError(upload: Upload, error: Throwable) {
    UploadQueue.pop()
    EventReporter.error(upload.id, error)
  }

  private fun handleUnexpectedStop(error: Throwable) {
    val stopReason =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) this.stopReason else "unknown"

    val error =
      CancellationException("Worker stopped due to: $stopReason. Original error: ${error.message}")

    while (!UploadQueue.isEmpty()) {
      val upload = UploadQueue.pop()
      EventReporter.error(upload.id, error)
    }
  }

  private fun handleUnableToStartForeground(error: Throwable) {
    val error =
      Error("Failed to start foreground service. Original error: ${error.message}")

    while (!UploadQueue.isEmpty()) {
      val upload = UploadQueue.pop()
      EventReporter.error(upload.id, error)
    }
  }


  private fun startNotificationUpdateJob() = CoroutineScope(Dispatchers.IO).launch {
    while (true) {
      try {
        delay(1000L)

        val connection = checkConnection()

        val notificationConnectivity =
          if (!connection.connected) NotificationConnectivity.NoInternet
          else if (UploadQueue.isAllWifiOnly() && !connection.wifi) NotificationConnectivity.NoWifi
          else NotificationConnectivity.Ok

        val (id, notification) = buildNotification(context, notificationConnectivity)
        notificationManager.notify(id, notification)
      } catch (error: Throwable) {
        if (isStopped) return@launch
        EventReporter.globalError("UploadWorker.updateNotification", error)
      }
    }
  }


  // builds the notification required to enable Foreground mode
  override suspend fun getForegroundInfo(): ForegroundInfo {
    val (id, notification) = buildNotification(context, NotificationConnectivity.Ok)

    // Starting Android 14, FOREGROUND_SERVICE_TYPE_DATA_SYNC is mandatory, otherwise app will crash
    return if (Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU)
      ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    else
      ForegroundInfo(id, notification)
  }

  private fun checkConnection(): Connection {
    val network = connectivityManager.activeNetwork
    val capabilities = connectivityManager.getNetworkCapabilities(network)
    val connected = capabilities?.hasCapability(NET_CAPABILITY_VALIDATED) == true
    val wifi = capabilities?.hasTransport(TRANSPORT_WIFI) == true

    return Connection(wifi = wifi, connected = connected)
  }


  val notificationManager: NotificationManager
    get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  val connectivityManager: ConnectivityManager
    get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
}

