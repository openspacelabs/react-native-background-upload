package com.vydia.RNUploader

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.facebook.react.bridge.*
import com.google.gson.Gson
import com.vydia.RNUploader.Upload.MissingOptionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


// This is the recommended way to create a DataStore instance
// https://developer.android.com/topic/libraries/architecture/datastore#preferences-create
internal val Context.vydiaDataStore by preferencesDataStore(name = "com.vydia.RNUploader")

object UploadConfigKeys {
  val NOTIFICATION_ID = intPreferencesKey("notification_id")
  val NOTIFICATION_TITLE = stringPreferencesKey("notification_title")
  val NOTIFICATION_TITLE_NO_INTERNET = stringPreferencesKey("notification_title_no_internet")
  val NOTIFICATION_TITLE_NO_WIFI = stringPreferencesKey("notification_title_no_wifi")
  val NOTIFICATION_CHANNEL = stringPreferencesKey("notification_channel")
}

class UploaderModule(private val context: ReactApplicationContext) :
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

  @ReactMethod
  fun initialize(opts: ReadableMap, promise: Promise) {
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val notificationId = opts.getString("notificationId")
          ?: throw MissingOptionException("notificationId")
        val notificationTitle = opts.getString("notificationTitle")
          ?: throw MissingOptionException("notificationTitle")
        val notificationTitleNoInternet = opts.getString("notificationTitleNoInternet")
          ?: throw MissingOptionException("notificationTitleNoInternet")
        val notificationTitleNoWifi = opts.getString("notificationTitleNoWifi")
          ?: throw MissingOptionException("notificationTitleNoWifi")
        val notificationChannel = opts.getString("notificationChannel")
          ?: throw MissingOptionException("notificationChannel")

        context.vydiaDataStore.edit { settings ->
          settings[UploadConfigKeys.NOTIFICATION_TITLE] = notificationTitle
          settings[UploadConfigKeys.NOTIFICATION_ID] = notificationId.hashCode()
          settings[UploadConfigKeys.NOTIFICATION_TITLE_NO_INTERNET] = notificationTitleNoInternet
          settings[UploadConfigKeys.NOTIFICATION_TITLE_NO_WIFI] = notificationTitleNoWifi
          settings[UploadConfigKeys.NOTIFICATION_CHANNEL] = notificationChannel
        }
      } catch (exc: Throwable) {
        if (exc !is MissingOptionException) {
          exc.printStackTrace()
          Log.e(TAG, exc.message, exc)
        }
        promise.reject(exc)
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
      if (exc !is MissingOptionException) {
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

