package ai.openspace.backgroundupload

/**
 * The state changes of one entry, as pure functions. [QueueController] and
 * [WorkerOps] apply them inside `QueueStore.compute`, so each one is atomic
 * against the other side.
 */
object EntryTransitions {

  /** A worker takes a queued entry. */
  fun toRunning(e: QueueEntry, now: Long) =
    e.copy(state = EntryState.RUNNING, nextAttemptAt = null, updatedAt = now)

  /** A 401/403 under the current header generation. No backoff. */
  fun toParked(e: QueueEntry, headerGeneration: Int, now: Long) = e.copy(
    state = EntryState.AWAITING_AUTH,
    parkedGeneration = headerGeneration,
    backoffStreak = 0,
    updatedAt = now,
  )

  /**
   * A short backoff that the worker waits out itself. The row stays running
   * and shows when the next attempt is due.
   */
  fun toBackingOff(e: QueueEntry, nextAttemptAt: Long, now: Long) =
    e.copy(nextAttemptAt = nextAttemptAt, updatedAt = now)

  /** One attempt starts: attempts + 1, its X-Request-Id, and no pending backoff. */
  fun toAttempt(e: QueueEntry, requestId: String, now: Long) = e.copy(
    attempts = e.attempts + 1,
    lastRequestId = requestId,
    nextAttemptAt = null,
    updatedAt = now,
  )

  /** A backoff too long to wait inside the worker. The streak is kept so the next wait keeps growing. */
  fun toReleased(e: QueueEntry, nextAttemptAt: Long, streak: Int, now: Long) = e.copy(
    state = EntryState.QUEUED,
    nextAttemptAt = nextAttemptAt,
    backoffStreak = streak,
    updatedAt = now,
  )

  fun toSettled(e: QueueEntry, state: EntryState, eventId: String, bytesSent: Long, now: Long) = e.copy(
    state = state,
    settledEventId = eventId,
    bytesSent = bytesSent,
    nextAttemptAt = null,
    parkedGeneration = null,
    updatedAt = now,
  )

  /**
   * pause(). parkedGeneration is kept so resume() can return the entry to
   * awaiting-auth. nextAttemptAt is cleared: resume() retries at once.
   */
  fun toPaused(e: QueueEntry, now: Long) =
    e.copy(state = EntryState.PAUSED, nextAttemptAt = null, updatedAt = now)

  /** resume(): back to awaiting-auth only when no updateHeaders() came in between. */
  fun toResumed(e: QueueEntry, headerGeneration: Int, now: Long): QueueEntry =
    if (e.parkedGeneration != null && e.parkedGeneration == headerGeneration) {
      e.copy(state = EntryState.AWAITING_AUTH, updatedAt = now)
    } else {
      e.copy(state = EntryState.QUEUED, parkedGeneration = null, updatedAt = now)
    }

  /** A system stop of a running worker. WorkManager runs the row again. No outcome. */
  fun toStopped(e: QueueEntry, now: Long) = e.copy(state = EntryState.QUEUED, updatedAt = now)

  /** updateHeaders() on a parked entry. */
  fun toUnparked(e: QueueEntry, paused: Boolean, now: Long) = e.copy(
    state = if (paused) EntryState.PAUSED else EntryState.QUEUED,
    parkedGeneration = null,
    updatedAt = now,
  )

  /** Whether a worker of [generation] may still settle [e]. Not after a cancel, a replace, or a settle. */
  fun canSettle(e: QueueEntry?, generation: Int) =
    e != null && e.generation == generation && e.isLive

  /** Whether a worker of [generation] still owns the running entry. */
  fun isOwnedRun(e: QueueEntry?, generation: Int) =
    e != null && e.generation == generation && e.state == EntryState.RUNNING
}
