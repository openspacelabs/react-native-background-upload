package ai.openspace.backgroundupload

import com.facebook.react.bridge.ReadableMap
import java.util.UUID

// Data model of a single upload
// Can be created from RN's ReadableMap
// Can be used for JSON deserialization
data class Upload(
  val id: String,
  val url: String,
  val path: String,
  val method: String,
  val maxRetries: Int,
  val wifiOnly: Boolean,
  // Non-2xx statuses to treat as a successful completion (e.g. [409] when
  // duplicate-create conflicts are expected). Everything else non-2xx is a
  // terminal http error. Empty by default.
  val acceptStatus: List<Int>,
  val headers: Map<String, String>,
  val notificationId: Int,
  val notificationTitle: String,
  val notificationTitleNoInternet: String,
  val notificationTitleNoWifi: String,
  val notificationChannel: String,
  /**
   * Suppresses the progress notification for this upload.
   *
   * The notification is not decoration: posting one is what lets the worker run
   * in foreground mode, which is how a long-running worker survives Doze and
   * memory pressure. A suppressed upload is an ordinary background worker, so
   * the OS may defer it or stop it mid-flight for WorkManager to re-run later.
   * Suppress only payloads small enough that a restart costs nothing.
   *
   * An opt-out rather than an opt-in so that absence means "notify": this model
   * is serialized into WorkManager's database, and a job enqueued by a build
   * that predates the option can be replayed by a build that has it.
   */
  val noNotification: Boolean,
) {
  val showsNotification get() = !noNotification

  class MissingOptionException(optionName: String) :
    IllegalArgumentException("Missing '$optionName'")

  companion object {
    const val DEFAULT_NOTIFICATION_CHANNEL = "background-upload"

    fun fromReadableMap(map: ReadableMap) = Upload(
      id = map.getString("customUploadId") ?: UUID.randomUUID().toString(),
      url = map.getString(Upload::url.name) ?: throw MissingOptionException(Upload::url.name),
      path = map.getString(Upload::path.name) ?: throw MissingOptionException(Upload::path.name),
      method = map.getString(Upload::method.name) ?: "POST",
      maxRetries = if (map.hasKey(Upload::maxRetries.name)) map.getInt(Upload::maxRetries.name) else 5,
      wifiOnly = if (map.hasKey(Upload::wifiOnly.name)) map.getBoolean(Upload::wifiOnly.name) else false,
      acceptStatus = map.getArray(Upload::acceptStatus.name)?.let { arr ->
        (0 until arr.size()).map { i -> arr.getInt(i) }
      } ?: listOf(),
      headers = map.getMap(Upload::headers.name).let { headers ->
        if (headers == null) return@let mapOf()
        val map = mutableMapOf<String, String>()
        for (entry in headers.entryIterator) {
          map[entry.key] = entry.value.toString()
        }
        return@let map
      },
      // Notification options are optional: the library supplies sensible defaults
      // and creates its own channel, so consumers don't need any notifee plumbing.
      notificationId = (map.getString(Upload::notificationId.name)
        ?: DEFAULT_NOTIFICATION_CHANNEL).hashCode(),
      notificationTitle = map.getString(Upload::notificationTitle.name)
        ?: "Uploading…",
      notificationTitleNoInternet = map.getString(Upload::notificationTitleNoInternet.name)
        ?: "Waiting for connection…",
      notificationTitleNoWifi = map.getString(Upload::notificationTitleNoWifi.name)
        ?: "Waiting for Wi-Fi…",
      notificationChannel = map.getString(Upload::notificationChannel.name)
        ?: DEFAULT_NOTIFICATION_CHANNEL,
      noNotification = if (map.hasKey(Upload::noNotification.name))
        map.getBoolean(Upload::noNotification.name) else false,
    )
  }
}



