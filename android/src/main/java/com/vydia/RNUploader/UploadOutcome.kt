package com.vydia.RNUploader

import java.io.IOException

// Pure classification of terminal upload outcomes. Kept free of Android/React
// types so it can be unit-tested on a plain JVM — this is the highest-consequence
// logic in the uploader (it decides success vs failure), so it's covered directly.
object UploadOutcome {

  // Whether an HTTP response counts as a successful completion. 2xx always, plus
  // any per-request acceptStatus codes (axios validateStatus semantics). Anything
  // else — including 4xx/5xx — is a terminal http error, not a completion.
  fun isAccepted(code: Int, acceptStatus: List<Int>): Boolean =
    code in 200..299 || acceptStatus.contains(code)

  // Classify a thrown error into a stable kind for the JS layer. `fileExists`
  // is passed in (not read here) to keep this pure; callers should default it to
  // true when the existence check itself fails, so a flaky file probe reads as a
  // retryable network error rather than a terminal "file gone".
  fun errorKind(error: Throwable, fileExists: Boolean): String = when {
    error is IOException && !fileExists -> "file"
    error is IOException -> "network"
    else -> "unknown"
  }
}
