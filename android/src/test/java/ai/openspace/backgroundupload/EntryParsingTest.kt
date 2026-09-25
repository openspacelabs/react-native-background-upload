package ai.openspace.backgroundupload

import com.facebook.react.bridge.JavaOnlyArray
import com.facebook.react.bridge.JavaOnlyMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EntryParsingTest {

  private fun entryMap(descriptor: JavaOnlyMap, varsJson: Any? = """{"n":1}""") =
    JavaOnlyMap.of("id", "e1", "key", "note", "varsJson", varsJson, "descriptor", descriptor)

  private fun base(vararg extra: Any?) =
    JavaOnlyMap.of("url", "https://example.com/items", "expiresAt", 9_000.0, *extra)

  @Test
  fun `a JSON POST parses with defaults and keeps the JSON text as JS wrote it`() {
    val text = """{"text":"hi","n":1,"status":null}"""
    val p = EntryParsing.parse(entryMap(base("dataJson", text), varsJson = """{"b":2,"a":null}"""))
    assertEquals("e1", p.id)
    assertEquals("note", p.key)
    assertEquals("""{"b":2,"a":null}""", p.varsJson) // key order and null values survive
    assertEquals(9_000L, p.expiresAt)
    assertEquals("POST", p.descriptor.method)
    assertEquals(text, p.descriptor.dataJson)
    assertEquals(StagedBody.JSON, p.descriptor.bodyKind)
  }

  @Test
  fun `null vars cross as the text null`() {
    assertEquals("null", EntryParsing.parse(entryMap(base(), varsJson = "null")).varsJson)
  }

  @Test
  fun `a dataJson of null is a real JSON body`() {
    val d = EntryParsing.parse(entryMap(base("dataJson", "null"))).descriptor
    assertEquals("null", d.dataJson)
    assertEquals(StagedBody.JSON, d.bodyKind)
  }

  @Test
  fun `no dataJson is no body`() {
    val d = EntryParsing.parse(entryMap(base())).descriptor
    assertNull(d.dataJson)
    assertEquals(StagedBody.NONE, d.bodyKind)
  }

  @Test
  fun `a missing or malformed varsJson or dataJson is rejected`() {
    val cases = listOf(
      entryMap(base(), varsJson = null),
      entryMap(base(), varsJson = JavaOnlyMap.of("n", 1.0)), // the old object form
      entryMap(base(), varsJson = "{n:1}"), // lenient JSON
      entryMap(base(), varsJson = """{"n":1} trailing"""),
      entryMap(base("dataJson", "")),
      entryMap(base("dataJson", "{'a':1}")),
      entryMap(base("dataJson", JavaOnlyMap.of("a", 1.0))),
      entryMap(base("data", JavaOnlyMap.of("a", 1.0))), // the old data form would send no body
    )
    cases.forEach { m ->
      assertThrows("$m", EntryParsing.InvalidEntryException::class.java) { EntryParsing.parse(m) }
    }
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
  fun `wifiOnly parses true, false, or absent`() {
    assertEquals(true, EntryParsing.parse(entryMap(base("wifiOnly", true))).descriptor.wifiOnly)
    assertEquals(false, EntryParsing.parse(entryMap(base("wifiOnly", false))).descriptor.wifiOnly)
    assertNull(EntryParsing.parse(entryMap(base())).descriptor.wifiOnly)
    assertThrows(EntryParsing.InvalidEntryException::class.java) {
      EntryParsing.parse(entryMap(base("wifiOnly", "yes")))
    }
  }

  @Test
  fun `a pause scope with no keys is the whole queue, and keys must be non-empty strings`() {
    assertNull(EntryParsing.scopeKeys(JavaOnlyMap()))
    // Codegen passes null when a stale bundle calls pause() with no argument.
    assertNull(EntryParsing.scopeKeys(null))
    assertEquals(listOf("capture", "video"), EntryParsing.scopeKeys(JavaOnlyMap.of("keys", JavaOnlyArray.of("capture", "video"))))
    assertEquals(emptyList<String>(), EntryParsing.scopeKeys(JavaOnlyMap.of("keys", JavaOnlyArray())))
    // A keys field that native drops would widen the scope to the whole queue.
    listOf(
      JavaOnlyMap.of("keys", null),
      JavaOnlyMap.of("keys", "capture"),
      JavaOnlyMap.of("keys", JavaOnlyArray.of("capture", 1.0)),
      // JS and the README refuse empty keys; native agrees.
      JavaOnlyMap.of("keys", JavaOnlyArray.of("capture", "")),
    ).forEach { m ->
      assertThrows("$m", EntryParsing.InvalidEntryException::class.java) { EntryParsing.scopeKeys(m) }
    }
  }

  @Test
  fun `what native can not run is rejected`() {
    val cases = listOf(
      JavaOnlyMap.of("url", "https://example.com"), // no expiresAt
      JavaOnlyMap.of("expiresAt", 1.0), // no url and no parts
      base("dataJson", "1", "file", "/a"), // two body kinds
      base("method", "GET", "dataJson", "1"), // GET with a body
      base("method", "GET", "dataJson", "null"), // GET with the JSON body null
      base("method", "GET", "file", "/a"),
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
  fun `a GET with no body parses`() {
    assertEquals("GET", EntryParsing.parse(entryMap(base("method", "GET"))).descriptor.method)
  }

  @Test
  fun `a bad header value is rejected with its name and offset, never its value`() {
    val secret = "Bearer s3cr3t-token"
    val e = assertThrows(EntryParsing.InvalidEntryException::class.java) {
      EntryParsing.parse(entryMap(base("headers", JavaOnlyMap.of("Authorization", "$secret\n"))))
    }
    assertEquals("headers: the value of header 'Authorization' has an invalid character at offset ${secret.length}", e.message)
    assertFalse(e.message!!.contains("s3cr3t"))
  }

  @Test
  fun `a bad header name is rejected with the valid part before the offset only`() {
    val e = assertThrows(EntryParsing.InvalidEntryException::class.java) {
      EntryParsing.requireValidHeaders(mapOf("Authorization: Bearer s3cr3t" to "v"), "headers")
    }
    assertEquals("headers: the header name that starts 'Authorization:' has an invalid character at offset 14", e.message)
    assertFalse(e.message!!.contains("s3cr3t"))
    assertThrows(EntryParsing.InvalidEntryException::class.java) {
      EntryParsing.requireValidHeaders(mapOf("" to "v"), "headers")
    }
  }

  @Test
  fun `the header check accepts what OkHttp sends`() {
    EntryParsing.requireValidHeaders(mapOf("X-Tab" to "a\tb", "X-Tilde" to "~!#", "Content-Range" to "bytes 0-9/10"), "headers")
    okhttp3.Headers.Builder().add("X-Tab", "a\tb").add("X-Tilde", "~!#")
  }

  @Test
  fun `an updateHeaders patch is checked like descriptor headers`() {
    assertEquals(mapOf("Authorization" to "Bearer new"), EntryParsing.headerPatch(JavaOnlyMap.of("Authorization", "Bearer new")))
    val e = assertThrows(EntryParsing.InvalidEntryException::class.java) {
      EntryParsing.headerPatch(JavaOnlyMap.of("Authorization", "Bearer\nnew"))
    }
    assertFalse(e.message!!.contains("Bearer"))
  }

  @Test
  fun `file scheme stripping`() {
    assertEquals("/a/b c.jpg", EntryParsing.stripFileScheme("file:///a/b%20c.jpg"))
    assertEquals("/a/b.jpg", EntryParsing.stripFileScheme("/a/b.jpg"))
  }
}
