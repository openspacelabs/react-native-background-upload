package ai.openspace.backgroundupload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkedPartsTest {

  @Test
  fun `tilesExactly covers the edge shapes`() {
    assertTrue(ChunkedParts.tilesExactly(listOf(part(0, 100), part(100, 250)), 250))
    assertTrue(ChunkedParts.tilesExactly(listOf(part(100, 250), part(0, 100)), 250)) // any order
    assertFalse(ChunkedParts.tilesExactly(emptyList(), 0))
    assertFalse(ChunkedParts.tilesExactly(listOf(part(0, 0)), 0)) // empty range
    assertFalse(ChunkedParts.tilesExactly(listOf(part(0, 100), part(150, 250)), 250)) // gap
    assertFalse(ChunkedParts.tilesExactly(listOf(part(0, 150), part(100, 250)), 250)) // overlap
    assertFalse(ChunkedParts.tilesExactly(listOf(part(50, 250)), 250)) // not from 0
    assertFalse(ChunkedParts.tilesExactly(listOf(part(0, 200)), 250)) // short
    assertFalse(ChunkedParts.tilesExactly(listOf(part(0, 251)), 250)) // past the end
  }

  @Test
  fun `the same parts in another order are the same upload`() {
    val a = listOf(part(0, 100), part(100, 250))
    assertTrue(ChunkedParts.sameParts(a, a.reversed()))
    // Headers are not compared: a resume sends fresh ones.
    assertTrue(ChunkedParts.sameParts(a, a.map { it.copy(headers = mapOf("X" to "new")) }))
  }

  @Test
  fun `new urls or a new split are different parts`() {
    val a = listOf(part(0, 100), part(100, 250))
    assertFalse(ChunkedParts.sameParts(a, listOf(part(0, 100, url = "https://v2/1"), part(100, 250))))
    assertFalse(ChunkedParts.sameParts(a, listOf(part(0, 120), part(120, 250))))
    assertFalse(ChunkedParts.sameParts(a, listOf(part(0, 250))))
  }

  @Test
  fun `accepted flags follow the range, not the index`() {
    val stored = listOf(part(0, 100, accepted = true), part(100, 250))
    val incoming = listOf(part(100, 250), part(0, 100))
    val carried = ChunkedParts.carryAccepted(stored, incoming)
    assertTrue(carried.first { it.start == 0L }.accepted)
    assertFalse(carried.first { it.start == 100L }.accepted)
  }

  @Test
  fun `byte math and pending indexes`() {
    val parts = listOf(part(0, 100, accepted = true), part(100, 250))
    assertEquals(250, ChunkedParts.totalBytes(parts))
    assertEquals(100, ChunkedParts.acceptedBytes(parts))
    assertEquals(listOf(1), ChunkedParts.pendingIndexes(parts))
    assertEquals(emptyList<Int>(), ChunkedParts.pendingIndexes(ChunkedParts.withAccepted(parts, 1)))
  }
}
