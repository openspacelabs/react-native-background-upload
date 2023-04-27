package com.vydia.RNUploader

import android.content.Context

class UploadProgress {

  companion object {
    private fun storage(context: Context) =
      context.getSharedPreferences("RNFileUpload-Progress", Context.MODE_PRIVATE)

    fun start(context: Context, uploadId: String, fileSize: Long) =
      storage(context).edit()
        .putLong("$uploadId-uploaded", 0)
        .putLong("$uploadId-size", fileSize)
        .commit()

    fun update(context: Context, uploadId: String, bytesUploaded: Long) =
      storage(context).edit()
        .putLong("$uploadId-uploaded", bytesUploaded)
        .commit()

    fun total(context: Context): Int {
      val storage = storage(context)

      val totalBytesUploaded = storage.all.keys
        .filter { it.endsWith("-uploaded") }
        .sumOf { storage.getLong(it, 0L) }

      val totalFileSize = storage.all.keys
        .filter { it.endsWith("-size") }
        .sumOf { storage.getLong(it, 0L) }


      if (totalFileSize == 0L) return 0
      return (totalBytesUploaded * 100 / totalFileSize).toInt()
    }

    fun maybeClear(context: Context) {
      if (total(context) < 100) return
      val storage = storage(context)
      val editor = storage.edit()
      storage.all.keys.forEach { key -> editor.remove(key) }
      editor.apply()
    }
  }
}