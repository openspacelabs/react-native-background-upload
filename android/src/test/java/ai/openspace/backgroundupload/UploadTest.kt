package ai.openspace.backgroundupload

import com.google.gson.Gson
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
    acceptStatus = listOf(),
    headers = mapOf(),
    notificationId = 1,
    notificationTitle = "Uploading…",
    notificationTitleNoInternet = "Waiting for connection…",
    notificationTitleNoWifi = "Waiting for Wi-Fi…",
    notificationChannel = "background-upload",
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
}
