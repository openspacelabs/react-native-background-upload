package ai.openspace.backgroundupload

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Writes a request body into the entry directory before the entry is saved.
 * After enqueue resolves, every byte the request needs is in the library
 * directory, so the caller may delete its source.
 *
 * | Descriptor    | Staged file         | Content-Type                                        |
 * | none          | none                | none                                                |
 * | data          | body-<gen>.json     | application/json unless the caller set one          |
 * | form          | body-<gen>.multipart| multipart/form-data; boundary=… (replaces the caller's) |
 * | file          | file-<gen> (copy)   | the caller's own                                    |
 * | file + parts  | blob / blob-<gen> (move) | the caller's own                               |
 *
 * Each staged file has a name per generation, so a replace writes a new file
 * next to the old one. The old body is deleted only after the new entry is
 * saved. Every source is checked before anything is written. A chunked blob
 * of generation 1 keeps the v9 name `blob`, so v9 blobs are found in place.
 */
object BodyStaging {
  const val JSON_CONTENT_TYPE = "application/json"
  private const val BUFFER = 64 * 1024
  private val CRLF = "\r\n".toByteArray()

  data class Staged(val body: StagedBody, val headers: Map<String, String>)

  /**
   * @param ownedBlob the chunked blob the entry (or a v9 manifest) already
   *   owns. The parts run over it when the caller's file is gone (v9 recreate).
   * @param keepOwned run over [ownedBlob] even when the caller's file is
   *   present. Only for the same parts, whose accepted flags carry over.
   */
  fun stage(
    d: Descriptor,
    dir: File,
    generation: Int,
    ownedBlob: File? = null,
    keepOwned: Boolean = false,
  ): Staged {
    checkSources(d, dir, generation, ownedBlob, keepOwned)
    val body = when (d.bodyKind) {
      StagedBody.NONE -> StagedBody(StagedBody.NONE, null, null, 0)
      StagedBody.JSON -> {
        val name = "body-$generation.json"
        val target = File(dir, name)
        writeJson(d.dataJson!!, target)
        StagedBody(StagedBody.JSON, name, null, target.length())
      }
      StagedBody.MULTIPART -> {
        val name = "body-$generation.multipart"
        val target = File(dir, name)
        val boundary = newBoundary()
        writeMultipart(d.form!!, boundary, target)
        StagedBody(StagedBody.MULTIPART, name, boundary, target.length())
      }
      StagedBody.FILE -> {
        val name = "file-$generation"
        val target = File(dir, name)
        copyFile(File(d.file!!), target)
        StagedBody(StagedBody.FILE, name, null, target.length())
      }
      else -> {
        val parts = d.parts!!
        val source = File(d.file!!)
        val runOver = chunkedInput(d, dir, generation, ownedBlob, keepOwned)
        // Checked before the move, so a rejected plan moves nothing.
        if (!ChunkedParts.tilesExactly(parts, runOver.length())) {
          throw QueueException(
            QueueException.E_INVALID,
            "parts must tile the file exactly: [0, ${runOver.length()})",
          )
        }
        val blob = if (runOver.path == source.path) {
          File(dir, blobName(generation)).also { takeOwnership(source, it) }
        } else runOver
        StagedBody(StagedBody.CHUNKED, blob.name, null, ChunkedParts.totalBytes(parts))
      }
    }
    return Staged(body, headersFor(d.headers, body))
  }

  /** The Content-Type rule of the table above. */
  fun headersFor(headers: Map<String, String>, body: StagedBody): Map<String, String> =
    when (body.kind) {
      StagedBody.JSON ->
        if (HeaderMap.contains(headers, "Content-Type")) headers
        else headers + ("Content-Type" to JSON_CONTENT_TYPE)
      StagedBody.MULTIPART ->
        HeaderMap.without(headers, "Content-Type") +
          ("Content-Type" to "multipart/form-data; boundary=${body.boundary}")
      else -> headers
    }

  /** The chunked blob name of [generation]. Generation 1 keeps the v9 name. */
  fun blobName(generation: Int) =
    if (generation <= 1) QueueStore.BLOB_FILE else "${QueueStore.BLOB_FILE}-$generation"

  /**
   * The file a chunked plan runs over, before anything moves:
   * 1. [ownedBlob] when [keepOwned] and it exists;
   * 2. the caller's file when present (a new file wins over an old blob);
   * 3. this generation's blob, left by a crash after the move;
   * 4. [ownedBlob], when the caller's file was moved away at an earlier enqueue.
   */
  internal fun chunkedInput(d: Descriptor, dir: File, generation: Int, ownedBlob: File?, keepOwned: Boolean): File {
    val source = File(d.file!!)
    val leftover = File(dir, blobName(generation))
    return when {
      keepOwned && ownedBlob != null && ownedBlob.exists() -> ownedBlob
      source.isFile -> source
      leftover.exists() -> leftover
      ownedBlob != null && ownedBlob.exists() -> ownedBlob
      else -> throw QueueException(QueueException.E_FILE_MISSING, "file does not exist: ${source.path}")
    }
  }

  /** Every source must exist and be readable before any write. */
  internal fun checkSources(d: Descriptor, dir: File, generation: Int, ownedBlob: File?, keepOwned: Boolean) {
    d.form?.forEach { part ->
      part.path?.let { requireReadable(File(it), "form part '${part.name}'") }
    }
    when (d.bodyKind) {
      StagedBody.FILE -> requireReadable(File(d.file!!), "file")
      StagedBody.CHUNKED -> chunkedInput(d, dir, generation, ownedBlob, keepOwned)
    }
  }

  private fun requireReadable(file: File, what: String) {
    if (!file.isFile || !file.canRead()) {
      throw QueueException(QueueException.E_FILE_MISSING, "$what does not exist or can not be read: ${file.path}")
    }
  }

  internal fun writeJson(dataJson: String, target: File) =
    AtomicFiles.writeText(target, dataJson)

  /**
   * RFC 7578. Per part: the boundary line, Content-Disposition with the name
   * (and a filename for a file part), Content-Type, a blank line, the bytes,
   * CRLF. Then the closing delimiter. File parts stream from disk.
   */
  internal fun writeMultipart(form: List<FormPart>, boundary: String, target: File) {
    AtomicFiles.writeAtomically(target) { raw ->
      val out = BufferedOutputStream(raw, BUFFER)
      for (part in form) {
        out.ascii("--$boundary")
        out.write(CRLF)
        val disposition = StringBuilder("Content-Disposition: form-data; name=\"")
          .append(escapeQuoted(part.name)).append('"')
        if (part.path != null) {
          val fileName = part.fileName ?: File(part.path).name
          disposition.append("; filename=\"").append(escapeQuoted(fileName)).append('"')
        }
        out.write(disposition.toString().toByteArray(Charsets.UTF_8))
        out.write(CRLF)
        out.write("Content-Type: ${part.contentType}".toByteArray(Charsets.UTF_8))
        out.write(CRLF)
        out.write(CRLF)
        if (part.path != null) {
          File(part.path).inputStream().use { it.copyTo(out, BUFFER) }
        } else {
          out.write((part.string ?: "").toByteArray(Charsets.UTF_8))
        }
        out.write(CRLF)
      }
      out.ascii("--$boundary--")
      out.write(CRLF)
      out.flush()
    }
  }

  /** The WHATWG form encoding of a quoted name: `"`, CR and LF are percent-encoded. */
  internal fun escapeQuoted(value: String): String =
    value.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A")

  internal fun copyFile(source: File, target: File) {
    source.inputStream().use { input ->
      AtomicFiles.writeAtomically(target) { out -> input.copyTo(out, BUFFER) }
    }
  }

  /**
   * Moves the caller's file to [blob] (v9 verbatim). A crash between the move
   * and the entry save leaves the bytes at the blob path with no entry; a
   * retry whose source is gone adopts them ([chunkedInput] step 3).
   */
  internal fun takeOwnership(source: File, blob: File) {
    if (source.absoluteFile == blob.absoluteFile) return
    if (!source.exists()) {
      if (blob.exists()) return
      throw QueueException(QueueException.E_FILE_MISSING, "file does not exist: ${source.path}")
    }
    blob.parentFile?.mkdirs()
    if (blob.exists()) blob.delete()
    if (!source.renameTo(blob)) {
      // renameTo can not cross file systems. Files.move falls back to copy + delete.
      Files.move(source.toPath(), blob.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    blob.parentFile?.let { AtomicFiles.syncDirectory(it) }
  }

  internal fun newBoundary(): String = "----RNBGU" + UUID.randomUUID().toString().replace("-", "")

  private fun OutputStream.ascii(text: String) = write(text.toByteArray(Charsets.US_ASCII))
}
