package com.vydia.RNUploader

import android.content.Context
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter
import com.vydia.RNUploader.Upload.Companion.uploadByRequestId
import com.vydia.RNUploader.Upload.Companion.uploads

class GlobalRequestObserverDelegate(private val reactContext: ReactApplicationContext) : {
  private val TAG = "UploadReceiver"


  fun reportCancelled(uploadId: RNUploadId) {
    uploads.remove(uploadId)
    sendEvent("cancelled", Arguments.createMap().apply {
      putString("id", uploadId.value)
    })
  }

  override fun onError(context: Context, uploadInfo: UploadInfo, exception: Throwable) {
    // if the upload cannot be found, don't do anything
    val upload = uploadByRequestId(UploadServiceId(uploadInfo.uploadId)) ?: return
    if (exception is UserCancelledUploadException) return reportCancelled(upload.id)

    uploads.remove(upload.id)
    sendEvent("error", Arguments.createMap().apply {
      putString("id", upload.id.value)
      putString("error", exception.message ?: "Unknown exception")
    })
  }

  override fun onSuccess(context: Context, uploadInfo: UploadInfo, serverResponse: ServerResponse) {
    val upload = uploadByRequestId(UploadServiceId(uploadInfo.uploadId)) ?: return

    uploads.remove(upload.id)
    sendEvent("completed", Arguments.createMap().apply {
      putString("id", upload.id.value)
      putInt("responseCode", serverResponse.code)
      putString("responseBody", serverResponse.bodyString)
      putMap("responseHeaders", Arguments.createMap().apply {
        serverResponse.headers.forEach { (key, value) ->
          putString(key, value)
        }
      })
    })
  }

  override fun onProgress(context: Context, uploadInfo: UploadInfo) {
    val upload = uploadByRequestId(UploadServiceId(uploadInfo.uploadId)) ?: return

    sendEvent("progress", Arguments.createMap().apply {
      putString("id", upload.id.value)
      putInt("progress", uploadInfo.progressPercent) //0-100
    })
  }

  /**
   * Sends an event to the JS module.
   */
  private fun sendEvent(eventName: String, params: WritableMap?) {
    // Right after JS reloads, react instance might not be available yet
    if (!reactContext.hasActiveCatalystInstance()) return

    try {
      val jsModule = reactContext.getJSModule(RCTDeviceEventEmitter::class.java)
      jsModule.emit("RNFileUploader-$eventName", params)
    } catch (exc: Throwable) {
      Log.e(TAG, "sendEvent() failed", exc)
    }
  }
}
