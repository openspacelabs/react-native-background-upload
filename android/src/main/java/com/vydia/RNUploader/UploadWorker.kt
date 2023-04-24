package com.vydia.RNUploader

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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


private interface Headers : Map<String, String>

private const val MAX_CONCURRENCY = 1 // only 1 worker is allowed to do its work at a time
private val semaphore = Semaphore(MAX_CONCURRENCY)
private val TypeOfHeaders = object : TypeToken<Collection<Headers>>() {}.type
private val client = HttpClient(CIO)

class UploadWorker(private val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  enum class Input { UploadId, Path, Url, Method, Headers, MaxRetries, NotificationId, NotificationChannel }
  enum class State { Retries }

  // inputs
  private val input = inputData
  private val uploadId = input.getString(Input.UploadId.name) ?: throw Throwable("Id is null")
  private val filePath = input.getString(Input.Path.name) ?: throw Throwable("Path is null")
  private val url = input.getString(Input.Url.name) ?: throw Throwable("URL is null")
  private val method = input.getString(Input.Method.name) ?: throw Throwable("Method is null")
  private val headers = input.getString(Input.Headers.name) ?: "{}"
  private val maxRetries = input.getInt(Input.MaxRetries.name, 0)


  // Notification inputs
  private val notificationId = input.getString(Input.NotificationId.name)
    ?: throw Throwable("Notification ID is null")
  private val channel = input.getString(Input.NotificationChannel.name)
    ?: throw Throwable("Notification Channel ID is null")


  private val retries = state(context, uploadId).getInt(State.Retries.name, 0)


  @SuppressLint("ApplySharedPref")
  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    // wait until its turn to execute
    semaphore.acquire()
    setForeground(getForegroundInfo())

    try {
      val httpMethod = HttpMethod.parse(method)
      val body = File(filePath).readChannel()
      val headersMap = Gson().fromJson<Headers>(headers, TypeOfHeaders)

      // Use ktor instead of okhttp. Ktor request is coroutine friendly and
      // will get cancelled when the coroutine gets cancelled
      val response = client.request(url) {
        method = httpMethod
        setBody(body)
        headersMap.forEach { (key, value) -> headers.append(key, value) }
        onUpload { bytesSentTotal, contentLength ->
          // progress
          eventReporter?.progress(uploadId, bytesSentTotal, contentLength)
        }
      }

      // success
      eventReporter?.success(uploadId, response)
      clearState(context, uploadId)
      Result.success()
    } catch (error: Throwable) {

      if (error is CancellationException) {
        // cancelled by user or when there's a new worker with the same ID
        if (isStopped) {
          eventReporter?.cancelled(uploadId)
          clearState(context, uploadId)
          return@withContext Result.failure()
        }
        // cancelled automatically, probably due to constraints not met
        else throw error
      }

      // keep retrying
      if (retries < maxRetries) {
        state(context, uploadId).edit().putInt(State.Retries.name, retries + 1).commit()
        return@withContext Result.retry()
      }

      // no more retrying
      eventReporter?.error(uploadId, error)
      clearState(context, uploadId)
      Result.failure()
    } finally {
      // stop waiting
      semaphore.release()
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo {
    val notification = NotificationCompat.Builder(applicationContext, channel).build()
    return ForegroundInfo(notificationId.hashCode(), notification)
  }
}

private fun stateId(uploadId: String) = "RNUpload-worker-$uploadId"

private fun state(context: Context, uploadId: String) =
  context.getSharedPreferences(stateId(uploadId), Context.MODE_PRIVATE)

private fun clearState(context: Context, uploadId: String) {
  val state = state(context, uploadId)
  state.edit().clear().apply()
}
