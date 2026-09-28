package ai.openspace.backgroundupload

/**
 * What a run needs from the platform: time, the network, progress, the
 * notification, and live attempt events. [EntryWorker] is the real one. The
 * JVM tests pass a fake with a scripted [send] and a manual clock, so every
 * branch of [EntryRun] runs with no device.
 */
internal interface TransferHost {
  fun now(): Long

  suspend fun sleep(ms: Long)

  /** One request through the library-wide cap of 4. Throws on a transport failure. */
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

  /** Whether the system stopped this run at its time limit (JobScheduler, about 10 minutes). */
  fun stoppedByTimeout(): Boolean
}
