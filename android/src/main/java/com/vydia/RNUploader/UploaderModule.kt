package com.vydia.RNUploader

import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.google.gson.Gson


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


  /**
   * Returns terminal events (completed/error/cancelled) that JS has not yet
   * acknowledged, including ones that fired while JS was dead. Read these on
   * startup, process them, then call ackEvents to remove them.
   */
  @ReactMethod
  fun getUnacknowledgedEvents(promise: Promise) {
    try {
      val events = EventJournal.get(reactApplicationContext).unacknowledged()
      val arr = Arguments.createArray()
      events.forEach { arr.pushMap(it.toWritableMap()) }
      promise.resolve(arr)
    } catch (exc: Throwable) {
      Log.e(TAG, exc.message, exc)
      promise.reject(exc)
    }
  }


  /**
   * Removes journaled events by eventId once JS has processed them.
   */
  @ReactMethod
  fun ackEvents(eventIds: ReadableArray, promise: Promise) {
    try {
      val ids = (0 until eventIds.size()).mapNotNull { eventIds.getString(it) }
      EventJournal.get(reactApplicationContext).ack(ids)
      promise.resolve(true)
    } catch (exc: Throwable) {
      Log.e(TAG, exc.message, exc)
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
      // Using KEEP policy to prevent it from cancelling the work if it's already running.
      // Otherwise, it will emit "cancelled" and then go on to emit "progress" events,
      // which is confusing and quite difficult to manage. "cancelled" should be reserved for
      // when the user explicitly cancels the upload.
      .beginUniqueWork(upload.id, ExistingWorkPolicy.KEEP, request)
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

