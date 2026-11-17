package ai.openspace.backgroundupload

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// The HTTP client and the library-wide transmission gate. The simple and
// chunked workers share them. Thus "requests transmitting at one time" means
// one thing across all uploads.

// OkHttp's defaults for the three timeouts below are 10 s each. 10 s of
// silence is too short: a server that takes longer to answer still stores
// the request, the phone sends it again, and the server can store it twice.

// The time to open a TCP connection.
private const val CONNECT_TIMEOUT_SECONDS = 30L

// The longest silence while reading. This includes the wait for the server's
// answer after the last body byte. 60 s matches iOS's default request timeout.
// A dead connection is found after this time, and holds its transfer slot
// until then.
private const val READ_TIMEOUT_SECONDS = 60L

// The longest a single write of the body may block.
private const val WRITE_TIMEOUT_SECONDS = 60L

// A cap on the whole call: DNS, connect, the body, the server's work, and the
// response. OkHttp counts any retries it makes on its own inside the same cap.
// It is not a silence limit; the read and write timeouts above are. 24 h lets
// a large body finish on a slow network and still ends a call that never
// finishes. When it expires, the attempt fails and the retry rules apply.
private const val CALL_TIMEOUT_HOURS = 24L

// The number of requests transmitting at one time across ALL uploads, chunked
// parts included. This is the design's library-wide cap of 4. Every request,
// a simple upload or a chunked part, must pass this semaphore. Thus on
// Android the cap is hard. A semaphore controls this, not OkHttp's connection
// limits, because those limits add a delay between requests.
internal const val MAX_TRANSFER_CONCURRENCY = 4

// The slots of that cap that only a high-priority request may use. A small
// request, such as a session's JSON, then never waits behind a backlog of
// file parts.
internal const val HIGH_PRIORITY_RESERVED = 1

/**
 * The library-wide cap of [total] requests transmitting at one time, with
 * [reserved] of them kept for high-priority requests. A normal request takes
 * a lane permit, then a slot, so normal requests hold at most total -
 * reserved slots. A high-priority request takes any free slot.
 */
internal class TransferGate(total: Int, reserved: Int) {
  private val slots = Semaphore(total)
  private val normalLane = Semaphore(total - reserved)

  val availableSlots: Int
    get() = slots.availablePermits

  suspend fun <T> withPermit(highPriority: Boolean, block: suspend () -> T): T =
    if (highPriority) {
      slots.withPermit { block() }
    } else {
      normalLane.withPermit { slots.withPermit { block() } }
    }
}

internal val transferGate = TransferGate(MAX_TRANSFER_CONCURRENCY, HIGH_PRIORITY_RESERVED)

// Use Okhttp as it provides the most standard behaviors even though it's not coroutine friendly
internal val uploadHttpClient = OkHttpClient.Builder()
  .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
  .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
  .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
  .callTimeout(CALL_TIMEOUT_HOURS, TimeUnit.HOURS)
  .build()
