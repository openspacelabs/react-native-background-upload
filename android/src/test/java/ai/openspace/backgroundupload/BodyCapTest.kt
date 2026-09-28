package ai.openspace.backgroundupload

import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.ForwardingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyCapTest {
  /** A source that counts the bytes read from it. */
  private class Counting(bytes: ByteArray) : ForwardingSource(Buffer().write(bytes)) {
    var read = 0L
    override fun read(sink: Buffer, byteCount: Long): Long =
      super.read(sink, byteCount).also { if (it > 0) read += it }
  }

  private fun source(bytes: ByteArray): Pair<Counting, BufferedSource> {
    val c = Counting(bytes)
    return c to c.buffer()
  }

  @Test
  fun `a body under the cap is read whole`() {
    val (_, s) = source("hello".toByteArray())
    assertEquals(BodyCap.Capped("hello", false), BodyCap.read(s, 10))
  }

  @Test
  fun `a body at exactly the cap is not truncated`() {
    val (_, s) = source("0123456789".toByteArray())
    assertEquals(BodyCap.Capped("0123456789", false), BodyCap.read(s, 10))
  }

  @Test
  fun `a huge body stops streaming just past the cap`() {
    val (counting, s) = source(ByteArray(5_000_000) { 'x'.code.toByte() })
    val capped = BodyCap.read(s, 1_000)
    assertTrue(capped.truncated)
    assertEquals(1_000, capped.text.length)
    // okio reads in 8 KB segments; nowhere near the 5 MB body.
    assertTrue("read ${counting.read}", counting.read < 64 * 1024)
  }

  @Test
  fun `a cut inside a UTF-8 character backs off to the last whole one`() {
    val euros = "€".repeat(4).toByteArray(Charsets.UTF_8) // 12 bytes
    val (_, s) = source(euros)
    val capped = BodyCap.read(s, 10)
    assertEquals("€".repeat(3), capped.text)
    assertTrue(capped.truncated)
  }

  @Test
  fun `cap measures UTF-8 bytes, not characters`() {
    assertEquals("ab" to false, BodyCap.cap("ab", 2))
    assertEquals("é" to true, BodyCap.cap("éé", 3))
    assertEquals(null to false, BodyCap.cap(null, 3))
    // A 4-byte character (an emoji) is never split.
    assertEquals("a" to true, BodyCap.cap("a😀", 4))
  }

  @Test
  fun `bytes that are not UTF-8 are cut at the cap`() {
    val bad = ByteArray(8) { 0x80.toByte() } // continuation bytes only
    assertEquals(5, BodyCap.utf8Boundary(bad, 5))
    assertEquals(3, BodyCap.utf8Boundary("abc".toByteArray(), 5))
  }
}
