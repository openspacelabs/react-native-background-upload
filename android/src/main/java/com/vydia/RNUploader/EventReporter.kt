package com.vydia.RNUploader

import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter

// Sends events to React Native
object EventReporter {

  private const val TAG = "UploadReceiver"
  fun cancelled(uploadId: String, reason: String) =
    sendEvent("cancelled", Arguments.createMap().apply {
      putString("id", uploadId)
      putString("cancelReason", reason)
    })

  fun error(uploadId: String, exception: Throwable, kind: String) =
    sendEvent("error", Arguments.createMap().apply {
      putString("id", uploadId)
      putString("error", exception.message ?: "Unknown exception")
      putString("errorKind", kind)
    })

  // A non-accepted HTTP response (e.g. 400/500). Emitted as an "error" with the
  // full response attached so consumers can inspect the body/status.
  fun httpError(uploadId: String, response: UploadResponse) =
    sendEvent("error", Arguments.createMap().apply {
      putString("id", uploadId)
      putString("error", "HTTP ${response.code}")
      putString("errorKind", "http")
      putInt("responseCode", response.code)
      putString("responseBody", response.body)
      putMap("responseHeaders", Arguments.makeNativeMap(response.headers))
    })

  fun success(uploadId: String, response: UploadResponse) =
    sendEvent("completed", Arguments.createMap().apply {
      putString("id", uploadId)
      putInt("responseCode", response.code)
      putString("responseBody", response.body)
      putMap("responseHeaders", Arguments.makeNativeMap(response.headers))
    })


  fun progress(uploadId: String, bytesSentTotal: Long, contentLength: Long) =
    sendEvent("progress", Arguments.createMap().apply {
      putString("id", uploadId)
      putDouble("progress", (bytesSentTotal.toDouble() * 100 / contentLength)) //0-100
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
