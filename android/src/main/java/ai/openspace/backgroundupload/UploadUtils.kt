package ai.openspace.backgroundupload

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicReference

// Throttling interval of the raw progress callback. ProgressThrottle limits
// the JS events above this.
private const val PROGRESS_INTERVAL = 500 // milliseconds

private const val RANGE_COPY_BUFFER = 64 * 1024

/** [truncated] when the body passed [BodyCap.SETTLED_MAX_BYTES] and the rest was not read. */
data class UploadResponse(
  val code: Int,
  val body: String,
  val headers: Map<String, String>,
  val truncated: Boolean = false,
)

/** One request as the worker sends it. [body] is null only for GET and DELETE with no body. */
data class TransferRequest(
  val url: String,
  val method: String,
  val headers: Map<String, String>,
  val body: RequestBody?,
  /** Whether the request may use the slots kept for high priority. See [TransferGate]. */
  val highPriority: Boolean = false,
)

/** Sends one request and reports bytes written. The headers are sent as they are. */
suspend fun okhttpSend(
  client: OkHttpClient,
  request: TransferRequest,
  onProgress: (Long) -> Unit,
): UploadResponse {
  val body = request.body?.let { withProgressListener(it, throttled(onProgress)) }
  val built = Request.Builder()
    .url(request.url)
    .headers(request.headers.toHeaders())
    .method(request.method, body)
    .build()
  return awaitResponse(client, built)
}

// Every body has a null content type, so OkHttp does not invent a
// Content-Type. The header on the request (the caller's, or the one staging
// set) is sent unchanged.

/** A whole staged file. */
fun fileBody(file: File): RequestBody = file.asRequestBody(null as MediaType?)

/** A zero-length body for a POST, PUT, or PATCH with no body. OkHttp requires one. */
fun emptyBody(): RequestBody = ByteArray(0).toRequestBody(null)

/**
 * The file bytes [start, end) as a request body: a chunked part. It streams
 * from disk with no temporary chunk file. A RandomAccessFile is opened fresh
 * on every writeTo, because OkHttp can replay a body (a connection-level
 * retry), and a one-shot stream would then send truncated data.
 */
fun rangeRequestBody(file: File, start: Long, end: Long): RequestBody = object : RequestBody() {
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

private suspend fun awaitResponse(client: OkHttpClient, request: Request): UploadResponse {
  val call = client.newCall(request)
  return awaitLanded(
    register = { onResult ->
      call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = onResult(Result.failure(e))

        override fun onResponse(call: Call, response: Response) {
          val result = try {
            response.use { res -> // close the response asap
              // The body unchanged: an empty body stays empty. A substituted
              // reason phrase would make accept `bodyIncludes` rules match text
              // the server never sent. The cap applies while it streams in.
              val body = res.body?.let {
                BodyCap.read(it.source(), BodyCap.SETTLED_MAX_BYTES, it.contentType()?.charset() ?: Charsets.UTF_8)
              }
              UploadResponse(
                res.code,
                body?.text.orEmpty(),
                res.headers.toMultimap().mapValues { it.value.joinToString(", ") },
                body?.truncated ?: false,
              )
            }
          } catch (e: IOException) {
            onResult(Result.failure(e))
            return
          }
          onResult(Result.success(result))
        }
      })
    },
    cancel = { call.cancel() },
  )
}

/**
 * Waits for one callback result and keeps a result that landed before a stop.
 *
 * [register] starts the work and gets the callback for its result. [cancel]
 * stops the work. A stop (WorkManager stops the worker, pause() or cancel()
 * cancels its work) with no result cancels the work and throws.
 *
 * Why: a plain suspendCancellableCoroutine drops a value when the stop comes
 * after the resume and before the coroutine runs again. Its docs say it "will
 * not resume successfully, even if CancellableContinuation.resume was already
 * invoked". For an upload, that throws away a response the server already
 * sent. The entry then goes back to queued and the server gets the request a
 * second time. So the result is saved before the resume, and a stop returns
 * it when it is there.
 *
 * A failure that landed first is thrown as it is (kotlinx delivers it).
 *
 * The caller's job is then cancelled, but plain code still runs. Each caller
 * must reach its save (ops.settle for a simple entry, markAccepted for a part)
 * with no suspension in between, because a suspension on a cancelled job
 * throws. EntryRunTest pins this for both callers.
 */
internal suspend fun <T : Any> awaitLanded(
  register: (onResult: (Result<T>) -> Unit) -> Unit,
  cancel: () -> Unit,
): T {
  val landed = AtomicReference<T?>(null)
  try {
    return suspendCancellableCoroutine { continuation ->
      // Runs at a stop, also after a resume that waits for dispatch. A cancel
      // after the response was read and closed does nothing.
      continuation.invokeOnCancellation { cancel() }
      register { result ->
        result.getOrNull()?.let { landed.set(it) }
        continuation.resumeWith(result)
      }
    }
  } catch (stop: CancellationException) {
    landed.get()?.let { return it }
    throw stop
  }
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

// OkHttp has no built-in progress report, so the body counts bytes as it writes.
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
