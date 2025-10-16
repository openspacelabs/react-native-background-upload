package com.vydia.RNUploader

import android.content.Context
import androidx.work.WorkManager
import com.vydia.RNUploader.UploaderModule.Companion.WORKER_TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Stores and aggregates total progress from all workers
object UploadProgress {
  private data class Progress(var bytesUploaded: Long, val size: Long)

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
  private fun clearIfNeeded(context: Context) {
    val workManager = WorkManager.getInstance(context)
    val works = workManager.getWorkInfosByTag(WORKER_TAG).get()

    if (works.all { it.state.isFinished }) map.clear()
  }

  init {
    CoroutineScope(Dispatchers.IO).launch {
      while (true) {
        delay(5000L)
        clearIfNeeded(UploaderModule.reactContext ?: continue)
      }
    }
  }
}