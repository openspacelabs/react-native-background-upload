package com.vydia.RNUploader

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.WorkManager
import com.vydia.RNUploader.UploaderModule.Companion.WORKER_TAG

// Stores and aggregates total progress from all workers
class UploadProgress {

  companion object {
    private fun storage(context: Context) =
      context.getSharedPreferences("RNFileUpload-Progress", Context.MODE_PRIVATE)

    fun set(context: Context, uploadId: String, bytesUploaded: Long, fileSize: Long) =
      storage(context).edit()
        .putLong("$uploadId-uploaded", bytesUploaded)
        .putLong("$uploadId-size", fileSize)
        .commit()

    fun remove(context: Context, uploadId: String) =
      storage(context).edit()
        .remove("$uploadId-uploaded")
        .remove("$uploadId-size")
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

    private val handler = Handler(Looper.getMainLooper())

    fun scheduleClearing(context: Context) =
      // Try clearing in 2 seconds. This is the safest and simplest way.
      handler.postDelayed({ maybeClear(context) }, 2000)

    fun maybeClear(context: Context) {
      val workManager = WorkManager.getInstance(context)
      val works = workManager.getWorkInfosByTag(WORKER_TAG).get()
      if (works.any { !it.state.isFinished }) return

      val storage = storage(context)
      val editor = storage.edit()
      storage.all.keys.forEach { key -> editor.remove(key) }
      editor.commit()
    }

    fun cancelScheduledClearing() {
      handler.removeCallbacksAndMessages(null)
    }
  }
}