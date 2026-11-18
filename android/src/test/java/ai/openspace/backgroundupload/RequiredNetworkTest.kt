package ai.openspace.backgroundupload

import org.junit.Assert.assertEquals
import org.junit.Test

/** The doorbell an entry waits for, and what the in-worker network check accepts. */
class RequiredNetworkTest {
  private val now = 10_000L

  @Test
  fun `the doorbell an entry waits for, for each entry and setting`() {
    // state, nextAttemptAt, the entry's own wifiOnly, the queue's wifiOnly → the doorbell.
    val table = listOf(
      Row(EntryState.QUEUED, null, null, false, RequiredNetwork.CONNECTED),
      Row(EntryState.QUEUED, null, null, true, RequiredNetwork.WIFI),
      Row(EntryState.QUEUED, null, true, false, RequiredNetwork.WIFI),
      Row(EntryState.QUEUED, null, false, true, RequiredNetwork.CONNECTED),
      // A short wait, or one already over, keeps the rule (a chunked sibling's backoff at a hand-over).
      Row(EntryState.QUEUED, now + 20_000, null, true, RequiredNetwork.WIFI),
      Row(EntryState.QUEUED, now - 60_000, null, false, RequiredNetwork.CONNECTED),
      // A long wait: its wake starts it, not a doorbell.
      Row(EntryState.QUEUED, now + 60_000, null, true, RequiredNetwork.NONE),
      Row(EntryState.RUNNING, null, null, true, RequiredNetwork.NONE),
      Row(EntryState.PAUSED, null, null, true, RequiredNetwork.NONE),
      Row(EntryState.AWAITING_AUTH, null, null, true, RequiredNetwork.NONE),
      Row(EntryState.ERROR, null, null, false, RequiredNetwork.NONE),
    )
    for (r in table) {
      val e = entry(state = r.state, nextAttemptAt = r.nextAttemptAt, descriptor = desc(dataJson = """{"a":1}""", wifiOnly = r.own))
      assertEquals(r.toString(), r.expected, requiredNetwork(e, QueueSettings(wifiOnly = r.queue), now))
    }
    assertEquals(RequiredNetwork.NONE, requiredNetwork(entry(legacy = true), QueueSettings(), now))
  }

  private data class Row(
    val state: EntryState,
    val nextAttemptAt: Long?,
    val own: Boolean?,
    val queue: Boolean,
    val expected: RequiredNetwork,
  )

  @Test
  fun `the network check, also while the process is bound to a network`() {
    // wifiOnly, default validated, default is Wi-Fi, the bound network is Wi-Fi (null: not bound) → result.
    val table = listOf(
      Check(false, true, false, null, Connectivity.Ok),
      Check(false, false, true, null, Connectivity.NoInternet),
      Check(true, true, true, null, Connectivity.Ok),
      Check(true, true, false, null, Connectivity.NoWifi),
      Check(true, false, true, null, Connectivity.NoWifi),
      // Bound to cellular: its sockets use cellular, so a Wi-Fi-only upload waits; the default network fits.
      Check(true, true, true, false, Connectivity.BoundElsewhere),
      Check(true, true, false, false, Connectivity.NoWifi),
      Check(true, true, true, true, Connectivity.Ok),
      // Not Wi-Fi only: the bind does not change the answer.
      Check(false, true, true, false, Connectivity.Ok),
      Check(false, false, true, false, Connectivity.NoInternet),
    )
    for (c in table) {
      assertEquals(c.toString(), c.expected, connectivityOf(c.wifiOnly, c.validated, c.defaultWifi, c.boundWifi))
    }
  }

  @Test
  fun `a network check that throws reads as no network`() {
    assertEquals(Connectivity.NoInternet, safeConnectivity(false) { throw SecurityException("no permission") })
    assertEquals(Connectivity.NoWifi, safeConnectivity(true) { throw IllegalStateException("binder died") })
    assertEquals(Connectivity.Ok, safeConnectivity(true) { Connectivity.Ok })
  }

  private data class Check(
    val wifiOnly: Boolean,
    val validated: Boolean,
    val defaultWifi: Boolean,
    val boundWifi: Boolean?,
    val expected: Connectivity,
  )
}
