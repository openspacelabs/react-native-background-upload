package com.vydia.RNUploader

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.File
import java.io.IOException
import kotlin.coroutines.resumeWithException

// Throttling interval of progress reports
private const val PROGRESS_INTERVAL = 500 // milliseconds

// Data class to hold the important response information
data class UploadResponse(
  val statusCode: Int,
  val statusMessage: String,
  val headers: Map<String, String>,
  val body: String? = null
)

// make an upload request using okhttp
suspend fun okhttpUpload(
  client: OkHttpClient,
  upload: Upload,
  onProgress: (Long) -> Unit,
  isCancelled: () -> Boolean
): UploadResponse =
  suspendCancellableCoroutine { continuation ->
    val requestBody = File(upload.path).asRequestBody()

    // Throttle progress reports
    var lastProgressReport = 0L
    fun throttled(): Boolean {
      val now = System.currentTimeMillis()
      if (now - lastProgressReport < PROGRESS_INTERVAL) return true
      lastProgressReport = now
      return false
    }

    // Build the request
    val request = Request.Builder()
      .url(upload.url)
      .headers(upload.headers.toHeaders())
      .method(upload.method, withProgressListener(requestBody) { progress ->
        if (!throttled()) onProgress(progress)
      })
      .build()

    // Create the call
    val call = client.newCall(request)

    // Start a polling coroutine to check for cancellation
    val cancellationCheck = CoroutineScope(continuation.context).launch {
      while (!call.isCanceled()) {
        if (isCancelled()) {
          call.cancel()
          continuation.resumeWithException(CancellationException("Upload cancelled externally"))
          break
        }
        delay(100) // Poll every 100ms
      }
    }

    // cancel everything if the coroutine is cancelled
    continuation.invokeOnCancellation {
      call.cancel()
      cancellationCheck.cancel()
    }

    // enqueue the call and add the callbacks
    call.enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        cancellationCheck.cancel()
        if (isCancelled()) return
        continuation.resumeWithException(e)
      }

      override fun onResponse(call: Call, response: Response) {
        cancellationCheck.cancel()
        response.use { // Automatically closes the response
          if (isCancelled()) return

          val uploadResponse = UploadResponse(
            statusCode = response.code,
            statusMessage = response.message,
            headers = response.headers.toMap(),
            body = response.body.string().let {
              it.ifBlank { response.message }
            }
          )
          continuation.resumeWith(Result.success(uploadResponse))
        }
      }
    })
  }

// create a request body that allows us to listen to progress.
// okhttp has no built-in way of reporting progress
private fun withProgressListener(
  body: RequestBody,
  onProgress: (Long) -> Unit
) = object : RequestBody() {
  override fun contentType() = body.contentType()
  override fun contentLength() = body.contentLength()
  override fun writeTo(sink: BufferedSink) {
    val countingSink = object : ForwardingSink(sink) {
      var bytesWritten = 0L

      override fun write(source: Buffer, byteCount: Long) {
        super.write(source, byteCount)
        bytesWritten += byteCount
        onProgress(bytesWritten)
      }
    }

    val bufferedSink = countingSink.buffer()
    body.writeTo(bufferedSink)
    bufferedSink.flush()
  }
}

class MissingOptionException(optionName: String) :
  IllegalArgumentException("Missing '$optionName'")


data class Connectivity(val wifi: Boolean, val connected: Boolean)

enum class NotificationConnectivity {
  NoWifi, NoInternet, Ok
}

fun buildNotification(context: Context, connectivity: NotificationConnectivity): Pair<Int, Notification> {
  val progress = UploadQueue.progressPercentage()
  val progress2Decimals = "%.2f".format(progress)
  val title = when (connectivity) {
    NotificationConnectivity.NoWifi -> NotificationConfigs.titleNoWifi
    NotificationConnectivity.NoInternet -> NotificationConfigs.titleNoInternet
    NotificationConnectivity.Ok -> NotificationConfigs.title
  }

  // Custom layout for progress notification.
  // The default hides the % text. This one shows it on the right,
  // like most examples in various docs.
  val content = RemoteViews(context.packageName, R.layout.notification)
  content.setTextViewText(R.id.notification_title, title)
  content.setTextViewText(R.id.notification_progress, "${progress2Decimals}%")
  content.setProgressBar(R.id.notification_progress_bar, 100, progress.toInt(), false)

  val notification = NotificationCompat.Builder(context, NotificationConfigs.channel).run {
    // Starting Android 12, the notification shows up with a confusing delay of 10s.
    // This fixes that delay.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
      foregroundServiceBehavior = Notification.FOREGROUND_SERVICE_IMMEDIATE

    // Required by android. Here we use the system's default upload icon
    setSmallIcon(android.R.drawable.stat_sys_upload)
    // These prevent the notification from being force-dismissed or dismissed when pressed
    setOngoing(true)
    setAutoCancel(false)
    // These help show the same custom content when the notification collapses and expands
    setCustomContentView(content)
    setCustomBigContentView(content)
    // opens the app when the notification is pressed
    setContentIntent(openAppIntent(context))
    build()
  }

  return Pair(NotificationConfigs.id, notification)
}

private fun openAppIntent(context: Context): PendingIntent? {
  val intent = Intent(context, NotificationReceiver::class.java)
  val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  return PendingIntent.getBroadcast(context, "RNFileUpload-notification".hashCode(), intent, flags)
}
