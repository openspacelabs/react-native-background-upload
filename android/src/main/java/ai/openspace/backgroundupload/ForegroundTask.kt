package ai.openspace.backgroundupload

import java.util.concurrent.Executor

/**
 * What the module does when the app comes to the foreground, off the UI
 * thread, on [executor] (the queue executor, so it never overlaps another
 * queue call). First a settings write that failed earlier is tried again,
 * then [retryNow] ([QueueController.retryNow]). Neither step throws on the
 * executor.
 *
 * The host's resume event fires on every Activity resume, also after a
 * permission dialog, a picker or a share sheet. So [retryNow] runs at most
 * once per [MIN_INTERVAL_MS]; the settings save runs every time.
 */
internal class ForegroundTask(
  private val executor: Executor,
  private val saveSettings: () -> Unit,
  private val retryNow: () -> Unit,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  companion object {
    const val MIN_INTERVAL_MS = 30_000L
  }

  // Read and written only on [executor], which runs one task at a time.
  private var lastRetryAt: Long? = null

  fun onForeground() {
    executor.execute {
      runCatching(saveSettings).onFailure { Diag.error("could not save the settings on return to the foreground", it) }
      val now = clock()
      val last = lastRetryAt
      if (last == null || now - last >= MIN_INTERVAL_MS) {
        lastRetryAt = now
        runCatching(retryNow).onFailure { Diag.error("retryNow on return to the foreground failed", it) }
      }
    }
  }
}
