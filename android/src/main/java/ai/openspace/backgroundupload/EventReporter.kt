package ai.openspace.backgroundupload

import android.content.Context
import com.facebook.react.bridge.Arguments

// Sends live events to JS through the module's codegen event emitters. Terminal
// outcomes are journaled before they reach here, so when JS is absent (headless
// worker, mid-reload) dropping the live event costs nothing — the consumer picks
// it up from getUnacknowledgedEvents instead.
object EventReporter {

  // Journal first, then emit. The journal is the durable record; it survives
  // when JS is dead. The live emit is best-effort. The two carry the identical
  // payload. Thus a consumer can acknowledge a live event by its eventId.
  fun journalAndEmit(context: Context, entry: EventJournal.Entry) {
    EventJournal.get(context).append(entry)
    emit(entry)
  }

  // Emit a terminal event from its journal entry, so the live event carries the
  // exact same payload (incl. eventId) as the journaled copy — letting a consumer
  // ackEvents([eventId]) right after handling a live event, and keeping iOS/Android
  // event shapes identical.
  fun emit(entry: EventJournal.Entry) {
    val module = UploaderModule.instance ?: return
    val params = entry.toWritableMap()
    when (entry.type) {
      "completed" -> module.emitCompletedEvent(params)
      "cancelled" -> module.emitCancelledEvent(params)
      else -> module.emitErrorEvent(params)
    }
  }

  fun progress(uploadId: String, bytesSentTotal: Long, contentLength: Long) {
    val module = UploaderModule.instance ?: return
    module.emitProgressEvent(Arguments.createMap().apply {
      putString("id", uploadId)
      // Guard against a zero-byte file (contentLength == 0) producing NaN.
      val pct = if (contentLength <= 0) 0.0 else bytesSentTotal.toDouble() * 100 / contentLength
      putDouble("progress", pct) // 0-100
    })
  }

  fun notification() {
    val module = UploaderModule.instance ?: return
    module.emitNotificationEvent(Arguments.createMap())
  }
}
