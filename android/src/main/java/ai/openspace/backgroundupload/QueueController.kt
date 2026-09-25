package ai.openspace.backgroundupload

import java.io.IOException
import java.util.UUID

/**
 * Every transition that JS causes: enqueue, pause, resume, cancel,
 * setWifiOnly, updateHeaders, ack, and the boot sweep. [UploaderModule] calls
 * it from one single-thread executor, so these calls never overlap each other.
 * Workers change entries at the same time; every change here is inside the
 * store lock, so each one is atomic against them.
 *
 * Order of a change: journal (when there is an outcome), store, work
 * schedule, then events. Events go out after the store lock is released.
 */
class QueueController(
  private val store: QueueStore,
  private val journal: EventJournal,
  private val settings: QueueSettingsStore,
  private val events: QueueEvents,
  private val scheduler: WorkScheduler,
  private val isWorkerRunning: (id: String) -> Boolean = WorkerGate::isRunning,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  // MARK: - enqueue

  private class Enqueued(val entry: QueueEntry?, val reEmit: EventJournal.SettledRecord?)

  /** A body staged before the store lock was taken, and the generation it was staged for. */
  private class PreStaged(val generation: Int, val staged: BodyStaging.Staged)

  /** Test seam: runs between the staging outside the lock and the commit under it. */
  internal var afterPreStage: () -> Unit = {}

  /**
   * Persists the entry and every staged byte, then schedules it. Returns the
   * id. Throws [QueueException] with E_RUNNING, E_FILE_MISSING, E_STORAGE, or
   * E_INVALID.
   *
   * A copied body is staged before the store lock when the decision can not
   * change meanwhile (see [EnqueueRules.preStageGeneration]), so a large
   * copy does not block every worker transition. Under the lock the
   * decision is made again; a staged body that no longer fits is deleted
   * and the body is staged again under the lock.
   */
  fun enqueue(p: EntryParsing.Parsed): String {
    val pre = preStage(p)
    var preUsed = false
    fun preFor(generation: Int): BodyStaging.Staged? =
      pre?.takeIf { it.generation == generation }?.staged?.also { preUsed = true }
    val result = try {
      store.locked { decideAndCommit(p, ::preFor) }
    } finally {
      if (pre != null && !preUsed) discard(p.id, pre)
    }
    result.reEmit?.let { record -> journal.listener()?.let { events.settled(record, it) } }
    result.entry?.let { entry ->
      scheduleRun(entry)
      events.state(entry.toRow())
    }
    return p.id
  }

  private fun preStage(p: EntryParsing.Parsed): PreStaged? {
    val generation = store.locked {
      val existing = store.load(p.id)
      val v9 = v9ManifestFor(existing, p.id)
      val action = EnqueueRules.decide(existing, v9, p.descriptor) { journal.find(it) != null }
      EnqueueRules.preStageGeneration(existing, action, p.descriptor)
    } ?: return null
    val staged = stageOrThrow(p.descriptor, store.entryDir(p.id), generation)
    afterPreStage()
    return PreStaged(generation, staged)
  }

  /** Deletes a pre-staged body that the commit did not use, unless the stored entry points at it. */
  private fun discard(id: String, pre: PreStaged) {
    val file = pre.staged.body.fileName?.let { java.io.File(store.entryDir(id), it) } ?: return
    val current = store.load(id)
    if (current == null || store.bodyFile(current) != file) file.delete()
  }

  /** Runs under the store lock. [preStaged] returns the body staged outside the lock for a generation, if any. */
  private fun decideAndCommit(p: EntryParsing.Parsed, preStaged: (generation: Int) -> BodyStaging.Staged?): Enqueued {
    val existing = store.load(p.id)
    val v9 = v9ManifestFor(existing, p.id)
    val s = settings.load()
    val now = clock()
    val dir = store.entryDir(p.id)
    return when (val action = EnqueueRules.decide(existing, v9, p.descriptor) { journal.find(it) != null }) {
      EnqueueRules.Action.RejectRunning -> throw QueueException(
        QueueException.E_RUNNING,
        "entry '${p.id}' is running; a different body is accepted once it stops",
      )
      // With no listener yet, the next drain delivers it.
      is EnqueueRules.Action.ReEmit -> Enqueued(null, journal.redeliver(action.eventId))
      EnqueueRules.Action.Resume -> {
        val next = EnqueueRules.resumed(existing!!, p, s.isPaused(p.key), s.headerGeneration, now)
        saveOrThrow(next)
        Enqueued(next, null)
      }
      EnqueueRules.Action.Create -> {
        val staged = preStaged(1) ?: stageOrThrow(p.descriptor, dir, 1)
        commit(EnqueueRules.created(p, staged, null, s.isPaused(p.key), s.headerGeneration, now))
      }
      is EnqueueRules.Action.AdoptV9 -> {
        val incoming = p.descriptor.parts
        val parts = incoming?.let { EnqueueRules.adoptedParts(action.manifest, it) }
        // The same parts resume over the v9 blob, as a same-body enqueue does.
        val keepOwned = incoming != null && ChunkedParts.sameParts(action.manifest.parts, incoming)
        val staged = stageOrThrow(p.descriptor.copy(parts = parts), dir, action.generation, store.blobFile(p.id), keepOwned)
        commit(EnqueueRules.adopted(p, staged, parts, existing, action.generation, s.isPaused(p.key), s.headerGeneration, now))
      }
      EnqueueRules.Action.Replace -> {
        val old = existing!!
        // Different parts: a present caller file wins; the old blob is the fallback.
        val ownedBlob = if (old.body?.kind == StagedBody.CHUNKED) store.bodyFile(old) else null
        val staged = preStaged(old.generation + 1)
          ?: stageOrThrow(p.descriptor, dir, old.generation + 1, ownedBlob)
        commit(EnqueueRules.replaced(old, p, staged, s.isPaused(p.key), s.headerGeneration, now))
      }
    }
  }

  /** A v9 manifest counts only where no v10 entry owns the id, or the owner is its legacy row. */
  private fun v9ManifestFor(existing: QueueEntry?, id: String): LegacyManifest? =
    if (existing == null || existing.legacy) store.legacyManifest(id) else null

  private fun stageOrThrow(
    d: Descriptor,
    dir: java.io.File,
    generation: Int,
    ownedBlob: java.io.File? = null,
    keepOwned: Boolean = false,
  ): BodyStaging.Staged =
    try {
      BodyStaging.stage(d, dir, generation, ownedBlob, keepOwned)
    } catch (e: QueueException) {
      throw e
    } catch (e: IOException) {
      throw QueueException(QueueException.E_STORAGE, "could not stage the body: ${e.message}")
    }

  /** Saves a newly staged entry. On failure the new body file is removed; the old entry stays as it was. */
  private fun commit(next: QueueEntry): Enqueued {
    try {
      store.save(next)
    } catch (e: IOException) {
      if (next.body?.kind != StagedBody.CHUNKED) store.bodyFile(next)?.delete()
      throw QueueException(QueueException.E_STORAGE, "could not save the entry: ${e.message}")
    }
    store.pruneUnreferenced(next)
    return Enqueued(next, null)
  }

  private fun saveOrThrow(next: QueueEntry) {
    try {
      store.save(next)
    } catch (e: IOException) {
      throw QueueException(QueueException.E_STORAGE, "could not save the entry: ${e.message}")
    }
  }

  // MARK: - queue control

  /**
   * [keys] null is the whole-queue gate; a list adds those keys to the
   * paused set, and an empty list changes nothing. Each live row that is now
   * paused (gate on, or its key in the set) moves to paused and its work
   * stops. No outcome.
   */
  fun pause(keys: List<String>? = null) {
    if (keys != null && keys.isEmpty()) return
    val s = settings.update {
      if (keys == null) it.copy(paused = true) else it.copy(pausedKeys = it.pausedKeys + keys)
    }
    val now = clock()
    val paused = transformAll { e ->
      if (e.isLive && e.state != EntryState.PAUSED && s.isPaused(e.key)) EntryTransitions.toPaused(e, now) else e
    }
    paused.forEach { scheduler.cancel(it.id) }
    paused.forEach { events.state(it.toRow()) }
  }

  /**
   * Undoes the pause of the same scope: [keys] null turns the gate off, a
   * list removes those keys from the set. A row comes back only when no
   * scope still pauses it, so the gate on + its key resumed stays paused.
   */
  fun resume(keys: List<String>? = null) {
    if (keys != null && keys.isEmpty()) return
    val s = settings.update {
      if (keys == null) it.copy(paused = false) else it.copy(pausedKeys = it.pausedKeys - keys.toSet())
    }
    val now = clock()
    val resumed = transformAll { e ->
      if (e.state == EntryState.PAUSED && !s.isPaused(e.key)) EntryTransitions.toResumed(e, s.headerGeneration, now) else e
    }
    resumed.forEach { events.state(it.toRow()) }
    // Every queued entry, not only the resumed ones: a run is idempotent.
    store.all().forEach { scheduleRun(it) }
  }

  /**
   * Live: journal a 'cancelled' (user) outcome, then forget after its ack.
   * Settled (or legacy): forget now, row, bytes, and its unacknowledged
   * outcomes. Unknown: no-op.
   *
   * A failed journal write rejects E_STORAGE and changes nothing: the entry
   * goes on, and JS can call cancel() again.
   *
   * A failed entry save after the journal write also rejects E_STORAGE, but
   * the cancel is durable: the work is stopped and the record is emitted.
   * Its ack, the next cancel(), a worker's begin or settle, or the boot
   * sweep applies it. A live entry that already has a record of its own
   * generation gets that record applied, not a second outcome.
   */
  fun cancel(id: String) {
    var stop = false
    var journaled: EventJournal.SettledRecord? = null
    var saved: QueueEntry? = null
    try {
      store.locked {
        stop = true // unknown or settled: stop any stray work, as before
        val e = store.load(id) ?: return@locked
        if (!e.isLive || e.legacy) {
          journal.ackEntry(id)
          store.remove(id)
          return@locked
        }
        stop = false // a live entry: only once an outcome is journaled
        val now = clock()
        EntryTransitions.journaledSettle(e, journal.forEntry(id), now)?.let {
          stop = true
          saveOrThrow(it.entry)
          journal.ack(it.extraEventIds)
          saved = it.entry
          return@locked
        }
        val record = cancelledRecord(e, now)
        journaled = try {
          journal.append(record) { store.referencedEventIds() + record.eventId }
        } catch (error: IOException) {
          throw QueueException(QueueException.E_STORAGE, "could not journal the cancel: ${error.message}")
        }
        stop = true
        val next = EntryTransitions.toSettled(e, EntryState.CANCELLED, record.eventId, e.bytesSent, now)
        saveOrThrow(next)
        saved = next
      }
    } finally {
      // Once an outcome is journaled, the worker must stop even when the
      // save failed: a running request would settle a second outcome.
      if (stop) scheduler.cancel(id)
      journaled?.let { record ->
        if (record.deliveries > 0) journal.listener()?.let { events.settled(record, it) }
      }
      saved?.let { events.state(it.toRow()) }
    }
  }

  private fun cancelledRecord(e: QueueEntry, now: Long) = EventJournal.SettledRecord(
    eventId = UUID.randomUUID().toString(),
    id = e.id,
    key = e.key,
    varsJson = e.varsJson,
    at = now,
    attempts = e.attempts,
    requestId = e.lastRequestId,
    deliveries = 0, // the journal sets it
    state = EntryState.CANCELLED.wire,
    bytesSent = e.bytesSent,
    totalBytes = e.totalBytes,
    url = e.descriptor?.reportUrl ?: "",
    method = e.descriptor?.method ?: "POST",
    partIndex = null,
    kind = EventJournal.KIND_CANCELLED,
    response = null,
    errorKind = null,
    message = null,
    cancelReason = "user",
    generation = e.generation,
  )

  fun setWifiOnly(enabled: Boolean) {
    settings.update { it.copy(wifiOnly = enabled) }
  }

  fun configureRetry(defaults: RetryDefaults) {
    settings.update { it.copy(retry = defaults) }
  }

  /**
   * Bumps the header generation, merges [patch] into every entry not yet
   * forgotten, and requeues the parked ones. Workers compare each entry's
   * own headerGeneration, which changes here under the store lock together
   * with its headers. So a worker that gets a 401 either sees the patched
   * entry and re-issues, or parks first and is requeued here.
   */
  fun updateHeaders(patch: Map<String, String>) {
    val s = settings.update { it.copy(headerGeneration = it.headerGeneration + 1) }
    val now = clock()
    val unparkedIds = mutableSetOf<String>()
    val changed = transformAll { e ->
      val patched = e.withHeadersPatched(patch, s.headerGeneration)
      if (patched.state != EntryState.AWAITING_AUTH) return@transformAll patched
      unparkedIds += e.id
      EntryTransitions.toUnparked(patched, s.isPaused(patched.key), now)
    }
    changed.filter { it.id in unparkedIds }.forEach { entry ->
      scheduleRun(entry)
      events.state(entry.toRow())
    }
  }

  // MARK: - journal

  /**
   * getUnacknowledgedEvents(): [listener] becomes the JS listener, and every
   * unacknowledged outcome is returned, each counted as one more delivery.
   */
  fun unacknowledged(listener: Any, isActive: () -> Boolean = { true }): List<EventJournal.SettledRecord> =
    journal.drain(listener, isActive)

  /**
   * Removes the records. An acked completed or cancelled outcome of the
   * entry's current life forgets the entry: row and bytes. An error keeps
   * the row until cancel() or a same-id enqueue. Unknown ids are ignored.
   *
   * A record of the entry's current life on a live entry means the settle
   * journaled and emitted, but its store write failed. The record is applied
   * first, as the boot sweep would; without that, the sweep finds no record
   * after this ack and runs the finished request again. When that save fails
   * too, the record stays unacked so the sweep can apply it later.
   */
  fun ack(eventIds: List<String>) {
    val forgotten = mutableListOf<String>()
    val repaired = mutableListOf<QueueEntry>()
    store.locked {
      eventIds.forEach { eventId ->
        val record = journal.find(eventId) ?: return@forEach
        var e = store.load(record.id)
        if (e != null && e.isLive && !e.legacy && e.generation == record.generation) {
          val next = EntryTransitions.toSettled(e, EntryTransitions.stateOf(record), record.eventId, record.bytesSent, clock())
          if (!trySave(next)) return@forEach
          repaired += next
          e = next
        }
        journal.ack(listOf(eventId))
        if (record.kind == EventJournal.KIND_ERROR || e == null) return@forEach
        if (e.generation == record.generation && e.settledEventId == record.eventId) {
          store.remove(record.id)
          forgotten += record.id
        }
      }
    }
    forgotten.forEach { scheduler.cancel(it) }
    repaired.filter { it.id !in forgotten }.forEach { events.state(it.toRow()) }
  }

  // MARK: - boot sweep

  /**
   * Repairs what a process death can leave, then schedules queued work. An
   * entry whose worker runs in this process is skipped: that worker finishes
   * its own transition. Run at module init, after [LegacyImport].
   *
   * 1. A live entry with a journal record of its own generation: the process
   *    died between the journal append and the store transition. Apply it.
   * 2. A running entry with no worker: a process death mid-run. Queue it.
   *    A live row that disagrees with the paused settings, the gate or its
   *    key (a death partway through pause() or resume()): make it agree.
   * 3. A settled entry with records of its generation other than its own:
   *    orphans from a cancel race. Ack them.
   * 4. A completed or cancelled entry whose own record is gone: the ack
   *    landed but the forget did not. Forget it.
   * 5. Schedule every queued entry, and the expiry wake of every parked one.
   */
  fun sweep() {
    val now = clock()
    val changed = mutableListOf<QueueEntry>()
    val toForget = mutableListOf<String>()
    val toSchedule = mutableListOf<QueueEntry>()
    val s = settings.load()
    store.locked {
      val records = journal.unacknowledged().groupBy { it.id }
      for (e in store.all()) {
        if (e.legacy || isWorkerRunning(e.id)) continue
        val own = records[e.id].orEmpty().filter { it.generation == e.generation }
        if (e.isLive) {
          val journaled = EntryTransitions.journaledSettle(e, own, now)
          if (journaled != null) {
            if (trySave(journaled.entry)) {
              journal.ack(journaled.extraEventIds)
              changed += journaled.entry
            }
            continue
          }
          var cur = e
          if (e.state == EntryState.RUNNING) {
            cur = EntryTransitions.toStopped(e, now)
          }
          // A process death partway through pause() or resume() leaves rows
          // that disagree with the settings (the gate or the paused keys).
          val paused = s.isPaused(e.key)
          if (paused && cur.state != EntryState.PAUSED) {
            cur = EntryTransitions.toPaused(cur, now)
          } else if (!paused && cur.state == EntryState.PAUSED) {
            cur = EntryTransitions.toResumed(cur, s.headerGeneration, now)
          }
          if (cur !== e) {
            if (trySave(cur)) changed += cur else continue
          }
          if (!paused || cur.state == EntryState.AWAITING_AUTH) toSchedule += cur
        } else {
          val orphans = own.filter { it.eventId != e.settledEventId }
          if (orphans.isNotEmpty()) journal.ack(orphans.map { it.eventId })
          val forgettable = e.state == EntryState.COMPLETED || e.state == EntryState.CANCELLED
          if (forgettable && own.none { it.eventId == e.settledEventId }) {
            store.remove(e.id)
            toForget += e.id
          }
        }
      }
    }
    toForget.forEach { scheduler.cancel(it) }
    toSchedule.forEach { scheduleRun(it, keepWake = true) }
    changed.forEach { events.state(it.toRow()) }
  }

  private fun trySave(entry: QueueEntry): Boolean = try {
    store.save(entry)
    true
  } catch (e: IOException) {
    Diag.error("could not save '${entry.id}'", e)
    false
  }

  // MARK: - helpers

  /**
   * Runs [entry] when it is queued. A backoff longer than a worker waits goes
   * to the wake; a parked entry gets a wake at its expiry, so it settles
   * 'expired' on time.
   */
  private fun scheduleRun(entry: QueueEntry, keepWake: Boolean = false) {
    val now = clock()
    when (entry.state) {
      EntryState.QUEUED -> {
        val at = entry.nextAttemptAt
        if (at != null && at - now > RetryClassifier.IN_WORKER_BACKOFF_MAX_MS) {
          scheduler.scheduleWake(entry, at, replace = !keepWake)
        } else {
          scheduler.schedule(entry)
        }
      }
      EntryState.AWAITING_AUTH -> scheduler.scheduleWake(entry, entry.expiresAt, replace = !keepWake)
      else -> Unit
    }
  }

  /** Applies [transform] to every non-legacy entry under the store lock. Returns the ones it changed. */
  private fun transformAll(transform: (QueueEntry) -> QueueEntry): List<QueueEntry> {
    val changed = mutableListOf<QueueEntry>()
    store.locked {
      store.all().filter { !it.legacy }.forEach { e ->
        store.compute(e.id) { cur ->
          if (cur == null) null else transform(cur).also { if (it !== cur) changed += it }
        }
      }
    }
    return changed
  }
}
