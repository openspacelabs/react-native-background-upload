package ai.openspace.backgroundupload

import androidx.work.WorkInfo

/**
 * What a run needs from the platform: time, the network, progress, the
 * notification, and live attempt events. [EntryWorker] is the real one. The
 * JVM tests pass a fake with a scripted [send] and a manual clock, so every
 * branch of [EntryRun] runs with no device.
 */
internal interface TransferHost {
  fun now(): Long

  suspend fun sleep(ms: Long)

  /**
   * Runs [block] while it holds one slot of the library-wide cap of 4. The
   * wait for a free slot happens here, before [block] starts.
   */
  suspend fun <T> withSlot(highPriority: Boolean, block: suspend () -> T): T

  /** One request. Call it inside [withSlot]. Throws on a transport failure. */
  suspend fun send(request: TransferRequest, onProgress: (Long) -> Unit): UploadResponse

  /** The network state for the queue's wifi-only setting. The notification shows it. */
  fun connectivity(wifiOnly: Boolean): Connectivity

  /** Foreground mode for an entry that shows the notification. Never throws. */
  suspend fun foreground(entry: QueueEntry)

  fun progressStarted(id: String, total: Long, sent: Long)

  fun progress(id: String, sent: Long, total: Long)

  /** The trailing progress event, then the progress state is dropped. */
  fun progressEnded(id: String, completed: Boolean)

  fun attempt(event: AttemptEvent)

  /** Why the system stopped this run. Read it only after the run was cancelled. */
  fun stopKind(): StopKind
}

/**
 * Why the system stopped a run. [logged] kinds say why a queue did not move,
 * so the run writes one persisted warning that names the kind.
 */
internal enum class StopKind(val logged: Boolean) {
  /** The job's time limit, or the foreground service's time limit. */
  TIME_LIMIT(false),

  /** A WorkManager network constraint stopped being met. */
  NETWORK(false),

  QUOTA(true),
  APP_STANDBY(true),
  BACKGROUND_RESTRICTION(true),
  DEVICE_STATE(true),
  USER(true),

  /** Every other reason, and every stop on API 29 and 30, which cannot read the reason. */
  OTHER(false);

  companion object {
    /**
     * Maps a WorkManager stop reason (WorkInfo.STOP_REASON_*). A cancel by the
     * app, a preempt, the other constraints, NOT_STOPPED, UNKNOWN and any
     * reason a later Android adds are OTHER.
     */
    fun of(reason: Int): StopKind = when (reason) {
      WorkInfo.STOP_REASON_TIMEOUT, WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> TIME_LIMIT
      WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> NETWORK
      WorkInfo.STOP_REASON_QUOTA -> QUOTA
      WorkInfo.STOP_REASON_APP_STANDBY -> APP_STANDBY
      WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> BACKGROUND_RESTRICTION
      WorkInfo.STOP_REASON_DEVICE_STATE -> DEVICE_STATE
      WorkInfo.STOP_REASON_USER -> USER
      else -> OTHER
    }
  }
}
