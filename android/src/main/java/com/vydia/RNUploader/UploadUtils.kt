package com.vydia.RNUploader

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.Headers.Companion.toHeaders
import okhttp3.RequestBody.Companion.asRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.File
import java.io.IOException
import kotlin.coroutines.resumeWithException

// Throttling interval of progress reports
private const val PROGRESS_INTERVAL = 500 // milliseconds


// make an upload request using okhttp
suspend fun okhttpUpload(
  client: OkHttpClient,
  upload: Upload,
  file: File,
  onProgress: (Long) -> Unit
) =
  suspendCancellableCoroutine<Response> { continuation ->
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

      override fun onResponse(call: Call, response: Response) =
        continuation.resumeWith(Result.success(response))
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