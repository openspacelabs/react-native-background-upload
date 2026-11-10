package ai.openspace.backgroundupload

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Write-ahead file writes. Every durable file in the library goes through
 * [writeAtomically]: write a tmp sibling, fsync it, rename it over the
 * target. A crash at any point leaves either the old target or the new one,
 * never a partial file.
 */
internal object AtomicFiles {
  const val TMP_SUFFIX = ".tmp"

  fun tmpFor(target: File) = File(target.parentFile, target.name + TMP_SUFFIX)

  /**
   * Throws IOException when the target could not be replaced. The old target is then intact.
   *
   * The directory create and the rename use java.nio, not File.mkdirs and
   * File.renameTo, which return only a Boolean: a nio failure says why, for
   * example "Permission denied". Not android.system.Os, which would also
   * give the errno: it is a stub in the JVM unit tests.
   */
  fun writeAtomically(target: File, write: (FileOutputStream) -> Unit) {
    val parent = target.parentFile ?: throw IOException("no parent directory for ${target.path}")
    if (!parent.isDirectory) Files.createDirectories(parent.toPath())
    val tmp = tmpFor(target)
    try {
      FileOutputStream(tmp).use { out ->
        write(out)
        out.flush()
        out.fd.sync()
      }
      // ATOMIC_MOVE is rename(2): atomic, and it replaces the target.
      Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (error: Throwable) {
      tmp.delete()
      throw error
    }
    syncDirectory(parent)
  }

  fun writeText(target: File, text: String) =
    writeAtomically(target) { it.write(text.toByteArray(Charsets.UTF_8)) }

  /**
   * Makes a rename durable. This works on Linux (Android). Some file systems
   * do not allow it, so a failure is ignored: the rename itself is still atomic.
   */
  fun syncDirectory(dir: File) {
    runCatching {
      FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
  }
}
