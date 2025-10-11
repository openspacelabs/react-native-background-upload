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
    val uploadedBytes = current().bytesUploaded + completedBytes

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
  fun progress(uploadId: String, bytesUploaded: Long) {
    queue.find { it.id == uploadId }?.bytesUploaded = bytesUploaded
  }

  @Synchronized
  fun complete() {
    val upload = queue.removeFirst()

    upload.bytesUploaded = upload.size
    upload.completed = true
    // Keep completed upload sizes for a while to report overall progress correctly
    completedBytes += upload.size
  }

  @Synchronized
  fun pop() = queue.removeFirst()

  @Synchronized
  fun cancel(uploadId: String) = queue.removeIf { it.id == uploadId }

  @Synchronized
  fun current() = queue.first()

  @Synchronized
  fun clear() {
    queue.clear()
    completedBytes = 0L
  }

  @Synchronized
  fun isEmpty() = queue.isEmpty()
}