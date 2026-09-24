package ai.openspace.backgroundupload

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The pure scheduling half of chunked execution: the window. It has no
 * Android or OkHttp types, so its two invariants (at most WINDOW parts in
 * flight, never two requests for one part index) are unit-testable on a
 * plain JVM. The retry decisions moved to [RetryClassifier] in v10.
 */
object ChunkedEngine {

  // Parts of one upload in flight at one time. A library constant, not an option.
  const val WINDOW = 3

  /**
   * Runs [executePart] exactly one time per index, with at most [window]
   * parts at one time. One coroutine per index is what guarantees no two
   * requests for one part are in flight (concurrent PUTs of one partNum are
   * unsafe on the server). An executor that throws cancels the other parts,
   * and the error propagates.
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
