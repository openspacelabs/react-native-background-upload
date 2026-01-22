package com.vydia.RNUploader

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
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

class UploadWorker(private val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  enum class Input { Params }

  private lateinit var upload: Upload
  private var retries = 0

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

        val response = upload() ?: continue
        handleSuccess(response)
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
    UploadProgress.add(upload.id, size, upload.wifiOnly)

    // Don't bother to run on an invalid network
    if (Connectivity.fetch(context, upload.wifiOnly) != Connectivity.Ok) {
      UploadNotification.update(context)
      return null
    }

    // wait for its turn to run
    semaphore.acquire()
    try {
      // mark as active upload for notification
      UploadNotification.setActiveUpload(upload)
      UploadNotification.update(context)

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
      // by this point, another worker might have started and set itself as active upload
      // so only clear if it's still this upload
      UploadNotification.releaseActiveUpload(upload)
    }
  }

  private fun handleProgress(bytesSentTotal: Long, fileSize: Long) {
    UploadProgress.set(upload.id, bytesSentTotal)
    EventReporter.progress(upload.id, bytesSentTotal, fileSize)
    UploadNotification.update(context)
  }

  private fun handleSuccess(response: UploadResponse) {
    UploadProgress.complete(upload.id)
    EventReporter.success(upload.id, response)
    UploadNotification.update(context)
  }

  private fun handleError(error: Throwable) {
    UploadProgress.remove(upload.id)
    EventReporter.error(upload.id, error)
    UploadNotification.update(context)
  }

  // Check if cancelled by user or new worker with same ID
  // Worker won't rerun, perform teardown
  private fun checkAndHandleCancellation(): Boolean {
    if (!isStopped) return false

    UploadProgress.remove(upload.id)
    EventReporter.cancelled(upload.id)
    UploadNotification.update(context)
    return true
  }

  /** @return whether to retry */
  private fun checkRetry(error: Throwable): Boolean {
    var unlimitedRetry = false

    // Error was thrown due to unmet network preferences.
    // Also happens every time you switch from one network to any other
    if (Connectivity.fetch(context, upload.wifiOnly) != Connectivity.Ok) unlimitedRetry = true
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
    return retries <= UploadNotification.maxRetries
  }


  override suspend fun getForegroundInfo(): ForegroundInfo {
    val notification = UploadNotification.build(context)
    val id = UploadNotification.id
    // Starting Android 14, FOREGROUND_SERVICE_TYPE_DATA_SYNC is mandatory, otherwise app will crash
    return if (Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU)
      ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    else
      ForegroundInfo(id, notification)
  }
}


