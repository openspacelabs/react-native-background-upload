package com.vydia.RNUploader

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.vydia.RNUploader.UploaderModule.Companion.MAX_CONCURRENCY
import com.vydia.RNUploader.UploaderModule.Companion.eventReporter
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File


// All workers will start `doWork` immediately but only 1 runs at a time.
private val semaphore = Semaphore(MAX_CONCURRENCY)
private val client = HttpClient(CIO)

class UploadWorker(private val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  enum class Input { Params }
  enum class State { Retries }

  private lateinit var upload: Upload

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val httpMethod: HttpMethod
    val body: ByteReadChannel
    val retries: Int
    try {
      val paramsJson = inputData.getString(Input.Params.name) ?: throw Throwable("No Params")
      upload = Gson().fromJson(paramsJson, Upload::class.java)
      retries = state(context, upload.id).getInt(State.Retries.name, 0)
      body = File(upload.path).readChannel()
      httpMethod = HttpMethod.parse(upload.method)
      setForeground(getForegroundInfo())
    } catch (error: Throwable) {
      return@withContext Result.failure()
    }


    // wait until its turn to execute
    semaphore.acquire()


    try {
      // Use ktor instead of okhttp. Ktor request is coroutine friendly and
      // will get cancelled when the coroutine gets cancelled
      val response = client.request(upload.url) {
        method = httpMethod
        setBody(body)
        upload.headers.forEach { (key, value) -> headers.append(key, value) }
        onUpload { bytesSentTotal, contentLength ->
          // progress
          eventReporter?.progress(upload.id, bytesSentTotal, contentLength)
        }
      }

      // success
      eventReporter?.success(upload.id, response)
      clearState(context, upload.id)
      return@withContext Result.success()
    } catch (error: Throwable) {

      if (error is CancellationException) {
        // cancelled by user or when there's a new worker with the same ID
        if (isStopped) {
          eventReporter?.cancelled(upload.id)
          clearState(context, upload.id)
          return@withContext Result.failure()
        }
        // cancelled automatically, probably due to constraints not met
        else throw error
      }

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

  override suspend fun getForegroundInfo(): ForegroundInfo {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val id = upload.notificationId.hashCode()
    val statusBarNotification = manager.activeNotifications.find { it.id == id }
      ?: throw Throwable("No notification found for ${upload.notificationId}")
    return ForegroundInfo(id, statusBarNotification.notification)
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
