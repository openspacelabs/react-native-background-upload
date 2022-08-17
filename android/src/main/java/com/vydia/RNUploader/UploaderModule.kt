package com.vydia.RNUploader

import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.facebook.react.bridge.*
import java.io.File

data class DeferredUpload(val id: String, val options: StartUploadOptions)

class UploaderModule(val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {
  private val TAG = "UploaderBridge"
  private val workManager = WorkManager.getInstance(reactContext)
  private val connectivityManager =
    reactContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager


  override fun getName(): String {
    return "RNFileUploader"
  }

  // Store data in static variables in case JS reloads
  companion object {
    val deferredUploads = mutableListOf<DeferredUpload>()
  }

  init {
    // Initialize everything here so listeners can continue to listen
    // seamlessly after JS reloads

    // == register upload listener ==
    GlobalRequestListener.initialize(reactContext)

    // == register network listener ==

    connectivityManager.registerDefaultNetworkCallback(object : NetworkCallback() {
      override fun onAvailable(network: Network) {
        processDeferredUploads()
      }

      override fun onCapabilitiesChanged(
        network: Network,
        networkCapabilities: NetworkCapabilities
      ) {
        processDeferredUploads()
      }

      override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        processDeferredUploads()
      }

      override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
        processDeferredUploads()
      }
    })
  }

  /**
   * Gets file information for the path specified.  Example valid path is: /storage/extSdCard/DCIM/Camera/20161116_074726.mp4
   * Returns an object such as: {extension: "mp4", size: "3804316", exists: true, mimeType: "video/mp4", name: "20161116_074726.mp4"}
   */
  @ReactMethod
  fun getFileInfo(path: String, promise: Promise) {
    try {
      val params = Arguments.createMap()
      val fileInfo = File(path)
      params.putString("name", fileInfo.name)
      if (!fileInfo.exists() || !fileInfo.isFile) {
        params.putBoolean("exists", false)
      } else {
        params.putBoolean("exists", true)
        params.putString(
          "size",
          fileInfo.length().toString()
        ) //use string form of long because there is no putLong and converting to int results in a max size of 17.2 gb, which could happen.  Javascript will need to convert it to a number
        val extension = MimeTypeMap.getFileExtensionFromUrl(path)
        params.putString("extension", extension)
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        params.putString("mimeType", mimeType)
      }
      promise.resolve(params)
    } catch (exc: Exception) {
      exc.printStackTrace()
      Log.e(TAG, exc.message, exc)
      promise.reject(exc)
    }
  }

  @ReactMethod
  fun chunkFile(parentFilePath: String, chunkDirPath: String, numChunks: Int, promise: Promise) {
    try {
      promise.resolve(chunkFile(parentFilePath, chunkDirPath, numChunks))
    } catch (error: Throwable) {
      promise.reject(error)
    }
  }

  private fun processDeferredUploads() {
    val uploads = deferredUploads.size
    Log.d(TAG, "Processing $uploads deferred uploads")

    val startedUploads = mutableListOf<DeferredUpload>()
    deferredUploads.forEach {
      if (_startUpload(it.options)) startedUploads.add(it)
    }
    deferredUploads.removeAll(startedUploads)
  }


  /**
   * Starts a file upload.
   * Returns a promise with the string ID of the upload.
   */
  @ReactMethod
  fun startUpload(rawOptions: ReadableMap, promise: Promise) {
    try {
      val options = StartUploadOptions(rawOptions)
      val started = _startUpload(options)
      if (!started) deferredUploads.add(DeferredUpload(options.id, options))
      promise.resolve(options.id)
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
  private fun _startUpload(options: StartUploadOptions): Boolean {

    val notificationManager =
      (reactContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
    initializeNotificationChannel(options.notificationChannel, notificationManager)

    val request = if (options.requestType == StartUploadOptions.RequestType.RAW) {
      OneTimeWorkRequestBuilder<UploadWorkerRaw>().setInputData(options.toData()).build()
    } else {
      OneTimeWorkRequestBuilder<UploadWorkerMultipart>().setInputData(options.toData()).build()
    }

    if (!validateNetwork(options.discretionary, connectivityManager)) return false

    workManager.enqueue(request)
    return true
  }


  /**
   * Cancels file upload
   * Accepts upload ID as a first argument, this upload will be cancelled
   * Event "cancelled" will be fired when upload is cancelled.
   */
  @ReactMethod
  fun cancelUpload(cancelUploadId: String?, promise: Promise) {
    if (cancelUploadId !is String) {
      promise.reject(InvalidUploadOptionException("Upload ID must be a string"))
      return
    }

    // look in the deferredUploads list first
    if (deferredUploads.removeIf {
        it.id == cancelUploadId
      }) {
      promise.resolve(true)
      // report error for consistency sake
      //      uploadEventListener.onError(
      //        reactContext,
      //        UploadInfo(cancelUploadId),
      //        UserCancelledUploadException()
      //      )
      return
    }

    // if it's not in the deferredUploads, it must have been started,
    // so we call stopUpload()
    try {
      // TODO
      // UploadService.stopUpload(cancelUploadId)
      promise.resolve(true)
    } catch (exc: java.lang.Exception) {
      exc.printStackTrace()
      Log.e(TAG, exc.message, exc)
      promise.reject(exc)
    }
  }

  /**
   * Cancels all file uploads
   */
  @ReactMethod
  fun stopAllUploads(promise: Promise) {
    try {
      // TODO
      // UploadService.stopAllUploads()
      promise.resolve(true)
    } catch (exc: java.lang.Exception) {
      exc.printStackTrace()
      Log.e(TAG, exc.message, exc)
      promise.reject(exc)
    }
  }
}


