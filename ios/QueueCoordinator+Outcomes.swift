import Foundation

// Terminal outcomes: settle, ack, forget, and the repair paths for a settled
// row whose journal file is missing. Runs on the coordinator queue.
extension QueueCoordinator {
  /// First wait before a failed journal write is tried again. It doubles up
  /// to `journalRetryMaxMs`.
  static let journalRetryMs = 5_000
  static let journalRetryMaxMs = 600_000

  /// The one terminal path. Cancels the entry's remaining tasks, emits the
  /// trailing progress, journals the outcome, saves the settled row, emits
  /// `state`, then emits `settled` with deliveries 1 when a listener exists.
  /// With no listener the outcome stays at deliveries 0 for the drain.
  ///
  /// A failed journal write still settles and emits: the request already
  /// ran, so a retry would send it twice, and a user cancel must not run
  /// again. The event stays in memory, a timer retries the write, and ack
  /// finds the row by its settledEventId.
  func settle(_ id: String, _ outcome: Outcome) {
    guard var e = index.entry(id) else { return }
    cancelTasks(id, purpose: .superseded)
    chunked.stop(id)
    switch outcome {
    case .completed: e.bytesSent = e.totalBytes
    // A simple entry keeps the live bytes of its last attempt.
    default: if e.isChunked { e.bytesSent = e.acceptedBytes }
    }
    emitProgress(id, sent: e.bytesSent, total: e.totalBytes)

    var event = JournaledEvent(
      eventId: UUID().uuidString, id: id, key: e.key, varsJSON: e.varsJSON, at: now(),
      attempts: e.attempts, requestId: e.lastRequestId, deliveries: 0, bytesSent: e.bytesSent,
      totalBytes: e.totalBytes, url: e.targetURL, method: e.method, partIndex: e.lastPartIndex,
      generation: e.generation, kind: .completed)
    switch outcome {
    case .completed(let response):
      event.kind = .completed
      event.response = response
      e.state = .completed
    case .error(let error):
      event.kind = .error
      event.error = error
      event.partIndex = error.partIndex ?? e.lastPartIndex
      e.state = .error
    case .cancelled(let reason):
      event.kind = .cancelled
      event.cancelReason = reason
      event.partIndex = nil
      e.state = .cancelled
    }
    let journaled = journal.append(event, keeping: referencedEventIds().union([event.eventId]))
    e.settledEventId = event.eventId
    e.nextAttemptAt = nil
    e.authParked = false
    commit(e)
    // Checked after the append. A drain that ran before this line already
    // set the flag; one that runs after reads the journal and counts it.
    let live = sink?.canDeliver() == true
    if journaled {
      if live {
        var delivered = journal.markDelivered([event.eventId]).first ?? event
        delivered.deliveries = max(delivered.deliveries, 1)
        sink?.emitSettled(delivered.bridged)
      }
    } else {
      event.deliveries = live ? 1 : 0
      pendingJournal[event.eventId] = event
      retryJournal(event.eventId, delayMs: Self.journalRetryMs)
      if live { sink?.emitSettled(event.bridged) }
    }
    disarmExpiry(id)
    throttle.reset(id)
  }

  /// A settle whose journal write landed but whose entry.json save failed
  /// leaves a live row on disk for an outcome that already happened. This
  /// moves the row to that outcome, with no emit: the journal drain
  /// delivers it. Returns the settled row, or nil when `event` is not the
  /// outcome of this row's generation.
  @discardableResult
  func applyJournaled(_ event: JournaledEvent, to e: QueueEntry) -> QueueEntry? {
    guard e.isLive, !e.legacy, event.id == e.id, event.generation == e.generation else { return nil }
    var n = e
    switch event.kind {
    case .completed: n.state = .completed
    case .error: n.state = .error
    case .cancelled: n.state = .cancelled
    }
    n.settledEventId = event.eventId
    n.bytesSent = event.bytesSent
    n.nextAttemptAt = nil
    n.authParked = false
    cancelTasks(e.id, purpose: .superseded)
    chunked.stop(e.id)
    disarmExpiry(e.id)
    commit(n, emit: false)
    return n
  }

  /// Relaunch, before any task is matched: every live row whose own
  /// generation already has an unacked outcome takes that outcome, so it is
  /// never sent again.
  func repairLostSettles() {
    let byId = Dictionary(grouping: journal.unacknowledged(), by: \.id)
    for e in index.entries() where e.isLive && !e.legacy {
      guard let event = byId[e.id]?.last(where: { $0.generation == e.generation }) else { continue }
      applyJournaled(event, to: e)
    }
  }

  /// Deletes the row and the bytes. `dropEvents` also deletes the id's
  /// unacked outcomes (cancel on a settled entry).
  func forget(_ id: String, dropEvents: Bool) {
    cancelTasks(id, purpose: .superseded)
    chunked.stop(id)
    store.remove(id)
    index.remove(id)
    if dropEvents {
      journal.removeForId(id)
      pendingJournal = pendingJournal.filter { $0.value.id != id }
    }
    disarmExpiry(id)
    throttle.reset(id)
  }

  /// One ack. The event comes from the journal, or from memory when its
  /// write failed. When neither has it (pruned, or a crash after the file
  /// went), the row that names the eventId still settles.
  func ackLocked(_ eventId: String) {
    let event = journal.load(eventId) ?? pendingJournal[eventId]
    pendingJournal[eventId] = nil
    journal.ack([eventId])
    var owner = event.flatMap { index.entry($0.id) }
      ?? index.entries().first { $0.settledEventId == eventId }
    // An ack can run before the relaunch repair: settle a live row first.
    if let event, let e = owner, let settled = applyJournaled(event, to: e) { owner = settled }
    guard let e = owner, e.isSettled, !e.legacy, e.state != .error else { return }
    if let event {
      guard event.kind != .error, event.generation == e.generation else { return }
    } else {
      guard e.settledEventId == eventId else { return }
    }
    forget(e.id, dropEvents: false)
  }

  /// Every unacked outcome, oldest first, the in-memory ones included. Each
  /// return counts as a delivery.
  func unacknowledgedLocked() -> [JournaledEvent] {
    let stored = journal.markDelivered(journal.unacknowledged().map(\.eventId))
    for (eventId, var event) in pendingJournal {
      event.deliveries += 1
      pendingJournal[eventId] = event
    }
    return (stored + pendingJournal.values).sorted { ($0.at, $0.eventId) < ($1.at, $1.eventId) }
  }

  /// A re-emit (same-id rule 7). Counts a delivery.
  func redeliver(_ eventId: String) -> JournaledEvent? {
    if let event = journal.markDelivered([eventId]).first { return event }
    guard var event = pendingJournal[eventId] else { return nil }
    event.deliveries += 1
    pendingJournal[eventId] = event
    return event
  }

  /// The settled outcome of `e`, from the journal or from memory.
  func settledEvent(_ e: QueueEntry) -> JournaledEvent? {
    e.settledEventId.flatMap { journal.load($0) ?? pendingJournal[$0] }
  }

  /// Relaunch repair: a completed or cancelled row whose outcome file is
  /// gone can never be acked. That happens after a crash between the ack's
  /// delete and the forget, or when the journal write failed and the process
  /// died. Forget the row and its bytes. An error row stays, as it does
  /// after an ack, until cancel() or a same-id enqueue.
  func sweepOrphanedOutcomes() {
    for e in index.entries() where e.isSettled && !e.legacy && e.state != .error {
      guard let eventId = e.settledEventId, pendingJournal[eventId] == nil,
            journal.load(eventId) == nil else { continue }
      forget(e.id, dropEvents: false)
    }
  }

  /// Every eventId a row still names. The journal never prunes these.
  func referencedEventIds() -> Set<String> {
    Set(index.entries().compactMap(\.settledEventId))
  }

  /// Tries a failed journal write again, with doubling waits, while the
  /// entry still names the event. An outcome the entry no longer names (an
  /// ack, a cancel, a reopen) is dropped from memory.
  private func retryJournal(_ eventId: String, delayMs: Int) {
    schedule(delayMs) { [weak self] in
      guard let self, let event = self.pendingJournal[eventId] else { return }
      guard self.index.entry(event.id)?.settledEventId == eventId else {
        self.pendingJournal[eventId] = nil
        return
      }
      if self.journal.append(event, keeping: self.referencedEventIds()) {
        self.pendingJournal[eventId] = nil
      } else {
        self.retryJournal(eventId, delayMs: min(delayMs * 2, Self.journalRetryMaxMs))
      }
    }
  }
}
