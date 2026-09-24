package ai.openspace.backgroundupload

import com.facebook.react.bridge.WritableMap

/**
 * One HTTP attempt, before the library interprets it. Live only: never
 * journaled. [outcome] is `completed` when the response is accepted and
 * `error` otherwise, so a 401 is `error` with httpCode 401 even though the
 * entry parks.
 */
data class AttemptEvent(
  val id: String,
  val key: String,
  val requestId: String,
  val attempt: Int,
  val url: String,
  val method: String,
  val partIndex: Int?,
  val outcome: String,
  val httpCode: Int?,
  val responseBody: String?,
  val responseBodyTruncated: Boolean?,
  val responseHeaders: Map<String, String>?,
  val errorKind: String?,
  val errorMessage: String?,
  val cancelReason: String?,
  val at: Long,
) {
  companion object {
    const val MAX_BODY_CHARS = 4 * 1024

    fun ofResponse(
      entry: QueueEntry,
      requestId: String,
      url: String,
      partIndex: Int?,
      response: UploadResponse,
      accepted: Boolean,
      at: Long,
    ): AttemptEvent {
      val (body, truncated) = EventJournal.capBody(response.body, MAX_BODY_CHARS)
      return AttemptEvent(
        id = entry.id, key = entry.key, requestId = requestId, attempt = entry.attempts,
        url = url, method = entry.descriptor?.method ?: "POST", partIndex = partIndex,
        outcome = if (accepted) "completed" else "error",
        httpCode = response.code, responseBody = body, responseBodyTruncated = truncated,
        responseHeaders = response.headers,
        errorKind = if (accepted) null else "http",
        errorMessage = if (accepted) null else "HTTP ${response.code}",
        cancelReason = null, at = at,
      )
    }

    fun ofFailure(
      entry: QueueEntry,
      requestId: String,
      url: String,
      partIndex: Int?,
      errorKind: String,
      message: String,
      at: Long,
    ) = AttemptEvent(
      id = entry.id, key = entry.key, requestId = requestId, attempt = entry.attempts,
      url = url, method = entry.descriptor?.method ?: "POST", partIndex = partIndex,
      outcome = "error", httpCode = null, responseBody = null, responseBodyTruncated = null,
      responseHeaders = null, errorKind = errorKind, errorMessage = message,
      cancelReason = null, at = at,
    )
  }

  fun toMap(): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
    put("id", id)
    put("key", key)
    put("requestId", requestId)
    put("attempt", attempt.toDouble())
    put("url", url)
    put("method", method)
    partIndex?.let { put("partIndex", it.toDouble()) }
    put("outcome", outcome)
    httpCode?.let { put("httpCode", it.toDouble()) }
    responseBody?.let { put("responseBody", it) }
    responseBodyTruncated?.let { put("responseBodyTruncated", it) }
    responseHeaders?.let { put("responseHeaders", it) }
    errorKind?.let { put("errorKind", it) }
    errorMessage?.let { put("errorMessage", it) }
    cancelReason?.let { put("cancelReason", it) }
    put("at", at.toDouble())
  }

  fun toWritableMap(): WritableMap = JsonBridge.toWritableMap(toMap())
}
