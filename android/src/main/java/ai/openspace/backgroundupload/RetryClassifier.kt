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
 * | other 4xx matching a `transient` rule       | Transient(rule) under its maxRetries, else Terminal http |
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
    /** [rule] is the `transient` rule that excused the response; null for the standard table or `exempt`. */
    data class Transient(val rule: UploadOutcome.TransientRule? = null) : Verdict()
    object Auth : Verdict()
    data class Terminal(val errorKind: String, val message: String) : Verdict()
  }

  data class Policy(
    val baseMs: Long,
    val maxMs: Long,
    val jitter: Double,
    val exempt: List<Int>,
    val transient: List<UploadOutcome.TransientRule> = emptyList(),
  )

  /** A wait up to this long happens inside the worker. A longer one releases the worker. */
  const val IN_WORKER_BACKOFF_MAX_MS = 30_000L

  /**
   * The longest wait after a call that failed while it connected, with no
   * earlier try in the call ([reachedNoServer]). Every attempt starts on a
   * network Android reports as validated, so such a failure says little
   * about the server, and a network change cannot wake a queued entry.
   * Every other failure keeps the policy's maxMs: a failure after the
   * request went out may have reached the server, and a sooner repeat
   * would make a duplicate likelier.
   */
  const val TRANSPORT_FAILURE_MAX_MS = 15L * 60 * 1000

  /** The app clears a wait only this long after the failure that set it. */
  const val CLEAR_MIN_AGE_MS = 30_000L

  /**
   * Whether this call failed while it connected, with no earlier try inside
   * the same call. Then this call sent no request. (An earlier attempt, in
   * an earlier call, may still have reached the server.)
   *
   * From OkHttp 4.12.0: RealConnection.connectSocket throws ConnectException
   * ("Failed to connect to"); the DNS lookup (Dns.SYSTEM) throws
   * UnknownHostException; the socket connect can throw
   * NoRouteToHostException or PortUnreachableException; the TLS handshake
   * throws SSLHandshakeException, and connectTls throws
   * SSLPeerUnverifiedException. OkHttp retries inside one call: it throws
   * the last failure with the earlier tries it recovered from attached as
   * suppressed (Util.withSuppressed in RetryAndFollowUpInterceptor), and a
   * RouteException adds later connect failures as suppressed too. An
   * earlier try may have sent the body, so any suppressed exception rules
   * the failure out. A SocketTimeoutException is left out: a connect
   * timeout and a read or write timeout throw the same class.
   */
  fun reachedNoServer(error: Throwable): Boolean =
    error.suppressed.isEmpty() && (
      error is java.net.ConnectException ||
        error is java.net.UnknownHostException ||
        error is java.net.NoRouteToHostException ||
        error is java.net.PortUnreachableException ||
        error is javax.net.ssl.SSLHandshakeException ||
        error is javax.net.ssl.SSLPeerUnverifiedException
      )

  /**
   * The highest backoff streak an entry keeps when the app clears its wait.
   * Streak 5 is the last wait a worker sleeps out in place (16 times
   * baseMs); the next failure waits 32 times baseMs, then the wait doubles.
   */
  const val RETRY_NOW_MAX_STREAK = 5

  /** [policy] for the wait after a transport failure: maxMs at most [TRANSPORT_FAILURE_MAX_MS]. */
  fun transportFailurePolicy(policy: Policy): Policy =
    policy.copy(maxMs = min(policy.maxMs, TRANSPORT_FAILURE_MAX_MS))

  fun policy(defaults: RetryDefaults, override: RetryOverride?): Policy = Policy(
    baseMs = override?.baseMs ?: defaults.baseMs,
    maxMs = override?.maxMs ?: defaults.maxMs,
    jitter = override?.jitter ?: defaults.jitter,
    exempt = override?.exempt ?: defaults.exempt,
    transient = override?.transient ?: defaults.transient,
  )

  /**
   * [hits] is the count so far per rule [UploadOutcome.TransientRule.identity]:
   * the entry's for a simple request, the part's for a chunked one. A matched
   * rule decides even past its cap: `exempt` does not rescue it.
   */
  fun classifyResponse(
    code: Int,
    body: String?,
    accept: List<UploadOutcome.AcceptRule>,
    exempt: List<Int>,
    transient: List<UploadOutcome.TransientRule> = emptyList(),
    hits: Map<String, Int> = emptyMap(),
  ): Verdict = when {
    UploadOutcome.isAccepted(code, body, accept) -> Verdict.Accepted
    code == 401 || code == 403 -> Verdict.Auth
    code == 408 || code == 429 || code in 500..599 -> Verdict.Transient()
    code in 400..499 -> {
      val rule = UploadOutcome.matchesTransient(code, body, transient)
      when {
        rule != null -> {
          val cap = rule.maxRetries
          if (cap == null || (hits[rule.identity] ?: 0) < cap) Verdict.Transient(rule)
          else Verdict.Terminal("http", "HTTP $code")
        }
        code in exempt -> Verdict.Transient()
        else -> Verdict.Terminal("http", "HTTP $code")
      }
    }
    else -> Verdict.Terminal("http", "HTTP $code")
  }

  /** A CancellationException is never classified; the caller rethrows it first. */
  fun classifyFailure(error: Throwable, fileExists: Boolean): Verdict = when {
    error is IOException && !fileExists ->
      Verdict.Terminal("file", "request body file is missing: ${error.message ?: error.javaClass.simpleName}")
    error is IOException -> Verdict.Transient()
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
