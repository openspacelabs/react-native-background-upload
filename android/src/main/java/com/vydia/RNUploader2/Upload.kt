package com.vydia.RNUploader2

import com.facebook.react.bridge.ReadableMap
import java.util.UUID

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
) {
  // Progress tracking properties
  var bytesUploaded: Long = 0L
  var size: Long = 0L

  companion object {
    fun fromRawOptions(map: ReadableMap) = Upload(
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
    )
  }
}
