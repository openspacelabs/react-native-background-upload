package ai.openspace.backgroundupload

import java.util.concurrent.ConcurrentHashMap

/**
 * At most one worker EXECUTES per entry id, process-wide (renamed from the
 * v9 ChunkedWorkerGate; both workers use it now).
 *
 * The unique-work chain almost guarantees this, but not across a cancel:
 * cancelUniqueWork marks the row CANCELLED at once while the old worker's
 * coroutine still winds down, and the wake-up work runs under a second
 * unique name. Two concurrent requests for one chunked part are unsafe on
 * the server. A starting worker acquires its id here and a second one waits.
 *
 * Same-process only. A worker in a dead process holds nothing.
 */
object WorkerGate {
  private val holders = ConcurrentHashMap<String, Any>()

  /** True when [token] now holds the id, or already held it. False while another token holds it. */
  fun tryAcquire(id: String, token: Any): Boolean {
    val current = holders.putIfAbsent(id, token)
    return current == null || current === token
  }

  /** Releases only when [token] is the holder, so a late release can not evict a successor. */
  fun release(id: String, token: Any) {
    holders.remove(id, token)
  }

  fun isRunning(id: String): Boolean = holders.containsKey(id)
}
