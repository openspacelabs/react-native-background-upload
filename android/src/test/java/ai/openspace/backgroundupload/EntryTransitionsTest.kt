package ai.openspace.backgroundupload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryTransitionsTest {

  @Test
  fun `a settle on a cancelled entry or an older generation is not allowed`() {
    for (accepted in listOf(true, false)) {
      assertTrue(EntryTransitions.canSettle(entry(state = EntryState.RUNNING), 1, accepted))
      assertTrue(EntryTransitions.canSettle(entry(state = EntryState.AWAITING_AUTH), 1, accepted)) // the expiry wake
      assertFalse(EntryTransitions.canSettle(entry(state = EntryState.CANCELLED), 1, accepted))
      assertFalse(EntryTransitions.canSettle(entry(state = EntryState.COMPLETED), 1, accepted))
      assertFalse(EntryTransitions.canSettle(entry(state = EntryState.RUNNING, generation = 2), 1, accepted))
      assertFalse(EntryTransitions.canSettle(null, 1, accepted))
    }
  }

  @Test
  fun `under pause only an accepted response settles`() {
    assertTrue(EntryTransitions.canSettle(entry(state = EntryState.PAUSED), 1, accepted = true))
    assertFalse(EntryTransitions.canSettle(entry(state = EntryState.PAUSED), 1, accepted = false))
  }

  @Test
  fun `a live entry with a record of its own generation settles from the newest one`() {
    val live = entry(state = EntryState.RUNNING, generation = 2)
    val older = record("00000000-0000-0000-0000-0000000000a1", generation = 2, at = 1)
    val newest = record("00000000-0000-0000-0000-0000000000a2", kind = EventJournal.KIND_ERROR, generation = 2, at = 2)
    val otherLife = record("00000000-0000-0000-0000-0000000000a3", generation = 1, at = 3)
    val otherId = record("00000000-0000-0000-0000-0000000000a4", id = "x", generation = 2, at = 4)
    val j = EntryTransitions.journaledSettle(live, listOf(older, newest, otherLife, otherId), now = 9)!!
    assertEquals(EntryState.ERROR, j.entry.state)
    assertEquals(newest.eventId, j.entry.settledEventId)
    assertEquals(listOf(older.eventId), j.extraEventIds)
    assertNull(EntryTransitions.journaledSettle(live, listOf(otherLife, otherId), 9))
    assertNull(EntryTransitions.journaledSettle(entry(state = EntryState.ERROR, generation = 2), listOf(newest), 9))
  }

  @Test
  fun `resume returns to awaiting-auth only when the parked generation is current`() {
    val paused = entry(state = EntryState.PAUSED, parkedGeneration = 3)
    assertEquals(EntryState.AWAITING_AUTH, EntryTransitions.toResumed(paused, headerGeneration = 3, now = 9).state)
    val stale = EntryTransitions.toResumed(paused, headerGeneration = 4, now = 9)
    assertEquals(EntryState.QUEUED, stale.state)
    assertNull(stale.parkedGeneration)
    assertEquals(EntryState.QUEUED, EntryTransitions.toResumed(entry(state = EntryState.PAUSED), 0, 9).state)
  }

  @Test
  fun `pause keeps the parked generation and clears the wake time`() {
    val p = EntryTransitions.toPaused(entry(state = EntryState.AWAITING_AUTH, parkedGeneration = 2, nextAttemptAt = 50), 9)
    assertEquals(EntryState.PAUSED, p.state)
    assertEquals(2, p.parkedGeneration)
    assertNull(p.nextAttemptAt)
  }

  @Test
  fun `park, release, run, stop, settle`() {
    val running = EntryTransitions.toRunning(entry(nextAttemptAt = 5), 9)
    assertEquals(EntryState.RUNNING, running.state)
    assertNull(running.nextAttemptAt)

    val parked = EntryTransitions.toParked(running.copy(backoffStreak = 4), 7, 10)
    assertEquals(EntryState.AWAITING_AUTH, parked.state)
    assertEquals(7, parked.parkedGeneration)
    assertEquals(0, parked.backoffStreak)

    val released = EntryTransitions.toReleased(running, 99, 6, 11)
    assertEquals(EntryState.QUEUED, released.state)
    assertEquals(99L, released.nextAttemptAt)
    assertEquals(6, released.backoffStreak)

    assertEquals(EntryState.QUEUED, EntryTransitions.toStopped(running, 12).state)

    val settled = EntryTransitions.toSettled(parked, EntryState.ERROR, "ev", 5, 13)
    assertEquals("ev", settled.settledEventId)
    assertNull(settled.parkedGeneration)
    assertEquals(13, settled.updatedAt)
  }

  @Test
  fun `a short backoff keeps the row running and shows the time, and the next attempt clears it`() {
    val waiting = EntryTransitions.toBackingOff(entry(state = EntryState.RUNNING), 9_000, 5)
    assertEquals(EntryState.RUNNING, waiting.state)
    assertEquals(9_000L, waiting.nextAttemptAt)
    assertEquals(9_000.0, waiting.toRow().toMap()["nextAttemptAt"])
    val attempt = EntryTransitions.toAttempt(waiting, "req-2", 6)
    assertNull(attempt.nextAttemptAt)
    assertEquals(1, attempt.attempts)
    assertEquals("req-2", attempt.lastRequestId)
    assertFalse(attempt.toRow().toMap().containsKey("nextAttemptAt"))
  }
}

class EnqueueRulesTest {
  private val body = desc(dataJson = """{"a":1}""")
  private val other = desc(dataJson = """{"a":2}""")

  private fun decide(existing: QueueEntry?, incoming: Descriptor = body, hasRecord: Boolean = true, v9: LegacyManifest? = null) =
    EnqueueRules.decide(existing, v9, incoming) { hasRecord }

  @Test
  fun `the same-id table`() {
    assertEquals(EnqueueRules.Action.Create, decide(null))
    val v9 = LegacyManifest("e1", listOf(part(0, 10)), emptyList())
    assertEquals(EnqueueRules.Action.AdoptV9(v9, 1), decide(null, v9 = v9))
    val legacy = entry(legacy = true, descriptor = null, body = null)
    assertEquals(EnqueueRules.Action.Replace, decide(legacy))
    // A legacy row over a v9 manifest: adopt it, one generation up.
    assertEquals(EnqueueRules.Action.AdoptV9(v9, 2), decide(legacy, v9 = v9))
    assertEquals(EnqueueRules.Action.ReEmit("ev"), decide(entry(state = EntryState.COMPLETED, settledEventId = "ev")))
    assertEquals(EnqueueRules.Action.Replace, decide(entry(state = EntryState.COMPLETED, settledEventId = "ev"), hasRecord = false))
    EntryState.values().filter { it != EntryState.COMPLETED }.forEach { state ->
      assertEquals("$state", EnqueueRules.Action.Resume, decide(entry(state = state)))
    }
    assertEquals(EnqueueRules.Action.RejectRunning, decide(entry(state = EntryState.RUNNING), other))
    EntryState.values().filter { it != EntryState.RUNNING }.forEach { state ->
      assertEquals("$state", EnqueueRules.Action.Replace, decide(entry(state = state), other))
    }
  }

  @Test
  fun `resume replaces the metadata, keeps the body and accepted parts`() {
    val parts = listOf(part(0, 10, accepted = true), part(10, 20))
    val stored = entry(
      state = EntryState.AWAITING_AUTH,
      descriptor = desc(url = null, method = "PUT", file = "/f", parts = parts),
      body = StagedBody(StagedBody.CHUNKED, "blob", null, 20),
      attempts = 4,
      parkedGeneration = 1,
    )
    val incoming = parsed(
      descriptor = desc(url = null, method = "PUT", file = "/f", headers = mapOf("Authorization" to "Bearer new"),
        parts = parts.map { it.copy(accepted = false) }),
      varsJson = """{"n":2}""",
      expiresAt = 77,
    )
    val next = EnqueueRules.resumed(stored, incoming, paused = false, headerGeneration = 5, now = 9)
    assertEquals(EntryState.QUEUED, next.state)
    assertEquals(mapOf("Authorization" to "Bearer new"), next.descriptor!!.headers)
    assertTrue(next.descriptor!!.parts!![0].accepted)
    assertEquals(10, next.bytesSent)
    assertEquals(4, next.attempts)
    assertEquals(77, next.expiresAt)
    assertEquals("""{"n":2}""", next.varsJson)
    assertEquals(5, next.headerGeneration)
    assertNull(next.parkedGeneration)
    assertEquals(1, next.generation) // a live entry keeps its life
  }

  @Test
  fun `resume of a settled entry reopens it with a fresh generation and attempts 0`() {
    val settled = entry(state = EntryState.ERROR, settledEventId = "ev", generation = 2, attempts = 3)
    val next = EnqueueRules.resumed(settled, parsed(), false, 0, 9)
    assertEquals(EntryState.QUEUED, next.state)
    assertEquals(3, next.generation)
    assertEquals(0, next.attempts)
    assertNull(next.settledEventId)
  }

  @Test
  fun `resume clears a pending backoff so the entry runs now`() {
    val waiting = entry(state = EntryState.QUEUED, nextAttemptAt = 99_000).copy(backoffStreak = 6)
    val next = EnqueueRules.resumed(waiting, parsed(), false, 0, 9)
    assertNull(next.nextAttemptAt)
    assertEquals(0, next.backoffStreak)
  }

  @Test
  fun `resume of a running entry stays running, and under pause becomes paused`() {
    assertEquals(EntryState.RUNNING, EnqueueRules.resumed(entry(state = EntryState.RUNNING), parsed(), true, 0, 9).state)
    assertEquals(EntryState.PAUSED, EnqueueRules.resumed(entry(state = EntryState.QUEUED), parsed(), true, 0, 9).state)
  }

  @Test
  fun `resume re-applies the json content type`() {
    val next = EnqueueRules.resumed(entry(), parsed(descriptor = desc(dataJson = """{"a":1}""", headers = mapOf())), false, 0, 9)
    assertEquals(mapOf("Content-Type" to "application/json"), next.descriptor!!.headers)
  }

  @Test
  fun `adopting v9 parts carries the flags only for the same parts`() {
    val v9 = LegacyManifest("e1", listOf(part(0, 10, accepted = true), part(10, 20)), emptyList())
    assertTrue(EnqueueRules.adoptedParts(v9, listOf(part(0, 10), part(10, 20)))[0].accepted)
    assertFalse(EnqueueRules.adoptedParts(v9, listOf(part(0, 20)))[0].accepted)
  }

  @Test
  fun `a copied body is staged outside the lock only when no worker can change the decision`() {
    val json = desc(dataJson = """{"a":2}""")
    val create = EnqueueRules.Action.Create
    val replace = EnqueueRules.Action.Replace
    assertEquals(1, EnqueueRules.preStageGeneration(null, create, json))
    assertEquals(1, EnqueueRules.preStageGeneration(null, create, desc(file = "/f")))
    assertEquals(3, EnqueueRules.preStageGeneration(entry(state = EntryState.ERROR, generation = 2), replace, json))
    assertEquals(2, EnqueueRules.preStageGeneration(entry(state = EntryState.PAUSED), replace, json))
    // A worker can take a queued entry meanwhile, and a running one is the worker's.
    assertNull(EnqueueRules.preStageGeneration(entry(state = EntryState.QUEUED), replace, json))
    assertNull(EnqueueRules.preStageGeneration(entry(state = EntryState.RUNNING), replace, json))
    // A chunked move and a bodiless request stage under the lock.
    assertNull(EnqueueRules.preStageGeneration(null, create, desc(url = null, file = "/f", parts = listOf(part(0, 10)))))
    assertNull(EnqueueRules.preStageGeneration(null, create, desc()))
    // Nothing to stage.
    assertNull(EnqueueRules.preStageGeneration(entry(), EnqueueRules.Action.Resume, json))
    assertNull(EnqueueRules.preStageGeneration(entry(), EnqueueRules.Action.RejectRunning, json))
  }
}
