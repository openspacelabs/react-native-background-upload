package com.vydia.RNUploader

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.File
import java.io.IOException

class Uploader


class UploadWorker(appContext: Context, workerParams: WorkerParameters) :
  CoroutineWorker(appContext, workerParams) {

  companion object {
    const val INPUT_KEY_PATH = "path"
    const val INPUT_KEY_URL = "url"
    const val INPUT_KEY_METHOD = "method"
    const val INPUT_KEY_NOTIFICATION_CHANNEL_ID = "notificationChannelId"
    const val INPUT_KEY_NOTIFICATION_TITLE = "notificationTitle"
    const val INPUT_KEY_NOTIFICATION_ID = "notificationId"
    private val client = OkHttpClient()
  }


  // OkHttp inputs
  private val filePath = inputData.getString(INPUT_KEY_PATH) ?: throw Throwable("Path is null")
  private val url = inputData.getString(INPUT_KEY_URL) ?: throw Throwable("URL is null")
  private val method = inputData.getString(INPUT_KEY_METHOD) ?: throw Throwable("URL is method")

  // Progress setup
  private val file = File(filePath)
  private val fileRequestBody = file.asRequestBody("application/octet-stream".toMediaType())
  private var bytesWritten = 0L
  private val contentLength = fileRequestBody.contentLength()
  private val requestBody = withProgressListener(fileRequestBody) { newBytes ->
    bytesWritten += newBytes
    bytesWritten * 100 / contentLength
    // TODO Broadcast progress
  }

  // Request creation
  private val request = Request.Builder().url(url).method(method, requestBody).build()

  // Notification inputs
  private val notificationId = inputData.getString(INPUT_KEY_NOTIFICATION_ID)
    ?: throw Throwable("Notification ID is null")
  private val channelId = inputData.getString(INPUT_KEY_NOTIFICATION_CHANNEL_ID)
    ?: throw Throwable("Notification channel ID is null")
  private val title = inputData.getString(INPUT_KEY_NOTIFICATION_TITLE)
    ?: throw Throwable("Notification title is null")


  override suspend fun doWork(): Result =
    withContext(Dispatchers.IO) {
      val response = client.newCall(request).execute()

      if (response.isSuccessful) {
        // TODO broadcast success
        return@withContext Result.success()
      }

      // TODO broadcast failure
      return@withContext Result.failure()
    }

  override suspend fun getForegroundInfo(): ForegroundInfo {
    // Create a Notification channel if necessary
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      createChannel()
    }

    val notification = NotificationCompat.Builder(applicationContext, channelId)
      .setContentTitle(title)
      .setTicker(title)
      .setProgress(100, 0, true) // start out with indeterminate
      .setOngoing(true)
      .build()


    return ForegroundInfo(notificationId.hashCode(), notification)
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private fun createChannel() {
    // TODO Create a Notification channel
  }
}

private fun withProgressListener(
  requestBody: RequestBody,
  progress: (newBytesCount: Long) -> Unit
): RequestBody {
  return object : RequestBody() {
    override fun contentType(): MediaType? = requestBody.contentType()
    override fun contentLength(): Long = requestBody.contentLength()

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
      val progressSink = object : ForwardingSink(sink) {
        override fun write(source: Buffer, byteCount: Long) {
          super.write(source, byteCount)
          progress(byteCount)
        }
      }

      val bufferedSink = progressSink.buffer()
      requestBody.writeTo(bufferedSink)
      bufferedSink.flush()
    }
  }
}

