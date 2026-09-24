package ai.openspace.backgroundupload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueEntryTest {

  private val form = listOf(FormPart("photo", "image/jpeg", null, "/p.jpg", null))

  @Test
  fun `sameBodyAs compares each body kind by content`() {
    val json = entry(descriptor = desc(dataJson = "{\"a\":1}"))
    assertTrue(json.sameBodyAs(desc(dataJson = "{\"a\":1}", headers = mapOf("New" to "h"))))
    assertFalse(json.sameBodyAs(desc(dataJson = "{\"a\":2}")))

    val multipart = entry(descriptor = desc(form = form))
    assertTrue(multipart.sameBodyAs(desc(form = form)))
    assertFalse(multipart.sameBodyAs(desc(form = form.map { it.copy(name = "other") })))

    val file = entry(descriptor = desc(file = "/a.bin"))
    assertTrue(file.sameBodyAs(desc(file = "/a.bin")))
    assertFalse(file.sameBodyAs(desc(file = "/b.bin")))

    val none = entry(descriptor = desc(method = "DELETE"))
    assertTrue(none.sameBodyAs(desc(method = "DELETE")))
  }

  @Test
  fun `chunked compares parts and ignores the path`() {
    val parts = listOf(part(0, 100), part(100, 250))
    val chunked = entry(descriptor = desc(url = null, method = "PUT", file = "/moved.bin", parts = parts))
    assertTrue(chunked.sameBodyAs(desc(url = null, method = "PUT", file = "/elsewhere.bin", parts = parts.reversed())))
    assertFalse(chunked.sameBodyAs(desc(url = null, method = "PUT", file = "/moved.bin", parts = listOf(part(0, 250)))))
  }

  @Test
  fun `a kind change, url change, or method change is a different body`() {
    val json = entry(descriptor = desc(dataJson = "{}"))
    assertFalse(json.sameBodyAs(desc(form = form)))
    assertFalse(json.sameBodyAs(desc(url = "https://example.com/other", dataJson = "{}")))
    assertFalse(json.sameBodyAs(desc(method = "PUT", dataJson = "{}")))
    assertFalse(entry(descriptor = null, legacy = true).sameBodyAs(desc(dataJson = "{}")))
  }

  @Test
  fun `withHeadersPatched matches names in any case and keeps the patch spelling`() {
    val e = entry(descriptor = desc(headers = mapOf("authorization" to "Bearer old", "X-Keep" to "1")))
    val patched = e.withHeadersPatched(mapOf("Authorization" to "Bearer new", "X-Add" to "2"), generation = 3)
    assertEquals(
      mapOf("X-Keep" to "1", "Authorization" to "Bearer new", "X-Add" to "2"),
      patched.descriptor!!.headers,
    )
    assertEquals(3, patched.headerGeneration)
  }

  @Test
  fun `withHeadersPatched replaces a part's own copy of a patched header only`() {
    val parts = listOf(
      Part("https://p/1", mapOf("AUTHORIZATION" to "Bearer stale", "Content-Range" to "0-99"), 0, 100),
      Part("https://p/2", mapOf("Content-Range" to "100-249"), 100, 250),
    )
    val e = entry(descriptor = desc(url = null, file = "/f", parts = parts))
    val patched = e.withHeadersPatched(mapOf("Authorization" to "Bearer new"), 1).descriptor!!.parts!!
    assertEquals(mapOf("Content-Range" to "0-99", "Authorization" to "Bearer new"), patched[0].headers)
    assertEquals(mapOf("Content-Range" to "100-249"), patched[1].headers)
  }

  @Test
  fun `toRow carries vars as an object and nextAttemptAt only when set`() {
    val row = entry().toRow().toMap()
    assertEquals(mapOf("n" to 1.0), row["vars"])
    assertEquals("queued", row["state"])
    assertFalse(row.containsKey("nextAttemptAt"))
    assertEquals(
      setOf("id", "key", "vars", "state", "bytesSent", "totalBytes", "attempts", "updatedAt"),
      row.keys,
    )
    val waiting = entry(nextAttemptAt = 9_000).toRow().toMap()
    assertEquals(9_000.0, waiting["nextAttemptAt"])
  }

  @Test
  fun `a malformed vars text reads as null in the row`() {
    assertNull(entry().copy(varsJson = "{bad").toRow().vars)
  }

  @Test
  fun `isLive covers the four live states`() {
    val live = EntryState.values().filter { it.isLive }.toSet()
    assertEquals(setOf(EntryState.QUEUED, EntryState.RUNNING, EntryState.AWAITING_AUTH, EntryState.PAUSED), live)
  }

  @Test
  fun `header maps match names without regard to case`() {
    assertTrue(HeaderMap.contains(mapOf("Content-Type" to "x"), "content-type"))
    assertEquals("x", HeaderMap.get(mapOf("content-type" to "x"), "Content-Type"))
    assertEquals(mapOf("B" to "2", "a" to "3"), HeaderMap.merge(mapOf("A" to "1", "B" to "2"), mapOf("a" to "3")))
    assertEquals(mapOf("B" to "2"), HeaderMap.without(mapOf("a" to "1", "B" to "2"), "A"))
  }
}
