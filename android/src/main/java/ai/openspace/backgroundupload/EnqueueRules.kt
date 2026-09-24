package ai.openspace.backgroundupload

/**
 * The same-id rules of enqueue() (plan 5.4), as pure functions. [decide]
 * picks the action; the builders make the next entry from the staged body.
 *
 * | Stored entry                         | Action                                    |
 * | none, no v9 manifest                 | Create                                    |
 * | none, v9 manifest                    | AdoptV9: keep the blob and accepted parts |
 * | legacy row                           | Replace (generation + 1)                  |
 * | same body, completed, record present | ReEmit: deliveries + 1, no re-run         |
 * | same body, completed, record gone    | Replace (it was acked)                    |
 * | same body, any other state           | Resume (a settled one reopens: gen + 1)   |
 * | different body, running              | RejectRunning (E_RUNNING)                 |
 * | different body, otherwise            | Replace (generation + 1, attempts 0)      |
 */
object EnqueueRules {

  sealed class Action {
    object Create : Action()
    data class AdoptV9(val manifest: LegacyManifest) : Action()
    data class ReEmit(val eventId: String) : Action()
    object Resume : Action()
    object Replace : Action()
    object RejectRunning : Action()
  }

  fun decide(
    existing: QueueEntry?,
    v9: LegacyManifest?,
    incoming: Descriptor,
    hasRecord: (eventId: String) -> Boolean,
  ): Action {
    if (existing == null) return if (v9 != null) Action.AdoptV9(v9) else Action.Create
    if (existing.legacy) return Action.Replace
    if (existing.sameBodyAs(incoming)) {
      if (existing.state == EntryState.COMPLETED) {
        val eventId = existing.settledEventId
        return if (eventId != null && hasRecord(eventId)) Action.ReEmit(eventId) else Action.Replace
      }
      return Action.Resume
    }
    return if (existing.state == EntryState.RUNNING) Action.RejectRunning else Action.Replace
  }

  /**
   * The generation to stage a body for before the store lock is taken, or
   * null to stage under the lock. Only a copied body (JSON, multipart,
   * file), and only when no worker can change the decision meanwhile: a new
   * id, or a replace of an entry that a worker can not take (not queued,
   * not running). A chunked body is a move, which is fast, over a blob that
   * a worker may be reading, so it always stages under the lock.
   */
  fun preStageGeneration(existing: QueueEntry?, action: Action, incoming: Descriptor): Int? {
    val copied = incoming.bodyKind.let {
      it == StagedBody.JSON || it == StagedBody.MULTIPART || it == StagedBody.FILE
    }
    if (!copied) return null
    return when (action) {
      Action.Create -> 1
      Action.Replace -> existing
        ?.takeIf { it.state != EntryState.QUEUED && it.state != EntryState.RUNNING }
        ?.let { it.generation + 1 }
      else -> null
    }
  }

  /** The parts an adopted v9 manifest runs with: its accepted flags when the parts are the same. */
  fun adoptedParts(v9: LegacyManifest, incoming: List<Part>): List<Part> =
    if (ChunkedParts.sameParts(v9.parts, incoming)) ChunkedParts.carryAccepted(v9.parts, incoming)
    else incoming

  private fun initialState(paused: Boolean) = if (paused) EntryState.PAUSED else EntryState.QUEUED

  /** A new entry (Create, AdoptV9). [parts] carries adopted accepted flags. */
  fun created(
    p: EntryParsing.Parsed,
    staged: BodyStaging.Staged,
    parts: List<Part>?,
    paused: Boolean,
    headerGeneration: Int,
    now: Long,
  ): QueueEntry {
    val runParts = parts ?: p.descriptor.parts
    return QueueEntry(
      id = p.id,
      key = p.key,
      varsJson = p.varsJson,
      descriptor = p.descriptor.copy(headers = staged.headers, parts = runParts),
      body = staged.body,
      state = initialState(paused),
      attempts = 0,
      bytesSent = runParts?.let { ChunkedParts.acceptedBytes(it) } ?: 0L,
      totalBytes = staged.body.totalBytes,
      expiresAt = p.expiresAt,
      createdAt = now,
      updatedAt = now,
      headerGeneration = headerGeneration,
      generation = 1,
    )
  }

  /**
   * Same body. New headers, expiresAt, vars, accept, retry, and notification
   * flag replace the stored ones; the body and accepted parts stay. A settled
   * entry reopens with a fresh generation. A running one stays running (the
   * worker reads the new headers before its next attempt).
   */
  fun resumed(
    existing: QueueEntry,
    p: EntryParsing.Parsed,
    paused: Boolean,
    headerGeneration: Int,
    now: Long,
  ): QueueEntry {
    val stored = existing.descriptor!!
    val body = existing.body!!
    val parts = stored.parts?.let { ChunkedParts.carryAccepted(it, p.descriptor.parts!!) }
    val running = existing.state == EntryState.RUNNING
    val reopen = existing.isSettled
    return existing.copy(
      key = p.key,
      varsJson = p.varsJson,
      descriptor = stored.copy(
        headers = BodyStaging.headersFor(p.descriptor.headers, body),
        parts = parts,
        accept = p.descriptor.accept,
        retry = p.descriptor.retry,
        noNotification = p.descriptor.noNotification,
      ),
      state = if (running) EntryState.RUNNING else initialState(paused),
      bytesSent = parts?.let { ChunkedParts.acceptedBytes(it) } ?: if (running) existing.bytesSent else 0L,
      expiresAt = p.expiresAt,
      updatedAt = now,
      nextAttemptAt = null,
      backoffStreak = 0,
      headerGeneration = headerGeneration,
      parkedGeneration = null,
      generation = if (reopen) existing.generation + 1 else existing.generation,
      settledEventId = if (reopen) null else existing.settledEventId,
    )
  }

  /** Different body (or over a legacy or acked row). A new life over the same id. */
  fun replaced(
    existing: QueueEntry,
    p: EntryParsing.Parsed,
    staged: BodyStaging.Staged,
    paused: Boolean,
    headerGeneration: Int,
    now: Long,
  ): QueueEntry = created(p, staged, null, paused, headerGeneration, now).copy(
    createdAt = existing.createdAt,
    generation = existing.generation + 1,
  )
}
