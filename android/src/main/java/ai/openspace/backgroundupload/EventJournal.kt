package ai.openspace.backgroundupload

import android.content.Context
import com.google.gson.Gson
import java.io.File

// Durable record of terminal upload events (completed / error / cancelled).
// Written BEFORE the event is emitted to JS; deleted only when JS acknowledges.
// One JSON file per event named <eventId>.json — tmp+rename keeps each write
// self-contained so a crash mid-append can never corrupt other entries.
//
// `maxEntries` is a runaway guard: the design assumes JS drains the journal via
// ack() on every boot, but if that loop breaks (or a consumer hasn't adopted it
// yet) the directory would grow without bound. When exceeded we drop the OLDEST
// entries. Set high enough that legitimate heavy offline use won't hit it — this
// only fires in the pathological "nothing ever acks" case.
class EventJournal(
  private val dir: File,
  private val maxEntries: Int = MAX_ENTRIES,
) {

  data class Entry(
    val eventId: String,
    val uploadId: String,
    val type: String, // completed | error | cancelled
    val timestamp: Long,
    val responseCode: Int? = null,
    val responseBody: String? = null,
    val responseBodyTruncated: Boolean = false,
    val responseHeaders: Map<String, String>? = null,
    val error: String? = null,
    val errorKind: String? = null, // http | network | file | expired | unknown
    val cancelReason: String? = null, // user | system
    // Chunked uploads: the index of the failing part, when one part's response
    // caused the error.
    val partIndex: Int? = null,
  ) {
    fun toWritableMap(): com.facebook.react.bridge.WritableMap =
      com.facebook.react.bridge.Arguments.createMap().apply {
        putString("eventId", eventId)
        putString("id", uploadId)
        putString("type", type)
        putDouble("timestamp", timestamp.toDouble())
        responseCode?.let { putInt("responseCode", it) }
        responseBody?.let { putString("responseBody", it) }
        if (responseBodyTruncated) putBoolean("responseBodyTruncated", true)
        responseHeaders?.let {
          putMap("responseHeaders", com.facebook.react.bridge.Arguments.makeNativeMap(it))
        }
        error?.let { putString("error", it) }
        errorKind?.let { putString("errorKind", it) }
        cancelReason?.let { putString("cancelReason", it) }
        partIndex?.let { putInt("partIndex", it) }
      }
  }

  companion object {
    const val MAX_BODY_CHARS = 64 * 1024
    const val MAX_ENTRIES = 1000
    private val gson = Gson()

    // Char-count cap (not byte-accurate: splitting on a byte boundary risks
    // cutting a surrogate pair; a slightly loose cap is fine as a safety limit).
    // Returns the (possibly truncated) body and whether truncation occurred.
    // Single source of truth so the journaled copy and the live-emitted copy match.
    fun capBody(body: String?): Pair<String?, Boolean> =
      if (body != null && body.length > MAX_BODY_CHARS)
        body.substring(0, MAX_BODY_CHARS) to true
      else body to false

    @Volatile
    private var instance: EventJournal? = null

    // The worker may run in a process where React never initialized, so the
    // journal must be reachable from a bare Context, not the module.
    fun get(context: Context): EventJournal =
      instance ?: synchronized(this) {
        instance
          ?: EventJournal(File(context.filesDir, "rnbgupload-events")).also { instance = it }
      }
  }

  init {
    dir.mkdirs()
  }

  @Synchronized
  fun append(entry: Entry) {
    // Defensive cap in case a caller didn't pre-cap; idempotent when it did.
    val (body, truncated) = capBody(entry.responseBody)
    val bounded =
      if (truncated) entry.copy(responseBody = body, responseBodyTruncated = true) else entry
    // A journal write must NEVER throw into the caller. The worker calls this
    // right after a successful upload; a propagated IOException (e.g. disk full)
    // would be classified as a retryable error and re-run the upload, sending
    // duplicate data to the server. Losing one journal entry is the lesser evil.
    try {
      val tmp = File(dir, "${entry.eventId}.tmp")
      tmp.writeText(gson.toJson(bounded))
      tmp.renameTo(File(dir, "${entry.eventId}.json"))
    } catch (t: Throwable) {
      t.printStackTrace()
      return
    }
    pruneToMax()
  }

  // Keep the directory bounded. Prune by file modification time (no parsing)
  // rather than the entry's own timestamp — cheaper, and close enough since a
  // file's mtime is when it was journaled. Guarded: a prune failure must not
  // propagate for the same reason append() must not.
  private fun pruneToMax() {
    try {
      // Sweep orphaned .tmp files (writeText succeeded but rename failed).
      dir.listFiles { f -> f.extension == "tmp" }?.forEach { it.delete() }
      val files = dir.listFiles { f -> f.extension == "json" } ?: return
      if (files.size <= maxEntries) return
      files.sortedBy { it.lastModified() }
        .take(files.size - maxEntries)
        .forEach { it.delete() }
    } catch (t: Throwable) {
      t.printStackTrace()
    }
  }

  @Synchronized
  @Suppress("SENSELESS_COMPARISON") // Gson can inject null into a non-null field
  fun unacknowledged(): List<Entry> =
    (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
      .mapNotNull { f ->
        runCatching { gson.fromJson(f.readText(), Entry::class.java) }.getOrNull()
      }
      // Gson bypasses the constructor, so a file missing a field yields null
      // despite the non-null Kotlin type. Check every field JS relies on being
      // present, not just eventId — an entry reaching JS with a null `type`
      // would fall silently through a `switch (event.type)`.
      .filter { it.eventId != null && it.uploadId != null && it.type != null }
      .sortedBy { it.timestamp }

  @Synchronized
  fun ack(eventIds: List<String>) {
    eventIds.forEach { File(dir, "$it.json").delete() }
  }
}
