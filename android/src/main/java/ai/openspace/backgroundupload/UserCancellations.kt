package ai.openspace.backgroundupload

// Upload ids the JS side explicitly cancelled. Consulted by the worker to
// distinguish user cancels from system kills (WorkManager 2.8.1 has no
// getStopReason). Same-process only: a user cancel always originates from live
// JS, so the set never needs to persist across process death.
object UserCancellations {
  private val ids = mutableSetOf<String>()

  @Synchronized
  fun mark(id: String) {
    ids.add(id)
  }

  @Synchronized
  fun consume(id: String): Boolean = ids.remove(id)
}
