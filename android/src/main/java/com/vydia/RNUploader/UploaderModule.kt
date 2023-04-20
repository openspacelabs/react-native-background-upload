package com.vydia.RNUploader

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.facebook.react.BuildConfig
import com.facebook.react.bridge.*
import com.vydia.RNUploader.Upload.Companion.defaultNotificationChannel
import com.vydia.RNUploader.Upload.Companion.uploads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.gotev.uploadservice.UploadService
import net.gotev.uploadservice.UploadServiceConfig.initialize
import net.gotev.uploadservice.UploadServiceConfig.retryPolicy
import net.gotev.uploadservice.UploadServiceConfig.threadPool
import net.gotev.uploadservice.data.RetryPolicyConfig
import net.gotev.uploadservice.observer.request.GlobalRequestObserver
import net.gotev.uploadservice.okhttp.OkHttpStack
import java.util.*
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit


class UploaderModule(val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {
  private val uploadEventListener = GlobalRequestObserverDelegate(reactContext)
  private val connectivityManager =
    reactContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  private val ioCoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())


  companion object {
    val TAG = "UploaderBridge"
    var httpStack: OkHttpStack? = null
    var wifiOnlyHttpStack: OkHttpStack? = null
  }

  override fun getName(): String {
    return "RNFileUploader"
  }


  init {
    // Initialize everything here so listeners can continue to listen
    // seamlessly after JS reloads

    // == limit number of concurrent uploads ==
    val pool = threadPool as ThreadPoolExecutor
    pool.corePoolSize = 1
    pool.maximumPoolSize = 1

    // == set retry policy ==
    retryPolicy = RetryPolicyConfig(
      // higher wait time to make time to wait for network change
      // and get checked if the request needs to be cancelled instead of emitting errors
      initialWaitTimeSeconds = 20,
      maxWaitTimeSeconds = TimeUnit.HOURS.toSeconds(1).toInt(),
      multiplier = 2,
      defaultMaxRetries = 2 // this will be overridden by the `maxRetries` option
    )


    val application = reactContext.applicationContext as Application

    // == initialize UploadService ==
    initialize(application, defaultNotificationChannel, BuildConfig.DEBUG)

    // == register upload listener ==
    GlobalRequestObserver(application, uploadEventListener)

    // == register network listener ==
    observeNetwork(connectivityManager,
      { network ->
        httpStack = buildHttpStack(network)
        handleNetworkChange(false)
      },
      { network ->
        wifiOnlyHttpStack = buildHttpStack(network)
        handleNetworkChange(true)
      }
    )
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

  private fun handleNetworkChange(wifiOnly: Boolean) {
    uploads.values
      .filter { it.wifiOnly == wifiOnly }
      .forEach {
        // stop the upload because we're switching network
        // setting requestId to null to prevent cancellation event reporting
        maybeCancelUpload(it.id, true)
        maybeStartUpload(it)
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
    if (upload.wifiOnly && wifiOnlyHttpStack == null) return
    if (!upload.wifiOnly && httpStack == null) return

    val notificationManager =
      (reactContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
    initializeNotificationChannel(upload.notificationChannel, notificationManager)

    val requestId = UUID.randomUUID().toString()

    val request = if (upload.requestType == Upload.RequestType.RAW) {
      UploadRequestBinary(reactContext, upload.url, upload.wifiOnly).apply {
        setFileToUpload(upload.path)
      }
    } else {
      UploadRequestMultipart(reactContext, upload.url, upload.wifiOnly).apply {
        addFileToUpload(upload.path, upload.field)
        upload.parameters.forEach { (key, value) -> addParameter(key, value) }
      }
    }
    Log.i(TAG, "starting request ID $requestId for ${upload.id}")

    request.apply {
      setMethod(upload.method)
      setMaxRetries(upload.maxRetries)
      setUploadID(requestId)
      upload.notification.let { if (it != null) setNotificationConfig { _, _ -> it } }
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

