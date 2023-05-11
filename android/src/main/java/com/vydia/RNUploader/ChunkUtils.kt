package com.vydia.RNUploader

import com.facebook.react.bridge.ReadableArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.FileInputStream
import java.io.FileOutputStream


data class Chunk(val position: Long, val size: Long, val path: String) {
  companion object {
    fun fromReactMethodParams(paramChunks: ReadableArray): List<Chunk> {
      val chunks = mutableListOf<Chunk>()
      for (i in 0 until paramChunks.size()) {
        val paramChunk = paramChunks.getMap(i)
        val position = paramChunk.getDouble("position").toLong()
        val size = paramChunk.getDouble("size").toLong()
        val path = paramChunk.getString("path") ?: throw Throwable("Path is not defined")

        if (size <= 0) throw Throwable("Size is smaller than or equal 0")
        if (position < 0) throw Throwable("Position is smaller than 0")

        chunks.add(Chunk(position, size, path))
      }

      return chunks
    }
  }
}

private const val BUFFER_SIZE = 128 * 1024 // 128 KB

suspend fun chunkFile(parentFilePath: String, chunks: List<Chunk>) = coroutineScope {
  chunks.map { chunk ->
    async(Dispatchers.IO) {
      val input = FileInputStream(parentFilePath)
      val output = FileOutputStream(chunk.path)
      try {
        val buffer = ByteArray(BUFFER_SIZE)
        input.skip(chunk.position)

        var remainingBytes = chunk.size
        while (remainingBytes > 0) {
          val bytesRead = input.read(buffer, 0, minOf(BUFFER_SIZE.toLong(), remainingBytes).toInt())
          if (bytesRead == -1) break
          output.write(buffer, 0, bytesRead)
          remainingBytes -= bytesRead
        }
      } finally {
        input.close()
        output.close()
      }
    }
  }.awaitAll()
}

