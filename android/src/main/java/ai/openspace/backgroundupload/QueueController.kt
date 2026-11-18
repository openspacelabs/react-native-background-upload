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
  /**
   * Whether the network now fits an upload with this Wi-Fi rule, as a run
   * checks it. Called at most twice per pass ([NetworkProbe]).
   */
  private val networkFits: (wifiOnly: Boolean) -> Boolean = { true },
  /** Whether an entry with no network may wait on a doorbell: API 30 and later. */
  private val doorbells: Boolean = true,
  /**
   * Runs [task] on the queue executor after [delayMs]. A pass whose
   * WorkManager calls failed asks for [repairRows] this way, so an entry
   * left with no row is started with no app call.
   */
  private val repairLater: (delayMs: Long, task: () -> Unit) -> Unit = { _, _ -> },
) {

  companion object {
    /** The first wait before [repairRows] runs after a failed pass; it doubles up to [REPAIR_MAX_MS]. */
    const val REPAIR_FIRST_MS = 30_000L
    const val REPAIR_MAX_MS = 300_000L
  }

  private val repairPending = java.util.concurrent.atomic.AtomicBoolean(false)

  @Volatile
  private var repairDelayMs = REPAIR_FIRST_MS

  // MARK: - enqueue

  /** [repair]: the entry was queued already (a same-id enqueue of the same body). */
  private class Enqueued(val entry: QueueEntry?, val reEmit: EventJournal.SettledRecord?, val repair: Boolean = false)

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
      // New work starts a run, which shows the notification and may take
      // foreground mode. A same-id enqueue of a queued entry is a repair.
      // The entry is saved: a WorkManager call that fails or times out does
      // not reject mutate(). The repair below starts the entry later.
      val pass = SchedulePass("enqueue")
      pass.schedule { scheduleRun(entry, probe = if (result.repair) NetworkProbe() else null) }
      events.state(entry.toRow())
      pass.finish()
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
        Enqueued(next, null, repair = existing.state == EntryState.QUEUED)
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
        // Different parts: a present caller file wins; the old blob is the
        // fallback. A legacy row has no body, so its fallback is the v9 blob.
        val ownedBlob = when {
          old.legacy -> store.blobFile(p.id)
          old.body?.kind == StagedBody.CHUNKED -> store.bodyFile(old)
          else -> null
        }
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
      throw QueueException(
        QueueException.E_STORAGE,
        "could not stage the body: ${e.message}" + StorageCause.suffix(e, withMessage = false),
        e,
      )
    }

  /** Saves a newly staged entry. On failure the new body file is removed; the old entry stays as it was. */
  private fun commit(next: QueueEntry): Enqueued {
    try {
      store.save(next)
    } catch (e: IOException) {
      if (next.body?.kind != StagedBody.CHUNKED) store.bodyFile(next)?.delete()
      throw QueueException(
        QueueException.E_STORAGE,
        "could not save the entry: ${e.message}" + StorageCause.suffix(e, withMessage = false),
        e,
      )
    }
    store.pruneUnreferenced(next)
    return Enqueued(next, null)
  }

  private fun saveOrThrow(next: QueueEntry) {
    try {
      store.save(next)
    } catch (e: IOException) {
      throw QueueException(
        QueueException.E_STORAGE,
        "could not save the entry: ${e.message}" + StorageCause.suffix(e, withMessage = false),
        e,
      )
    }
  }

  // MARK: - queue control

  /**
   * [keys] null is the whole-queue gate; a list adds those keys to the
   * paused set, and an empty list changes nothing. Each live row that is now
   * paused (gate on, or its key in the set) moves to paused and its work
   * stops. No outcome.
   *
   * A failed settings write does not throw (see [QueueSettingsStore.update]).
   * A failed entry save throws E_STORAGE after every other entry is moved
   * and its work stopped. The entry that failed keeps its old row; its
   * worker sees the paused gate before its next attempt.
   */
  fun pause(keys: List<String>? = null) {
    if (keys != null && keys.isEmpty()) return
    val s = settings.update {
      if (keys == null) it.copy(paused = true) else it.copy(pausedKeys = it.pausedKeys + keys)
    }
    val now = clock()
    val result = transformAll { e ->
      if (e.isLive && e.state != EntryState.PAUSED && s.isPaused(e.key)) EntryTransitions.toPaused(e, now) else e
    }
    result.changed.forEach { scheduler.cancel(it.id) }
    result.changed.forEach { events.state(it.toRow()) }
    result.throwIfFailed("pause")
  }

  /**
   * Undoes the pause of the same scope: [keys] null turns the gate off, a
   * list removes those keys from the set. A row comes back only when no
   * scope still pauses it, so the gate on + its key resumed stays paused.
   * A resumed entry keeps at most 5 backoff steps. A queued entry of the
   * same scope whose wait may be cleared ([EntryTransitions.clearsWait])
   * runs now.
   *
   * A failed entry save throws E_STORAGE after every other entry is moved
   * and every queued entry is scheduled. The entry that failed stays paused.
   */
  fun resume(keys: List<String>? = null) {
    if (keys != null && keys.isEmpty()) return
    val s = settings.update {
      if (keys == null) it.copy(paused = false) else it.copy(pausedKeys = it.pausedKeys - keys.toSet())
    }
    val now = clock()
    val result = transformAll { e ->
      when {
        e.state == EntryState.PAUSED && !s.isPaused(e.key) -> EntryTransitions.toResumed(e, now).let {
          // The wait kept over the pause follows the clear rule too.
          if (EntryTransitions.clearsWait(it, now)) EntryTransitions.toRetryNow(it, now) else it
        }
        inScope(e, keys) && EntryTransitions.clearsWait(e, now) -> EntryTransitions.toRetryNow(e, now)
        else -> e
      }
    }
    result.changed.forEach { events.state(it.toRow()) }
    // Every queued entry, not only the resumed ones: a run is idempotent.
    val probe = NetworkProbe()
    val pass = SchedulePass("resume")
    store.all().forEach { e -> pass.schedule { scheduleRun(e, probe = probe) } }
    pass.finish()
    result.throwIfFailed("resume")
  }

  /**
   * The app came to the foreground: queued entries in scope that may run
   * now are started. [keys] null is every key; an empty list changes nothing.
   *
   * An entry whose wait [EntryTransitions.clearsWait] allows stops waiting
   * ([EntryTransitions.toRetryNow]) and is scheduled. A wait after a server
   * response keeps its time. A queued entry with no wait, whatever its last
   * failure, is scheduled only when no row of it can start now
   * ([WorkScheduler.rowSummary], one query): that is not a retry, it only
   * makes sure a row exists, and a row WorkManager holds back after a stop
   * is replaced ([WorkScheduler.schedule]). A queued entry whose wait stays
   * and that has no delayed wake row gets its wake again (KEEP), also when
   * it has a main row: WorkManager may hold that row back for hours. Running, paused, parked and
   * settled entries are left alone. A queued entry sends nothing, and the
   * per-id gate holds any new row while a worker of the entry still winds
   * down, so this never starts a second transfer next to one in progress.
   *
   * It reads every entry file once, and saves only the entries it changes.
   * Never throws: an entry whose save or scheduling fails is skipped, the
   * others go on, and one error goes to the diagnostics. Every saved
   * change emits its state event.
   */
  fun retryNow(keys: List<String>? = null) {
    if (keys != null && keys.isEmpty()) return
    val now = clock()
    val queued = store.all().filter { !it.legacy && it.state == EntryState.QUEUED && inScope(it, keys) }
    val cleared = mutableListOf<QueueEntry>()
    val pass = SchedulePass("retryNow")
    fun failed(error: Throwable) = pass.failed(error)
    store.locked {
      queued.filter { EntryTransitions.clearsWait(it, now) }.forEach { e ->
        try {
          var next: QueueEntry? = null
          // The snapshot can be stale: the store decides under the lock.
          store.compute(e.id) { cur ->
            if (cur != null && EntryTransitions.clearsWait(cur, now)) EntryTransitions.toRetryNow(cur, now).also { next = it } else cur
          }
          next?.let { cleared += it }
        } catch (error: IOException) {
          failed(error)
        }
      }
    }
    val probe = NetworkProbe()
    cleared.forEach { e -> pass.schedule { scheduleRun(e, probe = probe) } }
    val clearedIds = cleared.map { it.id }.toSet()
    // A wait that is due, or shorter than a worker sleeps out, is no wait
    // here: such an entry follows the network rule like any other (a
    // chunked hand-over can keep a sibling part's short wait).
    fun longWait(e: QueueEntry) = e.nextAttemptAt?.let { it - now > RetryClassifier.IN_WORKER_BACKOFF_MAX_MS } == true
    val idle = queued.filter { !longWait(it) && it.id !in clearedIds }
    val waiting = queued.filter { longWait(it) && it.id !in clearedIds }
    if (idle.isNotEmpty() || waiting.isNotEmpty()) {
      val rows = runCatching { scheduler.rowSummary() }.onFailure(::failed).getOrNull()
      idle.filter { rows == null || it.id !in rows.startable }
        .forEach { e -> pass.schedule { scheduleRun(e, probe = probe) } }
      // A wait that stays needs its wake. KEEP leaves one that exists, so
      // this is at most one enqueue per entry that lacks a wake.
      waiting.filter { rows == null || it.id !in rows.withWake }
        .forEach { e -> pass.schedule { scheduler.scheduleWake(e, e.nextAttemptAt!!, replace = false) } }
    }
    cleared.forEach { events.state(it.toRow()) }
    pass.finish()
  }

  private fun inScope(e: QueueEntry, keys: List<String>?) = keys == null || e.key in keys

  /**
   * A doorbell row ran: WorkManager saw a network that fits its rule. Each
   * queued entry that waits for a network ([requiredNetwork]), has no row
   * that can start now and no worker, is started when the network fits it
   * now; for the others the doorbell of their rule is made sure again (a
   * doorbell that runs this gets one queued behind it). One WorkManager
   * query, and at most two network checks. Returns false when an entry
   * save, a WorkManager call or the query failed: the doorbell worker then
   * returns retry, so its row runs again.
   */
  fun answerDoorbell(rung: RequiredNetwork? = null): Boolean {
    val now = clock()
    val s = settings.load()
    val waiting = store.all().filter { requiredNetwork(it, s, now) != RequiredNetwork.NONE }
    if (waiting.isEmpty()) return true
    val pass = SchedulePass("doorbell")
    val rows = try {
      scheduler.rowSummary()
    } catch (error: Exception) {
      pass.failed(error)
      pass.finish(repair = false)
      return false
    }
    // WorkManager saw [rung]'s network. When the library's check does not,
    // the doorbell queued behind this one waits DOORBELL_REPEAT_MS.
    val probe = NetworkProbe(repeatOf = rung, awaitRows = true)
    // Only an ENQUEUED row that starts now covers an entry. A worker of the
    // entry, or a RUNNING row, may be one that handed over and is on its way
    // out; schedule() then queues one row behind it, and the per-id gate
    // runs them one after the other. The pass stops at the first
    // WorkManager timeout: the doorbell returns retry and rings again.
    waiting.filter { it.id !in rows.due }.forEach { e -> pass.schedule { scheduleRun(e, probe = probe) } }
    pass.finish(repair = false)
    return !pass.anyFailed
  }

  /**
   * One pass: the network check at most once per Wi-Fi rule (a check that
   * throws reads as no network), and each doorbell made sure at most once.
   * A doorbell that could not be stored is not tried again in the pass: its
   * entries get their main row. [repeatOf]: the doorbell that is answering;
   * one made for its own rule waits DOORBELL_REPEAT_MS. [awaitRows]: wait
   * until each main row is stored.
   */
  private inner class NetworkProbe(val repeatOf: RequiredNetwork? = null, val awaitRows: Boolean = false) {
    private val answers = mutableMapOf<Boolean, Boolean>()

    /** The doorbells already made sure in this pass. */
    val ensured = mutableSetOf<RequiredNetwork>()

    /** The doorbells that could not be stored in this pass. */
    val failed = mutableSetOf<RequiredNetwork>()

    fun fits(wifiOnly: Boolean): Boolean =
      answers.getOrPut(wifiOnly) { runCatching { networkFits(wifiOnly) }.getOrDefault(false) }
  }

  /**
   * Live: journal a 'cancelled' (user) outcome, then forget after its ack.
   * Settled (or legacy): forget now, row, bytes, and its unacknowledged
   * outcomes. No row: delete the id's directory when it has no entry file
   * ([QueueStore.removeUnowned]).
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
        stop = true // no row or settled: stop any stray work
        val e = store.load(id) ?: run {
          store.removeUnowned(id)
          return@locked
        }
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
          throw QueueException(
            QueueException.E_STORAGE,
            "could not journal the cancel: ${error.message}" + StorageCause.suffix(error, withMessage = false),
            error,
          )
        }
        stop = true
        // The journaled record is the cancel. A failed save does not undo
        // it: the ack, the next cancel(), or the boot sweep applies the
        // record. So cancel resolves, as on iOS; it rejects only when
        // nothing changed.
        val next = EntryTransitions.toSettled(e, EntryState.CANCELLED, record.eventId, e.bytesSent, now)
        try {
          store.save(next)
          saved = next
        } catch (error: IOException) {
          Diag.error("cancel journaled '$id' but could not save it; the record is applied later", error)
        }
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

  /**
   * The settings calls (this one, [pause], [resume], [configureRetry],
   * [updateHeaders]) never throw for a failed settings write. See
   * [QueueSettingsStore.update].
   *
   * A change to off moves the queued entries that follow the setting from
   * the Wi-Fi rule to any network (see below). The same value, or a change
   * to on, schedules nothing.
   */
  fun setWifiOnly(enabled: Boolean) {
    val before = settings.load().wifiOnly
    val after = settings.update { it.copy(wifiOnly = enabled) }.wifiOnly
    if (!before || after) return
    // Off: the entries that follow the setting now wait for any network.
    // Each one with no long wait starts when that fits, else it waits on
    // the CONNECTED doorbell. A worker of the entry may be one that handed
    // over and is on its way out: schedule() queues one row behind it. A
    // Wi-Fi doorbell left behind finds nothing to start and ends. On needs
    // no work: a run that starts on another network fails the check and
    // hands over to the Wi-Fi doorbell.
    val now = clock()
    val s = settings.load()
    val probe = NetworkProbe()
    val pass = SchedulePass("setWifiOnly")
    store.all()
      .filter { it.descriptor?.wifiOnly == null && requiredNetwork(it, s, now) != RequiredNetwork.NONE }
      .forEach { e -> pass.schedule { scheduleRun(e, probe = probe) } }
    pass.finish()
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
   *
   * A failed entry save throws E_STORAGE after every other entry is patched
   * and the unparked ones are scheduled. The entry that failed keeps its old
   * headers on disk and stays parked if it was. A later call patches it.
   */
  fun updateHeaders(patch: Map<String, String>) {
    // Above every entry's value too. After a failed settings write and a
    // relaunch, the file can hold an older generation than the entries, and
    // a 401 under the newest one must still see newer headers.
    val entriesMax = store.all().maxOfOrNull { it.headerGeneration } ?: 0
    val s = settings.update { it.copy(headerGeneration = maxOf(it.headerGeneration, entriesMax) + 1) }
    val now = clock()
    val unparkedIds = mutableSetOf<String>()
    val result = transformAll { e ->
      val patched = e.withHeadersPatched(patch, s.headerGeneration)
      if (patched.state != EntryState.AWAITING_AUTH) return@transformAll patched
      unparkedIds += e.id
      EntryTransitions.toUnparked(patched, s.isPaused(patched.key), now)
    }
    val pass = SchedulePass("updateHeaders")
    result.changed.filter { it.id in unparkedIds }.forEach { entry ->
      pass.schedule { scheduleRun(entry) }
      events.state(entry.toRow())
    }
    pass.finish()
    result.throwIfFailed("updateHeaders")
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
   *    A queued entry whose wait may be cleared ([EntryTransitions.clearsWait])
   *    runs now, with at most 5 backoff steps kept. When only that save
   *    fails, the entry is scheduled as it was found.
   * 3. A settled entry with records of its generation other than its own:
   *    orphans from a cancel race. Ack them.
   * 4. A completed or cancelled entry whose own record is gone: the ack
   *    landed but the forget did not. Forget it.
   * 5. Schedule every queued entry, and the expiry wake of every parked one.
   *    A row that WorkManager holds back after a stop is replaced
   *    ([WorkScheduler.schedule]), so each queued entry can start now.
   *
   * 6. Delete the orphan directories that hold only copied bodies and are
   *    older than [QueueStore.ORPHAN_MIN_AGE_MS] ([QueueStore.removeOrphans]).
   *
   * Then it writes the boot snapshot, on every start. The leftover count
   * and step 6 run after step 5, so they never delay queued work. The
   * count costs one directory listing and a few file checks per id
   * directory, less than the entry reads above. Step 6 looks only at the
   * orphans the count listed: one more listing of each, and the deletes.
   *
   * Step 6 can not delete the directory of an enqueue in progress: the
   * sweep and every enqueue run on the same single-thread executor
   * ([UploaderModule.queueExecutor]), so no enqueue is between staging and
   * its entry save while step 6 runs. Step 6 also checks for `entry.json`
   * under the store lock, which every entry save takes.
   */
  fun sweep() {
    val now = clock()
    val changed = mutableListOf<QueueEntry>()
    val toForget = mutableListOf<String>()
    val toSchedule = mutableListOf<QueueEntry>()
    // toSchedule as the sweep found it, before toStopped or toResumed reset
    // state and updatedAt. The boot snapshot describes these.
    val found = mutableListOf<QueueEntry>()
    val legacyIds = mutableListOf<String>()
    val s = settings.load()
    store.locked {
      val records = journal.unacknowledged().groupBy { it.id }
      for (e in store.all()) {
        if (e.legacy) legacyIds += e.id
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
            cur = EntryTransitions.toResumed(cur, now)
          }
          // The app started: a queued entry whose wait may be cleared runs now.
          val repaired = cur
          if (EntryTransitions.clearsWait(cur, now)) {
            cur = EntryTransitions.toRetryNow(cur, now)
          }
          if (cur !== e) {
            cur = when {
              trySave(cur) -> cur
              // Only the clear failed to save: go on as found. The wake
              // path (KEEP) below makes a lost wake again.
              repaired === e -> e
              cur !== repaired && trySave(repaired) -> repaired
              else -> continue
            }
            if (cur !== e) changed += cur
          }
          if (!paused || cur.state == EntryState.AWAITING_AUTH) {
            toSchedule += cur
            found += e
          }
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
    // Read before the cleanup below changes it: this is what an earlier
    // process left in WorkManager.
    val runs = runCatching { scheduler.libraryRuns() }.getOrNull()
    toForget.forEach { scheduler.cancel(it) }
    val probe = NetworkProbe()
    val pass = SchedulePass("boot sweep")
    toSchedule.forEach { e -> pass.schedule { scheduleRun(e, keepWake = true, probe = probe) } }
    // Every start, even an idle one: a v9 leftover count of zero is a value
    // the app reports. It replaces a snapshot JS has not taken yet, so
    // launches with no takeDiagnostics() do not push out real warnings.
    // The orphan cleanup runs here, after the work above is scheduled, on
    // this executor, so no enqueue is staging meanwhile.
    val counted = store.countLeftovers(legacyIds)
    val removed = store.removeOrphans(counted.orphanDirs, now)
    Diag.info(
      BootSnapshot.MESSAGE,
      BootSnapshot.of(found, runs, now, counted.afterCleanup(removed), s),
      replaceUntaken = true,
    )
    changed.forEach { events.state(it.toRow()) }
    pass.finish()
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
   * 'expired' on time, once that expiry is near (see [EXPIRY_WAKE_HORIZON_MS]).
   * Any other wake of the entry is stale, so it is cancelled.
   *
   * With a [probe] (a repair: the boot sweep, resume(), a return to the
   * foreground, a same-id enqueue, setWifiOnly(), a doorbell), an entry
   * whose network does not fit now gets no row: it waits on the doorbell of
   * its rule, so an offline app start starts no run. When the doorbell
   * cannot be stored, the entry gets its main row after all: that run waits
   * for the network and hands over again. Without a probe (new work, new
   * headers) the run starts in any case. API 29 has no doorbell.
   */
  private fun scheduleRun(entry: QueueEntry, keepWake: Boolean = false, probe: NetworkProbe? = null) {
    val now = clock()
    when (entry.state) {
      EntryState.QUEUED -> {
        val at = entry.nextAttemptAt
        if (at != null && at - now > RetryClassifier.IN_WORKER_BACKOFF_MAX_MS) {
          scheduler.scheduleWake(entry, at, replace = !keepWake)
        } else if (probe != null && waitsOnDoorbell(entry, probe, now)) {
          cancelStaleWake(entry.id)
        } else {
          // The main row first: these are two separate WorkManager calls,
          // and a process death between them must not leave the entry
          // with neither a wake nor a main row.
          if (probe?.awaitRows == true) scheduler.scheduleAndWait(entry) else scheduler.schedule(entry)
          cancelStaleWake(entry.id)
        }
      }
      EntryState.AWAITING_AUTH ->
        if (expiryWakeIsNear(entry.expiresAt, now)) {
          scheduler.scheduleWake(entry, entry.expiresAt, replace = !keepWake)
        } else {
          cancelStaleWake(entry.id)
        }
      else -> Unit
    }
  }

  /**
   * The scheduling of one pass. A WorkManager call that fails is caught per
   * entry, so the pass goes on and the caller does not reject. At the first
   * timeout (a WorkManager that does not answer) the pass stops scheduling:
   * each further call would hold the queue executor, which also serves
   * every JS call, for 10 s more. [finish] writes one diagnostics record
   * for the pass, and asks for [repairRows] later, which starts the entries
   * the pass left with no row.
   */
  private inner class SchedulePass(val what: String) {
    private var failures = 0
    private var skipped = 0
    private var first: Throwable? = null
    private var stopped = false

    val anyFailed get() = failures + skipped > 0

    fun failed(error: Throwable) {
      failures++
      if (first == null) first = error
      if (isTimeout(error)) stopped = true
    }

    fun schedule(block: () -> Unit) {
      if (stopped) {
        skipped++
        return
      }
      try {
        block()
      } catch (error: Exception) {
        failed(error)
      }
    }

    fun finish(repair: Boolean = true) {
      if (!anyFailed) return
      val tail = if (stopped) "; stopped at a WorkManager timeout with $skipped left" else ""
      Diag.error("$what: $failures entry saves or WorkManager calls failed$tail; the queue tries again", first)
      if (repair) requestRepair()
    }
  }

  private fun isTimeout(error: Throwable) =
    generateSequence(error) { it.cause }.any { it is java.util.concurrent.TimeoutException }

  /** Asks for one [repairRows] run later; one is pending at a time. The wait doubles after each failed repair. */
  private fun requestRepair() {
    if (!repairPending.compareAndSet(false, true)) return
    repairLater(repairDelayMs) {
      repairPending.set(false)
      repairRows()
    }
  }

  /**
   * Starts every queued entry that has nothing to start it: after a pass
   * whose WorkManager calls failed. One WorkManager query. An entry with
   * no long wait and no row that can start now is scheduled as a repair
   * (its main row when the network fits, else its doorbell); an entry with
   * a long wait and no wake gets its wake (KEEP); a parked entry gets its
   * expiry wake. It does not end a backoff wait. When it fails too, it
   * asks for itself again, later each time, up to [REPAIR_MAX_MS].
   */
  fun repairRows() {
    val now = clock()
    val entries = store.all().filter { !it.legacy && (it.state == EntryState.QUEUED || it.state == EntryState.AWAITING_AUTH) }
    val pass = SchedulePass("repair")
    if (entries.isNotEmpty()) {
      val rows = try {
        scheduler.rowSummary()
      } catch (error: Exception) {
        pass.failed(error)
        null
      }
      if (rows != null) {
        val probe = NetworkProbe()
        for (e in entries) {
          val at = e.nextAttemptAt
          val longWait = at != null && at - now > RetryClassifier.IN_WORKER_BACKOFF_MAX_MS
          when {
            e.state == EntryState.AWAITING_AUTH || longWait ->
              if (e.id !in rows.withWake) pass.schedule { scheduleRun(e, keepWake = true) }
            e.id !in rows.startable -> pass.schedule { scheduleRun(e, probe = probe) }
          }
        }
      }
    }
    repairDelayMs = if (pass.anyFailed) minOf(repairDelayMs * 2, REPAIR_MAX_MS) else REPAIR_FIRST_MS
    pass.finish()
  }

  /**
   * True when [entry]'s network does not fit now and its doorbell is
   * stored. False when it fits, on API 29, or when the doorbell failed.
   */
  private fun waitsOnDoorbell(entry: QueueEntry, probe: NetworkProbe, now: Long): Boolean {
    if (!doorbells) return false
    val network = requiredNetwork(entry, settings.load(), now)
    if (network == RequiredNetwork.NONE || probe.fits(network == RequiredNetwork.WIFI)) return false
    if (network in probe.ensured) return true
    if (network in probe.failed) return false
    val delay = if (network == probe.repeatOf) WorkManagerScheduler.DOORBELL_REPEAT_MS else 0L
    return runCatching { scheduler.ensureDoorbell(network, delay) }
      .onSuccess { probe.ensured += network }
      .onFailure {
        // A WorkManager that does not answer: the pass stops (SchedulePass).
        if (isTimeout(it)) throw it
        // One record per pass: the other entries of this rule skip the doorbell.
        probe.failed += network
        Diag.error("could not store the ${network.name} doorbell; its waiting entries start a run instead", it)
      }
      .isSuccess
  }

  // A running wake is the entry's worker; it finishes its own run.
  // [isWorkerRunning] reads the in-process gate, so it assumes WorkManager
  // runs workers in the app's process. The library sets no android:process.
  //
  // The caller's entry can be a stale snapshot: a worker may have released
  // the entry with a long wait and made its wake since. So the stored entry
  // is read again, and a wake it still needs is kept.
  private fun cancelStaleWake(id: String) {
    if (isWorkerRunning(id)) return
    val stored = runCatching { store.load(id) }.getOrNull()
    val at = stored?.nextAttemptAt
    if (stored?.state == EntryState.QUEUED && at != null && at - clock() > RetryClassifier.IN_WORKER_BACKOFF_MAX_MS) return
    scheduler.cancelWake(id)
  }

  /** The entries [transformAll] saved, and the saves that failed. */
  private class Transformed(val changed: List<QueueEntry>, val failures: Int, val firstFailure: IOException?) {
    /** Throws E_STORAGE when an entry save failed. Call it after the work for [changed] is done. */
    fun throwIfFailed(method: String) {
      val e = firstFailure ?: return
      throw QueueException(
        QueueException.E_STORAGE,
        "$method: could not save $failures entries: ${e.message}" + StorageCause.suffix(e, withMessage = false),
        e,
      )
    }
  }

  /**
   * Applies [transform] to every non-legacy entry under the store lock. An
   * entry whose save fails keeps its old row, and the loop goes on, so one
   * full-disk failure does not leave the other entries unchanged.
   */
  private fun transformAll(transform: (QueueEntry) -> QueueEntry): Transformed {
    val changed = mutableListOf<QueueEntry>()
    var failures = 0
    var first: IOException? = null
    store.locked {
      store.all().filter { !it.legacy }.forEach { e ->
        try {
          var next: QueueEntry? = null
          store.compute(e.id) { cur ->
            if (cur == null) null else transform(cur).also { if (it !== cur) next = it }
          }
          next?.let { changed += it } // only once the save landed
        } catch (error: IOException) {
          failures++
          if (first == null) first = error
        }
      }
    }
    return Transformed(changed, failures, first)
  }
}
