package ai.openspace.backgroundupload

// Pure classification of terminal upload outcomes. Kept free of Android/React
// types so it can be unit-tested on a plain JVM — this is the highest-consequence
// logic in the uploader (it decides success vs failure), so it's covered directly.
object UploadOutcome {

  /**
   * A non-2xx response to treat as success. `bodyIncludes` narrows the rule by
   * a response-body substring. This is necessary when one status has several
   * meanings, and only the message shows the difference (our backend's 409).
   * Gson persists it inside [Descriptor]; see consumer-rules.pro.
   */
  data class AcceptRule(
    val status: Int,
    val bodyIncludes: String? = null,
  )

  // Whether an HTTP response counts as a successful completion. A 2xx always
  // counts, and a matching per-request accept rule counts. Every other response,
  // 4xx and 5xx included, is a terminal http error, not a completion.
  fun isAccepted(code: Int, body: String?, accept: List<AcceptRule>): Boolean =
    code in 200..299 || accept.any { rule ->
      rule.status == code &&
        (rule.bodyIncludes == null || body?.contains(rule.bodyIncludes) == true)
    }
}
