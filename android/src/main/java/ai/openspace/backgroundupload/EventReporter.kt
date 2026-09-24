package ai.openspace.backgroundupload

import com.facebook.react.bridge.Arguments

/** The events that queue transitions produce. [EventReporter] sends them to JS; tests record them. */
interface QueueEvents {
  fun state(row: RequestRow)

  /**
   * The caller journaled [record] first, and emits it only when its
   * deliveries is above 0: [EventJournal] decides that under its lock.
   * [listener] is [EventJournal.listener]: the module whose JS drained, so
   * the delivery the journal counted goes to that JS.
   */
  fun settled(record: EventJournal.SettledRecord, listener: Any)
}

/**
 * Sends live events to JS through the module's codegen emitters. With no
 * module (a headless worker, a reload) an event is dropped. Settled outcomes
 * are journaled before they reach here, so a dropped one is replayed from
 * the journal.
 */
object EventReporter : QueueEvents {

  private val throttle = ProgressThrottle { id, sent, total ->
    val module = UploaderModule.instance ?: return@ProgressThrottle
    module.emitProgress(Arguments.createMap().apply {
      putString("id", id)
      putDouble("bytesSent", sent.toDouble())
      putDouble("totalBytes", total.toDouble())
    })
  }

  override fun state(row: RequestRow) {
    val module = UploaderModule.instance ?: return
    module.emitState(JsonBridge.toWritableMap(row.toMap()))
  }

  // Not UploaderModule.instance: a reload sets that before the new JS
  // subscribes, and the journal counted this delivery for the listener.
  override fun settled(record: EventJournal.SettledRecord, listener: Any) {
    val module = listener as? UploaderModule ?: return
    module.emitSettled(record.toWritableMap())
  }

  /** Moves the row's bytesSent in memory and emits through the throttle. */
  fun progress(id: String, sent: Long, total: Long) {
    RequestIndex.shared.setBytes(id, sent)
    throttle.offer(id, sent, total, isForeground())
  }

  fun flushProgress(id: String) = throttle.flush(id)

  fun dropProgress(id: String) = throttle.drop(id)

  fun attempt(event: AttemptEvent) {
    val module = UploaderModule.instance ?: return
    module.emitAttempt(event.toWritableMap())
  }

  fun notification() {
    val module = UploaderModule.instance ?: return
    module.emitNotification(Arguments.createMap())
  }

  private fun isForeground(): Boolean =
    runCatching { UploaderModule.instance?.isForeground() == true }.getOrDefault(false)
}
