package com.vydia.RNUploader

import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter
import io.ktor.client.statement.*
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

    fun success(uploadId: String, response: HttpResponse) =
      CoroutineScope(Dispatchers.IO).launch {
        sendEvent("completed", Arguments.createMap().apply {
          putString("id", uploadId)
          putInt("responseCode", response.status.value)
          putString("responseBody", response.bodyAsText())
          putMap("responseHeaders", Arguments.createMap().apply {
            response.headers.forEach { key, values ->
              putString(key, values.joinToString(", "))
            }
          })
        })
      }

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

}
