package ai.openspace.backgroundupload

import java.io.IOException
import kotlin.math.min
import kotlin.random.Random

/**
 * The retry table (plan section 6.1), as pure functions.
 *
 * | Response or failure                         | Verdict           |
 * | 2xx, or an accept rule matches              | Accepted          |
 * | 401, 403                                    | Auth (park)       |
 * | 408, 429, 5xx                               | Transient         |
 * | other 4xx in `exempt` (default [404])       | Transient         |
 * | other 4xx                                   | Terminal http     |
 * | any other status (1xx, a final 3xx)         | Terminal http     |
 * | IOException, payload file missing           | Terminal file     |
 * | IOException                                 | Transient         |
 * | anything else                               | Terminal unknown  |
 */
object RetryClassifier {

  sealed class Verdict {
    object Accepted : Verdict()
    object Transient : Verdict()
    object Auth : Verdict()
    data class Terminal(val errorKind: String, val message: String) : Verdict()
  }

  data class Policy(val baseMs: Long, val maxMs: Long, val jitter: Double, val exempt: List<Int>)

  /** A wait up to this long happens inside the worker. A longer one releases the worker. */
  const val IN_WORKER_BACKOFF_MAX_MS = 30_000L

  fun policy(defaults: RetryDefaults, override: RetryOverride?): Policy = Policy(
    baseMs = override?.baseMs ?: defaults.baseMs,
    maxMs = override?.maxMs ?: defaults.maxMs,
    jitter = override?.jitter ?: defaults.jitter,
    exempt = override?.exempt ?: defaults.exempt,
  )

  fun classifyResponse(
    code: Int,
    body: String?,
    accept: List<UploadOutcome.AcceptRule>,
    exempt: List<Int>,
  ): Verdict = when {
    UploadOutcome.isAccepted(code, body, accept) -> Verdict.Accepted
    code == 401 || code == 403 -> Verdict.Auth
    code == 408 || code == 429 || code in 500..599 -> Verdict.Transient
    code in 400..499 && code in exempt -> Verdict.Transient
    else -> Verdict.Terminal("http", "HTTP $code")
  }

  /** A CancellationException is never classified; the caller rethrows it first. */
  fun classifyFailure(error: Throwable, fileExists: Boolean): Verdict = when {
    error is IOException && !fileExists ->
      Verdict.Terminal("file", "request body file is missing: ${error.message ?: error.javaClass.simpleName}")
    error is IOException -> Verdict.Transient
    else -> Verdict.Terminal("unknown", error.message ?: error.javaClass.simpleName)
  }

  /**
   * The live attempt's errorKind for a transport failure, from its
   * [classifyFailure] verdict: file, unknown, or network for a transient one.
   */
  fun failureKind(verdict: Verdict): String = (verdict as? Verdict.Terminal)?.errorKind ?: "network"

  fun isExpired(now: Long, expiresAt: Long) = now >= expiresAt

  /**
   * base * 2^(streak-1), capped at maxMs, then spread by ± jitter and capped
   * again. streak 1 is baseMs.
   */
  fun backoffMs(policy: Policy, streak: Int, random: Random = Random.Default): Long {
    val exponent = (streak.coerceAtLeast(1) - 1).coerceAtMost(40)
    val raw = min(policy.baseMs.toDouble() * Math.pow(2.0, exponent.toDouble()), policy.maxMs.toDouble())
    val jitter = policy.jitter.coerceIn(0.0, 1.0)
    val spread = raw * (1.0 + jitter * (2.0 * random.nextDouble() - 1.0))
    return min(spread, policy.maxMs.toDouble()).toLong().coerceAtLeast(0L)
  }

  /** The wake time, never later than expiresAt, so an entry expires on time. */
  fun nextAttemptAt(now: Long, backoffMs: Long, expiresAt: Long): Long = min(now + backoffMs, expiresAt)
}
