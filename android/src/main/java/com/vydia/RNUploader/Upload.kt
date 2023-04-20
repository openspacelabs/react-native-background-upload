package com.vydia.RNUploader

import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.WritableNativeMap
import net.gotev.uploadservice.data.UploadNotificationConfig
import net.gotev.uploadservice.data.UploadNotificationStatusConfig
import java.util.*

data class RNUploadId(val value: String)
data class UploadServiceId(val value: String)
class Upload(options: ReadableMap) {

  // Store data in static variables in case JS reloads
  companion object {
    val defaultNotificationChannel = "BackgroundUploadChannel"
    val uploads = mutableMapOf<RNUploadId, Upload>()
    fun uploadByRequestId(requestId: UploadServiceId): Upload? {
      return uploads.values.find { it.requestId == requestId }
    }
  }

  enum class RequestType { RAW, MULTIPART }

  val id: RNUploadId
  val url: String
  val path: String
  var method = "POST"
    private set
  var requestType = RequestType.RAW
    private set
  var notificationChannel = defaultNotificationChannel
    private set
  var wifiOnly = false
    private set
  var maxRetries = 2
    private set
  var notification: UploadNotificationConfig? = null
    private set
  var headers: Map<String, String> = emptyMap()
    private set

  // === For Multipart ===

  var parameters: Map<String, String> = emptyMap()
    private set
  var field: String = ""
    private set

  // === State Data ===

  // the uploadID given to AndroidUploadService
  var requestId: UploadServiceId? = null

  init {
    id = RNUploadId(options.getString("customUploadId") ?: UUID.randomUUID().toString())
    url = options.getString("url") ?: throw InvalidUploadOptionException("Missing 'url' field.")
    path = options.getString("path") ?: throw InvalidUploadOptionException("Missing 'path' field.")
    method = options.getString("method") ?: method

    if (options.hasKey("type")) {
      val requestType =
        options.getString("type") ?: throw InvalidUploadOptionException("type must be string.")

      if (requestType != "raw" && requestType != "multipart")
        throw InvalidUploadOptionException("type should be string: raw or multipart.")

      this.requestType = RequestType.valueOf(requestType.uppercase())
    }

    if (options.hasKey("maxRetries") && options.getType("maxRetries") == ReadableType.Number)
      maxRetries = options.getInt("maxRetries")

    if (options.hasKey("wifiOnly"))
      wifiOnly = options.getBoolean("wifiOnly")


    val notification: WritableMap = WritableNativeMap()
    notification.putBoolean("enabled", true)
    if (options.hasKey("notification"))
      notification.merge(options.getMap("notification")!!)
    notificationChannel = notification.getString("notificationChannel") ?: notificationChannel

    if (notification.getBoolean("enabled")) {
      this.notification = UploadNotificationConfig(
        notificationChannelId = notificationChannel,
        isRingToneEnabled = notification.hasKey("enableRingTone") && notification.getBoolean("enableRingTone"),
        progress = UploadNotificationStatusConfig(
          title = if (notification.hasKey("onProgressTitle")) notification.getString("onProgressTitle")!! else "",
          message = if (notification.hasKey("onProgressMessage")) notification.getString("onProgressMessage")!! else ""
        ),
        success = UploadNotificationStatusConfig(
          title = if (notification.hasKey("onCompleteTitle")) notification.getString("onCompleteTitle")!! else "",
          message = if (notification.hasKey("onCompleteMessage")) notification.getString("onCompleteMessage")!! else "",
          autoClear = notification.hasKey("autoClear") && notification.getBoolean("autoClear")
        ),
        error = UploadNotificationStatusConfig(
          title = if (notification.hasKey("onErrorTitle")) notification.getString("onErrorTitle")!! else "",
          message = if (notification.hasKey("onErrorMessage")) notification.getString("onErrorMessage")!! else ""
        ),
        cancelled = UploadNotificationStatusConfig(
          title = if (notification.hasKey("onCancelledTitle")) notification.getString("onCancelledTitle")!! else "",
          message = if (notification.hasKey("onCancelledMessage")) notification.getString("onCancelledMessage")!! else ""
        )
      )
    }

    val parameters = options.getMap("parameters")
    if (parameters != null) {
      if (requestType != RequestType.MULTIPART)
        throw InvalidUploadOptionException("Parameters supported only in multipart type")

      val map = mutableMapOf<String, String>()
      val keys = parameters.keySetIterator()
      while (keys.hasNextKey()) {
        val key = keys.nextKey()
        map[key] = parameters.getString(key) ?: ""
      }

      this.parameters = map
    }

    if (requestType == RequestType.MULTIPART) {
      val field = options.getString("field")
        ?: throw InvalidUploadOptionException("field is required field for multipart type.")
      this.field = field
    }


    val headers = options.getMap("headers")
    if (headers != null) {
      val map = mutableMapOf<String, String>()
      val keys = headers.keySetIterator()
      while (keys.hasNextKey()) {
        val key = keys.nextKey()
        map[key] = headers.getString(key) ?: ""
      }

      this.headers = map
    }
  }
}

class InvalidUploadOptionException(message: String) : IllegalArgumentException(message)
