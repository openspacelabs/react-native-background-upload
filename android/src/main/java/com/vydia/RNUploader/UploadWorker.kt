package com.vydia.RNUploader

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import io.ktor.http.*
import io.ktor.util.cio.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

// All workers will start `doWork` immediately but only 1 runs at a time.
const val MAX_CONCURRENCY = 1
const val PROGRESS_INTERVAL = 500

// Plenty of time for a single request to complete
val REQUEST_TIMEOUT_MILLIS = TimeUnit.HOURS.toMillis(24L)

private val semaphore = Semaphore(MAX_CONCURRENCY)
private val client = HttpClient(CIO) {
  install(HttpTimeout) {
    requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
  }
}

class UploadWorker(private val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  enum class Input { Params }

  private lateinit var upload: Upload

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {

    // initialization, errors thrown here won't be retried
    val httpMethod: HttpMethod
    val retries: Int
    var lastProgressReport = 0L
    try {
      val paramsJson = inputData.getString(Input.Params.name) ?: throw Throwable("No Params")
      upload = Gson().fromJson(paramsJson, Upload::class.java)
      httpMethod = HttpMethod.parse(upload.method)
      retries = UploadRetry.get(context, upload.id)
      setForeground(getForegroundInfo())
    } catch (error: Throwable) {
      handleCancellation(error)
      EventReporter.error(upload.id, error)
      return@withContext Result.failure()
    }


    // complex work, errors thrown below here can trigger retry
    try {
      // these need to be before the semaphore
      // to update the shared progress asap
      val file = File(upload.path)
      val size = file.length()
      UploadProgress.start(context, upload.id, size)

      // wait until its turn to execute
      semaphore.acquire()

      // Use ktor instead of okhttp. Ktor request is coroutine friendly and
      // will get cancelled when the coroutine gets cancelled
      val response = client.request(upload.url) {
        method = httpMethod
        setBody(file.readChannel())
        upload.headers.forEach { (key, value) -> headers.append(key, value) }
        onUpload { bytesSentTotal, _ ->
          val now = System.currentTimeMillis()
          if (now - lastProgressReport >= PROGRESS_INTERVAL) {
            lastProgressReport = now
            UploadProgress.update(context, upload.id, bytesSentTotal)
            EventReporter.progress(upload.id, bytesSentTotal, size)
            setForeground(getForegroundInfo())
          }
        }
      }

      UploadRetry.clear(context, upload.id)
      UploadProgress.update(context, upload.id, size)
      UploadProgress.scheduleClearing(context)
      EventReporter.success(upload.id, response)
      return@withContext Result.success()
    } catch (error: Throwable) {
      handleCancellation(error)

      // keep retrying
      if (retries < upload.maxRetries) {
        UploadRetry.set(context, upload.id, retries + 1)
        return@withContext Result.retry()
      }

      // no more retrying
      UploadRetry.clear(context, upload.id)
      UploadProgress.remove(context, upload.id)
      UploadProgress.scheduleClearing(context)
      EventReporter.error(upload.id, error)
      return@withContext Result.failure()
    } finally {
      // stop waiting
      semaphore.release()
    }
  }

  private fun handleCancellation(error: Throwable) {
    // Cancelled by user or new worker with same ID
    // Worker won't rerun, perform teardown
    if (isStopped) {
      UploadRetry.clear(context, upload.id)
      UploadProgress.remove(context, upload.id)
      UploadProgress.scheduleClearing(context)
      EventReporter.cancelled(upload.id)
      throw error
    }

    // Auto-cancelled, likely due to unmet constraints
    // Clearing state not needed, worker will restart
    if (error is CancellationException) throw error
  }

  override suspend fun getForegroundInfo(): ForegroundInfo {
    val channelId = "upload-progress"
    val id = upload.notificationId.hashCode()
    val progress = UploadProgress.total(context)

    // TODO pass params to notification
    val content = RemoteViews(context.packageName, R.layout.notification)
    content.setTextViewText(R.id.notification_title, "Uploading...")
    content.setTextViewText(R.id.notification_progress, "$progress%")
    content.setImageViewResource(R.id.notification_icon, android.R.drawable.stat_notify_chat)
    content.setProgressBar(R.id.notification_progress_bar, 100, progress, false)

    val notification = NotificationCompat.Builder(context, channelId)
      .setOngoing(true)
      .setAutoCancel(false)
      .setCustomContentView(content)
      .setContentIntent(openAppIntent(context))
      .build()

    return ForegroundInfo(id, notification)
  }
}

private fun openAppIntent(context: Context): PendingIntent? {
  val packageName = context.packageName
  val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)

  return launchIntent?.let {
    it.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
  }
}


