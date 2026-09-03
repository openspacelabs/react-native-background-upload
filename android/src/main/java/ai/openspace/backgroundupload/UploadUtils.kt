package ai.openspace.backgroundupload

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType
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
import java.io.RandomAccessFile
import kotlin.coroutines.resumeWithException

// Throttling interval of progress reports
private const val PROGRESS_INTERVAL = 500 // milliseconds

private const val RANGE_COPY_BUFFER = 64 * 1024

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
): UploadResponse {
  val request = Request.Builder()
    .url(upload.url)
    .headers(upload.headers.toHeaders())
    .method(upload.method, withProgressListener(file.asRequestBody(), throttled(onProgress)))
    .build()
  return awaitResponse(client, request)
}

/**
 * PUTs one byte range of the source file: a chunked part. It streams straight
 * from disk, with no temporary chunk file. The headers are the consumer's,
 * unchanged. The library adds nothing, per the design's protocol-as-data rule.
 */
suspend fun okhttpUploadPart(
  client: OkHttpClient,
  part: ChunkedManifest.Part,
  file: File,
  onProgress: (Long) -> Unit
): UploadResponse {
  val request = Request.Builder()
    .url(part.url)
    .headers(part.headers.toHeaders())
    .put(withProgressListener(rangeRequestBody(file, part.start, part.end), throttled(onProgress)))
    .build()
  return awaitResponse(client, request)
}

private suspend fun awaitResponse(client: OkHttpClient, request: Request): UploadResponse =
  suspendCancellableCoroutine { continuation ->
    val call = client.newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) =
        continuation.resumeWithException(e)

      override fun onResponse(call: Call, response: Response) {
        val result = response.use { res -> // close the response asap
          UploadResponse(
            res.code,
            // The body, unchanged: an empty body stays empty. A substituted
            // HTTP reason phrase would make accept `bodyIncludes` rules match
            // text that the server never sent. iOS also reports the body
            // as-is.
            res.body?.string().orEmpty(),
            res.headers.toMultimap().mapValues { it.value.joinToString(", ") }
          )
        }

        continuation.resumeWith(Result.success(result))
      }
    })
  }

private fun throttled(onProgress: (Long) -> Unit): (Long) -> Unit {
  var lastProgressReport = 0L
  return { progress ->
    val now = System.currentTimeMillis()
    if (now - lastProgressReport >= PROGRESS_INTERVAL) {
      lastProgressReport = now
      onProgress(progress)
    }
  }
}

/**
 * Streams the file bytes [start, end) as a request body. A RandomAccessFile
 * backs it, opened fresh on every writeTo call. OkHttp can replay a body (for
 * example, after a connection-level retry), and a one-shot stream would then
 * send truncated data silently.
 */
private fun rangeRequestBody(file: File, start: Long, end: Long) = object : RequestBody() {
  // Null, so no Content-Type is invented. The consumer's header is already on
  // the request, unchanged.
  override fun contentType(): MediaType? = null

  override fun contentLength() = end - start

  override fun writeTo(sink: BufferedSink) {
    RandomAccessFile(file, "r").use { raf ->
      raf.seek(start)
      val buffer = ByteArray(RANGE_COPY_BUFFER)
      var remaining = end - start
      while (remaining > 0L) {
        val read = raf.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
        if (read < 0) throw IOException(
          "source file ended before part range [$start, $end): ${file.path}",
        )
        sink.write(buffer, 0, read)
        remaining -= read
      }
    }
  }
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
