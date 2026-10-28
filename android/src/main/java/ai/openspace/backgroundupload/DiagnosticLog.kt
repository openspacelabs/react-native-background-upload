package ai.openspace.backgroundupload

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * The library's recent warnings, errors, and boot snapshots, kept on disk so
 * JS can read them on a later launch. A headless worker has no JS, and a
 * boot snapshot is written before JS subscribes, so a live event would lose
 * both. JS drains the log with takeDiagnostics().
 *
 * Holds the newest [MAX_RECORDS]. A write or read failure is swallowed: the
 * log must never break the queue that it reports on.
 */
class DiagnosticLog(private val file: File) {

  data class Record(
    val at: Long,
    val level: String,
    val message: String,
    val error: String? = null,
    val data: Map<String, Any?>? = null,
  ) {
    fun toMap(): Map<String, Any?> = mapOf(
      "at" to at,
      "level" to level,
      "message" to message,
      "error" to error,
      "data" to data,
    )
  }

  companion object {
    const val MAX_RECORDS = 50
    const val INFO = "info"
    const val WARN = "warn"
    const val ERROR = "error"

    private val gson = Gson()
    private val listType = object : TypeToken<List<Record>>() {}.type

    @Volatile
    private var instance: DiagnosticLog? = null

    /** The shared log. The first call also points [Diag] at it. */
    fun get(context: Context): DiagnosticLog =
      instance ?: synchronized(this) {
        instance ?: DiagnosticLog(File(QueueStore.rootDir(context), "diagnostics.json"))
          .also {
            instance = it
            Diag.log = it
          }
      }

    /** The error's class and message, without the stack: enough to group it. */
    fun describe(error: Throwable?): String? =
      error?.let { "${it.javaClass.name}: ${it.message ?: ""}".trimEnd(' ', ':') }
  }

  // The file's records, read once. Every write goes through this instance.
  private var cached: List<Record>? = null

  private fun records(): List<Record> = cached ?: read().also { cached = it }

  @Synchronized
  fun append(record: Record) {
    runCatching {
      val next = (records() + record).takeLast(MAX_RECORDS)
      cached = next
      AtomicFiles.writeText(file, gson.toJson(next))
    }
  }

  /** Every record, oldest first, then an empty log. */
  @Synchronized
  fun drain(): List<Record> {
    val records = records()
    cached = emptyList()
    if (records.isNotEmpty()) {
      // A file that will not delete is emptied, so the next process does not report these again.
      runCatching { if (!file.delete()) AtomicFiles.writeText(file, "[]") }
    }
    return records
  }

  private fun read(): List<Record> {
    if (!file.exists()) return emptyList()
    return runCatching { gson.fromJson<List<Record>>(file.readText(), listType) }
      .getOrNull()
      .orEmpty()
      // Gson does not run constructors, so a corrupt record can hold nulls.
      .filter {
        @Suppress("SENSELESS_COMPARISON")
        it != null && it.level != null && it.message != null
      }
  }
}
