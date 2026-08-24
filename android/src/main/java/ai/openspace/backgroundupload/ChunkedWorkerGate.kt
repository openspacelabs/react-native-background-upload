package ai.openspace.backgroundupload

import java.util.concurrent.ConcurrentHashMap

/**
 * At most one [ChunkedUploadWorker] EXECUTES per upload id, process-wide.
 *
 * The unique-work chain almost guarantees this, but not across a cancel.
 * cancelUniqueWork marks the row CANCELLED immediately, while the cancelled
 * worker's coroutine still winds down. Thus a startUpload that arrives right
 * after a cancelUpload can enqueue (and start) a replacement worker while the
 * old worker still has a part PUT in flight. Two concurrent PUTs of one
 * partNum are verified unsafe on the server side. A starting worker acquires
 * its id here, and a successor waits for the release.
 *
 * This is also the truthful "is this upload running" for the recreate rule.
 * A worker registers before its first manifest read, and it releases in a
 * finally block. WorkManager's row state stays RUNNING for a moment after
 * doWork returns. This gate does not: it never reports a finished run as
 * running.
 *
 * The gate is same-process only, like [UserCancellations]. A worker in a dead
 * process holds nothing, and WorkManager runs our workers in the app process.
 */
object ChunkedWorkerGate {
  private val holders = ConcurrentHashMap<String, Any>()

  /** True when [token] now holds the id, or already held it. False while another token holds it. */
  fun tryAcquire(id: String, token: Any): Boolean {
    val current = holders.putIfAbsent(id, token)
    return current == null || current === token
  }

  /** Releases only when [token] is the holder. Thus a stale release cannot evict a successor. */
  fun release(id: String, token: Any) {
    holders.remove(id, token)
  }

  fun isRunning(id: String): Boolean = holders.containsKey(id)
}
