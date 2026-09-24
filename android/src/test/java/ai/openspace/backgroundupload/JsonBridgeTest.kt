package ai.openspace.backgroundupload

import com.facebook.react.bridge.JavaOnlyArray
import com.facebook.react.bridge.JavaOnlyMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonBridgeTest {

  @Test
  fun `integral doubles print as integers, as JSON stringify does`() {
    assertEquals("""{"n":1,"neg":-3,"zero":0}""", JsonBridge.toJson(mapOf("n" to 1.0, "neg" to -3.0, "zero" to -0.0)))
    assertEquals("12345678901", JsonBridge.toJson(12_345_678_901.0))
  }

  @Test
  fun `fractions and very large magnitudes keep a decimal form`() {
    assertEquals("1.5", JsonBridge.toJson(1.5))
    assertEquals("0.1", JsonBridge.toJson(0.1))
    // Above 2^53 a double can not hold every integer, so it stays a double.
    assertEquals(1e20, (JsonBridge.parse(JsonBridge.toJson(1e20)) as Double), 0.0)
  }

  @Test
  fun `nested maps and lists round trip`() {
    val value = mapOf("a" to listOf(1.0, "x", true, null, mapOf("b" to 2.5)), "c" to mapOf<String, Any?>())
    val text = JsonBridge.toJson(value)
    assertEquals("""{"a":[1,"x",true,null,{"b":2.5}],"c":{}}""", text)
    assertEquals(value, JsonBridge.parse(text))
  }

  @Test
  fun `keys are sorted so the same object always gives the same text`() {
    assertEquals(JsonBridge.toJson(mapOf("b" to 1.0, "a" to 2.0)), JsonBridge.toJson(mapOf("a" to 2.0, "b" to 1.0)))
  }

  @Test
  fun `null is the text null, and HTML characters are not escaped`() {
    assertEquals("null", JsonBridge.toJson(null))
    assertNull(JsonBridge.parse("null"))
    assertEquals("\"<a&b>\"", JsonBridge.toJson("<a&b>"))
  }

  @Test
  fun `malformed text throws`() {
    assertThrows(Exception::class.java) { JsonBridge.parse("{\"a\":") }
    assertThrows(Exception::class.java) { JsonBridge.parse("[1,") }
  }

  @Test
  fun `bridge maps read into plain values`() {
    val map = JavaOnlyMap.of(
      "n", 2.0,
      "s", "x",
      "b", false,
      "z", null,
      "m", JavaOnlyMap.of("k", 1.0),
      "a", JavaOnlyArray.of(1.0, "y"),
    )
    assertEquals(
      mapOf("n" to 2.0, "s" to "x", "b" to false, "z" to null, "m" to mapOf("k" to 1.0), "a" to listOf(1.0, "y")),
      JsonBridge.fromReadable(map),
    )
    assertNull(JsonBridge.valueOf(map, "absent"))
  }

  @Test
  fun `plain values write to the bridge`() {
    val out = JsonBridge.toWritableMap(
      mapOf("n" to 1.0, "list" to listOf("a", 2.0), "nested" to mapOf("k" to true), "none" to null),
      ::JavaOnlyMap,
      ::JavaOnlyArray,
    ) as JavaOnlyMap
    assertEquals(1.0, out.getDouble("n"), 0.0)
    assertEquals("a", out.getArray("list")!!.getString(0))
    assertEquals(true, out.getMap("nested")!!.getBoolean("k"))
    assertEquals(true, out.isNull("none"))
  }
}
