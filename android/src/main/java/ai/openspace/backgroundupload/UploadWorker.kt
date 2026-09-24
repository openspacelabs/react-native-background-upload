package ai.openspace.backgroundupload

import android.content.Context
import androidx.work.WorkerParameters
import okhttp3.RequestBody
import java.io.File

/** The WorkManager class for a single-body entry. The run is [EntryWorker]'s. */
class UploadWorker(context: Context, params: WorkerParameters) : EntryWorker(context, params)

/**
 * One request with one body: none, JSON, multipart, or a copied file. One
 * attempt at a time ([EntryRun.attempt]), until a verdict ends it:
 * accepted → Completed; auth → re-issue (newer headers) or park;
 * transient → back off (short: here; long: release); terminal → Failed.
 */
internal class SimpleTransfer(private val run: EntryRun) {

  suspend fun run(start: QueueEntry): Settlement {
    val d0 = start.descriptor!!
    val file = run.store.bodyFile(start)
    // The payload probe: a staged body that is gone can never be sent.
    if (file != null && !file.exists()) {
      return Settlement.Failed("file", "the staged request body is missing", null, null, d0.reportUrl, d0.method)
    }
    val total = start.body?.totalBytes ?: 0L
    run.progressStarted(total, 0L)
    var streak = start.backoffStreak

    while (true) {
      val latest = run.ops.latest(run.entryId, run.generation)
      if (RetryClassifier.isExpired(run.host.now(), latest.expiresAt)) throw EntryRun.ExpiredException()
      run.waitForNetwork()

      val a = run.attempt(
        partIndex = null,
        body = { d, _ -> requestBody(file, d.method) },
        onProgress = { sent -> run.reportProgress(sent, total) },
        fileExists = { file == null || file.exists() },
      )
      when (val r = a.result) {
        is EntryRun.AttemptResult.Accepted -> return Settlement.Completed(r.response, a.url, a.method)
        is EntryRun.AttemptResult.Auth -> {
          if (!r.reissue) throw EntryRun.ParkException(r.headerGeneration)
          streak = 0 // updateHeaders() landed while this attempt was in flight: re-issue now.
        }
        EntryRun.AttemptResult.Transient -> {
          run.reportProgress(0L, total)
          streak++
          run.backoffOrRelease(a.policy, streak, a.entry.expiresAt)
        }
        is EntryRun.AttemptResult.Terminal ->
          return Settlement.Failed(r.errorKind, r.message, r.response, null, a.url, a.method, bytesSent = run.liveBytes)
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
