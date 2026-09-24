package ai.openspace.backgroundupload

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.util.Base64

/**
 * The durable queue: one directory per entry id. This is the v9 chunked
 * manifest store, generalized in place. The root directory keeps its v9 name
 * (`rnbgupload-chunked`) so v9 blobs and manifests are found without a move.
 *
 * A v10 directory holds `entry.json` and at most one staged body file (see
 * [StagedBody.fileName]). A v9 directory holds `manifest.json` and `blob`
 * until a same-id enqueue adopts it.
 *
 * Every write is tmp + fsync + rename ([AtomicFiles]). Bodies are staged
 * before `entry.json` is saved, so an `entry.json` on disk means its body is
 * durable. The store keeps [RequestIndex] current on every save and remove.
 * It is reachable from a bare Context, because a worker can run in a process
 * where React never started.
 */
class QueueStore(private val dir: File, private val index: RequestIndex = RequestIndex()) {

  companion object {
    const val ENTRY_FILE = "entry.json"
    const val V9_MANIFEST_FILE = "manifest.json"
    const val BLOB_FILE = "blob"

    private val gson = Gson()

    @Volatile
    private var instance: QueueStore? = null

    fun rootDir(context: Context) = File(context.filesDir, "rnbgupload-chunked")

    /** The process-wide store. The first call loads every row into [RequestIndex.shared]. */
    fun get(context: Context): QueueStore =
      instance ?: synchronized(this) {
        instance ?: QueueStore(rootDir(context), RequestIndex.shared)
          .also { it.loadIndex() }
          .also { instance = it }
      }
  }

  init {
    dir.mkdirs()
  }

  /** Rebuilds the index from disk. */
  @Synchronized
  fun loadIndex() {
    index.replaceAll(all().map { it.toRow() })
  }

  // Ids come from the caller and can hold path separators, so the directory
  // name is an encoding of the id. The id is read back from the file.
  fun entryDir(id: String) =
    File(dir, Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray()))

  fun blobFile(id: String) = File(entryDir(id), BLOB_FILE)

  /** The staged body file of [entry], or null for a bodiless request. */
  fun bodyFile(entry: QueueEntry): File? =
    entry.body?.fileName?.let { File(entryDir(entry.id), it) }

  private fun entryFile(id: String) = File(entryDir(id), ENTRY_FILE)

  /** Runs [block] under the store lock. For work that spans several store calls. */
  fun <T> locked(block: () -> T): T = synchronized(this) { block() }

  @Synchronized
  fun load(id: String): QueueEntry? = read(entryFile(id))

  /** Throws IOException when the entry did not persist. */
  @Synchronized
  fun save(entry: QueueEntry) {
    AtomicFiles.writeText(entryFile(entry.id), gson.toJson(entry))
    index.put(entry.toRow())
  }

  /**
   * An atomic read-modify-write. The lock spans load, [transform], and save,
   * so nothing can write between them and be erased. A result that is the
   * same object as the input writes nothing. A null result writes nothing:
   * forgetting an entry is always an explicit [remove]. A throwing
   * transform or a failed write propagates.
   */
  @Synchronized
  fun compute(id: String, transform: (QueueEntry?) -> QueueEntry?): QueueEntry? {
    val current = load(id)
    val next = transform(current)
    if (next != null && next !== current) save(next)
    return next
  }

  /** Best effort, for a worker that can go on from memory: null when the entry is gone or the write failed. */
  @Synchronized
  fun update(id: String, transform: (QueueEntry) -> QueueEntry): QueueEntry? =
    runCatching {
      val current = load(id) ?: return null
      val next = transform(current)
      if (next !== current) save(next)
      next
    }.getOrNull()

  /**
   * Forgets the entry: row and bytes. `entry.json` goes first, so a partial
   * delete never leaves a row that points at missing bytes.
   */
  @Synchronized
  fun remove(id: String) {
    entryFile(id).delete()
    entryDir(id).deleteRecursively()
    index.remove(id)
  }

  /** Every v10 entry. A directory with only a v9 manifest is not a row. */
  @Synchronized
  fun all(): List<QueueEntry> =
    (dir.listFiles { f -> f.isDirectory } ?: emptyArray())
      .mapNotNull { d -> File(d, ENTRY_FILE).takeIf { it.exists() }?.let { read(it) } }

  /** Every eventId a row names. The journal prune spares them. */
  @Synchronized
  fun referencedEventIds(): Set<String> = all().mapNotNull { it.settledEventId }.toSet()

  /**
   * The v9 chunked manifest in [id]'s directory. The caller asks only when
   * there is no v10 entry or the entry is a legacy row: a v10 entry
   * prunes the manifest when it adopts it.
   */
  @Synchronized
  fun legacyManifest(id: String): LegacyManifest? {
    val file = File(entryDir(id), V9_MANIFEST_FILE)
    if (!file.exists()) return null
    val parsed = runCatching { gson.fromJson(file.readText(), LegacyManifest::class.java) }.getOrNull()
    return LegacyManifest.validated(parsed)
  }

  /**
   * Deletes every file in the entry directory that [entry] does not use:
   * an older body, a v9 manifest it adopted, tmp files from a crash.
   */
  @Synchronized
  fun pruneUnreferenced(entry: QueueEntry) {
    val keep = setOfNotNull(ENTRY_FILE, entry.body?.fileName)
    entryDir(entry.id).listFiles()?.forEach { f ->
      if (f.name !in keep) f.deleteRecursively()
    }
  }

  private fun read(file: File): QueueEntry? {
    if (!file.exists()) return null
    val parsed = try {
      gson.fromJson(file.readText(), QueueEntry::class.java)
    } catch (error: Throwable) {
      Diag.warn("queue entry unreadable, skipped: ${file.parentFile?.name}", error)
      return null
    }
    return validated(parsed).also {
      if (it == null) Diag.warn("queue entry incomplete, skipped: ${file.parentFile?.name}")
    }
  }

  // Gson does not run constructors. A corrupt file, or one from an older
  // build, can hold null in a non-null field. Reject what the engine relies
  // on; normalize what has a safe default.
  @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
  private fun validated(e: QueueEntry?): QueueEntry? {
    if (e == null || e.id == null || e.key == null || e.state == null) return null
    if (!e.legacy && (e.descriptor == null || e.body == null || e.body.kind == null)) return null
    val d = e.descriptor?.let { d ->
      if (d.parts != null && d.parts.any { it == null || it.url == null }) return null
      d.copy(
        method = d.method ?: "POST",
        headers = d.headers ?: emptyMap(),
        accept = d.accept ?: emptyList(),
        parts = d.parts?.map { if (it.headers == null) it.copy(headers = emptyMap()) else it },
      )
    }
    return e.copy(
      varsJson = e.varsJson ?: "null",
      descriptor = d,
      generation = if (e.generation <= 0) 1 else e.generation,
    )
  }
}

/**
 * A v9 chunked manifest, only the fields v10 reads. The field names are the
 * v9 ones; Gson skips the rest of the file.
 */
data class LegacyManifest(
  val id: String,
  val parts: List<Part>,
  val accept: List<UploadOutcome.AcceptRule>,
) {
  companion object {
    @Suppress("SENSELESS_COMPARISON")
    fun validated(m: LegacyManifest?): LegacyManifest? {
      if (m == null || m.id == null || m.parts == null || m.parts.isEmpty()) return null
      if (m.parts.any { it == null || it.url == null }) return null
      return m.copy(
        parts = m.parts.map { if (it.headers == null) it.copy(headers = emptyMap()) else it },
        accept = m.accept ?: emptyList(),
      )
    }
  }
}

/** A rejection that reaches JS as `promise.reject(code, message)`. */
class QueueException(val code: String, message: String) : IOException(message) {
  companion object {
    const val E_RUNNING = "E_RUNNING"
    const val E_FILE_MISSING = "E_FILE_MISSING"
    const val E_STORAGE = "E_STORAGE"
    const val E_INVALID = "E_INVALID"
  }
}
