package ai.openspace.backgroundupload

import android.content.Context
import com.google.gson.Gson
import java.io.File

/** The configure() retry defaults. A request's `retry` overrides them field by field. */
data class RetryDefaults(
  val baseMs: Long = 1_000,
  val maxMs: Long = 7_200_000,
  val jitter: Double = 0.2,
  val exempt: List<Int> = listOf(404),
)

/** Queue-wide settings. They live next to the entries, in `settings.json`. */
data class QueueSettings(
  val wifiOnly: Boolean = false,
  val paused: Boolean = false,
  /** +1 per updateHeaders(). A 401 from an attempt sent under an older value re-issues at once. */
  val headerGeneration: Int = 0,
  val retry: RetryDefaults = RetryDefaults(),
)

/**
 * Reads and writes [QueueSettings]. The value is cached after the first read.
 * Workers read it before every attempt, so the cache matters. A corrupt file
 * reads as the defaults.
 */
class QueueSettingsStore(private val file: File) {

  companion object {
    private val gson = Gson()

    @Volatile
    private var instance: QueueSettingsStore? = null

    fun get(context: Context): QueueSettingsStore =
      instance ?: synchronized(this) {
        instance ?: QueueSettingsStore(File(QueueStore.rootDir(context), "settings.json"))
          .also { instance = it }
      }

    /** configure().retry → defaults. Absent fields keep the library defaults. */
    fun retryDefaults(retry: Map<String, Any?>?): RetryDefaults {
      val d = RetryDefaults()
      if (retry == null) return d
      val backoff = retry["backoff"] as? Map<*, *>
      val terminal = retry["terminalHttp"] as? Map<*, *>
      return RetryDefaults(
        baseMs = (backoff?.get("baseMs") as? Number)?.toLong() ?: d.baseMs,
        maxMs = (backoff?.get("maxMs") as? Number)?.toLong() ?: d.maxMs,
        jitter = (backoff?.get("jitter") as? Number)?.toDouble() ?: d.jitter,
        exempt = (terminal?.get("exempt") as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }
          ?: d.exempt,
      )
    }
  }

  private var cached: QueueSettings? = null

  @Synchronized
  fun load(): QueueSettings {
    cached?.let { return it }
    val read = if (file.exists()) {
      runCatching { gson.fromJson(file.readText(), QueueSettings::class.java) }.getOrNull()
    } else null
    return validated(read).also { cached = it }
  }

  /** Throws IOException when the write fails. The cache then keeps the old value. */
  @Synchronized
  fun update(transform: (QueueSettings) -> QueueSettings): QueueSettings {
    val next = transform(load())
    AtomicFiles.writeText(file, gson.toJson(next))
    cached = next
    return next
  }

  // Gson does not run constructors, so absent fields read as null or 0.
  @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
  private fun validated(s: QueueSettings?): QueueSettings {
    if (s == null) return QueueSettings()
    val d = RetryDefaults()
    val r = s.retry
    return QueueSettings(
      wifiOnly = s.wifiOnly,
      paused = s.paused,
      headerGeneration = s.headerGeneration,
      retry = if (r == null) d else RetryDefaults(
        baseMs = if (r.baseMs > 0) r.baseMs else d.baseMs,
        maxMs = if (r.maxMs > 0) r.maxMs else d.maxMs,
        jitter = r.jitter,
        exempt = r.exempt ?: d.exempt,
      ),
    )
  }
}
