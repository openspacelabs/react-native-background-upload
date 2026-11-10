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

  /**
   * A 4xx to retry instead of settling. `bodyIncludes` narrows the rule by a
   * response-body substring. `maxRetries` caps the retries the rule causes for
   * one entry: 3 retries three matching responses, the fourth settles. Gson
   * persists it inside [RetryOverride] and [RetryDefaults]; see consumer-rules.pro.
   */
  data class TransientRule(
    val status: Int,
    val bodyIncludes: String? = null,
    val maxRetries: Int? = null,
  ) {
    /**
     * The key of this rule's count. Format, the same on iOS: the status in
     * decimal; with bodyIncludes, then "|" and the bodyIncludes text
     * unchanged. So `{404}` is "404" and `{400, "Bad Request"}` is
     * "400|Bad Request". A status holds only digits, so the first "|" ends
     * it, and two rules share a key only when status and bodyIncludes are
     * both equal. No escaping is needed. maxRetries is not part of it: a
     * changed cap applies to the count so far. The list position is not part
     * of it: a reordered list keeps each count. The shared vector
     * test-vectors/transient-rule-identity.json pins the format on both
     * platforms. A getter, not a field, so Gson does not persist it.
     */
    val identity: String get() = if (bodyIncludes == null) "$status" else "$status|$bodyIncludes"
  }

  /** The first rule that matches, or null. */
  fun matchesTransient(code: Int, body: String?, rules: List<TransientRule>): TransientRule? =
    rules.firstOrNull { rule ->
      rule.status == code &&
        (rule.bodyIncludes == null || body?.contains(rule.bodyIncludes) == true)
    }

  // Whether an HTTP response counts as a successful completion. A 2xx always
  // counts, and a matching per-request accept rule counts. Every other response,
  // 4xx and 5xx included, is a terminal http error, not a completion.
  fun isAccepted(code: Int, body: String?, accept: List<AcceptRule>): Boolean =
    code in 200..299 || accept.any { rule ->
      rule.status == code &&
        (rule.bodyIncludes == null || body?.contains(rule.bodyIncludes) == true)
    }
}
