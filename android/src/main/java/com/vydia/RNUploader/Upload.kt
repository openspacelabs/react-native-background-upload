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
  companion object {
    fun fromOptions(options: ReadableMap) = Upload(
      id = options.getString("customUploadId") ?: UUID.randomUUID().toString(),
      url = options.getString("url") ?: throw InvalidUploadOptionException("Missing 'url'"),
      path = options.getString("path") ?: throw InvalidUploadOptionException("Missing 'path'"),
      method = (options.getString("method") ?: "POST").let { HttpMethod.parse(it) },
      maxRetries = if (options.hasKey("maxRetries")) options.getInt("maxRetries") else 5,
      wifiOnly = if (options.hasKey("wifiOnly")) options.getBoolean("wifiOnly") else false,
      headers = options.getMap("headers").let { headers ->
        if (headers == null) return@let mapOf()
        val map = mutableMapOf<String, String>()
        for (entry in headers.entryIterator) {
          map[entry.key] = entry.value.toString()
        }
        return@let map
      },
      notificationId = options.getString("notificationId")
        ?: throw InvalidUploadOptionException("Missing 'notificationId'"),
      notificationTitle = options.getString("notificationTitle")
        ?: throw InvalidUploadOptionException("Missing 'notificationTitle'"),
      notificationTitleNoInternet = options.getString("notificationTitleNoInternet")
        ?: throw InvalidUploadOptionException("Missing 'notificationTitleNoInternet'"),
      notificationTitleNoWifi = options.getString("notificationTitleNoWifi")
        ?: throw InvalidUploadOptionException("Missing 'notificationTitleNoWifi'"),
      notificationChannel = options.getString("notificationChannel")
        ?: throw InvalidUploadOptionException("Missing 'notificationChannel'"),
    )
  }
}


class InvalidUploadOptionException(message: String) : IllegalArgumentException(message)
