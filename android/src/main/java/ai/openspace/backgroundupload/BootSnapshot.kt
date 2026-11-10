package ai.openspace.backgroundupload

/**
 * What the boot sweep found, as one diagnostic record: the entries it
 * schedules, and every unfinished WorkManager run of the library before the
 * sweep changes them. Stale wakes show as delayed runs far in the future.
 * A queued or running entry with no run at all shows in entriesWithoutRun.
 * v9Leftovers counts the v9 work still on disk, and orphans the
 * directories with no entry file that are not v9, zeros included.
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
  ): Map<String, Any?> {
    val base = mapOf(
      "entries" to entries.size,
      "entriesByState" to entries.groupingBy { it.state.wire }.eachCount(),
      "entriesByKey" to entries.groupingBy { it.key }.eachCount(),
      "oldestUpdatedAt" to entries.minOfOrNull { it.updatedAt },
      "v9Leftovers" to leftovers.v9.toMap(),
      "orphans" to leftovers.orphans.toMap(),
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
    )
  }
}
