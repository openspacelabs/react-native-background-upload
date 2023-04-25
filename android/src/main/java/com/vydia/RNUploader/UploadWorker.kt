package com.vydia.RNUploader

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import androidx.work.*
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


// All workers will start `doWork` immediately but only 1 runs at a time.
private const val MAX_CONCURRENCY = 1
private val semaphore = Semaphore(MAX_CONCURRENCY)
private val client = HttpClient(CIO)

class UploadWorker(private val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  enum class Input { UploadId, Path, Url, Method, Headers, MaxRetries, NotificationId, NotificationChannel }
  enum class State { Retries }
  private class ParsedInput(input: Data) {
    // http request inputs
    val uploadId = input.getString(Input.UploadId.name) ?: throw Throwable("Id is null")
    val filePath = input.getString(Input.Path.name) ?: throw Throwable("Path is null")
    val url = input.getString(Input.Url.name) ?: throw Throwable("URL is null")
    val method = input.getString(Input.Method.name) ?: throw Throwable("Method is null")
    val headers = input.getString(Input.Headers.name) ?: "{}"
    val maxRetries = input.getInt(Input.MaxRetries.name, 0)

    // notification inputs
    val notificationId = input.getString(Input.NotificationId.name)
      ?: throw Throwable("Notification ID is null")

    // derivatives
    val httpMethod = HttpMethod.parse(method)
    val body = File(filePath).readChannel()
    private val headersType = object : TypeToken<Map<String, String>>() {}.type
    val headersMap: Map<String, String> = Gson().fromJson(headers, headersType)
  }


  private lateinit var input: ParsedInput
  private var retries: Int = 0

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    try {
      input = ParsedInput(inputData)
      retries = state(context, input.uploadId).getInt(State.Retries.name, 0)
      setForeground(getForegroundInfo())
    } catch (error: Throwable) {
      return@withContext Result.failure(workDataOf("error" to error.message))
    }


    // wait until its turn to execute
    semaphore.acquire()


    try {
      // Use ktor instead of okhttp. Ktor request is coroutine friendly and
      // will get cancelled when the coroutine gets cancelled
      val response = client.request(input.url) {
        method = input.httpMethod
        setBody(input.body)
        input.headersMap.forEach { (key, value) -> headers.append(key, value) }
        onUpload { bytesSentTotal, contentLength ->
          // progress
          eventReporter?.progress(input.uploadId, bytesSentTotal, contentLength)
        }
      }

      // success
      eventReporter?.success(input.uploadId, response)
      clearState(context, input.uploadId)
      return@withContext Result.success()
    } catch (error: Throwable) {

      if (error is CancellationException) {
        // cancelled by user or when there's a new worker with the same ID
        if (isStopped) {
          eventReporter?.cancelled(input.uploadId)
          clearState(context, input.uploadId)
          return@withContext Result.failure()
        }
        // cancelled automatically, probably due to constraints not met
        else throw error
      }

      // keep retrying
      if (retries < input.maxRetries) {
        state(context, input.uploadId).edit().putInt(State.Retries.name, retries + 1).commit()
        return@withContext Result.retry()
      }

      // no more retrying
      eventReporter?.error(input.uploadId, error)
      clearState(context, input.uploadId)
      return@withContext Result.failure()
    } finally {
      // stop waiting
      semaphore.release()
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val id = input.notificationId.hashCode()
    val statusBarNotification = manager.activeNotifications.find { it.id == id }
      ?: throw Throwable("No notification found for ${input.notificationId}")
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
