package com.vydia.RNUploader

import android.util.Log
import androidx.work.*
import com.facebook.react.bridge.*
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit.HOURS


class UploaderModule(context: ReactApplicationContext) :
  ReactContextBaseJavaModule(context) {

  companion object {
    const val TAG = "RNFileUploader.UploaderModule"
    const val WORKER_TAG = "RNFileUploader"

    // All workers will start `doWork` immediately but only 1 runs at a time.
    const val MAX_CONCURRENCY = 1

    // Plenty of time for a single request to complete
    val REQUEST_TIMEOUT_MILLIS = HOURS.toMillis(24L)
    var eventReporter: EventReporter? = null
      private set
  }

  private val workManager = WorkManager.getInstance(context)

  init {
    eventReporter = EventReporter(context)
  }


  override fun getName(): String = "RNFileUploader"

  @ReactMethod
  fun chunkFile(parentFilePath: String, chunks: ReadableArray, promise: Promise) {
    CoroutineScope(Dispatchers.IO).launch {
      try {
        chunkFile(this, parentFilePath, Chunk.fromReactMethodParams(chunks))
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
  private fun startUpload(options: ReadableMap): String {
    val upload = Upload.fromOptions(options)
    val data = Gson().toJson(upload)
    // TODO fix worker not waking up app in background using BroadcastReceiver
    // TODO cancellation on delete doesn't work
    // TODO test when notification not allowed
    // TODO test network handling
    // TODO workers get cancelled after app update
    // TODO: Invalid Content-Range header", "httpCode": 400,?
    // TODO: resume doesn't work

    val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
    if (upload.wifiOnly) constraints.setRequiredNetworkType(NetworkType.UNMETERED)

    val request = OneTimeWorkRequestBuilder<UploadWorker>()
      .addTag(WORKER_TAG)
      .setInputData(workDataOf(UploadWorker.Input.Params.name to data))
      .setConstraints(constraints.build())
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

