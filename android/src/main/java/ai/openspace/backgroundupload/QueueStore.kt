package ai.openspace.backgroundupload

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Base64

/**
 * The durable queue: one directory per entry id. This is the v9 chunked
 * manifest store, generalized in place. The root directory keeps its v9 name
 * (`rnbgupload-chunked`) so v9 blobs and manifests are found without a move.
 *
 * A v10 directory holds `entry.json` and at most one staged body file (see
 * [StagedBody.fileName]). A v9 directory holds `manifest.json` and `blob`
 * until a same-id enqueue adopts it or cancel deletes it.
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
    /** The most unowned v9 ids one count lists. The count itself stays exact. */
    const val MAX_UNOWNED_IDS = 100
    /** The v9 manifest and the tmp file of its atomic write. v10 writes neither. */
    val V9_MANIFEST_FILES = setOf(V9_MANIFEST_FILE, "manifest.tmp")
    const val BLOB_FILE = "blob"
    /**
     * The boot cleanup deletes an orphan directory only when its newest
     * file, and the directory itself, changed longer ago than this. A guard
     * against a staging path the name rules below do not know.
     */
    const val ORPHAN_MIN_AGE_MS = 24L * 60 * 60 * 1000

    /**
     * The files of an orphan that the boot cleanup may delete: copies whose
     * source the caller still has, and the tmp files of their atomic writes.
     * - `body-<gen>.json`, `body-<gen>.multipart`: written from the call
     *   ([BodyStaging.stage]);
     * - `file-<gen>`: a copy of the caller's file ([BodyStaging.copyFile]);
     * - any of these plus `.tmp`: an atomic write that did not finish
     *   ([AtomicFiles.tmpFor]).
     * Never a chunked blob (`blob`, `blob-<gen>`): that file was moved in,
     * so it can be the only copy, and a same-id enqueue adopts it. Never
     * `entry.json.tmp`: after a crash it can be the only trace of an entry
     * whose save renamed it but did not reach the disk, while its enqueue
     * already resolved.
     */
    private val COPIED_BODY = Regex("""(body-\d+\.(json|multipart)|file-\d+)(\.tmp)?""")

    fun isCopiedBodyFile(name: String): Boolean = COPIED_BODY.matches(name)

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

  /** The id whose [entryDir] is named [name], or null when no id encodes to it. */
  fun idOf(name: String): String? {
    val id = runCatching { String(Base64.getUrlDecoder().decode(name), Charsets.UTF_8) }.getOrNull()
    return id?.takeIf { entryDir(it).name == name }
  }

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

  /**
   * Deletes [id]'s directory when it holds no `entry.json`: v9 files that no
   * row owns, or a body a failed enqueue left behind, such as a moved
   * chunked file (the only copy of the caller's bytes). A directory with an
   * `entry.json`, readable or not, is kept. The empty id encodes to the store
   * root, so it deletes nothing.
   */
  @Synchronized
  fun removeUnowned(id: String) {
    if (id.isEmpty()) return
    val d = entryDir(id)
    if (isUnowned(d) && !d.deleteRecursively()) {
      Diag.warn("could not delete the unowned files of '$id'")
    }
  }

  /** A directory that [removeUnowned] deletes: it holds no `entry.json`. */
  private fun isUnowned(d: File) = d.isDirectory && !File(d, ENTRY_FILE).exists()

  /** Test seam: runs before the cleanup checks an orphan directory. */
  internal var beforeOrphanRemoval: (File) -> Unit = {}

  /** Test seam: runs before the count lists an unowned directory. */
  internal var beforeCountListing: (File) -> Unit = {}

  /**
   * Counts what no v10 entry owns, for the boot snapshot. Only directories
   * that [removeUnowned] would delete (no `entry.json`, not empty) count:
   * - v9: the directory holds a v9 manifest ([V9_MANIFEST_FILES]). Its id,
   *   decoded from the directory name, goes in unownedIds, sorted, at most
   *   [MAX_UNOWNED_IDS]. A name that decodes to no id is a warning; the
   *   directory still counts;
   * - orphan: any other, such as a body a failed enqueue left. A bare
   *   `blob` is an orphan: a first-generation v10 chunked body keeps that
   *   name, so it does not show v9.
   * Each legacy row in [legacyIds] counts once, and the v9 manifest and
   * blob it keeps count as legacy bytes. A legacy directory has its
   * `entry.json`, so it is never an unowned upload too.
   *
   * Lists directories and reads file sizes only. It opens no file and
   * changes nothing. It does not take the store lock, so workers do not
   * wait; a directory that changes during the count can be counted either
   * way. An error is a warning, and the counts found so far are returned.
   */
  fun countLeftovers(legacyIds: Collection<String>): Leftovers {
    var legacyBytes = 0L
    var v9Dirs = 0
    var v9Bytes = 0L
    val v9Ids = mutableListOf<String>()
    var orphanDirs = 0
    var orphanBytes = 0L
    val orphanList = mutableListOf<File>()
    try {
      val dirs = dir.listFiles()
      if (dirs == null) Diag.warn("leftover count: could not list the queue directory")
      dirs?.forEach { d ->
        if (!isUnowned(d)) return@forEach
        beforeCountListing(d)
        val files = d.listFiles()
        if (files == null) {
          // Gone since the root listing (a cancel or a forget) is not an error.
          if (d.exists()) Diag.warn("leftover count: could not list ${d.name}")
          return@forEach
        }
        if (files.isEmpty()) return@forEach
        val size = files.sumOf { if (it.isFile) it.length() else 0L }
        if (files.any { it.name in V9_MANIFEST_FILES }) {
          v9Dirs++
          v9Bytes += size
          val id = idOf(d.name)
          if (id == null) Diag.warn("leftover count: no id encodes to the directory ${d.name}") else v9Ids += id
        } else {
          orphanDirs++
          orphanBytes += size
          orphanList += d
        }
      }
      legacyIds.forEach { id ->
        val d = entryDir(id)
        if (isUnowned(d)) return@forEach // counted above, as the scan found it
        legacyBytes += (V9_MANIFEST_FILES + BLOB_FILE).sumOf { name ->
          File(d, name).let { if (it.isFile) it.length() else 0L }
        }
      }
    } catch (error: Exception) {
      Diag.warn("leftover count stopped early; the counts are partial", error)
    }
    return Leftovers(
      V9Leftovers(legacyIds.size, legacyBytes, v9Dirs, v9Bytes, v9Ids.sorted().take(MAX_UNOWNED_IDS)),
      Orphans(orphanDirs, orphanBytes),
      orphanList,
    )
  }

  /**
   * The boot cleanup. Deletes each directory of [dirs] (the orphans that
   * [countLeftovers] listed) when all of these hold, checked under the
   * store lock just before the delete:
   * - it has no `entry.json`;
   * - every entry in it is a regular file that [isCopiedBodyFile] accepts.
   *   So a v9 manifest, a chunked blob, `entry.json.tmp`, an unknown file,
   *   a symbolic link or a subdirectory keeps the whole directory;
   * - its newest file, and the directory itself, changed more than
   *   [ORPHAN_MIN_AGE_MS] before [now].
   * The caller runs it where no enqueue can be staging (the queue executor).
   * A failed delete is a warning; the other directories go on. It never
   * throws. Returns what it deleted: directories fully gone, and the bytes
   * of every file deleted.
   */
  fun removeOrphans(dirs: List<File>, now: Long): Orphans {
    var removedDirs = 0
    var removedBytes = 0L
    for (d in dirs) {
      try {
        beforeOrphanRemoval(d)
        val (bytes, gone) = removeOrphan(d, now) ?: continue
        removedBytes += bytes
        if (gone) removedDirs++
      } catch (error: Exception) {
        Diag.warn("orphan cleanup: could not check ${d.name}", error)
      }
    }
    return Orphans(0, 0, removedDirs, removedBytes)
  }

  /** One directory of [removeOrphans]. Null when it is kept untouched; else the bytes freed and whether it is gone. */
  @Synchronized
  private fun removeOrphan(d: File, now: Long): Pair<Long, Boolean>? {
    if (!d.isDirectory || !isUnowned(d)) return null
    val files = d.listFiles() ?: return null
    if (files.isEmpty()) return null
    val deletable = files.all {
      Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) && isCopiedBodyFile(it.name)
    }
    if (!deletable) return null
    // lastModified() is 0 when it can not be read: then the age is unknown.
    val times = files.map { it.lastModified() } + d.lastModified()
    if (times.any { it <= 0L } || now - times.max() <= ORPHAN_MIN_AGE_MS) return null
    var freed = 0L
    for (f in files) {
      val size = f.length()
      try {
        Files.delete(f.toPath())
      } catch (error: IOException) {
        Diag.warn("orphan cleanup: could not delete ${d.name}/${f.name}", error)
        return freed to false
      }
      freed += size
    }
    try {
      // Deletes an empty directory only.
      Files.delete(d.toPath())
    } catch (error: IOException) {
      Diag.warn("orphan cleanup: could not delete ${d.name}", error)
      return freed to false
    }
    return freed to true
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
 * v9 work still on the device, from [QueueStore.countLeftovers]. The
 * counts are disjoint: legacyRows + unownedUploads is the number of v9 ids.
 * - legacyRows: legacy rows; legacyBytes: the v9 manifest and blob they keep.
 * - unownedUploads: v9 directories with no row; unownedBytes: their files;
 *   unownedIds: their ids, sorted, at most [QueueStore.MAX_UNOWNED_IDS], so
 *   the app can cancel the ones it will not send again.
 * All zero once the app has adopted or cancelled every v9 id.
 */
data class V9Leftovers(
  val legacyRows: Int,
  val legacyBytes: Long,
  val unownedUploads: Int,
  val unownedBytes: Long,
  val unownedIds: List<String> = emptyList(),
) {
  fun toMap(): Map<String, Any?> = mapOf(
    "legacyRows" to legacyRows,
    "legacyBytes" to legacyBytes,
    "unownedUploads" to unownedUploads,
    "unownedBytes" to unownedBytes,
    "unownedIds" to unownedIds,
  )
}

/**
 * Directories with no entry file that are not v9: the library's own
 * leftovers. [dirs] and [bytes] are what is left; [removedDirs] and
 * [removedBytes] are what the boot cleanup deleted.
 */
data class Orphans(val dirs: Int, val bytes: Long, val removedDirs: Int = 0, val removedBytes: Long = 0) {
  fun toMap(): Map<String, Any?> =
    mapOf("dirs" to dirs, "bytes" to bytes, "removedDirs" to removedDirs, "removedBytes" to removedBytes)
}

/**
 * What [QueueStore.countLeftovers] found. [orphanDirs]: the orphan
 * directories it counted, for [QueueStore.removeOrphans].
 */
data class Leftovers(val v9: V9Leftovers, val orphans: Orphans, val orphanDirs: List<File> = emptyList()) {
  /** The counts after the cleanup deleted [removed]: what is left, and what went. */
  fun afterCleanup(removed: Orphans): Leftovers = copy(
    orphans = Orphans(
      (orphans.dirs - removed.removedDirs).coerceAtLeast(0),
      (orphans.bytes - removed.removedBytes).coerceAtLeast(0),
      removed.removedDirs,
      removed.removedBytes,
    ),
  )
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
