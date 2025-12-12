package com.vydia.RNUploader

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

data class UploadResponse(
  val code: Int,
  val body: String,
  val headers: Map<String, String>
)

// make an upload request using okhttp
suspend fun okhttpUpload(
  client: OkHttpClient,
  upload: Upload,
  file: File,
  onProgress: (Long) -> Unit
) =
  suspendCancellableCoroutine { continuation ->
    val requestBody = file.asRequestBody()
    var lastProgressReport = 0L
    fun throttled(): Boolean {
      val now = System.currentTimeMillis()
      if (now - lastProgressReport < PROGRESS_INTERVAL) return true
      lastProgressReport = now
      return false
    }

    val request = Request.Builder()
      .url(upload.url)
      .headers(upload.headers.toHeaders())
      .method(upload.method, withProgressListener(requestBody) { progress ->
        if (!throttled()) onProgress(progress)
      })
      .build()

    val call = client.newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) =
        continuation.resumeWithException(e)

      override fun onResponse(call: Call, response: Response) {
        val result = response.use { res -> // close the response asap
          UploadResponse(
            res.code,
            res.body?.string()?.takeIf { str -> str.isNotEmpty() } ?: res.message,
            res.headers.toMultimap().mapValues { it.value.joinToString(", ") }
          )
        }

        continuation.resumeWith(Result.success(result))
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