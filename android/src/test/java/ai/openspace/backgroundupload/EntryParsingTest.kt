package ai.openspace.backgroundupload

import com.facebook.react.bridge.JavaOnlyArray
import com.facebook.react.bridge.JavaOnlyMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EntryParsingTest {

  private fun entryMap(descriptor: JavaOnlyMap, vars: Any? = JavaOnlyMap.of("n", 1.0)) =
    JavaOnlyMap.of("id", "e1", "key", "note", "vars", vars, "descriptor", descriptor)

  private fun base(vararg extra: Any?) =
    JavaOnlyMap.of("url", "https://example.com/items", "expiresAt", 9_000.0, *extra)

  @Test
  fun `a JSON POST parses with defaults`() {
    val p = EntryParsing.parse(entryMap(base("data", JavaOnlyMap.of("n", 1.0, "text", "hi"))))
    assertEquals("e1", p.id)
    assertEquals("note", p.key)
    assertEquals("""{"n":1}""", p.varsJson)
    assertEquals(9_000L, p.expiresAt)
    assertEquals("POST", p.descriptor.method)
    assertEquals("""{"n":1,"text":"hi"}""", p.descriptor.dataJson)
    assertEquals(StagedBody.JSON, p.descriptor.bodyKind)
  }

  @Test
  fun `null vars store as the text null`() {
    assertEquals("null", EntryParsing.parse(entryMap(base(), vars = null)).varsJson)
  }

  @Test
  fun `a null data is no body, because the bridge turns undefined into null`() {
    assertNull(EntryParsing.parse(entryMap(base("data", null))).descriptor.dataJson)
  }

  @Test
  fun `chunked parts, headers, accept, retry, and android parse`() {
    val d = JavaOnlyMap.of(
      "method", "PUT",
      "file", "file:///data/a%20b.bin",
      "expiresAt", 9_000.0,
      "headers", JavaOnlyMap.of("Content-Type", "video/mp4", "X-N", 5.0),
      "parts", JavaOnlyArray.of(
        JavaOnlyMap.of("url", "https://s3/1", "range", JavaOnlyMap.of("start", 0.0, "end", 10.0)),
        JavaOnlyMap.of(
          "url", "https://s3/2", "headers", JavaOnlyMap.of("Content-Range", "10-19"),
          "range", JavaOnlyMap.of("start", 10.0, "end", 20.0),
        ),
      ),
      "accept", JavaOnlyArray.of(JavaOnlyMap.of("status", 409.0, "bodyIncludes", "already completed")),
      "retry", JavaOnlyMap.of(
        "backoff", JavaOnlyMap.of("baseMs", 50.0),
        "terminalHttp", JavaOnlyMap.of("exempt", JavaOnlyArray()),
      ),
      "android", JavaOnlyMap.of("noNotification", true),
    )
    val parsed = EntryParsing.parse(entryMap(d)).descriptor
    assertEquals("/data/a b.bin", parsed.file)
    assertEquals(StagedBody.CHUNKED, parsed.bodyKind)
    assertEquals(listOf(Part("https://s3/1", mapOf(), 0, 10), Part("https://s3/2", mapOf("Content-Range" to "10-19"), 10, 20)), parsed.parts)
    assertEquals(mapOf("Content-Type" to "video/mp4", "X-N" to "5"), parsed.headers)
    assertEquals(listOf(UploadOutcome.AcceptRule(409, "already completed")), parsed.accept)
    assertEquals(RetryOverride(50, null, null, emptyList()), parsed.retry)
    assertEquals(true, parsed.noNotification)
    assertEquals("https://s3/2", parsed.reportUrl)
  }

  @Test
  fun `form parts parse with exactly one of string or path`() {
    val d = base(
      "form", JavaOnlyArray.of(
        JavaOnlyMap.of("name", "meta", "contentType", "application/json", "string", "{}"),
        JavaOnlyMap.of("name", "photo", "contentType", "image/jpeg", "path", "/p.jpg", "fileName", "p.jpg"),
      ),
    )
    assertEquals(
      listOf(
        FormPart("meta", "application/json", "{}", null, null),
        FormPart("photo", "image/jpeg", null, "/p.jpg", "p.jpg"),
      ),
      EntryParsing.parse(entryMap(d)).descriptor.form,
    )
    val both = base("form", JavaOnlyArray.of(JavaOnlyMap.of("name", "x", "contentType", "t", "string", "s", "path", "/p")))
    assertThrows(EntryParsing.InvalidEntryException::class.java) { EntryParsing.parse(entryMap(both)) }
  }

  @Test
  fun `what native can not run is rejected`() {
    val cases = listOf(
      JavaOnlyMap.of("url", "https://example.com"), // no expiresAt
      JavaOnlyMap.of("expiresAt", 1.0), // no url and no parts
      base("data", 1.0, "file", "/a"), // two body kinds
      base("method", "GET", "data", 1.0), // GET with a body
      base("method", "TRACE"),
      JavaOnlyMap.of("url", "not a url", "expiresAt", 1.0),
      base("headers", JavaOnlyMap.of("Bad\nName", "v")),
      base("parts", JavaOnlyArray.of(JavaOnlyMap.of("url", "https://s3/1", "range", JavaOnlyMap.of("start", 0.0, "end", 1.0)))), // parts without file
    )
    cases.forEach { d ->
      assertThrows("$d", EntryParsing.InvalidEntryException::class.java) { EntryParsing.parse(entryMap(d)) }
    }
    assertThrows(EntryParsing.InvalidEntryException::class.java) {
      EntryParsing.parse(JavaOnlyMap.of("key", "k", "descriptor", base()))
    }
  }

  @Test
  fun `an updateHeaders patch is checked like descriptor headers`() {
    assertEquals(mapOf("Authorization" to "Bearer new"), EntryParsing.headerPatch(JavaOnlyMap.of("Authorization", "Bearer new")))
    assertThrows(EntryParsing.InvalidEntryException::class.java) {
      EntryParsing.headerPatch(JavaOnlyMap.of("Authorization", "Bearer\nnew"))
    }
  }

  @Test
  fun `file scheme stripping`() {
    assertEquals("/a/b c.jpg", EntryParsing.stripFileScheme("file:///a/b%20c.jpg"))
    assertEquals("/a/b.jpg", EntryParsing.stripFileScheme("/a/b.jpg"))
  }
}
