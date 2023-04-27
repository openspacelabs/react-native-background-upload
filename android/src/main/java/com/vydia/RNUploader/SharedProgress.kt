package com.vydia.RNUploader

import android.content.Context

class SharedProgress(context: Context) {
  private val storage =
    context.getSharedPreferences("RNFileUpload-Progress", Context.MODE_PRIVATE)


  fun update(uploadId: String, bytesUploaded: Long, fileSize: Long) {
    storage.edit()
      .putLong("$uploadId-uploaded", bytesUploaded)
      .putLong("$uploadId-size", fileSize)
      .commit()
  }

  fun total(): Int {
    val totalBytesUploaded = storage.all.keys
      .filter { it.endsWith("-uploaded") }
      .sumOf { storage.getLong(it, 0L) }

    val totalFileSize = storage.all.keys
      .filter { it.endsWith("-size") }
      .sumOf { storage.getLong(it, 0L) }

    return ((totalBytesUploaded / totalFileSize) * 100).toInt()
  }

  fun clear() {
    val editor = storage.edit()
    storage.all.keys.forEach { key -> editor.remove(key) }
    editor.apply()
  }
}