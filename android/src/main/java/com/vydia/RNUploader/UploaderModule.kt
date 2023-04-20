package com.vydia.RNUploader

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.facebook.react.bridge.*
import com.vydia.RNUploader.Upload.Companion.uploads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.*


class UploaderModule(val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {
  private val uploadEventListener = GlobalRequestObserverDelegate(reactContext)
  private val ioCoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())


  companion object {
    val TAG = "UploaderBridge"
  }

  override fun getName(): String {
    return "RNFileUploader"
  }


  @ReactMethod
  fun chunkFile(parentFilePath: String, chunks: ReadableArray, promise: Promise) {
    ioCoroutineScope.launch {
      try {
        chunkFile(this, parentFilePath, Chunk.fromReactMethodParams(chunks))
        promise.resolve(true)
      } catch (e: Exception) {
        promise.reject("chunkFileError", e)
      }
    }
  }


  /*
   * Starts a file upload.
   * Returns a promise with the string ID of the upload.
   */
  @ReactMethod
  fun startUpload(rawOptions: ReadableMap, promise: Promise) {
    try {
      val new = Upload(rawOptions)
      maybeCancelUpload(new.id, true)
      maybeStartUpload(new)

      uploads[new.id] = new
      promise.resolve(new.id.toString())
    } catch (exc: Throwable) {
      if (exc !is InvalidUploadOptionException) {
        exc.printStackTrace()
        Log.e(TAG, exc.message, exc)
      }
      promise.reject(exc)
    }
  }

  /**
   * @return whether the upload was started
   */
  private fun maybeStartUpload(upload: Upload) {
    val notificationManager =
      (reactContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
    initializeNotificationChannel(upload.notificationChannel, notificationManager)

    val requestId = UUID.randomUUID().toString()

    val request = if (upload.requestType == Upload.RequestType.RAW) {
      UploadRequestBinary(reactContext, upload.url, upload.wifiOnly).apply {
        setFileToUpload(upload.path)
      }
    }

    Log.i(TAG, "starting request ID $requestId for ${upload.id}")

    request.apply {
      setMethod(upload.method)
      setMaxRetries(upload.maxRetries)
      setUploadID(requestId)
      upload.headers.forEach { (key, value) -> addHeader(key, value) }
      startUpload()
    }

    upload.requestId = UploadServiceId(requestId)
  }


  /*
   * Cancels file upload
   * Accepts upload ID as a first argument, this upload will be cancelled
   * Event "cancelled" will be fired when upload is cancelled.
   */
  @ReactMethod
  fun cancelUpload(uploadId: String, promise: Promise) {
    try {
      maybeCancelUpload(RNUploadId(uploadId), false)
      promise.resolve(true)
    } catch (exc: Throwable) {
      exc.printStackTrace()
      Log.e(TAG, exc.message, exc)
      promise.reject(exc)
    }
  }

  private fun maybeCancelUpload(id: RNUploadId, silent: Boolean) {
    uploads[id]?.let { upload ->
      upload.requestId?.let {
        if (silent) upload.requestId = null
        UploadService.stopUpload(it.value)
        return
      }

      if (!silent) uploadEventListener.reportCancelled(upload.id)
    }
  }


  /*
   * Cancels all file uploads
   */
  @ReactMethod
  fun stopAllUploads(promise: Promise) {
    try {
      UploadService.stopAllUploads()
      promise.resolve(true)
    } catch (exc: java.lang.Exception) {
      exc.printStackTrace()
      Log.e(TAG, exc.message, exc)
      promise.reject(exc)
    }
  }


}

