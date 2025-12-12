package com.vydia.RNUploader

import android.os.Handler
import android.os.Looper

// Stores and aggregates total progress from all workers
object UploadProgress {
  private data class Progress(
    var bytesUploaded: Long,
    val size: Long,
    var complete: Boolean = false
  ) {
    fun complete() {
      bytesUploaded = size
      complete = true
    }
  }

  private val map = mutableMapOf<String, Progress>()

  @Synchronized
  fun add(id: String, size: Long) {
    map[id] = Progress(bytesUploaded = 0L, size = size)
  }

  @Synchronized
  fun set(uploadId: String, bytesUploaded: Long) {
    map[uploadId]?.bytesUploaded = bytesUploaded
  }

  @Synchronized
  fun complete(uploadId: String) {
    map[uploadId]?.complete()

    // Attempt to clear in 2 seconds. This is the simplest way to let the
    // last worker reset the overall progress.
    // Clearing progress ensures the notification starts at 0% next time.
    Handler(Looper.getMainLooper()).postDelayed({ clearIfCompleted() }, 2000)
  }

  @Synchronized
  fun remove(uploadId: String) {
    map.remove(uploadId)
  }

  @Synchronized
  fun total(): Double {
    val totalBytesUploaded = map.values.sumOf { it.bytesUploaded }
    val totalFileSize = map.values.sumOf { it.size }
    if (totalFileSize == 0L) return 0.0
    return (totalBytesUploaded.toDouble() * 100 / totalFileSize)
  }

  @Synchronized
  private fun clearIfCompleted() {
    if (map.values.all { it.complete }) map.clear()
  }
}