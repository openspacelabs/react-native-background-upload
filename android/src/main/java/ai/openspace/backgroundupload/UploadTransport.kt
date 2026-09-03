package ai.openspace.backgroundupload

import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// The HTTP client and the library-wide transmission gate. The simple and
// chunked workers share them. Thus "requests transmitting at one time" means
// one thing across all uploads.

// Max total time for a single request to complete
// This is 24hrs so plenty of time for large uploads
// Worst case is the time maxes out and the upload gets restarted.
// Not using unlimited time to prevent unexpected behaviors.
private const val REQUEST_TIMEOUT = 24L
private val REQUEST_TIMEOUT_UNIT = TimeUnit.HOURS

// The number of requests transmitting at one time across ALL uploads, chunked
// parts included. This is the design's library-wide cap of 4. Every request,
// a simple upload or a chunked part, must pass this semaphore. Thus on
// Android the cap is hard. A semaphore controls this, not OkHttp's connection
// limits, because those limits add a delay between requests.
internal const val MAX_TRANSFER_CONCURRENCY = 4
internal val transferSemaphore = Semaphore(MAX_TRANSFER_CONCURRENCY)

// Use Okhttp as it provides the most standard behaviors even though it's not coroutine friendly
internal val uploadHttpClient = OkHttpClient.Builder()
  .callTimeout(REQUEST_TIMEOUT, REQUEST_TIMEOUT_UNIT)
  .build()
