package ai.openspace.backgroundupload

import android.content.Context
import com.facebook.react.bridge.ReadableMap
import com.google.gson.Gson

/**
 * The text and the identity of the upload progress notification. JS sets it
 * one time with `configure()`. The class writes it to SharedPreferences. Thus a
 * worker that WorkManager relaunches — with no JS, possibly in a process where
 * React never initialized — shows the same text. Each field falls back to a
 * library default. Thus uploads work when `configure()` was never called.
 */
data class NotificationConfig(
  val notificationId: String,
  val notificationTitle: String,
  val notificationTitleNoInternet: String,
  val notificationTitleNoWifi: String,
  val notificationChannel: String,
) {
  // The id given to NotificationManager. All uploads share the configured id.
  // Thus they share one notification, and its progress bar is the total.
  val systemNotificationId get() = notificationId.hashCode()

  companion object {
    const val DEFAULT_NOTIFICATION_CHANNEL = "background-upload"

    val DEFAULTS = NotificationConfig(
      notificationId = DEFAULT_NOTIFICATION_CHANNEL,
      notificationTitle = "Uploading…",
      notificationTitleNoInternet = "Waiting for connection…",
      notificationTitleNoWifi = "Waiting for Wi-Fi…",
      notificationChannel = DEFAULT_NOTIFICATION_CHANNEL,
    )

    private const val PREFS_NAME = "rnbgupload-config"
    private const val PREFS_KEY = "notificationConfig"
    private val gson = Gson()

    fun fromReadableMap(map: ReadableMap) = NotificationConfig(
      notificationId = map.getString(NotificationConfig::notificationId.name)
        ?: DEFAULTS.notificationId,
      notificationTitle = map.getString(NotificationConfig::notificationTitle.name)
        ?: DEFAULTS.notificationTitle,
      notificationTitleNoInternet = map.getString(NotificationConfig::notificationTitleNoInternet.name)
        ?: DEFAULTS.notificationTitleNoInternet,
      notificationTitleNoWifi = map.getString(NotificationConfig::notificationTitleNoWifi.name)
        ?: DEFAULTS.notificationTitleNoWifi,
      notificationChannel = map.getString(NotificationConfig::notificationChannel.name)
        ?: DEFAULTS.notificationChannel,
    )

    // Gson does not use the constructor. Thus a blob from a build with
    // different fields, or a corrupt blob, can make a non-null field null.
    // Each field falls back alone. A bad blob must give default text. It must
    // never crash a worker.
    @Suppress("SENSELESS_COMPARISON")
    fun fromJson(json: String?): NotificationConfig {
      val parsed = json?.let {
        runCatching { gson.fromJson(it, NotificationConfig::class.java) }.getOrNull()
      } ?: return DEFAULTS
      return NotificationConfig(
        notificationId = parsed.notificationId ?: DEFAULTS.notificationId,
        notificationTitle = parsed.notificationTitle ?: DEFAULTS.notificationTitle,
        notificationTitleNoInternet = parsed.notificationTitleNoInternet
          ?: DEFAULTS.notificationTitleNoInternet,
        notificationTitleNoWifi = parsed.notificationTitleNoWifi
          ?: DEFAULTS.notificationTitleNoWifi,
        notificationChannel = parsed.notificationChannel ?: DEFAULTS.notificationChannel,
      )
    }

    fun save(context: Context, config: NotificationConfig) {
      prefs(context).edit().putString(PREFS_KEY, gson.toJson(config)).apply()
    }

    fun load(context: Context): NotificationConfig =
      fromJson(prefs(context).getString(PREFS_KEY, null))

    private fun prefs(context: Context) =
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  }
}
