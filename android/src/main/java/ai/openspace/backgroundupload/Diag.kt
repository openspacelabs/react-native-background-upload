package ai.openspace.backgroundupload

import android.util.Log

/**
 * Logging that is safe in the JVM unit tests. There, android.util.Log is a
 * stub that throws, so every call is wrapped.
 */
internal object Diag {
  const val TAG = "RNFileUploader"

  fun warn(message: String, error: Throwable? = null) {
    runCatching { Log.w(TAG, message, error) }
  }

  fun error(message: String, error: Throwable? = null) {
    runCatching { Log.e(TAG, message, error) }
  }
}
