package ai.openspace.backgroundupload

/**
 * One part of a chunked upload, as the caller wrote it. The library sends the
 * file bytes [start, end) to [url]. [accepted] is set when the server
 * accepted the part. The field names match the v9 manifest, so a v9
 * manifest.json reads into this class unchanged.
 */
data class Part(
  val url: String,
  val headers: Map<String, String>,
  val start: Long,
  val end: Long, // exclusive
  val accepted: Boolean = false,
  /**
   * This part's count of the responses each `retry.transient` rule has
   * excused in this generation, by [UploadOutcome.TransientRule.identity].
   * Each part has its own count, so each gets the rule's full maxRetries.
   * Null before the first, and in a v9 manifest.
   */
  val transientHitsByRule: Map<String, Int>? = null,
) {
  val size get() = end - start
}

/** Pure rules over a parts list. Moved from the v9 ChunkedManifest with the same meaning. */
object ChunkedParts {

  /** Whether [parts] cover [0, size) exactly: no gap, no overlap, nothing past the end. Order does not matter. */
  fun tilesExactly(parts: List<Part>, size: Long): Boolean {
    if (parts.isEmpty()) return false
    var cursor = 0L
    for (part in parts.sortedBy { it.start }) {
      if (part.start != cursor || part.end <= part.start) return false
      cursor = part.end
    }
    return cursor == size
  }

  /**
   * The same upload: the same count, ranges, and urls, in any order. Headers
   * are not compared, because a resume sends fresh headers.
   */
  fun sameParts(a: List<Part>, b: List<Part>): Boolean {
    if (a.size != b.size) return false
    val x = a.sortedBy { it.start }
    val y = b.sortedBy { it.start }
    return x.indices.all { i ->
      x[i].url == y[i].url && x[i].start == y[i].start && x[i].end == y[i].end
    }
  }

  /**
   * The [incoming] parts with the accepted flags of [stored]. A flag follows
   * the range start, not the index. With [keepHits] the transient counts
   * follow it too; a reopen passes false, and every part starts at 0.
   */
  fun carryAccepted(stored: List<Part>, incoming: List<Part>, keepHits: Boolean = false): List<Part> {
    val byStart = stored.associateBy { it.start }
    return incoming.map { part ->
      val old = byStart[part.start]
      part.copy(
        accepted = old?.accepted == true,
        transientHitsByRule = if (keepHits) old?.transientHitsByRule else null,
      )
    }
  }

  /** [parts] with one more hit of [identity] on part [index]. */
  fun withTransientHit(parts: List<Part>, index: Int, identity: String): List<Part> =
    parts.mapIndexed { i, part ->
      if (i != index) part
      else {
        val hits = part.transientHitsByRule ?: emptyMap()
        part.copy(transientHitsByRule = hits + (identity to (hits[identity] ?: 0) + 1))
      }
    }

  fun totalBytes(parts: List<Part>): Long = parts.sumOf { it.size }

  fun acceptedBytes(parts: List<Part>): Long = parts.filter { it.accepted }.sumOf { it.size }

  fun pendingIndexes(parts: List<Part>): List<Int> = parts.indices.filter { !parts[it].accepted }

  fun withAccepted(parts: List<Part>, index: Int): List<Part> =
    parts.mapIndexed { i, part -> if (i == index) part.copy(accepted = true) else part }
}
