package ai.openspace.backgroundupload

import android.content.Context
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID
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
 * Per part: accepted → persist the flag; auth → the whole entry parks (the
 * sibling parts stop); transient → a short backoff waits in the part while
 * the siblings go on, a long one releases the whole worker (accepted parts
 * are kept); terminal → the entry fails with that part's index.
 */
internal class ChunkedTransfer(private val host: EntryWorker) {

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
    val blob = host.bodyFile(start)
    if (blob == null || !blob.exists()) {
      return Settlement.Failed("file", "the chunked file is missing", null, null, d0.reportUrl, d0.method)
    }
    total = ChunkedParts.totalBytes(parts)
    acceptedBytes.set(ChunkedParts.acceptedBytes(parts))
    UploadProgress.add(host.entryId, total)
    UploadProgress.set(host.entryId, acceptedBytes.get())

    try {
      ChunkedEngine.run(pending) { index -> executePart(index, blob, start.backoffStreak) }
    } catch (failed: PartFailed) {
      return failed.settlement
    }
    // Every executor returned, and an executor returns only when its part was
    // accepted: the server's auto-publish condition.
    return Settlement.Completed(null, lastAcceptedUrl ?: d0.reportUrl, d0.method)
  }

  private suspend fun executePart(index: Int, blob: File, initialStreak: Int) {
    var streak = initialStreak
    while (true) {
      if (index in acceptedHere) return
      val latest = host.ops.latest(host.entryId, host.generation)
      val stored = latest.descriptor!!.parts!![index]
      if (stored.accepted) return
      if (RetryClassifier.isExpired(host.now(), latest.expiresAt)) throw EntryWorker.ExpiredException()

      // A range past EOF can never be sent. length() is 0 for a missing file;
      // that case falls through to the transfer, which classifies it as file.
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
      host.waitForNetwork()

      val requestId = UUID.randomUUID().toString()
      val entry = host.ops.recordAttempt(host.entryId, host.generation, requestId)
      // The generation of the headers this attempt sends: both come from the same entry.
      val headerGeneration = entry.headerGeneration
      val d = entry.descriptor!!
      val part = d.parts!![index]
      val policy = host.policy(entry)

      val response = try {
        transferSemaphore.withPermit {
          okhttpSend(
            uploadHttpClient,
            TransferRequest(
              part.url, d.method, host.headersFor(d, part, requestId),
              rangeRequestBody(blob, part.start, part.end),
            ),
          ) { sent -> onPartProgress(index, sent) }
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: Throwable) {
        onPartProgress(index, 0L)
        val fileExists = runCatching { blob.exists() }.getOrDefault(true)
        val message = error.message ?: error.javaClass.simpleName
        EventReporter.attempt(
          AttemptEvent.ofFailure(
            entry, requestId, part.url, index, RetryClassifier.failureKind(error, fileExists), message, host.now(),
          ),
        )
        when (val verdict = RetryClassifier.classifyFailure(error, fileExists)) {
          is RetryClassifier.Verdict.Terminal -> throw PartFailed(
            Settlement.Failed(verdict.errorKind, verdict.message, null, index, part.url, d.method),
          )
          else -> {
            streak++
            host.backoffOrRelease(policy, streak, entry.expiresAt)
            continue
          }
        }
      }

      val verdict = RetryClassifier.classifyResponse(response.code, response.body, d.accept, policy.exempt)
      EventReporter.attempt(
        AttemptEvent.ofResponse(
          entry, requestId, part.url, index, response, verdict == RetryClassifier.Verdict.Accepted, host.now(),
        ),
      )
      when (verdict) {
        RetryClassifier.Verdict.Accepted -> {
          markAccepted(index, part)
          return
        }
        RetryClassifier.Verdict.Auth -> {
          if (host.ops.hasNewerHeaders(host.entryId, host.generation, headerGeneration)) {
            streak = 0
            continue
          }
          throw EntryWorker.ParkException(headerGeneration)
        }
        RetryClassifier.Verdict.Transient -> {
          onPartProgress(index, 0L)
          streak++
          host.backoffOrRelease(policy, streak, entry.expiresAt)
        }
        is RetryClassifier.Verdict.Terminal -> throw PartFailed(
          Settlement.Failed("http", "HTTP ${response.code} on part $index", response, index, part.url, d.method),
        )
      }
    }
  }

  private fun markAccepted(index: Int, part: Part) {
    // Remembered here too, so a lost flag write does not re-send the part in this run.
    acceptedHere += index
    host.ops.markAccepted(host.entryId, host.generation, index)
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
    host.reportProgress(sent, total)
  }
}
