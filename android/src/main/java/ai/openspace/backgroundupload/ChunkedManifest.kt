package ai.openspace.backgroundupload

import android.content.Context
import com.facebook.react.bridge.ReadableMap
import com.google.gson.Gson
import java.io.File
import java.util.Base64

/**
 * The durable record of one chunked upload: the moved source file, the parts
 * that the consumer authored, and which of them the server has accepted.
 * [ChunkedManifestStore] persists it as JSON at startUpload, BEFORE the work
 * is enqueued. Thus a worker rescheduled after process death (or a startUpload
 * after a crash, a stop, or a reauth) resumes from it without a call into JS.
 * This manifest IS the resume mechanism.
 *
 * The data shape is kept free of Android and React types (Gson round-trips
 * it, and JVM tests construct it directly). The ReadableMap parsing lives in
 * the companion, like [Upload]'s.
 */
data class ChunkedManifest(
  val id: String,
  /** The library-owned copy of the bytes (the consumer's file, renamed in). */
  val sourcePath: String,
  val parts: List<Part>,
  val accept: List<UploadOutcome.AcceptRule>,
  /** Epoch ms. After this time, the upload stops with errorKind 'expired'. */
  val expiresAt: Long,
  val wifiOnly: Boolean,
  val noNotification: Boolean,
  val createdAt: Long,
) {
  /**
   * One part, exactly as the consumer authored it. The library sends the file
   * bytes [start, end) as the body of a PUT to [url], with [headers]
   * unchanged. It never derives or edits a protocol field.
   */
  data class Part(
    val url: String,
    val headers: Map<String, String>,
    val start: Long,
    val end: Long, // exclusive
    val accepted: Boolean = false,
  ) {
    val size get() = end - start
  }

  val showsNotification get() = !noNotification
  val totalBytes get() = parts.sumOf { it.size }
  val acceptedBytes get() = parts.filter { it.accepted }.sumOf { it.size }

  /** The server's auto-publish condition. It is the only thing that 'completed' may mean. */
  val allAccepted get() = parts.all { it.accepted }

  fun isExpired(now: Long) = now >= expiresAt

  fun pendingIndexes() = parts.indices.filter { !parts[it].accepted }

  fun withPartAccepted(index: Int) = copy(
    parts = parts.mapIndexed { i, part -> if (i == index) part.copy(accepted = true) else part },
  )

  class ReconcileException(message: String) : IllegalArgumentException(message)

  /**
   * A startUpload re-call with an existing id is one of two things:
   *
   * **Resume** — the incoming parts are the SAME array (identical count,
   * ranges, and urls). The headers, the accept rules, expiresAt, and the flags
   * come from the new call. This is how fresh auth reaches stalled parts, and
   * how a salvage extends the deadline. The accepted part statuses, the moved
   * source, and createdAt survive from this manifest. A resume is permitted at
   * any time, running or not. A running worker re-reads the stored copy before
   * every attempt.
   *
   * **Recreate** — a DIFFERENT parts array. The consumer re-authored the
   * upload under a fresh server uploadId after the old one died (it expired
   * past the server's 31-day window, or it is otherwise unrecoverable). The
   * owned bytes are kept. The parts are replaced as a whole, and every part
   * status resets to unsent. The headers, the accept rules, and expiresAt come
   * from the new call. The new ranges must tile exactly [0, blobSize). A
   * partial or overlapping cover would silently upload wrong bytes. A recreate
   * is accepted only while the upload is NOT running (stalled on a terminal
   * error, expired, or cancelled). A different parts array while a worker
   * executes is a consumer bug, not a recreate, because the in-flight requests
   * belong to the old parts.
   */
  fun reconcile(incoming: ChunkedManifest, running: Boolean, blobSize: Long): ChunkedManifest {
    if (samePartsAs(incoming)) {
      // Accepted flags follow the RANGE, not the array index. samePartsAs is
      // order-independent, so the same tile can sit at a different index.
      val acceptedStarts = parts.filter { it.accepted }.map { it.start }.toSet()
      return incoming.copy(
        sourcePath = sourcePath,
        createdAt = createdAt,
        parts = incoming.parts.map { it.copy(accepted = it.start in acceptedStarts) },
      )
    }
    if (running) throw ReconcileException(
      "chunked upload '$id' is running; a different parts array is only accepted once it stops",
    )
    if (!tilesExactly(incoming.parts, blobSize)) throw ReconcileException(
      "chunked upload '$id' recreate parts must tile exactly [0, $blobSize)",
    )
    return incoming.copy(sourcePath = sourcePath, createdAt = createdAt)
  }

  // Order-independent, like tilesExactly. The same tiles, authored in a
  // different order, are the SAME upload (a resume), never a recreate.
  private fun samePartsAs(incoming: ChunkedManifest): Boolean {
    if (incoming.parts.size != parts.size) return false
    val stored = parts.sortedBy { it.start }
    val fresh = incoming.parts.sortedBy { it.start }
    return stored.indices.all { i ->
      fresh[i].url == stored[i].url &&
        fresh[i].start == stored[i].start &&
        fresh[i].end == stored[i].end
    }
  }

  companion object {
    /**
     * Whether [parts] cover [0, size) exactly: no gap, no overlap, and nothing
     * past the end. Order-independent, like everything else about parts.
     */
    fun tilesExactly(parts: List<Part>, size: Long): Boolean {
      if (parts.isEmpty()) return false
      val sorted = parts.sortedBy { it.start }
      var cursor = 0L
      for (part in sorted) {
        if (part.start != cursor || part.end <= part.start) return false
        cursor = part.end
      }
      return cursor == size
    }

    /**
     * Validates a first-call (create) manifest against the just-owned bytes.
     * Like a recreate, the parts must tile exactly [0, blobSize). A partial or
     * overlapping cover would silently upload wrong bytes. It throws BEFORE
     * the manifest is saved. Thus the moved blob stays adoptable by a
     * corrected retry (see UploaderModule.takeOwnership's orphan branch).
     */
    fun validatedForCreate(incoming: ChunkedManifest, blobSize: Long): ChunkedManifest {
      if (!tilesExactly(incoming.parts, blobSize)) throw ReconcileException(
        "chunked upload '${incoming.id}' parts must tile exactly [0, $blobSize)",
      )
      return incoming
    }

    /** @param sourcePath the library-owned destination, not the consumer's path. */
    fun fromReadableMap(map: ReadableMap, sourcePath: String, createdAt: Long): ChunkedManifest {
      val partsArr = map.getArray("parts") ?: throw Upload.MissingOptionException("parts")
      if (partsArr.size() == 0) throw IllegalArgumentException("parts must be a non-empty array")
      if (!map.hasKey("expiresAt")) throw Upload.MissingOptionException("expiresAt")
      return ChunkedManifest(
        id = map.getString("id") ?: throw Upload.MissingOptionException("id"),
        sourcePath = sourcePath,
        parts = (0 until partsArr.size()).map { i ->
          val part = partsArr.getMap(i) ?: throw Upload.MissingOptionException("parts[$i]")
          val range = part.getMap("range") ?: throw Upload.MissingOptionException("parts[$i].range")
          Part(
            url = part.getString("url") ?: throw Upload.MissingOptionException("parts[$i].url"),
            headers = parseHeaderMap(part.getMap("headers")),
            start = range.getDouble("start").toLong(),
            end = range.getDouble("end").toLong(),
          )
        },
        accept = parseAcceptRules(map.getArray("accept")),
        expiresAt = map.getDouble("expiresAt").toLong(),
        wifiOnly = if (map.hasKey("wifiOnly")) map.getBoolean("wifiOnly") else false,
        noNotification = if (map.hasKey("noNotification")) map.getBoolean("noNotification") else false,
        createdAt = createdAt,
      )
    }
  }
}

/**
 * A file-backed store: one directory per upload id, which holds
 * `manifest.json` and `blob` (the moved source bytes). It has the same
 * durability pattern as [EventJournal]: tmp+rename writes, and corrupt files
 * read as absent. It is reachable from a bare Context, because the worker can
 * run in a process where React never initialized.
 */
class ChunkedManifestStore(private val dir: File) {

  companion object {
    private val gson = Gson()

    @Volatile
    private var instance: ChunkedManifestStore? = null

    fun get(context: Context): ChunkedManifestStore =
      instance ?: synchronized(this) {
        instance ?: ChunkedManifestStore(File(context.filesDir, "rnbgupload-chunked"))
          .also { instance = it }
      }
  }

  init {
    dir.mkdirs()
  }

  // Upload ids come from the consumer, and they can contain path separators or
  // other filesystem-hostile characters. Thus the directory name is an encoding
  // of the id, never the id itself. The id is read back from the manifest, not
  // decoded from the name.
  private fun uploadDir(id: String) =
    File(dir, Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray()))

  private fun manifestFile(id: String) = File(uploadDir(id), "manifest.json")

  /** Where startUpload moves the source file for this id. */
  fun blobFile(id: String) = File(uploadDir(id), "blob")

  @Synchronized
  fun load(id: String): ChunkedManifest? {
    val file = manifestFile(id)
    if (!file.exists()) return null
    val parsed = runCatching { gson.fromJson(file.readText(), ChunkedManifest::class.java) }
      .getOrNull()
    return validated(parsed)
  }

  /** Throws on a write failure. A manifest that did not persist must fail the startUpload call. */
  @Synchronized
  fun save(manifest: ChunkedManifest) {
    val dir = uploadDir(manifest.id)
    dir.mkdirs()
    val tmp = File(dir, "manifest.tmp")
    tmp.writeText(gson.toJson(manifest))
    if (!tmp.renameTo(manifestFile(manifest.id))) {
      throw java.io.IOException("failed to persist chunked manifest for '${manifest.id}'")
    }
  }

  /**
   * An atomic read-modify-write. Thus a worker that marks a part accepted can
   * never clobber a concurrent startUpload's fresh headers (or another part's
   * flag). Returns null, without a throw, when the manifest is gone or the
   * write failed. A caller that can continue from memory does that.
   */
  @Synchronized
  fun update(id: String, transform: (ChunkedManifest) -> ChunkedManifest): ChunkedManifest? =
    runCatching {
      val manifest = load(id) ?: return null
      val next = transform(manifest)
      save(next)
      next
    }.getOrNull()

  /**
   * An atomic create-or-transform. The store lock spans load, [transform], and
   * save. Thus nothing — a running worker's markAccepted included — can write
   * between them and be erased. startUpload's load, reconcile, and save must
   * go through here, not as three separate calls. [transform] receives null
   * when no manifest exists. Unlike [update], a transform that throws (a
   * reconcile rejection) or a failed write propagates, because startUpload
   * must fail loudly, not continue from memory.
   */
  @Synchronized
  fun compute(id: String, transform: (ChunkedManifest?) -> ChunkedManifest): ChunkedManifest {
    val next = transform(load(id))
    save(next)
    return next
  }

  /** Whether a manifest is stored for this id (without parsing it). */
  @Synchronized
  fun contains(id: String): Boolean = manifestFile(id).exists()

  /** Deletes the manifest AND the moved bytes. Does nothing for an unknown id (a simple upload). */
  @Synchronized
  fun remove(id: String) {
    uploadDir(id).deleteRecursively()
  }

  @Synchronized
  fun all(): List<ChunkedManifest> =
    (dir.listFiles { f -> f.isDirectory } ?: emptyArray())
      .mapNotNull { d ->
        val file = File(d, "manifest.json")
        if (!file.exists()) return@mapNotNull null
        validated(runCatching { gson.fromJson(file.readText(), ChunkedManifest::class.java) }.getOrNull())
      }

  // Gson does not use the constructor. Thus a corrupt or field-renamed file can
  // make non-null Kotlin fields null. Reject a file that lacks a field that the
  // engine relies on. Normalize an absent accept list; do not reject it.
  @Suppress("SENSELESS_COMPARISON")
  private fun validated(m: ChunkedManifest?): ChunkedManifest? {
    if (m == null || m.id == null || m.sourcePath == null || m.parts == null) return null
    if (m.parts.isEmpty()) return null
    if (m.parts.any { it == null || it.url == null || it.headers == null }) return null
    return if (m.accept == null) m.copy(accept = listOf()) else m
  }
}
