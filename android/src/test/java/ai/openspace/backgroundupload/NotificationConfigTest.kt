package ai.openspace.backgroundupload

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationConfigTest {
  private val gson = Gson()

  private val configured = NotificationConfig(
    notificationId = "my-id",
    notificationTitle = "Backing up…",
    notificationTitleNoInternet = "Offline",
    notificationTitleNoWifi = "No wifi",
    notificationChannel = "my-channel",
  )

  @Test
  fun `no stored config yields the defaults`() {
    assertEquals(NotificationConfig.DEFAULTS, NotificationConfig.fromJson(null))
  }

  @Test
  fun `a configured blob survives a persistence round trip`() {
    assertEquals(configured, NotificationConfig.fromJson(gson.toJson(configured)))
  }

  @Test
  fun `a corrupt blob degrades to the defaults`() {
    assertEquals(NotificationConfig.DEFAULTS, NotificationConfig.fromJson("]["))
  }

  // A blob from a build with fewer fields must not make the other fields null.
  @Test
  fun `fields missing from a stored blob fall back individually`() {
    val config = NotificationConfig.fromJson("""{"notificationTitle":"Custom"}""")
    assertEquals("Custom", config.notificationTitle)
    assertEquals(NotificationConfig.DEFAULTS.notificationChannel, config.notificationChannel)
    assertEquals(NotificationConfig.DEFAULTS.notificationTitleNoWifi, config.notificationTitleNoWifi)
    assertEquals(
      NotificationConfig.DEFAULTS.notificationTitleNoInternet,
      config.notificationTitleNoInternet,
    )
  }

  // This is the same derivation that v8 applied to the per-upload option. Thus
  // an app that gives its old notificationId to configure() keeps the same
  // system notification.
  @Test
  fun `the system notification id derives from the configured string`() {
    assertEquals("my-id".hashCode(), configured.systemNotificationId)
  }
}
