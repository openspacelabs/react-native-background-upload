package ai.openspace.backgroundupload

import android.util.Log

/**
 * Logging that is safe in the JVM unit tests. There, android.util.Log is a
 * stub that throws, so every call is wrapped.
 *
 * Warnings and errors also go to [log] once [DiagnosticLog.get] has run in
 * this process, so JS can report them later.
 */
internal object Diag {
  const val TAG = "RNFileUploader"

  @Volatile
  var log: DiagnosticLog? = null

  /** Clock for the records. Tests replace it. */
  var clock: () -> Long = System::currentTimeMillis

  /**
   * [persist] false keeps an expected, repeating warning out of [log], so it
   * cannot crowd out the rest.
   */
  fun warn(message: String, error: Throwable? = null, persist: Boolean = true) {
    runCatching { Log.w(TAG, message, error) }
    if (persist) record(DiagnosticLog.WARN, message, error, null)
  }

  fun error(message: String, error: Throwable? = null) {
    runCatching { Log.e(TAG, message, error) }
    record(DiagnosticLog.ERROR, message, error, null)
  }

  /** A structured report, such as the boot snapshot. Not an error by itself. */
  fun info(message: String, data: Map<String, Any?>) {
    runCatching { Log.i(TAG, "$message $data") }
    record(DiagnosticLog.INFO, message, null, data)
  }

  private fun record(level: String, message: String, error: Throwable?, data: Map<String, Any?>?) {
    val target = log ?: return
    runCatching {
      target.append(DiagnosticLog.Record(clock(), level, message, DiagnosticLog.describe(error), data))
    }
  }
}
