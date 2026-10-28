package ai.openspace.backgroundupload

/**
 * What the boot sweep found, as one diagnostic record: the entries it
 * schedules, and every unfinished WorkManager run of the library before the
 * sweep changes them. Stale wakes show as delayed runs far in the future.
 * A queued entry with no run at all shows in entriesWithoutRun.
 */
internal object BootSnapshot {
  const val MESSAGE = "boot sweep"

  /**
   * [entries]: the queued and parked entries the sweep schedules. [runs]:
   * null when WorkManager could not be read.
   */
  fun of(entries: List<QueueEntry>, runs: List<PendingRun>?, now: Long): Map<String, Any?> {
    val base = mapOf(
      "entries" to entries.size,
      "entriesByState" to entries.groupingBy { it.state.wire }.eachCount(),
      "entriesByKey" to entries.groupingBy { it.key }.eachCount(),
      "oldestUpdatedAt" to entries.minOfOrNull { it.updatedAt },
    )
    if (runs == null) return base + ("runsUnavailable" to true)
    val withRun = runs.mapNotNull { it.entryId }.toSet()
    return base + mapOf(
      "entriesWithoutRun" to entries.count { it.state == EntryState.QUEUED && it.id !in withRun },
      "runs" to runs.size,
      "runsByState" to runs.groupingBy { it.state }.eachCount(),
      "delayedRuns" to runs.count { it.delayed },
      "delayedPastHorizon" to runs.count { run ->
        run.delayed && run.nextScheduleAt != null && run.nextScheduleAt - now > EXPIRY_WAKE_HORIZON_MS
      },
      "maxRunAttemptCount" to runs.maxOfOrNull { it.runAttemptCount },
    )
  }
}
