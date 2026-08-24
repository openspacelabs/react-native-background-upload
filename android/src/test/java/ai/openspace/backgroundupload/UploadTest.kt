package ai.openspace.backgroundupload

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadTest {
  private val gson = Gson()

  private fun upload(noNotification: Boolean) = Upload(
    id = "u1",
    url = "https://example.com/upload",
    path = "/tmp/file",
    method = "POST",
    wifiOnly = false,
    accept = listOf(),
    headers = mapOf(),
    noNotification = noNotification,
  )

  @Test
  fun `an upload notifies unless it opts out`() {
    assertTrue(upload(noNotification = false).showsNotification)
    assertFalse(upload(noNotification = true).showsNotification)
  }

  @Test
  fun `the opt-out survives a serialization round trip`() {
    val json = gson.toJson(upload(noNotification = true))
    assertFalse(gson.fromJson(json, Upload::class.java).showsNotification)
  }

  // WorkManager stores this model as JSON, so an upload can be enqueued by one
  // build and run by the next. A job from a build without the option must keep
  // its notification rather than silently losing foreground mode.
  @Test
  fun `a job enqueued without the option still notifies`() {
    val json = gson.toJsonTree(upload(noNotification = true)).asJsonObject
    json.remove(Upload::noNotification.name)
    assertTrue(gson.fromJson(json, Upload::class.java).showsNotification)
  }

  // One build can enqueue a WorkManager job, and the next build can replay it.
  // This is the exact JSON shape that a v8 build serialized into input data
  // (Gson.toJson of the v8 Upload model): `acceptStatus: List<Int>`, and no
  // `accept`. Gson does not use the constructor. Thus, without normalized(),
  // the replayed object's `accept` is NULL, and the worker NPEs after the file
  // has fully transmitted. WorkManager then re-runs it and re-sends the whole
  // file.
  private val v8JobJson = """
    {
      "id": "u1",
      "url": "https://example.com/upload",
      "path": "/tmp/file",
      "method": "PUT",
      "maxRetries": 5,
      "wifiOnly": false,
      "acceptStatus": [409, 208],
      "headers": {"Authorization": "Bearer t"},
      "notificationId": 123456,
      "notificationTitle": "Uploading…",
      "notificationTitleNoInternet": "Waiting for connection…",
      "notificationTitleNoWifi": "Waiting for Wi-Fi…",
      "notificationChannel": "background-upload",
      "noNotification": false
    }
  """

  @Test
  fun `a replayed v8 job maps acceptStatus to accept rules and is safe to run`() {
    val replayed = gson.fromJson(v8JobJson, Upload::class.java).normalized()
    assertEquals(
      listOf(UploadOutcome.AcceptRule(409), UploadOutcome.AcceptRule(208)),
      replayed.accept,
    )
    // The worker-facing calls that NPE'd on the un-normalized object.
    assertTrue(UploadOutcome.isAccepted(409, "duplicate", replayed.accept))
    assertFalse(UploadOutcome.isAccepted(400, "", replayed.accept))
    assertEquals("u1", replayed.id)
    assertEquals(mapOf("Authorization" to "Bearer t"), replayed.headers)
    assertTrue(replayed.showsNotification)
  }

  @Test
  fun `a replayed v8 job with an empty acceptStatus gets no rules`() {
    val json = gson.fromJson(v8JobJson, com.google.gson.JsonObject::class.java)
    json.add("acceptStatus", com.google.gson.JsonArray())
    val replayed = gson.fromJson(json, Upload::class.java).normalized()
    assertEquals(emptyList<UploadOutcome.AcceptRule>(), replayed.accept)
    assertTrue(UploadOutcome.isAccepted(200, "", replayed.accept))
  }

  @Test
  fun `a job with neither accept nor acceptStatus normalizes to no rules`() {
    val json = gson.fromJson(v8JobJson, com.google.gson.JsonObject::class.java)
    json.remove("acceptStatus")
    val replayed = gson.fromJson(json, Upload::class.java).normalized()
    assertEquals(emptyList<UploadOutcome.AcceptRule>(), replayed.accept)
    assertFalse(UploadOutcome.isAccepted(409, "duplicate", replayed.accept))
  }

  @Test
  fun `normalized passes a current-shape job through unchanged`() {
    val current = upload(noNotification = true).copy(
      accept = listOf(UploadOutcome.AcceptRule(409, "already completed")),
    )
    val replayed = gson.fromJson(gson.toJson(current), Upload::class.java).normalized()
    assertEquals(current, replayed)
  }
}
