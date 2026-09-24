package ai.openspace.backgroundupload

import com.google.gson.annotations.SerializedName

/** The row states. [wire] is the RequestState string JS sees. */
enum class EntryState(val wire: String) {
  @SerializedName("queued") QUEUED("queued"),
  @SerializedName("running") RUNNING("running"),
  @SerializedName("awaiting-auth") AWAITING_AUTH("awaiting-auth"),
  @SerializedName("paused") PAUSED("paused"),
  @SerializedName("completed") COMPLETED("completed"),
  @SerializedName("error") ERROR("error"),
  @SerializedName("cancelled") CANCELLED("cancelled");

  val isLive get() = this == QUEUED || this == RUNNING || this == AWAITING_AUTH || this == PAUSED
}

/** One multipart field. [path] is the caller's file, read only at staging. */
data class FormPart(
  val name: String,
  val contentType: String,
  val string: String?,
  val path: String?,
  val fileName: String?,
)

/** A request's `retry`, as a partial override of the configure() defaults. */
data class RetryOverride(
  val baseMs: Long?,
  val maxMs: Long?,
  val jitter: Double?,
  val exempt: List<Int>?,
)

/** What request(vars) returned, as native needs it to run. */
data class Descriptor(
  /** Null only with parts. */
  val url: String?,
  val method: String,
  /** The merged headers, plus the Content-Type that staging sets. */
  val headers: Map<String, String>,
  /** JSON text of `data`. It is also the bytes of the staged body. */
  val dataJson: String?,
  val form: List<FormPart>?,
  /** The caller's path, with any file:// prefix removed. */
  val file: String?,
  /** The accepted flags live here. */
  val parts: List<Part>?,
  val accept: List<UploadOutcome.AcceptRule>,
  val retry: RetryOverride?,
  val noNotification: Boolean,
) {
  val bodyKind: String
    get() = when {
      parts != null -> StagedBody.CHUNKED
      file != null -> StagedBody.FILE
      form != null -> StagedBody.MULTIPART
      dataJson != null -> StagedBody.JSON
      else -> StagedBody.NONE
    }

  /** The url to report for this request: the descriptor's, or the last part's. */
  val reportUrl: String get() = url ?: parts?.lastOrNull()?.url ?: ""
}

/**
 * Where the request body is on disk. [fileName] is relative to the entry
 * directory, so a moved app data directory does not break it.
 */
data class StagedBody(
  val kind: String,
  val fileName: String?,
  val boundary: String?,
  val totalBytes: Long,
) {
  companion object {
    const val NONE = "none"
    const val JSON = "json"
    const val MULTIPART = "multipart"
    const val FILE = "file"
    const val CHUNKED = "chunked"
  }
}

/** One queue entry. [QueueStore] persists it as `entry.json` with Gson. */
data class QueueEntry(
  val id: String,
  val key: String,
  /** "null" for null vars. */
  val varsJson: String,
  /** Null only for a legacy row. */
  val descriptor: Descriptor?,
  /** Null only for a legacy row. */
  val body: StagedBody?,
  val state: EntryState,
  /** Attempts in this life. Chunked: across every part. */
  val attempts: Int,
  val bytesSent: Long,
  val totalBytes: Long,
  val expiresAt: Long,
  val createdAt: Long,
  val updatedAt: Long,
  /** Set while the entry waits out a backoff: queued for a long one, running for a short one. */
  val nextAttemptAt: Long? = null,
  /** The consecutive transient failures before the last release. The next backoff continues from it. */
  val backoffStreak: Int = 0,
  /** The settings header generation the headers were last merged at. */
  val headerGeneration: Int = 0,
  /** Set while awaiting-auth (and kept under pause): the header generation it parked under. */
  val parkedGeneration: Int? = null,
  /** +1 each time a settled entry reopens, and on a different-body replace. Journal records carry it. */
  val generation: Int = 1,
  /** The journal record of this life's outcome. */
  val settledEventId: String? = null,
  /** The X-Request-Id of the last attempt. */
  val lastRequestId: String? = null,
  val legacy: Boolean = false,
) {
  val isLive get() = state.isLive
  val isSettled get() = !state.isLive

  fun toRow() = RequestRow(
    id = id,
    key = key,
    varsJson = varsJson,
    state = state.wire,
    bytesSent = bytesSent,
    totalBytes = totalBytes,
    attempts = attempts,
    updatedAt = updatedAt,
    nextAttemptAt = nextAttemptAt,
    createdAt = createdAt,
  )

  /**
   * Whether [incoming] carries the same body. A different body kind, a
   * different url or method, or different content is a different body.
   * Chunked compares the parts (the path is ignored: the owned blob is the
   * truth, as in v9).
   */
  fun sameBodyAs(incoming: Descriptor): Boolean {
    val stored = descriptor ?: return false
    if (stored.bodyKind != incoming.bodyKind) return false
    if (stored.method != incoming.method) return false
    return when (stored.bodyKind) {
      StagedBody.CHUNKED -> ChunkedParts.sameParts(stored.parts!!, incoming.parts!!)
      StagedBody.FILE -> stored.url == incoming.url && stored.file == incoming.file
      StagedBody.MULTIPART -> stored.url == incoming.url && stored.form == incoming.form
      StagedBody.JSON -> stored.url == incoming.url && stored.dataJson == incoming.dataJson
      else -> stored.url == incoming.url
    }
  }

  /**
   * updateHeaders(): the patch replaces same-named headers (any case) and adds
   * the rest. A part that carries its own copy of a patched header gets the
   * new value too, because a stale per-part Authorization would shadow the
   * fresh one.
   */
  fun withHeadersPatched(patch: Map<String, String>, generation: Int): QueueEntry {
    val d = descriptor ?: return copy(headerGeneration = generation)
    return copy(
      descriptor = d.copy(
        headers = HeaderMap.merge(d.headers, patch),
        parts = d.parts?.map { part ->
          val shared = patch.filterKeys { name -> HeaderMap.contains(part.headers, name) }
          if (shared.isEmpty()) part else part.copy(headers = HeaderMap.merge(part.headers, shared))
        },
      ),
      headerGeneration = generation,
    )
  }
}

/** One row of getRequests() and of a state event. */
class RequestRow(
  val id: String,
  val key: String,
  val varsJson: String,
  val state: String,
  val bytesSent: Long,
  val totalBytes: Long,
  val attempts: Int,
  val updatedAt: Long,
  val nextAttemptAt: Long?,
  /** Sort order only; not sent to JS. */
  val createdAt: Long,
) {
  /** vars parsed back to an object, once. A malformed text reads as null. */
  val vars: Any? by lazy { runCatching { JsonBridge.parse(varsJson) }.getOrNull() }

  fun withBytes(sent: Long) = RequestRow(
    id, key, varsJson, state, sent, totalBytes, attempts, updatedAt, nextAttemptAt, createdAt,
  )

  /** The RequestRow shape. nextAttemptAt only when set. */
  fun toMap(): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
    put("id", id)
    put("key", key)
    put("vars", vars)
    put("state", state)
    put("bytesSent", bytesSent.toDouble())
    put("totalBytes", totalBytes.toDouble())
    put("attempts", attempts.toDouble())
    put("updatedAt", updatedAt.toDouble())
    nextAttemptAt?.let { put("nextAttemptAt", it.toDouble()) }
  }
}

/** Header maps whose names match without regard to case. */
object HeaderMap {
  fun contains(headers: Map<String, String>, name: String) =
    headers.keys.any { it.equals(name, ignoreCase = true) }

  fun get(headers: Map<String, String>, name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

  /** [over] replaces same-named entries of [base] (any case); its spelling is kept. */
  fun merge(base: Map<String, String>, over: Map<String, String>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    base.forEach { (k, v) -> if (!contains(over, k)) out[k] = v }
    out.putAll(over)
    return out
  }

  fun without(headers: Map<String, String>, name: String): Map<String, String> =
    headers.filterKeys { !it.equals(name, ignoreCase = true) }
}
