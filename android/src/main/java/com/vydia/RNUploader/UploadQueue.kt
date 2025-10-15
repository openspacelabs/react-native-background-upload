package com.vydia.RNUploader

import java.io.File
import java.io.FileNotFoundException

object UploadQueue {
  private val queue = ArrayDeque<Upload>()

  /**
   * Keeps track of the total bytes of completed uploads to report overall progress correctly.
   */
  private var completedBytes = 0L

  /**
   * Returns the overall progress percentage of all uploads in the queue.
   * 0 - 100
   */
  @Synchronized
  fun progressPercentage(): Float {
    if (queue.isEmpty()) return 0f

    val totalBytes = queue.sumOf { it.size } + completedBytes
    if (totalBytes == 0L) return 0f
    val uploadedBytes = (current()?.bytesUploaded ?: 0L) + completedBytes

    return uploadedBytes.toFloat() / totalBytes.toFloat() * 100
  }

  @Synchronized
  fun add(upload: Upload) {
    if (queue.any { it.id == upload.id }) return

    val file = File(upload.path)
    if (file.exists())
      upload.size = file.length()
    else
      throw FileNotFoundException("File at path ${upload.path} does not exist")

    queue.add(upload)
  }

  @Synchronized
  fun progress(bytesUploaded: Long) {
    current()?.bytesUploaded = bytesUploaded
  }

  @Synchronized
  fun complete() {
    // Extract size immediately and allow upload object to be garbage collected
    completedBytes += queue.removeFirst().size
  }

  @Synchronized
  fun pop() = queue.removeFirst()

  @Synchronized
  fun cancel(uploadId: String) = queue.removeIf { it.id == uploadId }


  @Synchronized
  fun skipWifiOnly(): Boolean {
    // Find index instead of element to avoid double search
    val index = queue.indexOfFirst { !it.wifiOnly }
    if (index == -1) return false
    if (index == 0) return true // Already at front

    // Remove by index (still O(n) but avoids the find step)
    val upload = queue.removeAt(index)
    queue.addFirst(upload)
    return true
  }

  @Synchronized
  fun isAllWifiOnly() = !queue.isEmpty() && queue.all { it.wifiOnly }

  @Synchronized
  fun current() = queue.firstOrNull()

  @Synchronized
  fun clear() {
    queue.clear()
    completedBytes = 0L
  }

  @Synchronized
  fun isEmpty() = queue.isEmpty()
}