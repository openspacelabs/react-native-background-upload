package ai.openspace.backgroundupload

import android.system.ErrnoException
import android.system.OsConstants

/**
 * The underlying error of an E_STORAGE rejection, as a suffix for its
 * message: ` (cause: <class>: <message>; errno <NAME>)`. The errno part
 * comes from an [ErrnoException] in the cause chain and is left out when
 * there is none.
 *
 * A later option: also pass the same fields as a structured `userInfo.cause`
 * on the rejection. Not done: it is not checked that RN brings `userInfo`
 * to JS from a TurboModule promise.
 */
internal object StorageCause {
  /** [withMessage] false: the rejection message already holds [error]'s message. */
  fun suffix(error: Throwable, withMessage: Boolean = true): String {
    val name = error.javaClass.name
    val head = if (withMessage && error.message != null) "$name: ${error.message}" else name
    val errno = errnoName(error)?.let { "; errno $it" } ?: ""
    return " (cause: $head$errno)"
  }

  /** The message of a rejection that is not a [QueueException]: its message, then its cause. */
  fun rejectMessage(error: Throwable): String =
    (error.message ?: error.javaClass.simpleName) + suffix(error, withMessage = false)

  private fun errnoName(error: Throwable): String? {
    val errno = generateSequence(error) { it.cause }.take(8).filterIsInstance<ErrnoException>().firstOrNull()
      ?: return null
    return runCatching { OsConstants.errnoName(errno.errno) }.getOrNull() ?: errno.errno.toString()
  }
}
