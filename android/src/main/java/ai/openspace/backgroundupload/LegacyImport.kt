package ai.openspace.backgroundupload

import android.content.Context
import com.google.gson.Gson
import java.io.File

/**
 * First v10 launch. Each v9 journal entry becomes a read-only settled row
 * with key `legacy` and the v9 upload id. Nothing is delivered; the app
 * reads the rows with getRequests() and cancels them. v9 chunked manifests
 * are left in place: a same-id enqueue adopts them.
 *
 * The caller cancels the v9 WorkManager rows after this returns true. The
 * order is safe: under v10 code a v9 row exits at once (it has no entry
 * id), so nothing can write a v9 journal file after the import.
 *
 * Runs once, guarded by a marker file. A failed row save leaves the marker
 * unwritten, so the next launch tries again.
 */
object LegacyImport {
  const val MARKER = "v9-imported"
  const val KEY = "legacy"
  const val V9_JOURNAL_DIR = "rnbgupload-events"

  /** The v9 journal Entry, every field nullable: Gson reads whatever is there. */
  data class V9Entry(
    val eventId: String?,
    val uploadId: String?,
    val type: String?,
    val timestamp: Long?,
  )

  private val gson = Gson()

  /** Returns true when the import ran at this launch (no marker yet). */
  fun runOnce(context: Context, store: QueueStore): Boolean {
    val marker = File(QueueStore.rootDir(context), MARKER)
    if (marker.exists()) return false
    val complete = import(File(context.filesDir, V9_JOURNAL_DIR), store)
    if (complete) {
      runCatching { AtomicFiles.writeText(marker, "1") }
        .onFailure { Diag.error("could not write the v9 import marker", it) }
    }
    return true
  }

  /**
   * Imports every v9 journal entry in [v9Dir]. The newest entry per upload
   * id wins. Returns false when a row could not be saved (its file is kept).
   */
  internal fun import(v9Dir: File, store: QueueStore): Boolean {
    val files = v9Dir.listFiles { f -> f.extension == "json" } ?: return true
    val read = files.mapNotNull { f ->
      val entry = runCatching { gson.fromJson(f.readText(), V9Entry::class.java) }.getOrNull()
      if (entry == null) {
        Diag.warn("v9 journal file unreadable, skipped: ${f.name}")
        f.delete()
        null
      } else f to entry
    }
    var complete = true
    read.groupBy { it.second.uploadId }.forEach { (uploadId, group) ->
      val newest = group.maxByOrNull { it.second.timestamp ?: 0L }!!.second
      val row = if (uploadId == null) null else legacyRow(newest)
      val saved = when {
        row == null -> true
        store.load(row.id) != null -> true // a v10 entry already owns the id
        else -> runCatching { store.save(row) }.isSuccess
      }
      if (saved) group.forEach { it.first.delete() } else complete = false
    }
    return complete
  }

  internal fun legacyRow(entry: V9Entry): QueueEntry? {
    val id = entry.uploadId ?: return null
    val state = when (entry.type) {
      "completed" -> EntryState.COMPLETED
      "error" -> EntryState.ERROR
      "cancelled" -> EntryState.CANCELLED
      else -> return null
    }
    val at = entry.timestamp ?: 0L
    return QueueEntry(
      id = id,
      key = KEY,
      varsJson = "null",
      descriptor = null,
      body = null,
      state = state,
      attempts = 0,
      bytesSent = 0,
      totalBytes = 0,
      expiresAt = at,
      createdAt = at,
      updatedAt = at,
      generation = 1,
      legacy = true,
    )
  }
}
