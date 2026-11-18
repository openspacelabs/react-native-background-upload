package ai.openspace.backgroundupload

/**
 * What the boot sweep found, as one diagnostic record: the entries it
 * schedules, and every unfinished WorkManager run of the library before the
 * sweep changes them. Stale wakes show as delayed runs far in the future.
 * A queued or running entry with no run at all shows in entriesWithoutRun.
 * v9Leftovers counts the v9 work still on disk, and orphans the
 * directories with no entry file that are not v9: what the boot cleanup
 * left, and what it deleted. Zeros included. wifiOnly is the queue's
 * setting, and entriesByWake counts the queued entries by the wake they
 * sleep on: a backoff wait, the network, or Wi-Fi.
 */
internal object BootSnapshot {
  const val MESSAGE = "boot sweep"

  /**
   * [entries]: the entries the sweep schedules, as it found them, so an
   * entry that was running when its process died still reads as running
   * with its old updatedAt. [runs]: null when WorkManager could not be read.
   */
  fun of(
    entries: List<QueueEntry>,
    runs: List<PendingRun>?,
    now: Long,
    leftovers: Leftovers,
    wifiOnly: Boolean,
  ): Map<String, Any?> {
    val base = mapOf(
      "entries" to entries.size,
      "entriesByState" to entries.groupingBy { it.state.wire }.eachCount(),
      "entriesByKey" to entries.groupingBy { it.key }.eachCount(),
      "oldestUpdatedAt" to entries.minOfOrNull { it.updatedAt },
      "v9Leftovers" to leftovers.v9.toMap(),
      "orphans" to leftovers.orphans.toMap(),
      "wifiOnly" to wifiOnly,
    )
    if (runs == null) return base + ("runsUnavailable" to true)
    val withRun = runs.mapNotNull { it.entryId }.toSet()
    return base + mapOf(
      "entriesWithoutRun" to entries.count {
        (it.state == EntryState.QUEUED || it.state == EntryState.RUNNING) && it.id !in withRun
      },
      "runs" to runs.size,
      "runsByState" to runs.groupingBy { it.state }.eachCount(),
      "delayedRuns" to runs.count { it.delayed },
      "delayedPastHorizon" to runs.count { run ->
        run.delayed && run.nextScheduleAt != null && run.nextScheduleAt - now > EXPIRY_WAKE_HORIZON_MS
      },
      "maxRunAttemptCount" to runs.maxOfOrNull { it.runAttemptCount },
      "entriesByWake" to entriesByWake(entries, runs),
    )
  }

  /**
   * Each queued entry once, by its wake: "backoff" a delayed wake (a
   * backoff wait), else "wifi" a wake that waits for Wi-Fi, else "network"
   * one that waits for any network. An entry with none of them is not
   * counted. Only a wake has a delay or a network rule.
   */
  private fun entriesByWake(entries: List<QueueEntry>, runs: List<PendingRun>): Map<String, Int> {
    val byEntry = runs.filter { it.entryId != null }.groupBy { it.entryId }
    val counts = linkedMapOf("backoff" to 0, "network" to 0, "wifi" to 0)
    for (e in entries) {
      if (e.state != EntryState.QUEUED) continue
      val own = byEntry[e.id].orEmpty()
      val kind = when {
        own.any { it.delayed } -> "backoff"
        own.any { it.network == RequiredNetwork.WIFI } -> "wifi"
        own.any { it.network == RequiredNetwork.CONNECTED } -> "network"
        else -> continue
      }
      counts[kind] = counts.getValue(kind) + 1
    }
    return counts
  }
}
