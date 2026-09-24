package ai.openspace.backgroundupload

import android.content.Context
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** The WorkManager class for a chunked entry. The run is [EntryWorker]'s. */
class ChunkedUploadWorker(context: Context, params: WorkerParameters) : EntryWorker(context, params)

/**
 * The parts of one chunked entry, at most [ChunkedEngine.WINDOW] at a time,
 * each part through the shared 4-request semaphore. One logical entry has
 * one progress stream (byte-weighted) and one outcome: completed only when
 * every part is accepted.
 *
 * Each part runs [EntryRun.attempt] until a verdict ends it.
 * Per part: accepted → persist the flag; auth → the whole entry parks (the
 * sibling parts stop); transient → a short backoff waits in the part while
 * the siblings go on, a long one releases the whole worker (accepted parts
 * are kept); terminal → the entry fails with that part's index.
 */
internal class ChunkedTransfer(private val run: EntryRun) {

  /** A terminal part failure. Not a CancellationException, so it stops the sibling parts. */
  private class PartFailed(val settlement: Settlement.Failed) : Exception(settlement.message)

  private val partSent = ConcurrentHashMap<Int, Long>()
  private val acceptedHere = ConcurrentHashMap.newKeySet<Int>()
  private val acceptedBytes = AtomicLong(0)
  private var total = 0L

  @Volatile
  private var lastAcceptedUrl: String? = null

  suspend fun run(start: QueueEntry): Settlement {
    val d0 = start.descriptor!!
    val parts = d0.parts!!
    lastAcceptedUrl = parts.lastOrNull { it.accepted }?.url
    val pending = ChunkedParts.pendingIndexes(parts)
    // A run over an all-accepted entry that has not settled yet.
    if (pending.isEmpty()) return Settlement.Completed(null, lastAcceptedUrl ?: d0.reportUrl, d0.method)
    val blob = run.store.bodyFile(start)
    if (blob == null || !blob.exists()) {
      return Settlement.Failed("file", "the chunked file is missing", null, null, d0.reportUrl, d0.method)
    }
    total = ChunkedParts.totalBytes(parts)
    acceptedBytes.set(ChunkedParts.acceptedBytes(parts))
    run.progressStarted(total, acceptedBytes.get())

    try {
      ChunkedEngine.run(pending) { index -> executePart(index, blob, start.backoffStreak) }
    } catch (failed: PartFailed) {
      return failed.settlement
    }
    // Every executor returned, and an executor returns only when its part was
    // accepted: the server's auto-publish condition.
    return Settlement.Completed(null, lastAcceptedUrl ?: d0.reportUrl, d0.method)
  }

  internal suspend fun executePart(index: Int, blob: File, initialStreak: Int) {
    var streak = initialStreak
    while (true) {
      if (index in acceptedHere) return
      val latest = run.ops.latest(run.entryId, run.generation)
      val stored = latest.descriptor!!.parts!![index]
      if (stored.accepted) return
      if (RetryClassifier.isExpired(run.host.now(), latest.expiresAt)) throw EntryRun.ExpiredException()

      // A range past EOF can never be sent. length() is 0 for a missing file;
      // that case falls through to the attempt, which classifies it as file.
      val blobLength = runCatching { blob.length() }.getOrDefault(0L)
      if (blobLength > 0L && stored.end > blobLength) {
        throw PartFailed(
          Settlement.Failed(
            "file",
            "part $index range [${stored.start}, ${stored.end}) exceeds the file size $blobLength",
            null, index, stored.url, latest.descriptor.method,
          ),
        )
      }
      run.waitForNetwork()

      val a = run.attempt(
        partIndex = index,
        body = { _, part -> rangeRequestBody(blob, part!!.start, part.end) },
        onProgress = { sent -> onPartProgress(index, sent) },
        fileExists = { blob.exists() },
      )
      when (val r = a.result) {
        is EntryRun.AttemptResult.Accepted -> {
          markAccepted(index, a.entry.descriptor!!.parts!![index])
          return
        }
        is EntryRun.AttemptResult.Auth -> {
          if (!r.reissue) throw EntryRun.ParkException(r.headerGeneration)
          streak = 0
        }
        EntryRun.AttemptResult.Transient -> {
          onPartProgress(index, 0L)
          streak++
          run.backoffOrRelease(a.policy, streak, a.entry.expiresAt)
        }
        is EntryRun.AttemptResult.Terminal -> {
          onPartProgress(index, 0L)
          val message = r.response?.let { "HTTP ${it.code} on part $index" } ?: r.message
          throw PartFailed(Settlement.Failed(r.errorKind, message, r.response, index, a.url, a.method))
        }
      }
    }
  }

  private fun markAccepted(index: Int, part: Part) {
    // Remembered here too, so a lost flag write does not re-send the part in this run.
    acceptedHere += index
    run.ops.markAccepted(run.entryId, run.generation, index)
    acceptedBytes.addAndGet(part.size)
    lastAcceptedUrl = part.url
    partSent.remove(index)
    report()
  }

  private fun onPartProgress(index: Int, sent: Long) {
    if (sent == 0L) partSent.remove(index) else partSent[index] = sent
    report()
  }

  private fun report() {
    val sent = (acceptedBytes.get() + partSent.values.sum()).coerceAtMost(total)
    run.reportProgress(sent, total)
  }
}
