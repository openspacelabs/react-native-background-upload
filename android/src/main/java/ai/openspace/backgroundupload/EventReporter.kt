package ai.openspace.backgroundupload

import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter

// Sends events to React Native
object EventReporter {

  private const val TAG = "UploadReceiver"

  // Emit a terminal event from its journal entry, so the live event carries the
  // exact same payload (incl. eventId) as the journaled copy — letting a consumer
  // ackEvents([eventId]) right after handling a live event, and keeping iOS/Android
  // event shapes identical. The event name is the entry's type.
  fun emit(entry: EventJournal.Entry) = sendEvent(entry.type, entry.toWritableMap())

  fun progress(uploadId: String, bytesSentTotal: Long, contentLength: Long) =
    sendEvent("progress", Arguments.createMap().apply {
      putString("id", uploadId)
      // Guard against a zero-byte file (contentLength == 0) producing NaN.
      val pct = if (contentLength <= 0) 0.0 else bytesSentTotal.toDouble() * 100 / contentLength
      putDouble("progress", pct) // 0-100
    })

  fun notification() = sendEvent("notification")

  /** Sends an event to the JS module */
  private fun sendEvent(eventName: String, params: WritableMap = Arguments.createMap()) {
    val reactContext = UploaderModule.reactContext ?: return

    // Right after JS reloads, react instance might not be available yet
    if (!reactContext.hasActiveReactInstance()) return

    try {
      val jsModule = reactContext.getJSModule(RCTDeviceEventEmitter::class.java)
      jsModule.emit("RNFileUploader-$eventName", params)
    } catch (exc: Throwable) {
      Log.e(TAG, "sendEvent() failed", exc)
    }
  }
}
