package com.vydia.RNUploader

import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class UploaderModule(context: ReactApplicationContext) :
  ReactContextBaseJavaModule(context) {

  companion object {
    const val TAG = "RNFileUploader.UploaderModule"
    const val WORKER_TAG = "RNFileUploader"
    var reactContext: ReactApplicationContext? = null
      private set
  }

  private val workManager = WorkManager.getInstance(context)

  init {
    reactContext = context
  }


  override fun getName(): String = "RNFileUploader"


  @ReactMethod
  fun initialize(opts: ReadableMap, promise: Promise) =
    CoroutineScope(Dispatchers.IO).launch {
      try {
        NotificationConfigs.update(opts)
        promise.resolve(true)
      } catch (exc: Throwable) {
        if (exc !is MissingOptionException) {
          exc.printStackTrace()
          Log.e(TAG, exc.message, exc)
        }
        promise.reject(exc)
      }
    }


  /*
   * Starts a file upload.
   * Returns a promise with the string ID of the upload.
   */
  @ReactMethod
  fun startUpload(rawOptions: ReadableMap, promise: Promise) {
    try {
      val upload = Upload.fromRawOptions(rawOptions)
      UploadQueue.add(upload)

      val request = OneTimeWorkRequestBuilder<UploadWorker>()
        .build()

      workManager
        .beginUniqueWork(WORKER_TAG, ExistingWorkPolicy.KEEP, request)
        .enqueue()

      promise.resolve(upload.id)
    } catch (exc: Throwable) {
      if (exc !is MissingOptionException) {
        exc.printStackTrace()
        Log.e(TAG, exc.message, exc)
      }
      promise.reject(exc)
    }
  }

  /*
   * Cancels file upload
   * Accepts upload ID as a first argument, this upload will be cancelled
   * Event "cancelled" will be fired when upload is cancelled.
   */
  @ReactMethod
  fun cancelUpload(uploadId: String, promise: Promise) {
    try {
      // Just remove from queue, worker will handle progress cleanup
      UploadQueue.remove(uploadId)
      promise.resolve(true)
    } catch (exc: Throwable) {
      exc.printStackTrace()
      Log.e(TAG, exc.message, exc)
      promise.reject(exc)
    }
  }

  /*
   * Cancels all file uploads
   */
  @ReactMethod
  fun stopAllUploads(promise: Promise) {
    try {
      UploadQueue.clear()
      workManager.cancelAllWorkByTag(WORKER_TAG)
      promise.resolve(true)
    } catch (exc: Throwable) {
      exc.printStackTrace()
      Log.e(TAG, exc.message, exc)
      promise.reject(exc)
    }
  }

}
