package ai.openspace.backgroundupload

import android.content.Context
import com.facebook.react.bridge.WritableMap
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The durable record of settled outcomes (completed, error, cancelled). A
 * record is written BEFORE the outcome is emitted to JS and deleted only when
 * JS acknowledges it. One JSON file per record, `<eventId>.json`, written
 * with tmp + fsync + rename, so a crash mid-write can not corrupt another
 * record.
 *
 * [maxEntries] is a runaway guard: if nothing ever acknowledges, the oldest
 * records are dropped, except those a row still names. It only fires in
 * that broken case.
 */
class EventJournal(
  private val dir: File,
  private val maxEntries: Int = MAX_ENTRIES,
  /** Runs a task after a delay. Tests pass a manual one. */
  private val retryLater: (delayMs: Long, task: () -> Unit) -> Unit = ::onTimer,
) {

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
        val (body, cut) = BodyCap.cap(response.body, BodyCap.SETTLED_MAX_BYTES)
        return Response(response.code, response.headers, body, response.truncated || cut)
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
     * How many times this outcome reached a JS listener: 1 after a live
     * emit, 0 when it was journaled with no listener; +1 per later delivery
     * (replay, re-emit). [append] sets it.
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

    const val MAX_ENTRIES = 1000

    /** The first wait before a held record is written again. It doubles up to [RETRY_MAX_MS]. */
    const val RETRY_MS = 5_000L
    const val RETRY_MAX_MS = 600_000L
    private val gson = Gson()

    // Event ids are UUIDs that native mints. ackEvents takes ids from JS, and
    // an id is a file name here, so anything else is ignored.
    private val EVENT_ID = Regex("^[A-Za-z0-9-]{1,64}$")

    fun isValidEventId(id: String) = EVENT_ID.matches(id)

    private val timer by lazy {
      Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "RNFileUploader.journal").apply { isDaemon = true }
      }
    }

    private fun onTimer(delayMs: Long, task: () -> Unit) {
      timer.schedule(task, delayMs, TimeUnit.MILLISECONDS)
    }

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

  /**
   * The JS listener, set by [drain] and cleared by [stopListening]. While it
   * is set, a new record starts at 1 delivery and the caller emits it live.
   * While it is null, a record starts at 0 and the next drain delivers it.
   * Both decisions take this object's lock, so a record is either in the
   * drain or emitted live, never both and never neither.
   */
  private var listener: Any? = null

  /**
   * Records whose file write failed, by eventId. They count as journaled in
   * every read and ack, and a timer writes them once the disk allows. They
   * live only in memory: a process death loses them.
   */
  private val held = LinkedHashMap<String, SettledRecord>()
  private var retryScheduled = false

  private fun fileFor(eventId: String) = File(dir, "$eventId.json")

  /**
   * Writes [record] with deliveries 1 when a listener is set, else 0, and
   * returns it as written. The caller emits it only when deliveries > 0.
   * Throws IOException when the write failed; nothing is kept then.
   *
   * [keep] names the records the prune must not delete (every eventId a row
   * names). It runs only when the journal is over its cap, under this lock.
   * Callers hold the store lock (lock order: store, then journal).
   */
  @Synchronized
  fun append(record: SettledRecord, keep: () -> Set<String> = { emptySet() }): SettledRecord {
    val stamped = stamp(record)
    AtomicFiles.writeText(fileFor(stamped.eventId), gson.toJson(stamped))
    pruneToMax(keep)
    return stamped
  }

  /**
   * As [append], but never throws. A failed write holds the record in
   * memory and a timer tries it again. For a worker's settle: the request
   * already ran, and a thrown error would send it again.
   */
  @Synchronized
  fun appendOrHold(record: SettledRecord, keep: () -> Set<String> = { emptySet() }): SettledRecord =
    try {
      append(record, keep)
    } catch (t: Throwable) {
      Diag.error("journal append failed for ${record.eventId}; held in memory", t)
      val stamped = stamp(record)
      held[stamped.eventId] = stamped
      scheduleRetry(RETRY_MS)
      stamped
    }

  private fun stamp(record: SettledRecord): SettledRecord {
    val response = record.response?.let { r ->
      val (body, cut) = BodyCap.cap(r.body, BodyCap.SETTLED_MAX_BYTES)
      if (cut) r.copy(body = body, bodyTruncated = true) else r
    }
    return record.copy(deliveries = if (listener != null) 1 else 0, response = response)
  }

  /** Whether [eventId] is held in memory, not yet on disk. */
  @Synchronized
  fun isHeld(eventId: String) = eventId in held

  /** Writes every held record. Returns true when none is left. */
  @Synchronized
  fun writeHeld(): Boolean {
    val written = held.values.filter { r ->
      runCatching { AtomicFiles.writeText(fileFor(r.eventId), gson.toJson(r)) }.isSuccess
    }
    written.forEach { held.remove(it.eventId) }
    return held.isEmpty()
  }

  private fun scheduleRetry(delayMs: Long) {
    if (retryScheduled) return
    retryScheduled = true
    retryLater(delayMs) {
      synchronized(this) {
        retryScheduled = false
        if (!writeHeld()) scheduleRetry(minOf(delayMs * 2, RETRY_MAX_MS))
      }
    }
  }

  // Prunes by file time (no parsing), sparing the records rows name. Never
  // throws: it only runs in the broken case where nothing acknowledges.
  private fun pruneToMax(keep: () -> Set<String>) {
    try {
      dir.listFiles { f -> f.name.endsWith(AtomicFiles.TMP_SUFFIX) }?.forEach { it.delete() }
      val files = dir.listFiles { f -> f.extension == "json" } ?: return
      if (files.size <= maxEntries) return
      val named = keep()
      files.filter { it.nameWithoutExtension !in named }
        .sortedBy { it.lastModified() }
        .take(files.size - maxEntries)
        .forEach { it.delete() }
    } catch (t: Throwable) {
      Diag.error("journal prune failed", t)
    }
  }

  /**
   * getUnacknowledgedEvents(): sets [owner] as the listener and returns
   * every record, oldest first, each counted as one more delivery. One lock
   * spans both, see [listener].
   */
  @Synchronized
  fun drain(owner: Any, isActive: () -> Boolean = { true }): List<SettledRecord> {
    // [isActive] is read under this lock. A module torn down before its
    // queued drain runs does not become the listener, and counts nothing.
    if (!isActive()) return emptyList()
    listener = owner
    return unacknowledged().mapNotNull { incrementDeliveries(it.eventId) }
  }

  /** Clears the listener, only when [owner] set it: a reload builds the next module before it tears down this one. */
  @Synchronized
  fun stopListening(owner: Any) {
    if (listener === owner) listener = null
  }

  @Synchronized
  fun isListening() = listener != null

  /** The listener a live settled event goes to: the module whose JS drained last. */
  @Synchronized
  fun listener(): Any? = listener

  /**
   * A re-emit of a journaled outcome (same-id rule 7): one more delivery,
   * returned for a live emit. Null when no listener is set (the next drain
   * delivers it) or the record is gone.
   */
  @Synchronized
  fun redeliver(eventId: String): SettledRecord? =
    if (listener == null) null else incrementDeliveries(eventId)

  /** Every record, oldest first, the held ones included. Corrupt files are skipped. */
  @Synchronized
  fun unacknowledged(): List<SettledRecord> =
    ((dir.listFiles { f -> f.extension == "json" } ?: emptyArray()).mapNotNull { read(it) } + held.values)
      .sortedWith(compareBy<SettledRecord> { it.at }.thenBy { it.eventId })

  @Synchronized
  fun find(eventId: String): SettledRecord? =
    if (isValidEventId(eventId)) held[eventId] ?: read(fileFor(eventId)) else null

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
    if (eventId in held) {
      held[eventId] = next
      return next
    }
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
    eventIds.filter { isValidEventId(it) }.forEach {
      held.remove(it)
      fileFor(it).delete()
    }
  }

  /** Acks every record of entry [id]: cancel() of a settled entry forgets its outcomes. */
  @Synchronized
  fun ackEntry(id: String) {
    ack(forEntry(id).map { it.eventId })
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
