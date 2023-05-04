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

    @Synchronized
    fun set(context: Context, uploadId: String, bytesUploaded: Long, fileSize: Long) =
      storage(context).edit()
        .putLong("$uploadId-uploaded", bytesUploaded)
        .putLong("$uploadId-size", fileSize)
        .apply()

    @Synchronized
    fun remove(context: Context, uploadId: String) =
      storage(context).edit()
        .remove("$uploadId-uploaded")
        .remove("$uploadId-size")
        .apply()

    @Synchronized
    fun total(context: Context): Double {
      val storage = storage(context)

      val totalBytesUploaded = storage.all.keys
        .filter { it.endsWith("-uploaded") }
        .sumOf { storage.getLong(it, 0L) }

      val totalFileSize = storage.all.keys
        .filter { it.endsWith("-size") }
        .sumOf { storage.getLong(it, 0L) }

      if (totalFileSize == 0L) return 0.0
      return (totalBytesUploaded.toDouble() * 100 / totalFileSize)
    }

    private val handler = Handler(Looper.getMainLooper())

    // Attempt to clear in 2 seconds. This is the simplest way to let the
    // last worker reset the overall progress.
    // Clearing progress ensures the notification starts at 0% next time.
    fun scheduleClearing(context: Context) =
      handler.postDelayed({ clearIfNeeded(context) }, 2000)

    @Synchronized
    fun clearIfNeeded(context: Context) {
      val workManager = WorkManager.getInstance(context)
      val works = workManager.getWorkInfosByTag(WORKER_TAG).get()
      if (works.any { !it.state.isFinished }) return

      val storage = storage(context)
      storage.edit().clear().apply()
    }
  }
}