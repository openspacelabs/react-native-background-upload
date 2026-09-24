package ai.openspace.backgroundupload

import java.io.IOException
import java.util.UUID

/** How a run ended. */
sealed class Settlement {
  abstract val url: String
  abstract val method: String

  /** [response] is null for a chunked completion. */
  data class Completed(
    val response: UploadResponse?,
    override val url: String,
    override val method: String,
  ) : Settlement()

  data class Failed(
    val errorKind: String,
    val message: String,
    val response: UploadResponse?,
    val partIndex: Int?,
    override val url: String,
    override val method: String,
  ) : Settlement()
}

/**
 * The entry was paused, cancelled, replaced, or forgotten under a running
 * worker. The worker stops without a transition; the module owns what
 * happened. Not a CancellationException: it must fail a chunked part's
 * scope so the sibling parts stop too.
 */
class NotOwnedException(id: String) : Exception("entry '$id' is no longer owned by this run")

/**
 * Every transition the network causes: running, one attempt, part accepted,
 * awaiting-auth, queued-with-backoff, a system stop, and the settle. Each is
 * a `compute` guarded by the run's [generation], so a cancel, pause, or
 * replace that landed first always wins.
 */
class WorkerOps(
  private val store: QueueStore,
  private val journal: EventJournal,
  private val settings: QueueSettingsStore,
  private val events: QueueEvents,
  private val scheduler: WorkScheduler,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  enum class ParkResult { PARKED, REISSUE, NOT_OWNED }

  /** Takes a queued entry. Null when there is nothing to run. */
  fun begin(id: String): QueueEntry? {
    val now = clock()
    var changed = false
    val entry = store.compute(id) { e ->
      if (e != null && !e.legacy && (e.state == EntryState.QUEUED || e.state == EntryState.RUNNING)) {
        changed = true
        EntryTransitions.toRunning(e, now)
      } else e
    }
    if (entry == null || entry.state != EntryState.RUNNING) return null
    if (changed) events.state(entry.toRow())
    return entry
  }

  /** The stored entry, while this run still owns it. */
  fun latest(id: String, generation: Int): QueueEntry {
    val e = store.load(id)
    if (!EntryTransitions.isOwnedRun(e, generation)) throw NotOwnedException(id)
    return e!!
  }

  /**
   * Write-ahead for one attempt: attempts + 1 and the X-Request-Id, persisted
   * before the request is sent. Clears a short backoff's nextAttemptAt, and
   * emits the row when it did. Returns the fresh entry, whose headers the
   * attempt uses.
   */
  fun recordAttempt(id: String, generation: Int, requestId: String): QueueEntry {
    val now = clock()
    var clearedBackoff = false
    val next = store.compute(id) { e ->
      if (EntryTransitions.isOwnedRun(e, generation)) {
        clearedBackoff = e!!.nextAttemptAt != null
        EntryTransitions.toAttempt(e, requestId, now)
      } else e
    }
    if (next == null || next.lastRequestId != requestId || !EntryTransitions.isOwnedRun(next, generation)) {
      throw NotOwnedException(id)
    }
    if (clearedBackoff) events.state(next.toRow())
    return next
  }

  /**
   * A short backoff the worker waits out in place: the row stays running
   * and carries [nextAttemptAt]. Best effort; a lost write only hides the
   * time. For a chunked entry, a sibling part's next attempt clears it.
   */
  fun backingOff(id: String, generation: Int, nextAttemptAt: Long) {
    val now = clock()
    var applied = false
    val next = store.update(id) { e ->
      if (EntryTransitions.isOwnedRun(e, generation)) {
        applied = true
        EntryTransitions.toBackingOff(e, nextAttemptAt, now)
      } else e
    }
    if (applied && next != null) events.state(next.toRow())
  }

  /** A chunked part the server accepted. Best effort, as v9: a lost flag only re-sends that part later. */
  fun markAccepted(id: String, generation: Int, index: Int): QueueEntry? =
    store.update(id) { e ->
      val parts = e.descriptor?.parts
      if (e.generation != generation || parts == null || index !in parts.indices) e
      else {
        val next = ChunkedParts.withAccepted(parts, index)
        e.copy(
          descriptor = e.descriptor.copy(parts = next),
          bytesSent = ChunkedParts.acceptedBytes(next),
          backoffStreak = 0,
        )
      }
    }

  /**
   * Journal, then transition, then emit. When a cancel or a replace landed
   * first, the record is an orphan: it is acked at once and nothing is
   * emitted. Returns whether this run's outcome stands.
   */
  fun settle(id: String, generation: Int, s: Settlement): Boolean {
    val e = store.load(id)
    if (!EntryTransitions.canSettle(e, generation)) return false
    e!!
    val now = clock()
    val completed = s is Settlement.Completed
    val state = if (completed) EntryState.COMPLETED else EntryState.ERROR
    val bytesSent = if (completed) e.totalBytes else e.bytesSent
    val failed = s as? Settlement.Failed
    val record = EventJournal.SettledRecord(
      eventId = UUID.randomUUID().toString(),
      id = e.id,
      key = e.key,
      varsJson = e.varsJson,
      at = now,
      attempts = e.attempts,
      requestId = e.lastRequestId,
      deliveries = if (events.canDeliver()) 1 else 0,
      state = state.wire,
      bytesSent = bytesSent,
      totalBytes = e.totalBytes,
      url = s.url,
      method = s.method,
      partIndex = failed?.partIndex,
      kind = if (completed) EventJournal.KIND_COMPLETED else EventJournal.KIND_ERROR,
      response = when (s) {
        is Settlement.Completed -> s.response?.let { EventJournal.Response.of(it) } ?: EventJournal.Response.NONE
        is Settlement.Failed -> s.response?.let { EventJournal.Response.of(it) }
      },
      errorKind = failed?.errorKind,
      message = failed?.message,
      cancelReason = null,
      generation = generation,
    )
    // 1. The durable outcome. It never throws.
    journal.append(record)
    // JS can subscribe between the check above and the append, and its first
    // drain can miss this record. Count it as a live delivery then.
    val live = if (record.deliveries == 0 && events.canDeliver()) {
      journal.incrementDeliveries(record.eventId) ?: record
    } else record
    // 2. The transition, atomic against cancel().
    var applied = false
    val next = try {
      store.compute(id) { cur ->
        if (EntryTransitions.canSettle(cur, generation)) {
          applied = true
          EntryTransitions.toSettled(cur!!, state, record.eventId, bytesSent, now)
        } else cur
      }
    } catch (error: IOException) {
      // The record is durable; the boot sweep applies it to the entry.
      Diag.error("settle could not save '$id'; its ack or the boot sweep repairs it", error)
      if (live.deliveries > 0) events.settled(live)
      return true
    }
    if (!applied || next == null) {
      journal.ack(listOf(record.eventId))
      return false
    }
    // 3 and 4. Best effort.
    if (live.deliveries > 0) events.settled(live)
    events.state(next.toRow())
    return true
  }

  /**
   * Whether the stored entry holds newer headers than the ones an attempt
   * sent. [headerGeneration] is the entry's own value from [recordAttempt],
   * so it always belongs to the headers that went out. The settings value
   * is not used: updateHeaders() bumps it before it patches the entries.
   */
  fun hasNewerHeaders(id: String, generation: Int, headerGeneration: Int): Boolean =
    latest(id, generation).headerGeneration > headerGeneration

  /**
   * A 401/403. [headerGeneration] is the entry's value from [recordAttempt].
   * When the entry got newer headers since, the attempt re-issues at once
   * instead of parking. The check is inside the store lock, and
   * updateHeaders() patches entries inside it too, so it either patched
   * this entry first (REISSUE) or finds it parked and requeues it.
   */
  fun park(id: String, generation: Int, headerGeneration: Int): ParkResult {
    val now = clock()
    var result = ParkResult.NOT_OWNED
    val next = store.compute(id) { e ->
      when {
        !EntryTransitions.isOwnedRun(e, generation) -> e
        e!!.headerGeneration > headerGeneration -> {
          result = ParkResult.REISSUE
          e
        }
        else -> {
          result = ParkResult.PARKED
          EntryTransitions.toParked(e, headerGeneration, now)
        }
      }
    }
    if (result == ParkResult.PARKED && next != null) {
      events.state(next.toRow())
      // A parked entry still expires on time.
      scheduler.scheduleWake(next, next.expiresAt, replace = true)
    }
    return result
  }

  /** A backoff longer than a worker waits: back to queued, woken at [nextAttemptAt]. */
  fun release(id: String, generation: Int, nextAttemptAt: Long, streak: Int): Boolean {
    val now = clock()
    var applied = false
    val next = store.compute(id) { e ->
      if (EntryTransitions.isOwnedRun(e, generation)) {
        applied = true
        EntryTransitions.toReleased(e!!, nextAttemptAt, streak, now)
      } else e
    }
    if (!applied || next == null) return false
    events.state(next.toRow())
    scheduler.scheduleWake(next, nextAttemptAt, replace = true)
    return true
  }

  /** A system stop. A paused or cancelled entry is the module's, so only a running one moves. Never journals. */
  fun stopped(id: String, generation: Int) {
    val now = clock()
    var applied = false
    val next = runCatching {
      store.compute(id) { e ->
        if (EntryTransitions.isOwnedRun(e, generation)) {
          applied = true
          EntryTransitions.toStopped(e!!, now)
        } else e
      }
    }.getOrNull()
    if (applied && next != null) events.state(next.toRow())
  }

  fun settings(): QueueSettings = settings.load()
}
