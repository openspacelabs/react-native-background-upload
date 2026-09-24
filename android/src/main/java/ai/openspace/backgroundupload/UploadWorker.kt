package ai.openspace.backgroundupload

import android.content.Context
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withPermit
import okhttp3.RequestBody
import java.io.File
import java.util.UUID

/** The WorkManager class for a single-body entry. The run is [EntryWorker]'s. */
class UploadWorker(context: Context, params: WorkerParameters) : EntryWorker(context, params)

/**
 * One request with one body: none, JSON, multipart, or a copied file. One
 * attempt at a time, until a verdict ends it:
 * accepted → Completed; auth → re-issue (newer headers) or park;
 * transient → back off (short: here; long: release); terminal → Failed.
 */
internal class SimpleTransfer(private val host: EntryWorker) {

  suspend fun run(start: QueueEntry): Settlement {
    val d0 = start.descriptor!!
    val file = host.bodyFile(start)
    // The payload probe: a staged body that is gone can never be sent.
    if (file != null && !file.exists()) {
      return Settlement.Failed("file", "the staged request body is missing", null, null, d0.reportUrl, d0.method)
    }
    val total = start.body?.totalBytes ?: 0L
    UploadProgress.add(host.entryId, total)
    var streak = start.backoffStreak

    while (true) {
      val latest = host.ops.latest(host.entryId, host.generation)
      if (RetryClassifier.isExpired(host.now(), latest.expiresAt)) throw EntryWorker.ExpiredException()
      host.waitForNetwork()

      val requestId = UUID.randomUUID().toString()
      val entry = host.ops.recordAttempt(host.entryId, host.generation, requestId)
      // The generation of the headers this attempt sends: both come from the same entry.
      val headerGeneration = entry.headerGeneration
      val d = entry.descriptor!!
      val url = d.url!!
      val policy = host.policy(entry)

      val response = try {
        transferSemaphore.withPermit {
          okhttpSend(
            uploadHttpClient,
            TransferRequest(url, d.method, host.headersFor(d, null, requestId), requestBody(file, d.method)),
          ) { sent -> host.reportProgress(sent, total) }
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: Throwable) {
        host.reportProgress(0L, total)
        val fileExists = file == null || runCatching { file.exists() }.getOrDefault(true)
        val message = error.message ?: error.javaClass.simpleName
        EventReporter.attempt(
          AttemptEvent.ofFailure(
            entry, requestId, url, null, RetryClassifier.failureKind(error, fileExists), message, host.now(),
          ),
        )
        when (val verdict = RetryClassifier.classifyFailure(error, fileExists)) {
          is RetryClassifier.Verdict.Terminal ->
            return Settlement.Failed(verdict.errorKind, verdict.message, null, null, url, d.method)
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
          entry, requestId, url, null, response, verdict == RetryClassifier.Verdict.Accepted, host.now(),
        ),
      )
      when (verdict) {
        RetryClassifier.Verdict.Accepted -> return Settlement.Completed(response, url, d.method)
        RetryClassifier.Verdict.Auth -> {
          // updateHeaders() landed while this attempt was in flight: re-issue now.
          if (host.ops.hasNewerHeaders(host.entryId, host.generation, headerGeneration)) {
            streak = 0
            continue
          }
          throw EntryWorker.ParkException(headerGeneration)
        }
        RetryClassifier.Verdict.Transient -> {
          host.reportProgress(0L, total)
          streak++
          host.backoffOrRelease(policy, streak, entry.expiresAt)
        }
        is RetryClassifier.Verdict.Terminal ->
          return Settlement.Failed("http", verdict.message, response, null, url, d.method)
      }
    }
  }

  // OkHttp needs a body for POST, PUT, and PATCH, and forbids one for GET.
  private fun requestBody(file: File?, method: String): RequestBody? = when {
    file != null -> fileBody(file)
    method == "GET" || method == "DELETE" -> null
    else -> emptyBody()
  }
}
