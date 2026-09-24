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
  /// `state`, then emits `settled` with deliveries 1.
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
    default: e.bytesSent = e.isChunked ? e.acceptedBytes : (lastSent[id] ?? e.bytesSent)
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
    var delivered: JournaledEvent
    if journaled {
      delivered = journal.markDelivered([event.eventId]).first ?? event
    } else {
      event.deliveries = 1
      pendingJournal[event.eventId] = event
      retryJournal(event.eventId, delayMs: Self.journalRetryMs)
      delivered = event
    }
    delivered.deliveries = max(delivered.deliveries, 1)
    sink?.emitSettled(delivered.bridged)
    disarmExpiry(id)
    throttle.reset(id)
    lastSent[id] = nil
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
    lastSent[id] = nil
  }

  /// One ack. The event comes from the journal, or from memory when its
  /// write failed. When neither has it (pruned, or a crash after the file
  /// went), the row that names the eventId still settles.
  func ackLocked(_ eventId: String) {
    let event = journal.load(eventId) ?? pendingJournal[eventId]
    pendingJournal[eventId] = nil
    journal.ack([eventId])
    let owner = event.flatMap { index.entry($0.id) }
      ?? index.entries().first { $0.settledEventId == eventId }
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
