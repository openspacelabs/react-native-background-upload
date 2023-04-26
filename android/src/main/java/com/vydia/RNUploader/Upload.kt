package com.vydia.RNUploader

import com.facebook.react.bridge.ReadableMap
import java.util.*

data class Upload(
  val id: String,
  val url: String,
  val path: String,
  val method: String,
  val maxRetries: Int,
  val wifiOnly: Boolean,
  val notificationId: String,
  val headers: Map<String, String>
) {
  companion object {
    fun fromOptions(options: ReadableMap) = Upload(
      id = options.getString("customUploadId") ?: UUID.randomUUID().toString(),
      url = options.getString("url") ?: throw InvalidUploadOptionException("Missing 'url'"),
      path = options.getString("path") ?: throw InvalidUploadOptionException("Missing 'path'"),
      method = options.getString("method") ?: "POST",
      maxRetries = if (options.hasKey("maxRetries")) options.getInt("maxRetries") else 5,
      wifiOnly = if (options.hasKey("wifiOnly")) options.getBoolean("wifiOnly") else false,
      notificationId = options.getString("notificationId")
        ?: throw InvalidUploadOptionException("Missing 'notificationId'"),
      headers = options.getMap("headers").let { headers ->
        if (headers == null) return@let mapOf()
        val map = mutableMapOf<String, String>()
        for (entry in headers.entryIterator) {
          map[entry.key] = entry.value.toString()
        }
        return@let map
      }
    )

  }
}


class InvalidUploadOptionException(message: String) : IllegalArgumentException(message)
