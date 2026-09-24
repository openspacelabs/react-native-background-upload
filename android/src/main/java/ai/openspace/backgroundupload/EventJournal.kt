package ai.openspace.backgroundupload

import android.content.Context
import com.facebook.react.bridge.WritableMap
import com.google.gson.Gson
import java.io.File

/**
 * The durable record of settled outcomes (completed, error, cancelled). A
 * record is written BEFORE the outcome is emitted to JS and deleted only when
 * JS acknowledges it. One JSON file per record, `<eventId>.json`, written
 * with tmp + fsync + rename, so a crash mid-write can not corrupt another
 * record.
 *
 * [maxEntries] is a runaway guard: if nothing ever acknowledges, the oldest
 * records are dropped. It only fires in that broken case.
 */
class EventJournal(private val dir: File, private val maxEntries: Int = MAX_ENTRIES) {

  /** RawResponse. [status] is null for a chunked completion. */
  data class Response(
    val status: Int?,
    val headers: Map<String, String>?,
    val body: String?,
    val bodyTruncated: Boolean,
  ) {
    fun toMap(): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
      status?.let { put("status", it.toDouble()) }
      headers?.let { put("headers", it) }
      body?.let { put("body", it) }
      put("bodyTruncated", bodyTruncated)
    }

    companion object {
      fun of(response: UploadResponse): Response {
        val (body, truncated) = capBody(response.body)
        return Response(response.code, response.headers, body, truncated)
      }

      /** A chunked completion: N parts, no one response. */
      val NONE = Response(null, null, null, false)
    }
  }

  /** One settled outcome, in the SettledEvent shape plus [generation]. */
  data class SettledRecord(
    val eventId: String,
    val id: String,
    val key: String,
    val varsJson: String,
    val at: Long,
    val attempts: Int,
    val requestId: String?,
    /**
     * How many times this outcome reached JS: 1 after a live emit, 0 when it
     * was journaled with JS dead; +1 per later delivery (replay, re-emit).
     */
    val deliveries: Int,
    /** The entry state this outcome puts it in. */
    val state: String,
    val bytesSent: Long,
    val totalBytes: Long,
    val url: String,
    val method: String,
    val partIndex: Int?,
    /** completed | error | cancelled */
    val kind: String,
    /** completed; also error with errorKind http. */
    val response: Response?,
    val errorKind: String?,
    val message: String?,
    val cancelReason: String?,
    /** The entry life this belongs to (same-id rule 6). */
    val generation: Int,
  ) {
    fun toMap(): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
      put("eventId", eventId)
      put("id", id)
      put("key", key)
      put("vars", runCatching { JsonBridge.parse(varsJson) }.getOrNull())
      put("at", at.toDouble())
      put("attempts", attempts.toDouble())
      requestId?.let { put("requestId", it) }
      put("deliveries", deliveries.toDouble())
      put("state", state)
      put("bytesSent", bytesSent.toDouble())
      put("totalBytes", totalBytes.toDouble())
      put("url", url)
      put("method", method)
      partIndex?.let { put("partIndex", it.toDouble()) }
      put("kind", kind)
      when (kind) {
        KIND_COMPLETED -> put("response", (response ?: Response.NONE).toMap())
        KIND_ERROR -> put("error", LinkedHashMap<String, Any?>().apply {
          put("errorKind", errorKind ?: "unknown")
          put("message", message ?: "")
          response?.let { put("response", it.toMap()) }
          partIndex?.let { put("partIndex", it.toDouble()) }
        })
        KIND_CANCELLED -> put("cancelReason", cancelReason ?: "user")
      }
    }

    fun toWritableMap(): WritableMap = JsonBridge.toWritableMap(toMap())
  }

  companion object {
    const val KIND_COMPLETED = "completed"
    const val KIND_ERROR = "error"
    const val KIND_CANCELLED = "cancelled"

    /** 1 MB, the RawResponse cap. */
    const val MAX_BODY_CHARS = 1_048_576
    const val MAX_ENTRIES = 1000
    private val gson = Gson()

    // Event ids are UUIDs that native mints. ackEvents takes ids from JS, and
    // an id is a file name here, so anything else is ignored.
    private val EVENT_ID = Regex("^[A-Za-z0-9-]{1,64}$")

    fun isValidEventId(id: String) = EVENT_ID.matches(id)

    /**
     * A char-count cap. It is not byte-exact: a cut on a byte boundary could
     * split a surrogate pair. Returns the body and whether it was cut.
     */
    fun capBody(body: String?, max: Int = MAX_BODY_CHARS): Pair<String?, Boolean> =
      if (body != null && body.length > max) body.substring(0, max) to true else body to false

    @Volatile
    private var instance: EventJournal? = null

    /** v10 records live in `rnbgupload-settled`. The v9 `rnbgupload-events` is read once by [LegacyImport]. */
    fun get(context: Context): EventJournal =
      instance ?: synchronized(this) {
        instance ?: EventJournal(File(context.filesDir, "rnbgupload-settled")).also { instance = it }
      }
  }

  init {
    dir.mkdirs()
  }

  private fun fileFor(eventId: String) = File(dir, "$eventId.json")

  /**
   * Never throws. The worker calls this right after the server accepted the
   * request. A thrown IOException would look like a transient failure and
   * re-send the request. Losing one record is the lesser harm, so a failure
   * returns false and the caller goes on.
   */
  @Synchronized
  fun append(record: SettledRecord): Boolean {
    val bounded = record.response?.let { r ->
      val (body, truncated) = capBody(r.body)
      if (truncated) record.copy(response = r.copy(body = body, bodyTruncated = true)) else record
    } ?: record
    val written = try {
      AtomicFiles.writeText(fileFor(record.eventId), gson.toJson(bounded))
      true
    } catch (t: Throwable) {
      Diag.error("journal append failed for ${record.eventId}", t)
      false
    }
    pruneToMax()
    return written
  }

  // Prunes by file time (no parsing). Guarded for the same reason as append.
  private fun pruneToMax() {
    try {
      dir.listFiles { f -> f.name.endsWith(AtomicFiles.TMP_SUFFIX) }?.forEach { it.delete() }
      val files = dir.listFiles { f -> f.extension == "json" } ?: return
      if (files.size <= maxEntries) return
      files.sortedBy { it.lastModified() }.take(files.size - maxEntries).forEach { it.delete() }
    } catch (t: Throwable) {
      Diag.error("journal prune failed", t)
    }
  }

  /** Every record, oldest first. Corrupt files are skipped. */
  @Synchronized
  fun unacknowledged(): List<SettledRecord> =
    (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
      .mapNotNull { read(it) }
      .sortedBy { it.at }

  @Synchronized
  fun find(eventId: String): SettledRecord? =
    if (isValidEventId(eventId)) read(fileFor(eventId)) else null

  @Synchronized
  fun forEntry(id: String): List<SettledRecord> = unacknowledged().filter { it.id == id }

  /**
   * One more delivery of [eventId]: rewrites the record with deliveries + 1
   * and returns it. Null when the record is gone. When the rewrite fails the
   * incremented record is still returned, so the delivery goes ahead.
   */
  @Synchronized
  fun incrementDeliveries(eventId: String): SettledRecord? {
    val record = find(eventId) ?: return null
    val next = record.copy(deliveries = record.deliveries + 1)
    try {
      AtomicFiles.writeText(fileFor(eventId), gson.toJson(next))
    } catch (t: Throwable) {
      Diag.error("journal deliveries update failed for $eventId", t)
    }
    return next
  }

  /** Idempotent. Unknown and malformed ids are ignored. */
  @Synchronized
  fun ack(eventIds: List<String>) {
    eventIds.filter { isValidEventId(it) }.forEach { fileFor(it).delete() }
  }

  @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
  private fun read(file: File): SettledRecord? {
    if (!file.exists()) return null
    val r = runCatching { gson.fromJson(file.readText(), SettledRecord::class.java) }.getOrNull()
      ?: return null
    // Gson does not run constructors. Check every field JS relies on.
    if (r.eventId == null || r.id == null || r.key == null || r.kind == null || r.state == null) return null
    return r.copy(
      varsJson = r.varsJson ?: "null",
      url = r.url ?: "",
      method = r.method ?: "POST",
      deliveries = r.deliveries.coerceAtLeast(0),
    )
  }
}
