package ai.openspace.backgroundupload

import com.facebook.react.bridge.ReadableArray
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
  val wifiOnly: Boolean,
  // Non-2xx responses to treat as a successful completion (for example, a 409
  // whose body marks an expected duplicate). Every other non-2xx response is a
  // terminal http error. The list is empty by default.
  val accept: List<UploadOutcome.AcceptRule>,
  val headers: Map<String, String>,
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
  // v8 persisted `acceptStatus: List<Int>` where v9 persists `accept`. This is
  // not a constructor parameter. It exists only so Gson can surface the legacy
  // field to [normalized]. It is null, and thus never serialized, for every
  // upload that this build creates.
  private val acceptStatus: List<Int>? = null

  val showsNotification get() = !noNotification

  /**
   * Gson does not use the constructor. Thus a WorkManager job that an older
   * build enqueued can give this worker an object whose non-null fields are
   * null. A v8 job carries `acceptStatus` and no `accept`. That NPEs the first
   * time the worker touches [accept], after the file has fully transmitted,
   * and the re-runs then re-send the whole file. This is the same
   * normalize-after-fromJson pattern as ChunkedManifestStore.validated(): map
   * the legacy statuses to rules, default what is absent, and give the worker
   * an object that is safe to use.
   */
  @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
  fun normalized(): Upload = Upload(
    id = id,
    url = url,
    path = path,
    method = method ?: "POST",
    wifiOnly = wifiOnly,
    accept = accept
      ?: acceptStatus?.map { UploadOutcome.AcceptRule(it) }
      ?: emptyList(),
    headers = headers ?: emptyMap(),
    noNotification = noNotification,
  )

  class MissingOptionException(optionName: String) :
    IllegalArgumentException("Missing '$optionName'")

  companion object {
    fun fromReadableMap(map: ReadableMap) = Upload(
      id = map.getString(Upload::id.name) ?: UUID.randomUUID().toString(),
      url = map.getString(Upload::url.name) ?: throw MissingOptionException(Upload::url.name),
      path = map.getString(Upload::path.name) ?: throw MissingOptionException(Upload::path.name),
      method = map.getString(Upload::method.name) ?: "POST",
      wifiOnly = if (map.hasKey(Upload::wifiOnly.name)) map.getBoolean(Upload::wifiOnly.name) else false,
      accept = parseAcceptRules(map.getArray(Upload::accept.name)),
      headers = parseHeaderMap(map.getMap(Upload::headers.name)),
      // The notification text and identity are not per-upload options. The
      // worker reads them from the NotificationConfig that configure() saved.
      noNotification = if (map.hasKey(Upload::noNotification.name))
        map.getBoolean(Upload::noNotification.name) else false,
    )
  }
}

// Upload and ChunkedManifest share this: one accept-rules shape, one parser.
internal fun parseAcceptRules(arr: ReadableArray?): List<UploadOutcome.AcceptRule> {
  if (arr == null) return listOf()
  return (0 until arr.size()).mapNotNull { i ->
    val rule = arr.getMap(i) ?: return@mapNotNull null
    UploadOutcome.AcceptRule(
      status = rule.getInt("status"),
      bodyIncludes = if (rule.hasKey("bodyIncludes")) rule.getString("bodyIncludes") else null,
    )
  }
}

internal fun parseHeaderMap(headers: ReadableMap?): Map<String, String> {
  if (headers == null) return mapOf()
  val map = mutableMapOf<String, String>()
  for (entry in headers.entryIterator) {
    map[entry.key] = entry.value.toString()
  }
  return map
}
