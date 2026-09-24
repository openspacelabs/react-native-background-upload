package ai.openspace.backgroundupload

import java.util.concurrent.ConcurrentHashMap

/**
 * The in-memory rows behind the synchronous getRequests(). [QueueStore]
 * keeps it current: it calls [put] after every save and [remove] after every
 * remove. Progress ticks move [RequestRow.bytesSent] here only.
 */
class RequestIndex {
  companion object {
    val shared = RequestIndex()
  }

  private val rows = ConcurrentHashMap<String, RequestRow>()

  fun replaceAll(all: Collection<RequestRow>) {
    rows.clear()
    all.forEach { rows[it.id] = it }
  }

  /**
   * A save of a running entry keeps the larger bytesSent. The stored value
   * lags the in-memory progress, and a save for an attempt must not move the
   * row backwards.
   */
  fun put(row: RequestRow) {
    rows.compute(row.id) { _, old ->
      if (old != null && old.state == row.state && row.state == EntryState.RUNNING.wire &&
        old.bytesSent > row.bytesSent && old.bytesSent <= row.totalBytes
      ) row.withBytes(old.bytesSent) else row
    }
  }

  fun remove(id: String) {
    rows.remove(id)
  }

  /** Oldest first, then by id. */
  fun snapshot(): List<RequestRow> =
    rows.values.sortedWith(compareBy<RequestRow> { it.createdAt }.thenBy { it.id })

  /** A progress tick. A missing id is ignored. */
  fun setBytes(id: String, bytesSent: Long) {
    rows.computeIfPresent(id) { _, row -> row.withBytes(bytesSent) }
  }
}
