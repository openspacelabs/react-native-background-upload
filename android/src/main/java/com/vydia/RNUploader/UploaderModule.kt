package com.vydia.RNUploader

import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.facebook.react.bridge.*
import com.google.gson.Gson
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
    // workers may be killed abruptly for whatever reasons,
    // so they might not have had a chance to clear the progress data.
    UploadProgress.clearIfNeeded(context)
  }


  override fun getName(): String = "RNFileUploader"

  @ReactMethod
  fun chunkFile(parentFilePath: String, chunks: ReadableArray, promise: Promise) {
    CoroutineScope(Dispatchers.IO).launch {
      try {
        chunkFile(parentFilePath, Chunk.fromReadableArray(chunks))
        promise.resolve(true)
      } catch (e: Throwable) {
        promise.reject(e)
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
      val id = startUpload(rawOptions)
      promise.resolve(id)
    } catch (exc: Throwable) {
      if (exc !is Upload.MissingOptionException) {
        exc.printStackTrace()
        Log.e(TAG, exc.message, exc)
      }
      promise.reject(exc)
    }
  }

  /**
   * @return whether the upload was started
   */
  private fun startUpload(options: ReadableMap): String {
    val upload = Upload.fromReadableMap(options)
    val data = Gson().toJson(upload)

    val request = OneTimeWorkRequestBuilder<UploadWorker>()
      .addTag(WORKER_TAG)
      .setInputData(workDataOf(UploadWorker.Input.Params.name to data))
      .build()

    workManager
      // cancel workers with duplicate ID
      .beginUniqueWork(upload.id, ExistingWorkPolicy.REPLACE, request)
      .enqueue()

    return upload.id
  }


  /*
   * Cancels file upload
   * Accepts upload ID as a first argument, this upload will be cancelled
   * Event "cancelled" will be fired when upload is cancelled.
   */
  @ReactMethod
  fun cancelUpload(uploadId: String, promise: Promise) {
    try {
      workManager.cancelUniqueWork(uploadId)
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
      workManager.cancelAllWorkByTag(WORKER_TAG)
      promise.resolve(true)
    } catch (exc: Throwable) {
      exc.printStackTrace()
      Log.e(TAG, exc.message, exc)
      promise.reject(exc)
    }
  }


}

