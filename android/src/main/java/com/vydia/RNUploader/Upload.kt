package com.vydia.RNUploader

import com.facebook.react.bridge.ReadableMap
import io.ktor.http.*
import java.util.*

// Data model of a single upload
// Can be created from RN's ReadableMap
// Can be used for JSON deserialization
data class Upload(
  val id: String,
  val url: String,
  val path: String,
  val method: HttpMethod,
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
    fun fromOptions(o: ReadableMap) = Upload(
      id = o.getString("customUploadId") ?: UUID.randomUUID().toString(),
      url = o.getString(Upload::url.name) ?: throw MissingOptionException(Upload::url.name),
      path = o.getString(Upload::path.name) ?: throw MissingOptionException(Upload::path.name),
      method = (o.getString(Upload::method.name) ?: "POST").let { HttpMethod.parse(it) },
      maxRetries = if (o.hasKey(Upload::maxRetries.name)) o.getInt(Upload::maxRetries.name) else 5,
      wifiOnly = if (o.hasKey(Upload::wifiOnly.name)) o.getBoolean(Upload::wifiOnly.name) else false,
      headers = o.getMap(Upload::headers.name).let { headers ->
        if (headers == null) return@let mapOf()
        val map = mutableMapOf<String, String>()
        for (entry in headers.entryIterator) {
          map[entry.key] = entry.value.toString()
        }
        return@let map
      },
      notificationId = o.getString(Upload::notificationId.name)
        ?: throw MissingOptionException(Upload::notificationId.name),
      notificationTitle = o.getString(Upload::notificationTitle.name)
        ?: throw MissingOptionException(Upload::notificationTitle.name),
      notificationTitleNoInternet = o.getString(Upload::notificationTitleNoInternet.name)
        ?: throw MissingOptionException(Upload::notificationTitleNoInternet.name),
      notificationTitleNoWifi = o.getString(Upload::notificationTitleNoWifi.name)
        ?: throw MissingOptionException(Upload::notificationTitleNoWifi.name),
      notificationChannel = o.getString(Upload::notificationChannel.name)
        ?: throw MissingOptionException(Upload::notificationChannel.name),
    )
  }
}



