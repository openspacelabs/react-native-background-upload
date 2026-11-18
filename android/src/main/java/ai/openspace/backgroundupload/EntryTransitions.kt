package ai.openspace.backgroundupload

import kotlin.math.max
import kotlin.math.min

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
   * and shows when the next attempt is due. The failure's step, its [cause]
   * and its time are saved now, not at the next release. Chunked parts keep
   * their own streaks, so the entry keeps the highest and the part
   * ([partStart]) that holds it; only that part's acceptance resets it.
   */
  fun toBackingOff(
    e: QueueEntry,
    nextAttemptAt: Long,
    streak: Int,
    now: Long,
    cause: String? = null,
    partStart: Long? = null,
  ): QueueEntry {
    val higher = streak >= e.backoffStreak
    return e.copy(
      nextAttemptAt = nextAttemptAt,
      backoffStreak = max(e.backoffStreak, streak),
      backoffPartStart = if (higher) partStart else e.backoffPartStart,
      waitCause = cause,
      failedAt = now,
      updatedAt = now,
    )
  }

  /** One attempt starts: attempts + 1, its X-Request-Id, and no pending backoff. */
  fun toAttempt(e: QueueEntry, requestId: String, now: Long) = e.copy(
    attempts = e.attempts + 1,
    lastRequestId = requestId,
    nextAttemptAt = null,
    updatedAt = now,
  )

  /**
   * A backoff too long to wait inside the worker. The streak is kept so the
   * next wait keeps growing, with the failure's [cause] and time. For a
   * chunked entry the part that released the run ([partStart]) takes the
   * streak with its own count, also over a higher one another part held:
   * that part goes on from it in the next run, and a part that only was in
   * flight, or a slower one, does not hide a part that fails every time.
   */
  fun toReleased(
    e: QueueEntry,
    nextAttemptAt: Long,
    streak: Int,
    now: Long,
    cause: String? = null,
    partStart: Long? = null,
  ) = e.copy(
    state = EntryState.QUEUED,
    nextAttemptAt = nextAttemptAt,
    backoffStreak = streak,
    backoffPartStart = partStart,
    waitCause = cause,
    failedAt = now,
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
   * awaiting-auth. A backoff wait is kept with its cause and time:
   * resume() ends it only as [clearsWait] allows, so a pause and resume
   * cannot spend a transient rule's retries. A paused row does not show it.
   */
  fun toPaused(e: QueueEntry, now: Long) =
    e.copy(state = EntryState.PAUSED, updatedAt = now)

  /**
   * resume(): back to awaiting-auth only when no updateHeaders() came in
   * between. It compares the entry's own headerGeneration, which
   * updateHeaders() saves with the new headers. The settings value can be
   * older after a settings write that never landed. Back to queued, the
   * entry keeps what is left of its wait (resume() then applies
   * [clearsWait]), and the streak keeps at most
   * [RetryClassifier.RETRY_NOW_MAX_STREAK] steps, as after a cleared wait:
   * a pause and resume in normal work must not give a failing entry a
   * burst of attempts.
   */
  fun toResumed(e: QueueEntry, now: Long): QueueEntry =
    if (e.parkedGeneration != null && e.parkedGeneration == e.headerGeneration) {
      e.copy(state = EntryState.AWAITING_AUTH, updatedAt = now)
    } else {
      e.copy(
        state = EntryState.QUEUED,
        parkedGeneration = null,
        backoffStreak = min(e.backoffStreak, RetryClassifier.RETRY_NOW_MAX_STREAK),
        // The wait kept over the pause; one that ended during it is gone.
        nextAttemptAt = e.nextAttemptAt?.takeIf { it > now },
        updatedAt = now,
      )
    }

  /**
   * Whether the app may end [e]'s backoff wait now (boot sweep, resume(),
   * return to the foreground). Only a queued entry whose wait followed a
   * failure with no server response, or whose cause is unknown (an older
   * version saved it), and only [RetryClassifier.CLEAR_MIN_AGE_MS] or more
   * after that failure. A wait after a server response keeps its time: the
   * server asked for it in effect, and clearing it would spend a transient
   * rule's retries faster, so the request could settle as failed sooner.
   */
  fun clearsWait(e: QueueEntry, now: Long): Boolean =
    e.state == EntryState.QUEUED &&
      e.nextAttemptAt != null &&
      e.waitCause != WaitCause.RESPONSE &&
      now - (e.failedAt ?: e.updatedAt) >= RetryClassifier.CLEAR_MIN_AGE_MS

  /**
   * The app started, resumed, or came to the foreground, and [clearsWait]
   * holds: the entry stops waiting for its backoff and runs now. The streak
   * keeps at most [RetryClassifier.RETRY_NOW_MAX_STREAK] steps, so the entry
   * sends once now, and a server that is still down is not asked again
   * every few seconds.
   */
  fun toRetryNow(e: QueueEntry, now: Long) = e.copy(
    nextAttemptAt = null,
    backoffStreak = min(e.backoffStreak, RetryClassifier.RETRY_NOW_MAX_STREAK),
    updatedAt = now,
  )

  /**
   * A system stop of a running worker. No outcome. A nextAttemptAt that is
   * already past is cleared: a queued row does not show a due time behind it.
   */
  fun toStopped(e: QueueEntry, now: Long) = e.copy(
    state = EntryState.QUEUED,
    nextAttemptAt = e.nextAttemptAt?.takeIf { it > now },
    updatedAt = now,
  )

  /** updateHeaders() on a parked entry. */
  fun toUnparked(e: QueueEntry, paused: Boolean, now: Long) = e.copy(
    state = if (paused) EntryState.PAUSED else EntryState.QUEUED,
    parkedGeneration = null,
    updatedAt = now,
  )

  /**
   * Whether a worker of [generation] may still settle [e]. Not after a
   * cancel, a replace, or a settle. Under pause only an [accepted] response
   * settles: the server already took it. A failure waits for resume, which
   * runs the entry again.
   */
  fun canSettle(e: QueueEntry?, generation: Int, accepted: Boolean) =
    e != null && e.generation == generation && e.isLive && (accepted || e.state != EntryState.PAUSED)

  /** The entry state a journal record puts its entry in. */
  fun stateOf(record: EventJournal.SettledRecord): EntryState =
    EntryState.values().firstOrNull { it.wire == record.state } ?: EntryState.ERROR

  /** A settled entry, and the other records of its life to ack. */
  data class Journaled(val entry: QueueEntry, val extraEventIds: List<String>)

  /**
   * A live entry with a journal record of its own generation: the settle
   * journaled, then its store write was lost (a process death, a failed
   * save). Apply the newest record; do not run the request again. Null when
   * there is no such record. The boot sweep and a worker's begin share it.
   */
  fun journaledSettle(e: QueueEntry, records: List<EventJournal.SettledRecord>, now: Long): Journaled? {
    if (!e.isLive || e.legacy) return null
    val own = records.filter { it.id == e.id && it.generation == e.generation }
    val latest = own.maxByOrNull { it.at } ?: return null
    return Journaled(
      toSettled(e, stateOf(latest), latest.eventId, latest.bytesSent, now),
      own.filter { it !== latest }.map { it.eventId },
    )
  }

  /** Whether a worker of [generation] still owns the running entry. */
  fun isOwnedRun(e: QueueEntry?, generation: Int) =
    e != null && e.generation == generation && e.state == EntryState.RUNNING
}
