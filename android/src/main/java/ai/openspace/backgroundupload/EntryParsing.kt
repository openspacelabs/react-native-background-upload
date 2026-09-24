package ai.openspace.backgroundupload

import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/**
 * Turns the EnqueueEntry `{ id, key, varsJson, descriptor }` into Kotlin
 * values. JS has already validated the descriptor. Native checks only what
 * it needs to run, and rejects anything else with E_INVALID.
 *
 * `varsJson` and `descriptor.dataJson` are JSON text. They cross as strings
 * because React Native on iOS drops object keys whose value is null. Native
 * keeps the text as it came: it is the body that goes out. A dataJson of
 * "null" is the JSON body null, a real body.
 */
object EntryParsing {
  class InvalidEntryException(message: String) : IllegalArgumentException(message)

  data class Parsed(
    val id: String,
    val key: String,
    val varsJson: String,
    val descriptor: Descriptor,
    val expiresAt: Long,
  )

  private val METHODS = setOf("POST", "PUT", "PATCH", "DELETE", "GET")

  fun parse(entry: ReadableMap): Parsed {
    val id = entry.string("id")?.takeIf { it.isNotEmpty() } ?: invalid("id is required")
    val key = entry.string("key")?.takeIf { it.isNotEmpty() } ?: invalid("key is required")
    val varsJson = entry.string("varsJson") ?: invalid("varsJson is required")
    if (!JsonBridge.isJson(varsJson)) invalid("varsJson is not JSON text")
    val d = entry.map("descriptor") ?: invalid("descriptor is required")
    val expiresAt = d.number("expiresAt")?.toLong() ?: invalid("descriptor.expiresAt is required")
    return Parsed(id, key, varsJson, descriptor(d), expiresAt)
  }

  fun descriptor(d: ReadableMap): Descriptor {
    val method = (d.string("method") ?: "POST").uppercase()
    if (method !in METHODS) invalid("method $method is not supported")

    val parts = d.array("parts")?.let { parseParts(it) }
    val url = d.string("url")
    if (url == null && parts == null) invalid("url is required unless parts is set")
    url?.let { requireHttpUrl(it, "url") }

    // An old JS layer would send `data`. Ignoring it would send no body.
    if (d.isSet("data")) invalid("data crosses as dataJson")
    val dataJson = if (d.isSet("dataJson")) {
      val text = d.string("dataJson") ?: invalid("dataJson must be a string")
      if (!JsonBridge.isJson(text)) invalid("dataJson is not JSON text")
      text
    } else null
    val form = d.array("form")?.let { parseForm(it) }
    val file = d.string("file")?.let { stripFileScheme(it) }
    val kinds = listOfNotNull(dataJson?.let { "data" }, form?.let { "form" }, file?.let { "file" })
    if (kinds.size > 1) invalid("at most one of data, form, file; got ${kinds.joinToString()}")
    if (parts != null && file == null) invalid("parts requires file")
    if (method == "GET" && kinds.isNotEmpty()) invalid("a GET request can not carry a body")

    val headers = parseHeaderMap(d.map("headers"))
    requireValidHeaders(headers, "headers")
    if (d.isSet("wifiOnly") && d.bool("wifiOnly") == null) invalid("wifiOnly must be a boolean")

    return Descriptor(
      url = url,
      method = method,
      headers = headers,
      dataJson = dataJson,
      form = form,
      file = file,
      parts = parts,
      accept = parseAcceptRules(d.array("accept")),
      retry = d.map("retry")?.let { parseRetry(it) },
      noNotification = d.map("android")?.bool("noNotification") ?: false,
      wifiOnly = d.bool("wifiOnly"),
    )
  }

  /**
   * pause/resume scope `{ keys?: string[] }`. A null scope or a scope with
   * no keys is the whole queue (null comes from a caller that sent no
   * argument). A keys field that is not a list of non-empty strings is
   * refused: dropping it would widen the scope to the whole queue.
   */
  fun scopeKeys(scope: ReadableMap?): List<String>? {
    if (scope == null || !scope.hasKey("keys")) return null
    val arr = scope.array("keys") ?: invalid("scope.keys must be an array of strings")
    return (0 until arr.size()).map { i ->
      if (arr.getType(i) != ReadableType.String) invalid("scope.keys[$i] must be a string")
      arr.getString(i)?.takeIf { it.isNotEmpty() } ?: invalid("scope.keys[$i] must be a non-empty string")
    }
  }

  /** updateHeaders(patch): the header map, checked the same way as a descriptor's. */
  fun headerPatch(patch: ReadableMap): Map<String, String> =
    parseHeaderMap(patch).also { requireValidHeaders(it, "updateHeaders") }

  /** `file:///a%20b` → `/a b`. A plain path is returned as it is. */
  fun stripFileScheme(path: String): String {
    if (!path.startsWith("file://")) return path
    return runCatching { URI(path).path }.getOrNull() ?: path.removePrefix("file://")
  }

  private fun parseParts(arr: ReadableArray): List<Part> {
    if (arr.size() == 0) invalid("parts must be a non-empty array")
    return (0 until arr.size()).map { i ->
      val p = arr.getMap(i) ?: invalid("parts[$i] must be an object")
      val url = p.string("url") ?: invalid("parts[$i].url is required")
      requireHttpUrl(url, "parts[$i].url")
      val range = p.map("range") ?: invalid("parts[$i].range is required")
      val start = range.number("start")?.toLong() ?: invalid("parts[$i].range.start is required")
      val end = range.number("end")?.toLong() ?: invalid("parts[$i].range.end is required")
      if (start < 0 || end <= start) invalid("parts[$i].range must satisfy 0 <= start < end")
      val headers = parseHeaderMap(p.map("headers"))
      requireValidHeaders(headers, "parts[$i].headers")
      Part(url = url, headers = headers, start = start, end = end)
    }
  }

  private fun parseForm(arr: ReadableArray): List<FormPart> {
    if (arr.size() == 0) invalid("form must be a non-empty array")
    return (0 until arr.size()).map { i ->
      val p = arr.getMap(i) ?: invalid("form[$i] must be an object")
      val name = p.string("name") ?: invalid("form[$i].name is required")
      val contentType = p.string("contentType") ?: invalid("form[$i].contentType is required")
      val string = p.string("string")
      val path = p.string("path")?.let { stripFileScheme(it) }
      if ((string == null) == (path == null)) invalid("form[$i] must set exactly one of string, path")
      FormPart(name, contentType, string, path, p.string("fileName"))
    }
  }

  private fun parseRetry(r: ReadableMap): RetryOverride {
    val backoff = r.map("backoff")
    val exempt = r.map("terminalHttp")?.array("exempt")?.let { arr ->
      (0 until arr.size()).mapNotNull { i ->
        if (arr.getType(i) == ReadableType.Number) arr.getDouble(i).toInt() else null
      }
    }
    return RetryOverride(
      baseMs = backoff?.number("baseMs")?.toLong(),
      maxMs = backoff?.number("maxMs")?.toLong(),
      jitter = backoff?.number("jitter"),
      exempt = exempt,
    )
  }

  internal fun parseAcceptRules(arr: ReadableArray?): List<UploadOutcome.AcceptRule> {
    if (arr == null) return listOf()
    return (0 until arr.size()).mapNotNull { i ->
      val rule = arr.getMap(i) ?: return@mapNotNull null
      val status = rule.number("status") ?: return@mapNotNull null
      UploadOutcome.AcceptRule(status.toInt(), rule.string("bodyIncludes"))
    }
  }

  /** Header values keep their text. A number is written as JSON would write it. */
  internal fun parseHeaderMap(map: ReadableMap?): Map<String, String> {
    if (map == null) return mapOf()
    val out = LinkedHashMap<String, String>()
    JsonBridge.fromReadable(map).forEach { (k, v) ->
      when (v) {
        null -> Unit
        is Double -> out[k] = JsonBridge.numberText(v)
        else -> out[k] = v.toString()
      }
    }
    return out
  }

  private fun requireHttpUrl(url: String, where: String) {
    if (url.toHttpUrlOrNull() == null) invalid("$where is not an http(s) url: $url")
  }

  /**
   * OkHttp throws on a header name or value it can not send. Check it here,
   * so the error is an enqueue rejection and not a failure at attempt time.
   * The message names the header and the offset only: a value can be a
   * credential, and OkHttp's own message would print it.
   */
  internal fun requireValidHeaders(headers: Map<String, String>, where: String) {
    headers.forEach { (name, value) ->
      // OkHttp's rules: a name is 1+ chars in 0x21..0x7e; a value is tab or 0x20..0x7e.
      if (name.isEmpty()) invalid("$where: a header name is empty")
      name.indexOfFirst { it !in '\u0021'..'\u007e' }.takeIf { it >= 0 }?.let { i ->
        // Only the valid part before the offset: the rest could be a value
        // pasted into the name.
        invalid("$where: the header name that starts '${name.take(i)}' has an invalid character at offset $i")
      }
      value.indexOfFirst { it != '\t' && it !in '\u0020'..'\u007e' }.takeIf { it >= 0 }?.let { i ->
        invalid("$where: the value of header '$name' has an invalid character at offset $i")
      }
      // A backstop for any rule OkHttp adds later. Its message is not used.
      try {
        Headers.Builder().add(name, value)
      } catch (e: IllegalArgumentException) {
        invalid("$where: the HTTP client does not accept header '$name'")
      }
    }
  }

  private fun invalid(message: String): Nothing = throw InvalidEntryException(message)

  private fun ReadableMap.isSet(key: String) = hasKey(key) && getType(key) != ReadableType.Null

  private fun ReadableMap.string(key: String): String? =
    if (hasKey(key) && getType(key) == ReadableType.String) getString(key) else null

  private fun ReadableMap.number(key: String): Double? =
    if (hasKey(key) && getType(key) == ReadableType.Number) getDouble(key) else null

  private fun ReadableMap.bool(key: String): Boolean? =
    if (hasKey(key) && getType(key) == ReadableType.Boolean) getBoolean(key) else null

  private fun ReadableMap.map(key: String): ReadableMap? =
    if (hasKey(key) && getType(key) == ReadableType.Map) getMap(key) else null

  private fun ReadableMap.array(key: String): ReadableArray? =
    if (hasKey(key) && getType(key) == ReadableType.Array) getArray(key) else null
}
