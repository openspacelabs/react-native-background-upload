package com.vydia.RNUploader

import com.facebook.react.bridge.ReadableMap
import java.util.*

// Data model of a single upload
// Can be created from RN's ReadableMap
// Can be used for JSON deserialization
data class Upload(
  val id: String,
  val url: String,
  val path: String,
  val method: String,
  val maxRetries: Int,
  val wifiOnly: Boolean,
  val headers: Map<String, String>,
  val notificationId: String,
  val notificationTitle: String,
  val notificationTitleNoInternet: String,
  val notificationTitleNoWifi: String,
  val notificationChannel: String,
) {
  class MissingOptionException(optionName: String) :
    IllegalArgumentException("Missing '$optionName'")

  companion object {
    fun fromReadableMap(map: ReadableMap) = Upload(
      id = map.getString("customUploadId") ?: UUID.randomUUID().toString(),
      url = map.getString(Upload::url.name) ?: throw MissingOptionException(Upload::url.name),
      path = map.getString(Upload::path.name) ?: throw MissingOptionException(Upload::path.name),
      method = map.getString(Upload::method.name) ?: "POST",
      maxRetries = if (map.hasKey(Upload::maxRetries.name)) map.getInt(Upload::maxRetries.name) else 5,
      wifiOnly = if (map.hasKey(Upload::wifiOnly.name)) map.getBoolean(Upload::wifiOnly.name) else false,
      headers = map.getMap(Upload::headers.name).let { headers ->
        if (headers == null) return@let mapOf()
        val map = mutableMapOf<String, String>()
        for (entry in headers.entryIterator) {
          map[entry.key] = entry.value.toString()
        }
        return@let map
      },
      notificationId = map.getString(Upload::notificationId.name)
        ?: throw MissingOptionException(Upload::notificationId.name),
      notificationTitle = map.getString(Upload::notificationTitle.name)
        ?: throw MissingOptionException(Upload::notificationTitle.name),
      notificationTitleNoInternet = map.getString(Upload::notificationTitleNoInternet.name)
        ?: throw MissingOptionException(Upload::notificationTitleNoInternet.name),
      notificationTitleNoWifi = map.getString(Upload::notificationTitleNoWifi.name)
        ?: throw MissingOptionException(Upload::notificationTitleNoWifi.name),
      notificationChannel = map.getString(Upload::notificationChannel.name)
        ?: throw MissingOptionException(Upload::notificationChannel.name),
    )
  }
}



