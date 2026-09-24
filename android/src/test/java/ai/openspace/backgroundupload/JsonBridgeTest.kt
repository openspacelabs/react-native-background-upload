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
    assertEquals("1", JsonBridge.numberText(1.0))
    assertEquals("-3", JsonBridge.numberText(-3.0))
    assertEquals("0", JsonBridge.numberText(-0.0))
    assertEquals("12345678901", JsonBridge.numberText(12_345_678_901.0))
  }

  @Test
  fun `fractions and very large magnitudes keep a decimal form`() {
    assertEquals("1.5", JsonBridge.numberText(1.5))
    assertEquals("0.1", JsonBridge.numberText(0.1))
    // Above 2^53 a double can not hold every integer, so it stays a double.
    assertEquals(1e20, (JsonBridge.parse(JsonBridge.numberText(1e20)) as Double), 0.0)
  }

  @Test
  fun `JSON text parses to plain values`() {
    val value = mapOf("a" to listOf(1.0, "x", true, null, mapOf("b" to 2.5)), "c" to mapOf<String, Any?>())
    assertEquals(value, JsonBridge.parse("""{"a":[1,"x",true,null,{"b":2.5}],"c":{}}"""))
    assertNull(JsonBridge.parse("null"))
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

  @Test
  fun `isJson accepts one strict JSON value of any kind`() {
    listOf("null", "1", "-0.5e3", "\"s\"", "true", "[]", "{}", """{"a":[1,null,{"b":"c"}]}""", " {\"a\":1} ").forEach {
      assertEquals(it, true, JsonBridge.isJson(it))
    }
  }

  @Test
  fun `isJson rejects lenient and malformed text`() {
    listOf("", " ", "{a:1}", "{'a':1}", "[1,]", "{\"a\":1} x", "undefined", "NaN", "{\"a\":1}{}").forEach {
      assertEquals(it, false, JsonBridge.isJson(it))
    }
  }
}
