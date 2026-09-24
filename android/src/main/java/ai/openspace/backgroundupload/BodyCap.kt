package ai.openspace.backgroundupload

import okio.Buffer
import okio.BufferedSource
import java.nio.charset.Charset

/**
 * Response body caps, in UTF-8 bytes. A cut never splits a character: it
 * backs off to the last whole one. [read] applies the cap while the body
 * streams in, so a huge error page never sits in memory whole.
 */
object BodyCap {
  /** 1 MB, the RawResponse cap of a settled outcome. */
  const val SETTLED_MAX_BYTES = 1_048_576

  /** 4 KB, the body cap of a live attempt event. */
  const val ATTEMPT_MAX_BYTES = 4 * 1024

  /** A body as text and whether the cap cut it. */
  data class Capped(val text: String, val truncated: Boolean)

  /**
   * Reads at most [maxBytes] of [source]. Bytes past the cap are not read.
   * A UTF-8 body is cut on a character boundary; another charset is cut at
   * the byte cap and decoded as it is.
   */
  fun read(source: BufferedSource, maxBytes: Int, charset: Charset = Charsets.UTF_8): Capped {
    val buffer = Buffer()
    val limit = maxBytes.toLong() + 1
    while (buffer.size < limit) {
      if (source.read(buffer, limit - buffer.size) == -1L) break
    }
    val truncated = buffer.size > maxBytes
    val bytes = buffer.readByteArray()
    val keep = if (!truncated) bytes.size
    else if (charset == Charsets.UTF_8) utf8Boundary(bytes, maxBytes)
    else maxBytes
    return Capped(String(bytes, 0, keep, charset), truncated)
  }

  /** [text] cut to at most [maxBytes] of UTF-8. Null stays null. */
  fun cap(text: String?, maxBytes: Int): Pair<String?, Boolean> {
    if (text == null) return null to false
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return text to false
    return String(bytes, 0, utf8Boundary(bytes, maxBytes), Charsets.UTF_8) to true
  }

  /**
   * The longest prefix length of [bytes], at most [max], that does not end
   * inside a UTF-8 sequence. A continuation byte is 10xxxxxx.
   */
  internal fun utf8Boundary(bytes: ByteArray, max: Int): Int {
    if (bytes.size <= max) return bytes.size
    var end = max
    // bytes[end] is the first byte cut off. While it continues a sequence,
    // the sequence started before the cut, so drop its start too. A UTF-8
    // character is at most 4 bytes; past 3 steps the bytes are not UTF-8,
    // and the cut stays at max.
    var steps = 0
    while (end > 0 && steps < 3 && (bytes[end].toInt() and 0xC0) == 0x80) {
      end--
      steps++
    }
    return if ((bytes[end].toInt() and 0xC0) == 0x80) max else end
  }
}
