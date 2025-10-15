package com.vydia.RNUploader

import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Sends events to React Native
class EventReporter {
  companion object {
    private const val TAG = "UploadReceiver"
    fun cancelled(uploadId: String) =
      sendEvent("cancelled", Arguments.createMap().apply {
        putString("id", uploadId)
      })

    fun error(uploadId: String, exception: Throwable) =
      sendEvent("error", Arguments.createMap().apply {
        putString("id", uploadId)
        putString("error", exception.message ?: "Unknown exception")
      })

    // TODO expose via JS
    fun globalError(origin: String, exception: Throwable) =
      sendEvent("globalError", Arguments.createMap().apply {
        putString("origin", origin)
        putString("error", exception.message ?: "Unknown exception")
      })

    fun success(uploadId: String, response: UploadResponse) =
      CoroutineScope(Dispatchers.IO).launch {
        sendEvent("completed", Arguments.createMap().apply {
          putString("id", uploadId)
          putInt("responseCode", response.statusCode)
          putString("responseBody", response.body)
          putMap("responseHeaders", Arguments.makeNativeMap(response.headers))
        })
      }

    fun progress(uploadId: String) =
      sendEvent("progress", Arguments.createMap().apply {
        putString("id", uploadId)
        putDouble("progress", UploadQueue.progressPercentage().toDouble())
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

}
