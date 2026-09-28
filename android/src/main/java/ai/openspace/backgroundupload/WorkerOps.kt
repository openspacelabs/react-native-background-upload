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

  /** [bytesSent] is the live bytes of a simple entry's last attempt; null keeps the stored value. */
  data class Failed(
    val errorKind: String,
    val message: String,
    val response: UploadResponse?,
    val partIndex: Int?,
    override val url: String,
    override val method: String,
    val bytesSent: Long? = null,
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

  /**
   * Takes a queued entry. Null when there is nothing to run.
   *
   * A journal record of the entry's own generation means a settle was
   * journaled and its store write was lost (a process death between the
   * two, or a failed save). WorkManager can run the entry again before any
   * boot sweep. Then begin applies the record, as the sweep does, and does
   * not send the request again.
   */
  fun begin(id: String): QueueEntry? {
    val now = clock()
    var row: QueueEntry? = null
    var taken: QueueEntry? = null
    store.locked {
      val e = store.load(id) ?: return@locked
      if (e.legacy || (e.state != EntryState.QUEUED && e.state != EntryState.RUNNING)) return@locked
      val journaled = EntryTransitions.journaledSettle(e, journal.forEntry(id), now)
      if (journaled != null) {
        store.save(journaled.entry)
        journal.ack(journaled.extraEventIds)
        row = journaled.entry
        return@locked
      }
      val next = EntryTransitions.toRunning(e, now)
      store.save(next)
      row = next
      taken = next
    }
    row?.let { events.state(it.toRow()) }
    return taken
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
   * Journal, then transition, then emit, all under the store lock, so a
   * cancel, pause, or replace lands either before (this run's outcome is
   * dropped) or after. Returns whether this run's outcome stands.
   *
   * A failed journal write holds the record in memory ([EventJournal.appendOrHold]):
   * the request already ran, and a retry would send it twice. A failed
   * store write leaves the record for the ack, the next [begin], or the
   * boot sweep to apply.
   *
   * A record of this generation already in the journal (a cancel whose
   * entry save failed) wins: it is applied, as [begin] does, and this run's
   * outcome is dropped. One life has one outcome.
   */
  fun settle(id: String, generation: Int, s: Settlement): Boolean {
    val now = clock()
    val completed = s is Settlement.Completed
    val failed = s as? Settlement.Failed
    var delivered: EventJournal.SettledRecord? = null
    var settled: QueueEntry? = null
    store.locked {
      val e = store.load(id)
      if (!EntryTransitions.canSettle(e, generation, completed)) return@locked
      e!!
      val journaled = EntryTransitions.journaledSettle(e, journal.forEntry(id), now)
      if (journaled != null) {
        try {
          store.save(journaled.entry)
          journal.ack(journaled.extraEventIds)
          settled = journaled.entry
        } catch (error: IOException) {
          Diag.error("settle could not apply the journaled outcome of '$id'; its ack or the boot sweep applies it", error)
        }
        return@locked
      }
      val state = if (completed) EntryState.COMPLETED else EntryState.ERROR
      // A failed simple entry keeps the live bytes of its last attempt; a
      // chunked one keeps its accepted bytes (the stored value).
      val bytesSent = if (completed) e.totalBytes else failed?.bytesSent ?: e.bytesSent
      val record = EventJournal.SettledRecord(
        eventId = UUID.randomUUID().toString(),
        id = e.id,
        key = e.key,
        varsJson = e.varsJson,
        at = now,
        attempts = e.attempts,
        requestId = e.lastRequestId,
        deliveries = 0, // the journal sets it
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
      delivered = journal.appendOrHold(record) { store.referencedEventIds() + record.eventId }
      // 2. The transition.
      val next = EntryTransitions.toSettled(e, state, record.eventId, bytesSent, now)
      try {
        store.save(next)
        settled = next
      } catch (error: IOException) {
        Diag.error("settle could not save '$id'; its ack, the next run, or the boot sweep applies the record", error)
      }
    }
    val record = delivered
    if (record == null) {
      settled?.let { events.state(it.toRow()) }
      return false
    }
    // 3 and 4. Best effort.
    if (record.deliveries > 0) journal.listener()?.let { events.settled(record, it) }
    settled?.let { events.state(it.toRow()) }
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
