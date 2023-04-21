package com.vydia.RNUploader

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vydia.RNUploader.UploaderModule.Companion.eventReporter
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.util.cio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File


private interface Headers : Map<String, String>

private val TypeOfHeaders = object : TypeToken<Collection<Headers>>() {}.type
private val client = HttpClient(CIO)

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  enum class Input { UploadId, Path, Url, Method, Headers, NotificationId, NotificationChannelId }
  enum class Output { Error }

  // inputs
  private val input = inputData
  private val uploadId = input.getString(Input.UploadId.name) ?: throw Throwable("Id is null")
  private val filePath = input.getString(Input.Path.name) ?: throw Throwable("Path is null")
  private val url = input.getString(Input.Url.name) ?: throw Throwable("URL is null")
  private val method = input.getString(Input.Method.name) ?: throw Throwable("Method is null")
  private val headers = input.getString(Input.Headers.name) ?: "{}"


  // Notification inputs
  private val notificationId =
    input.getString(Input.NotificationId.name) ?: throw Throwable("Notification ID is null")
  private val channelId = input.getString(Input.NotificationChannelId.name)
    ?: throw Throwable("Notification Channel ID is null")


  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    setForeground(this@UploadWorker.getForegroundInfo())

    try {
      val httpMethod = HttpMethod.parse(this@UploadWorker.method)
      val body = File(filePath).readChannel()
      val headersMap = Gson().fromJson<Headers>(headers, TypeOfHeaders)

      val response = client.request(url) {
        method = httpMethod
        setBody(body)
        headersMap.forEach { (key, value) -> headers.append(key, value) }
        onUpload { bytesSentTotal, contentLength ->
          eventReporter?.progress(uploadId, bytesSentTotal, contentLength)
        }
      }

      eventReporter?.success(uploadId, response)
      return@withContext Result.success()
    } catch (error: Throwable) {
      val failure = workDataOf(Output.Error.name to error.message)
      return@withContext Result.failure(failure)
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo {
    val notification = NotificationCompat.Builder(applicationContext, channelId).build()
    return ForegroundInfo(notificationId.hashCode(), notification)
  }

}
