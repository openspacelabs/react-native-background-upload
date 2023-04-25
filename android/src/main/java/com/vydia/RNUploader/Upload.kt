package com.vydia.RNUploader

import com.facebook.react.bridge.ReadableMap
import java.util.*

class Upload(options: ReadableMap) {
  val id = options.getString("customUploadId") ?: UUID.randomUUID().toString()
  val url = options.getString("url") ?: throw InvalidUploadOptionException("Missing 'url'")
  val path = options.getString("path") ?: throw InvalidUploadOptionException("Missing 'path'")
  val method = options.getString("method") ?: "POST"
  val maxRetries =
    if (options.hasKey("maxRetries")) options.getInt("maxRetries")
    else 5

  val wifiOnly =
    if (options.hasKey("wifiOnly")) options.getBoolean("wifiOnly")
    else false

  val notificationId = options.getString("notificationId")
    ?: throw InvalidUploadOptionException("Missing 'notificationId'")


  val headers = options.getMap("headers")?.let { headers ->
    val map = mutableMapOf<String, String>()
    for (entry in headers.entryIterator) {
      map[entry.key] = entry.value.toString()
    }
    return@let map
  }
}

class InvalidUploadOptionException(message: String) : IllegalArgumentException(message)
