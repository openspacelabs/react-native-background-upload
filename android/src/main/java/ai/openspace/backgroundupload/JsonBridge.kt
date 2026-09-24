package ai.openspace.backgroundupload

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlin.math.abs
import kotlin.math.floor

/**
 * Moves values between three forms: the bridge (ReadableMap, WritableMap),
 * plain Kotlin values (Map, List, String, Double, Boolean, null), and JSON
 * text. `vars` and `data` are stored as JSON text.
 *
 * Numbers: RN gives every JS number to Kotlin as a Double. A Double with no
 * fraction is written as an integer, so `{ n: 1 }` becomes `{"n":1}`, as
 * JSON.stringify writes it, not `{"n":1.0}`.
 *
 * Key order: the bridge does not keep the JS key order. Object keys are
 * written sorted, so the same object always gives the same text. The
 * same-body check compares that text.
 */
object JsonBridge {
  private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

  // 2^53. Above this a Double can not hold every integer, so it keeps the
  // Double form.
  private const val MAX_SAFE_INTEGER = 9_007_199_254_740_992.0

  fun fromReadable(map: ReadableMap): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    val keys = map.keySetIterator()
    while (keys.hasNextKey()) {
      val key = keys.nextKey()
      out[key] = valueOf(map, key)
    }
    return out
  }

  fun fromReadableArray(array: ReadableArray): List<Any?> =
    (0 until array.size()).map { i ->
      when (array.getType(i)) {
        ReadableType.Null -> null
        ReadableType.Boolean -> array.getBoolean(i)
        ReadableType.Number -> array.getDouble(i)
        ReadableType.String -> array.getString(i)
        ReadableType.Map -> array.getMap(i)?.let { fromReadable(it) }
        ReadableType.Array -> array.getArray(i)?.let { fromReadableArray(it) }
      }
    }

  /** The plain value at [key]. Null for an absent key. */
  fun valueOf(map: ReadableMap, key: String): Any? {
    if (!map.hasKey(key)) return null
    return when (map.getType(key)) {
      ReadableType.Null -> null
      ReadableType.Boolean -> map.getBoolean(key)
      ReadableType.Number -> map.getDouble(key)
      ReadableType.String -> map.getString(key)
      ReadableType.Map -> map.getMap(key)?.let { fromReadable(it) }
      ReadableType.Array -> map.getArray(key)?.let { fromReadableArray(it) }
    }
  }

  /** Plain values to JSON text. */
  fun toJson(value: Any?): String = gson.toJson(toElement(value))

  /** JSON text to plain values. Throws on malformed text. Numbers come back as Double. */
  fun parse(json: String): Any? = fromElement(JsonParser.parseString(json))

  /** A number as JSON would print it: an integer when it has no fraction. */
  fun numberText(d: Double): String = gson.toJson(number(d))

  private fun number(d: Double): JsonPrimitive =
    if (d.isFinite() && d == floor(d) && abs(d) < MAX_SAFE_INTEGER) JsonPrimitive(d.toLong())
    else JsonPrimitive(d)

  private fun toElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull.INSTANCE
    is Boolean -> JsonPrimitive(value)
    is Double -> number(value)
    is Float -> number(value.toDouble())
    is Int, is Long, is Short, is Byte -> JsonPrimitive((value as Number).toLong())
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject().apply {
      value.entries
        .sortedBy { it.key.toString() }
        .forEach { (k, v) -> add(k.toString(), toElement(v)) }
    }
    is Iterable<*> -> JsonArray().apply { value.forEach { add(toElement(it)) } }
    is Array<*> -> JsonArray().apply { value.forEach { add(toElement(it)) } }
    else -> JsonPrimitive(value.toString())
  }

  private fun fromElement(element: JsonElement): Any? = when {
    element.isJsonNull -> null
    element.isJsonObject -> LinkedHashMap<String, Any?>().apply {
      element.asJsonObject.entrySet().forEach { (k, v) -> put(k, fromElement(v)) }
    }
    element.isJsonArray -> element.asJsonArray.map { fromElement(it) }
    else -> {
      val p = element.asJsonPrimitive
      when {
        p.isBoolean -> p.asBoolean
        p.isNumber -> p.asDouble
        else -> p.asString
      }
    }
  }

  /**
   * Plain values to the bridge. The factories default to the native ones. The
   * JVM tests pass JavaOnlyMap and JavaOnlyArray, because the native ones need
   * the React Native C++ library.
   */
  fun toWritableMap(
    map: Map<String, Any?>,
    newMap: () -> WritableMap = Arguments::createMap,
    newArray: () -> WritableArray = Arguments::createArray,
  ): WritableMap {
    val out = newMap()
    map.forEach { (k, v) -> putValue(out, k, v, newMap, newArray) }
    return out
  }

  fun toWritableArray(
    list: List<Any?>,
    newMap: () -> WritableMap = Arguments::createMap,
    newArray: () -> WritableArray = Arguments::createArray,
  ): WritableArray {
    val out = newArray()
    list.forEach { v ->
      when (v) {
        null -> out.pushNull()
        is Boolean -> out.pushBoolean(v)
        is Number -> out.pushDouble(v.toDouble())
        is String -> out.pushString(v)
        is Map<*, *> -> out.pushMap(toWritableMap(stringKeys(v), newMap, newArray))
        is List<*> -> out.pushArray(toWritableArray(v, newMap, newArray))
        else -> out.pushString(v.toString())
      }
    }
    return out
  }

  fun putValue(
    map: WritableMap,
    key: String,
    value: Any?,
    newMap: () -> WritableMap = Arguments::createMap,
    newArray: () -> WritableArray = Arguments::createArray,
  ) {
    when (value) {
      null -> map.putNull(key)
      is Boolean -> map.putBoolean(key, value)
      is Number -> map.putDouble(key, value.toDouble())
      is String -> map.putString(key, value)
      is Map<*, *> -> map.putMap(key, toWritableMap(stringKeys(value), newMap, newArray))
      is List<*> -> map.putArray(key, toWritableArray(value, newMap, newArray))
      else -> map.putString(key, value.toString())
    }
  }

  private fun stringKeys(map: Map<*, *>): Map<String, Any?> =
    map.entries.associate { (k, v) -> k.toString() to v }
}
