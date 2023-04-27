package com.vydia.RNUploader

import android.content.Context
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.vydia.RNUploader.UploaderModule.Companion.MAX_CONCURRENCY
import com.vydia.RNUploader.UploaderModule.Companion.REQUEST_TIMEOUT_MILLIS
import com.vydia.RNUploader.UploaderModule.Companion.eventReporter
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


// All workers will start `doWork` immediately but only 1 runs at a time.
private val semaphore = Semaphore(MAX_CONCURRENCY)
private val client = HttpClient(CIO) {
  install(HttpTimeout) {
    requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
  }
}

class UploadWorker(private val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  enum class Input { Params }
  enum class State { Retries }

  private lateinit var upload: Upload
  private lateinit var sharedProgress: SharedProgress

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {

    // initialization, errors thrown here won't be retried
    val httpMethod: HttpMethod
    val retries: Int
    try {
      val paramsJson = inputData.getString(Input.Params.name) ?: throw Throwable("No Params")
      upload = Gson().fromJson(paramsJson, Upload::class.java)
      retries = state(context, upload.id).getInt(State.Retries.name, 0)
      httpMethod = HttpMethod.parse(upload.method)
      sharedProgress = SharedProgress(context)
      setForeground(getForegroundInfo())
    } catch (error: Throwable) {
      handleCancellation(error)

      eventReporter?.error(upload.id, error)
      clearState(context, upload.id)
      return@withContext Result.failure()
    }


    // complex work, errors thrown below here can trigger retry
    try {
      // this can happen before the semaphore to save time
      val file = File(upload.path)
      val size = file.length()

      // wait until its turn to execute
      semaphore.acquire()

      // Use ktor instead of okhttp. Ktor request is coroutine friendly and
      // will get cancelled when the coroutine gets cancelled
      val response = client.request(upload.url) {
        method = httpMethod
        setBody(file.readChannel())
        upload.headers.forEach { (key, value) -> headers.append(key, value) }
        onUpload { bytesSentTotal, _ ->
          setForeground(getForegroundInfo())
          eventReporter?.progress(upload.id, bytesSentTotal, size)
        }
      }

      eventReporter?.success(upload.id, response)
      clearState(context, upload.id)
      return@withContext Result.success()
    } catch (error: Throwable) {
      handleCancellation(error)

      // keep retrying
      if (retries < upload.maxRetries) {
        state(context, upload.id).edit().putInt(State.Retries.name, retries + 1).commit()
        return@withContext Result.retry()
      }

      // no more retrying
      eventReporter?.error(upload.id, error)
      clearState(context, upload.id)
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
      eventReporter?.cancelled(upload.id)
      clearState(context, upload.id)
      throw error
    }

    // Auto-cancelled, likely due to unmet constraints
    // Clearing state not needed, worker will restart
    if (error is CancellationException) throw error
  }

  override suspend fun getForegroundInfo(): ForegroundInfo {
    val channelId = "upload-progress"
    val id = upload.notificationId.hashCode()
    val progress = sharedProgress.total()
    val notification = NotificationCompat.Builder(context, channelId)
      .setSmallIcon(android.R.drawable.stat_notify_chat)
      .setOngoing(true)
      .setAutoCancel(false)
      .setContentTitle("Uploading...")
      .setContentText("?%")
      .setProgress(100, progress, false)
      .build()

    return ForegroundInfo(id, notification)
  }
}


private fun state(context: Context, uploadId: String): SharedPreferences {
  // getSharedPreferences just doesn't like "/"
  val parsedUploadId = uploadId.replace("/", "|")
  return context.getSharedPreferences("RNFileUpload-worker-$parsedUploadId", Context.MODE_PRIVATE)
}

private fun clearState(context: Context, uploadId: String) {
  val state = state(context, uploadId)
  state.edit().clear().apply()
}
