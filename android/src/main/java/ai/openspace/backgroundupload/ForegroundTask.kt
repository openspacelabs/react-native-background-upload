package ai.openspace.backgroundupload

import java.util.concurrent.Executor

/**
 * What the module does when the app comes to the foreground, off the UI
 * thread, on [executor] (the queue executor, so it never overlaps another
 * queue call). First a settings write that failed earlier is tried again,
 * then queued entries stop waiting for their backoff and run now
 * ([QueueController.retryNow]). Neither step throws on the executor.
 */
internal class ForegroundTask(
  private val executor: Executor,
  private val saveSettings: () -> Unit,
  private val retryNow: () -> Unit,
) {
  fun onForeground() {
    executor.execute {
      runCatching(saveSettings).onFailure { Diag.error("could not save the settings on return to the foreground", it) }
      runCatching(retryNow).onFailure { Diag.error("retryNow on return to the foreground failed", it) }
    }
  }
}
