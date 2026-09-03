package ai.openspace.backgroundupload

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The pure scheduling half of chunked execution: the window, the retry
 * policy, and the backoff. It is kept free of Android and OkHttp types. Thus
 * the highest-consequence invariants (at most WINDOW parts in flight, and
 * never two requests for one part index) are unit-testable on a plain JVM.
 * [ChunkedUploadWorker] supplies the part executor.
 */
object ChunkedEngine {

  // The number of parts of one upload in flight at one time. This is a library
  // constant, not an option. If soak data shows that a different value is
  // better, this constant changes, not the API.
  const val WINDOW = 3

  // The library retries a non-accepted, non-transient HTTP response this many
  // times per part. Then the response becomes a terminal error and stalls the
  // upload. The budget is small on purpose. A response that the server repeats
  // (401, 400) does not change without a new startUpload. Only transient
  // failures retry without a limit.
  const val PART_HTTP_RETRIES = 3

  // The poll interval while the network is unusable (offline, or waiting for
  // wifi). The interval is constant, not exponential. We wait for conditions
  // here; we do not back off a server. And expiresAt bounds the total wait.
  const val CONNECTIVITY_POLL_MS = 10_000L

  private const val BACKOFF_BASE_MS = 1_000L
  private const val BACKOFF_CAP_MS = 60_000L

  // A 5xx means that the server failed, not that the request is wrong. Thus it
  // retries like a transport failure: without a limit, until expiresAt.
  fun isTransientHttp(code: Int) = code in 500..599

  /** What a starting worker must do for the manifest that it finds (or does not find). */
  enum class StartAction {
    /**
     * No manifest exists. The upload was completed and acknowledged, or it was
     * explicitly removed, while this run sat in the queue. Both are legitimate
     * ends, already reported (or deliberately not reported). Exit with success
     * and in silence. A journaled terminal here would be a spurious 'file'
     * error for an upload that nobody owns any more.
     */
    NO_MANIFEST,

    /**
     * Every part is already accepted: this is a trailing resume run. Re-report
     * the journaled completion (never mint a second terminal event) and stop.
     * Start no foreground service and no transfers.
     */
    ALREADY_COMPLETE,

    /** Pending parts remain. Run the engine. */
    RUN,
  }

  fun startAction(manifest: ChunkedManifest?): StartAction = when {
    manifest == null -> StartAction.NO_MANIFEST
    manifest.allAccepted -> StartAction.ALREADY_COMPLETE
    else -> StartAction.RUN
  }

  /** How a run that found (or produced) an all-accepted manifest reports the completion. */
  sealed class CompletionReport {
    /** An unacknowledged 'completed' entry exists. Re-emit it. Never mint a second entry. */
    data class ReEmit(val entry: EventJournal.Entry) : CompletionReport()

    /** A fresh completion with no journal entry yet. Journal and emit a new entry. */
    object Mint : CompletionReport()

    /**
     * A trailing run with nothing unacknowledged: the completion was journaled
     * AND acknowledged. Nobody is owed an event. This occurs when the trailing
     * run races ackEvents, which deletes the journal entry just before the
     * manifest. An event minted here would be a duplicate 'completed' for an
     * upload that the consumer already settled.
     */
    object None : CompletionReport()
  }

  fun completionReport(
    unacked: List<EventJournal.Entry>,
    uploadId: String,
    freshCompletion: Boolean,
  ): CompletionReport {
    val existing = unacked.firstOrNull { it.uploadId == uploadId && it.type == "completed" }
    return when {
      existing != null -> CompletionReport.ReEmit(existing)
      freshCompletion -> CompletionReport.Mint
      else -> CompletionReport.None
    }
  }

  /** Exponential backoff for transient failures: 1s, 2s, 4s, and more, capped at 60s. */
  fun backoffMs(attempt: Int): Long =
    (BACKOFF_BASE_MS shl (attempt - 1).coerceIn(0, 6)).coerceAtMost(BACKOFF_CAP_MS)

  /**
   * Runs [executePart] exactly one time per index, with at most [window] parts
   * at one time. One coroutine per part index is what guarantees that no two
   * requests for the same part are in flight (concurrent PUTs of one partNum
   * are verified unsafe on the server side). An executor that throws cancels
   * the remaining parts, and the error propagates. Terminal classification is
   * the caller's job.
   */
  suspend fun run(
    partIndexes: List<Int>,
    window: Int = WINDOW,
    executePart: suspend (Int) -> Unit,
  ) {
    val gate = Semaphore(window)
    coroutineScope {
      for (index in partIndexes) {
        launch { gate.withPermit { executePart(index) } }
      }
    }
  }
}
