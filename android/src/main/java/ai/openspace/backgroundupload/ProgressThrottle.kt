package ai.openspace.backgroundupload

/**
 * Limits progress events per id: at most one per second while the app is in
 * the foreground, one per 10 minutes in the background. A value held back is
 * kept as pending; [flush] sends it (the trailing edge on settle, park,
 * release, and stop).
 */
class ProgressThrottle(
  private val clock: () -> Long = System::currentTimeMillis,
  private val emit: (id: String, sent: Long, total: Long) -> Unit,
) {
  companion object {
    const val FOREGROUND_MS = 1_000L
    const val BACKGROUND_MS = 600_000L
  }

  private class Slot(var lastEmitAt: Long?, var pending: Pair<Long, Long>?)

  private val slots = HashMap<String, Slot>()

  fun offer(id: String, sent: Long, total: Long, foreground: Boolean) {
    val interval = if (foreground) FOREGROUND_MS else BACKGROUND_MS
    val now = clock()
    val send = synchronized(slots) {
      val slot = slots.getOrPut(id) { Slot(null, null) }
      val last = slot.lastEmitAt
      if (last == null || now - last >= interval) {
        slot.lastEmitAt = now
        slot.pending = null
        true
      } else {
        slot.pending = sent to total
        false
      }
    }
    if (send) emit(id, sent, total)
  }

  /** Sends the held-back value once, if there is one. */
  fun flush(id: String) {
    val pending = synchronized(slots) {
      val slot = slots[id] ?: return
      val p = slot.pending ?: return
      slot.pending = null
      slot.lastEmitAt = clock()
      p
    }
    emit(id, pending.first, pending.second)
  }

  fun drop(id: String) {
    synchronized(slots) { slots.remove(id) }
  }
}
