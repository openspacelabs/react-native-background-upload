package com.vydia.RNUploader

import android.util.Log
import androidx.work.*
import com.facebook.react.bridge.*
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit


class UploaderModule(context: ReactApplicationContext) :
  ReactContextBaseJavaModule(context) {

  companion object {
    const val TAG = "UploaderModule"
    const val WORKER_TAG = "RNUploader"
    var eventReporter: EventReporter? = null
      private set
  }

  private val workManager = WorkManager.getInstance(context)

  init {
    eventReporter = EventReporter(context)
  }


  override fun getName(): String = "RNUploader"

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
    val upload = Upload(options)
    val data = workDataOf(
      UploadWorker.Input.UploadId.name to upload.id,
      UploadWorker.Input.Path.name to upload.path,
      UploadWorker.Input.Url.name to upload.url,
      UploadWorker.Input.Method.name to upload.method,
      UploadWorker.Input.Headers.name to Gson().toJson(upload.headers),
      UploadWorker.Input.MaxRetries.name to upload.maxRetries,
      UploadWorker.Input.NotificationId.name to upload.notificationId,
      UploadWorker.Input.NotificationChannel.name to upload.notificationChannel
    )
    // TODO set up notification ID in JS and start testing
    // TODO examine event best practice

    val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
    if (upload.wifiOnly) constraints.setRequiredNetworkType(NetworkType.UNMETERED)

    val request = OneTimeWorkRequestBuilder<UploadWorker>()
      .addTag(WORKER_TAG)
      .setInputData(data)
      .setConstraints(constraints.build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2L, TimeUnit.SECONDS)
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

