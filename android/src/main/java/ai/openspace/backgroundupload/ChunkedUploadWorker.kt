package ai.openspace.backgroundupload

import android.app.NotificationManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Executes one chunked upload from its durable [ChunkedManifest]. The input
 * data carries only the upload id. The manifest is the record: startUpload
 * persists it before this work is enqueued. Thus a worker rescheduled after
 * process death resumes from disk, with no JS involved.
 *
 * One logical upload has one event stream: byte-weighted aggregate progress,
 * and one terminal event. 'completed' is journaled only when every part is
 * accepted. Every other terminal keeps the manifest and the bytes, so a later
 * startUpload can resume. The bytes are deleted only when a 'completed' event
 * is ACKED (see UploaderModule.ackEvents).
 */
class ChunkedUploadWorker(private val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  companion object {
    /**
     * The key for the upload id in the worker's input data. It is a string
     * literal for the same reason as [UploadWorker.PARAMS_KEY]: WorkManager's
     * database persists it across builds, and it must survive R8 renames and
     * refactors.
     */
    const val ID_KEY = "chunkedUploadId"

    /** How often a starting worker re-checks [ChunkedWorkerGate] for its id. */
    private const val GATE_POLL_MS = 100L
  }

  private lateinit var uploadId: String
  private val store by lazy { ChunkedManifestStore.get(context) }
  private val config by lazy { NotificationConfig.load(context) }
  private val notificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  // The latest known manifest. Part executors re-read the stored copy before
  // every attempt (see latest()). Thus a reconciling startUpload's fresh
  // headers, and an extended expiresAt, reach a worker that already runs.
  @Volatile
  private var manifest: ChunkedManifest? = null

  @Volatile
  private var connectivity = Connectivity.Ok

  // In-flight bytes per part index, for byte-weighted aggregate progress.
  private val partSent = ConcurrentHashMap<Int, Long>()
  private val acceptedBytes = AtomicLong(0)

  private class ExpiredException : Exception("upload expired")

  private class SourceMissingException(path: String) :
    IOException("chunked source file missing: $path")

  private class PartRejectedException(val partIndex: Int, val response: UploadResponse) :
    Exception("part $partIndex rejected with HTTP ${response.code}")

  private class PartBeyondEofException(val partIndex: Int, message: String) : Exception(message)

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    uploadId = inputData.getString(ID_KEY) ?: throw Throwable("No upload id")

    // Acquire the per-id execution gate BEFORE the first manifest read. A
    // cancel-then-start can start this worker while the cancelled one still
    // winds down, and two PUTs of one partNum are unsafe. Also, the manifest
    // read occurs only after the gate is held. That is what makes the module's
    // recreate check race-free (see ChunkedWorkerGate and
    // ChunkedManifestStore.compute).
    try {
      while (!ChunkedWorkerGate.tryAcquire(uploadId, this@ChunkedUploadWorker)) {
        delay(GATE_POLL_MS)
      }
    } catch (error: CancellationException) {
      // Cancelled while waiting. A user cancel still owes its terminal event.
      checkAndHandleCancellation()
      throw error
    }
    try {
      runUpload()
    } finally {
      ChunkedWorkerGate.release(uploadId, this@ChunkedUploadWorker)
    }
  }

  private suspend fun runUpload(): Result {
    val initial = store.load(uploadId)
    when (ChunkedEngine.startAction(initial)) {
      // The upload was completed-and-acknowledged, or it was removed, while
      // this run sat in the queue. Both are legitimate and already settled.
      // Exit in silence. A terminal journaled here would be a spurious error
      // for an upload that nobody owns.
      ChunkedEngine.StartAction.NO_MANIFEST -> return Result.success()
      // A trailing resume of a finished-but-unacknowledged upload. Re-report
      // the journaled completion. Skip the foreground service and the engine.
      ChunkedEngine.StartAction.ALREADY_COMPLETE -> {
        manifest = initial
        journalCompleted(freshCompletion = false)
        return Result.success()
      }
      ChunkedEngine.StartAction.RUN -> Unit
    }
    checkNotNull(initial) // RUN implies a manifest
    manifest = initial
    acceptedBytes.set(initial.acceptedBytes)
    UploadProgress.add(uploadId, initial.totalBytes)
    UploadProgress.set(uploadId, initial.acceptedBytes)

    // Initialization. A failure here is terminal: journaled, never retried.
    // The EXCEPTION is a refused foreground start, which the transfer
    // survives.
    try {
      if (initial.showsNotification) {
        ensureNotificationChannel(notificationManager, config)
        setForeground(getForegroundInfo())
      }
    } catch (error: Throwable) {
      if (!isForegroundStartDenied(error)) {
        if (!checkAndHandleCancellation()) {
          UploadProgress.remove(uploadId)
          handleFailure(error)
        }
        return terminalErrorResult()
      }
      // The app is in the background, and API 31+ refused the foreground
      // start. This is the usual state for a WorkManager relaunch (a reboot,
      // or a quota resume). The upload runs correctly without foreground
      // priority. A failure here would brick every headless resume.
    }

    return try {
      ChunkedEngine.run(initial.pendingIndexes()) { index -> executePart(index) }
      // Every executor returned. An executor returns only when its part was
      // accepted. That is exactly the server's auto-publish condition.
      UploadProgress.complete(uploadId)
      journalCompleted(freshCompletion = true)
      Result.success()
    } catch (error: Throwable) {
      if (checkAndHandleCancellation()) throw error
      UploadProgress.remove(uploadId)
      handleFailure(error)
      terminalErrorResult()
    }
  }

  /**
   * Uploads one part until it is accepted, or throws. Terminal conditions
   * (expiry, a missing source, or a non-accepted response out of retries)
   * propagate and cancel the sibling parts. Everything transient retries here,
   * bounded only by expiresAt.
   */
  private suspend fun executePart(index: Int) {
    var rejections = 0
    var transientAttempts = 0
    while (true) {
      val current = latest()
      val part = current.parts[index]
      if (part.accepted) return
      if (current.isExpired(System.currentTimeMillis())) throw ExpiredException()

      // A range past the blob's EOF can never transmit. The read would fail
      // on every attempt until expiry. Thus it is a terminal 'file' error
      // immediately (iOS classifies it the same way). length() is 0 for a
      // missing file. That case falls through to the transfer, which
      // classifies it as source-missing. The failed-probe-reads-as-network
      // default stays intact.
      val blobLength = runCatching { File(current.sourcePath).length() }.getOrDefault(0L)
      if (blobLength > 0L && part.end > blobLength) throw PartBeyondEofException(
        index,
        "part $index range [${part.start}, ${part.end}) exceeds source size $blobLength",
      )

      if (!validateAndReportConnectivity(current.wifiOnly)) {
        delay(ChunkedEngine.CONNECTIVITY_POLL_MS)
        continue
      }

      val response = try {
        transferSemaphore.withPermit {
          okhttpUploadPart(uploadHttpClient, part, File(current.sourcePath)) { sent ->
            onPartProgress(index, sent)
          }
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: IOException) {
        onPartProgress(index, 0L)
        // The default is fileExists=true. Thus a failed probe reads as
        // network, not file.
        val fileExists = runCatching { File(current.sourcePath).exists() }.getOrDefault(true)
        if (!fileExists) throw SourceMissingException(current.sourcePath)
        transientAttempts++
        delay(ChunkedEngine.backoffMs(transientAttempts))
        continue
      }

      if (UploadOutcome.isAccepted(response.code, response.body, current.accept)) {
        markAccepted(index)
        return
      }
      onPartProgress(index, 0L)
      if (ChunkedEngine.isTransientHttp(response.code)) {
        transientAttempts++
        delay(ChunkedEngine.backoffMs(transientAttempts))
        continue
      }
      rejections++
      if (rejections > ChunkedEngine.PART_HTTP_RETRIES) throw PartRejectedException(index, response)
      delay(ChunkedEngine.backoffMs(rejections))
    }
  }

  // The stored copy is the truth: a reconcile can have replaced the headers
  // or expiresAt. Fall back to the in-memory copy only when the read fails.
  private fun latest(): ChunkedManifest =
    store.load(uploadId)?.also { manifest = it } ?: manifest!!

  private fun markAccepted(index: Int) {
    // Persist the flag first, atomically against concurrent flips and
    // reconciles. This is best-effort. A lost flag only re-sends this part on
    // a later resume, and the consumer's accept rules absorb that ('already
    // completed'). That is better than a failure of an upload that the server
    // accepted.
    manifest = store.update(uploadId) { it.withPartAccepted(index) }
      ?: manifest?.withPartAccepted(index)
    manifest?.parts?.get(index)?.let { acceptedBytes.addAndGet(it.size) }
    partSent.remove(index)
    reportProgress()
  }

  private fun onPartProgress(index: Int, sent: Long) {
    if (sent == 0L) partSent.remove(index) else partSent[index] = sent
    reportProgress()
  }

  private fun reportProgress() {
    val total = manifest?.totalBytes ?: return
    val sent = (acceptedBytes.get() + partSent.values.sum()).coerceAtMost(total)
    UploadProgress.set(uploadId, sent)
    EventReporter.progress(uploadId, sent, total)
    updateNotification()
  }

  // A resume of a finished-but-unacknowledged upload (all parts accepted,
  // 'completed' journaled, and the consumer re-called startUpload before the
  // ack) must not mint a second terminal event. Re-emit the journaled one.
  // Then a live listener still hears it, with the eventId that the consumer
  // will acknowledge. And a trailing run whose completion was already ACKED
  // reports nothing at all. See ChunkedEngine.CompletionReport.
  private fun journalCompleted(freshCompletion: Boolean) {
    val report = ChunkedEngine.completionReport(
      EventJournal.get(context).unacknowledged(),
      uploadId,
      freshCompletion,
    )
    when (report) {
      is ChunkedEngine.CompletionReport.ReEmit -> EventReporter.emit(report.entry)
      // No response fields, because no single response represents N accepted
      // parts.
      ChunkedEngine.CompletionReport.Mint -> journalAndEmit(
        EventJournal.Entry(
          eventId = UUID.randomUUID().toString(),
          uploadId = uploadId,
          type = "completed",
          timestamp = System.currentTimeMillis(),
        ),
      )
      ChunkedEngine.CompletionReport.None -> Unit
    }
  }

  private fun handleFailure(error: Throwable) {
    val entry = when (error) {
      is ExpiredException -> errorEntry(
        error = "upload expired before every part was accepted",
        errorKind = "expired",
      )
      is PartRejectedException -> {
        val (body, truncated) = EventJournal.capBody(error.response.body)
        errorEntry(
          error = "HTTP ${error.response.code} on part ${error.partIndex}",
          errorKind = "http",
          partIndex = error.partIndex,
          responseCode = error.response.code,
          responseBody = body,
          responseBodyTruncated = truncated,
          responseHeaders = error.response.headers,
        )
      }
      is SourceMissingException -> errorEntry(error = error.message!!, errorKind = "file")
      is PartBeyondEofException -> errorEntry(
        error = error.message!!,
        errorKind = "file",
        partIndex = error.partIndex,
      )
      else -> {
        val fileExists = manifest?.let { m ->
          runCatching { File(m.sourcePath).exists() }.getOrDefault(true)
        } ?: true
        errorEntry(
          error = error.message ?: "Unknown exception",
          errorKind = UploadOutcome.errorKind(error, fileExists),
        )
      }
    }
    journalAndEmit(entry)
  }

  private fun errorEntry(
    error: String,
    errorKind: String,
    partIndex: Int? = null,
    responseCode: Int? = null,
    responseBody: String? = null,
    responseBodyTruncated: Boolean = false,
    responseHeaders: Map<String, String>? = null,
  ) = EventJournal.Entry(
    eventId = UUID.randomUUID().toString(),
    uploadId = uploadId,
    type = "error",
    timestamp = System.currentTimeMillis(),
    error = error,
    errorKind = errorKind,
    partIndex = partIndex,
    responseCode = responseCode,
    responseBody = responseBody,
    responseBodyTruncated = responseBodyTruncated,
    responseHeaders = responseHeaders,
  )

  // The semantics are the same as UploadWorker's. Only a user cancel is
  // terminal (journaled, cancelReason 'user'). A system stop emits nothing,
  // because WorkManager will re-run this upload, and the manifest resumes it.
  // The manifest and the bytes are kept in both cases. stopUpload's contract
  // is that the next startUpload resumes.
  private fun checkAndHandleCancellation(): Boolean {
    if (!isStopped) return false

    UploadProgress.remove(uploadId)

    if (!UserCancellations.consume(uploadId)) return true

    journalAndEmit(
      EventJournal.Entry(
        eventId = UUID.randomUUID().toString(),
        uploadId = uploadId,
        type = "cancelled",
        timestamp = System.currentTimeMillis(),
        cancelReason = "user",
      ),
    )
    return true
  }

  private fun journalAndEmit(entry: EventJournal.Entry) {
    // A terminal event for an id whose manifest is gone would report an
    // upload that nobody owns any more. Either removeUpload deleted it mid-run
    // (its work cancel races the in-flight PUT's IOException), or a completed
    // ack released it. Suppress the event; iOS's removedIds has the same idea.
    // A user cancel keeps its manifest, so real 'cancelled' events pass
    // through.
    if (!store.contains(uploadId)) return
    EventReporter.journalAndEmit(context, entry)
  }

  private fun validateAndReportConnectivity(wifiOnly: Boolean): Boolean {
    connectivity = validateConnectivity(context, wifiOnly)
    updateNotification()
    return connectivity == Connectivity.Ok
  }

  private fun updateNotification() {
    if (manifest?.showsNotification != true) return
    notificationManager.notify(
      config.systemNotificationId,
      buildUploadNotification(context, config, connectivity),
    )
  }

  override suspend fun getForegroundInfo(): ForegroundInfo =
    uploadForegroundInfo(config, buildUploadNotification(context, config, connectivity))
}

/**
 * The Result that a chunked run returns after it journals a terminal error:
 * SUCCESS, deliberately. The journal and the manifest are the upload's outcome
 * record, never the WorkManager row state. A row that finishes FAILED destroys
 * every appended dependent: WorkManager marks the dependents of a failed
 * prerequisite FAILED without a run. Thus a resume enqueued during the failing
 * run's teardown window would silently never run (see the APPEND_OR_REPLACE
 * note in UploaderModule.enqueueChunkedUpload). getAllUploads derives a
 * chunked upload's state from its manifest (allAccepted), not from row states.
 */
internal fun terminalErrorResult(): ListenableWorker.Result = ListenableWorker.Result.success()
