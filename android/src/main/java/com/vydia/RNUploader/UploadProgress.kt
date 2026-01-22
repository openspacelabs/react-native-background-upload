package com.vydia.RNUploader

import android.os.Handler
import android.os.Looper

// Stores and aggregates total progress from all workers
object UploadProgress {
  private data class Progress(
    var bytesUploaded: Long,
    val size: Long,
    val wifiOnly: Boolean,
    var complete: Boolean = false
  ) {
    fun complete() {
      bytesUploaded = size
      complete = true
    }
  }

  private val map = mutableMapOf<String, Progress>()

  @Synchronized
  fun add(id: String, size: Long, wifiOnly: Boolean) {
    map[id] = Progress(bytesUploaded = 0L, size = size, wifiOnly = wifiOnly)
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

  /**
   * Returns true if any incomplete upload can proceed without WiFi (wifiOnly=false).
   * Used to determine notification text when no upload is actively running:
   * - If true: at least one upload only needs mobile data, so show "Waiting for internet"
   * - If false: all uploads need WiFi, so show "Waiting for WiFi"
   * This ensures the notification reflects the minimum connectivity needed to make progress.
   */
  @Synchronized
  fun hasNonWifiOnlyUploads(): Boolean {
    return map.values.any { !it.complete && !it.wifiOnly }
  }
}