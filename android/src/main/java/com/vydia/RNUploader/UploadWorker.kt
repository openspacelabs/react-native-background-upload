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

class UploadWorker(private val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {
  private var foreground = false

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    try {
      // `setForeground` is recommended for long-running workers.
      // Foreground mode helps prioritize the worker, reducing the risk
      // of it being killed during low memory or Doze/App Standby situations.
      // ⚠️ This should be called in the foreground
      setForeground(getForegroundInfo())
      foreground = true
    } catch (error: Throwable) {
      // Should not block the worker if setting foreground fails
      // TODO report errors
    }

    // Update notification periodically
    var notificationJob: Job? = null
    try {
      notificationJob = launch {
        while (true) {
          try {
            delay(1000L)
            updateNotification()
          } catch (error: Throwable) {
            if (isStopped) return@launch
            // TODO report errors
          }
        }
      }
    } catch (_: Throwable) {
      // Should not block the worker if notification job setup fails
      // TODO report errors
    }

    try {
      // Keep processing uploads until the queue is empty
      while (!UploadQueue.isEmpty()) uploadCurrent()

      return@withContext Result.success()
    } finally {
      notificationJob?.cancel() // Cancel the notification updates when work is done
      UploadQueue.clear()
    }
  }

  private suspend fun uploadCurrent() {
    val upload = UploadQueue.current()
    // Complex work, errors thrown below here trigger retry.
    // We don't let WorkManager manage retries and network constraints as it's very buggy.
    // i.e. we'd occasionally get BackgroundServiceStartNotAllowedException,
    // or ForegroundServiceStartNotAllowedException, or "isStopped" gets set to "true"
    // for no reason
    var isRetried = false
    var retriesLeft = upload.maxRetries
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

        val connectivity = getConnectivity(context)

        // If there's no internet, wait until there is
        if (!connectivity.connected) continue

        // If upload requires wifi and we're not on wifi, try to switch to a non-wifi upload
        if (!connectivity.wifi && upload.wifiOnly) {
          // switched to a non-wifi upload
          if (UploadQueue.selectNext(wifiOnly = false)) return
          // no non-wifi uploads, wait for wifi
          continue
        }

        // Start the upload
        val response = okhttpUpload(
          client, upload,
          onProgress = { bytesSentTotal ->
            UploadQueue.progress(upload.id, bytesSentTotal)
            EventReporter.progress(upload.id)
          },
          isCancelled = {
            if (isStopped) true
            else if (UploadQueue.current() != upload) true
            else if (upload.wifiOnly && !getConnectivity(context).wifi) true
            else false
          }
        )

        // Mark upload as completed
        UploadQueue.complete()
        EventReporter.success(upload.id, response)
        return
      } catch (error: Throwable) {
//        TODO
//        EventReporter.cancelled(upload.id)
//        TODO handle cancellations from isCancelled
        if (isStopped) return
        if (UploadQueue.current() != upload) return


        // High chance error was thrown due to network connection issue
        // There's a bunch of different errors that can be thrown here,
        // so just check if the network is connected.
        if (!getConnectivity(context).connected) continue

        // Due to the flaky nature of networking, sometimes the network is
        // valid but the URL is still inaccessible, so keep waiting until
        // the URL is accessible
        if (error is UnknownHostException) continue

        // There are many IOExceptions that only differ by messages,
        // so we can't check using class, but theoretically,
        // only the one caused by file not existing should stop the retry.
        // The rest should be related to flaky network or flaky file I/O,
        // where we can retry without limit.
        if (error is IOException) {
          try {
            if (!File(upload.path).exists()) {
              handleError(upload, error)
              return
            }
          } catch (_: Throwable) {
            // if this errors, can't do anything but retry
            continue
          }
        }

        // Only penalize retries for other types of errors
        retriesLeft--
        if (retriesLeft > 0) continue

        // Finally, handle the error
        handleError(upload, error)
        return
      }
    }
  }

  private fun handleError(upload: Upload, error: Throwable) {
    UploadQueue.pop()
    EventReporter.error(upload.id, error)
  }


  private fun updateNotification() {
    if (!foreground) return

    val connectivity = getConnectivity(context)

    val notificationConnectivity =
      if (!connectivity.connected) NotificationConnectivity.NoInternet
      else if (UploadQueue.isAllWifiOnly() && !connectivity.wifi) NotificationConnectivity.NoWifi
      else NotificationConnectivity.Ok

    val (id, notification) = buildNotification(context, notificationConnectivity)
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.notify(id, notification)
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
}

private fun getConnectivity(context: Context): Connectivity {
  val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  val network = manager.activeNetwork
  val capabilities = manager.getNetworkCapabilities(network)
  val connected = capabilities?.hasCapability(NET_CAPABILITY_VALIDATED) == true
  val wifi = capabilities?.hasTransport(TRANSPORT_WIFI) == true

  return Connectivity(wifi = wifi, connected = connected)
}
