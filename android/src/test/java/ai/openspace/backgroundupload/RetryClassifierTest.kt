package ai.openspace.backgroundupload

import ai.openspace.backgroundupload.RetryClassifier.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

class RetryClassifierTest {
  private val defaultExempt = listOf(404)

  private fun classify(code: Int, body: String = "", accept: List<UploadOutcome.AcceptRule> = emptyList(), exempt: List<Int> = defaultExempt) =
    RetryClassifier.classifyResponse(code, body, accept, exempt)

  @Test
  fun `the retry table`() {
    assertEquals(Verdict.Accepted, classify(200))
    assertEquals(Verdict.Accepted, classify(204))
    assertEquals(Verdict.Accepted, classify(409, "upload already completed", listOf(UploadOutcome.AcceptRule(409, "already completed"))))
    assertEquals(Verdict.Terminal("http", "HTTP 409"), classify(409, "conflict", listOf(UploadOutcome.AcceptRule(409, "already completed"))))
    assertEquals(Verdict.Auth, classify(401))
    assertEquals(Verdict.Auth, classify(403))
    assertEquals(Verdict.Transient, classify(408))
    assertEquals(Verdict.Transient, classify(429))
    assertEquals(Verdict.Transient, classify(500))
    assertEquals(Verdict.Transient, classify(599))
    assertEquals(Verdict.Transient, classify(404)) // default exempt
    assertEquals(Verdict.Terminal("http", "HTTP 400"), classify(400))
    assertEquals(Verdict.Terminal("http", "HTTP 409"), classify(409))
    assertEquals(Verdict.Terminal("http", "HTTP 304"), classify(304))
    assertEquals(Verdict.Terminal("http", "HTTP 101"), classify(101))
  }

  @Test
  fun `a chunked part 404 with exempt empty is terminal`() {
    assertEquals(Verdict.Terminal("http", "HTTP 404"), classify(404, exempt = emptyList()))
  }

  @Test
  fun `an auth status stays auth even when exempt lists it`() {
    assertEquals(Verdict.Auth, classify(401, exempt = listOf(401)))
  }

  @Test
  fun `transport failures`() {
    assertEquals(Verdict.Transient, RetryClassifier.classifyFailure(IOException("reset"), fileExists = true))
    val file = RetryClassifier.classifyFailure(IOException("ENOENT"), fileExists = false)
    assertTrue(file is Verdict.Terminal && file.errorKind == "file")
    val other = RetryClassifier.classifyFailure(IllegalArgumentException("bad url"), fileExists = true)
    assertEquals(Verdict.Terminal("unknown", "bad url"), other)
    assertEquals("network", RetryClassifier.failureKind(IOException(), true))
  }

  private val policy = RetryClassifier.Policy(baseMs = 1_000, maxMs = 7_200_000, jitter = 0.0, exempt = defaultExempt)

  @Test
  fun `backoff doubles from base and caps at max`() {
    assertEquals(1_000, RetryClassifier.backoffMs(policy, 1))
    assertEquals(2_000, RetryClassifier.backoffMs(policy, 2))
    assertEquals(4_000, RetryClassifier.backoffMs(policy, 3))
    assertEquals(7_200_000, RetryClassifier.backoffMs(policy, 14))
    assertEquals(7_200_000, RetryClassifier.backoffMs(policy, 10_000))
    assertEquals(1_000, RetryClassifier.backoffMs(policy, 0)) // defensive
  }

  @Test
  fun `jitter stays within bounds and under max`() {
    val jittered = policy.copy(jitter = 0.2)
    val random = Random(42)
    repeat(1_000) {
      val ms = RetryClassifier.backoffMs(jittered, 3, random)
      assertTrue("$ms", ms in 3_200..4_800)
    }
    repeat(1_000) {
      assertTrue(RetryClassifier.backoffMs(jittered, 30, random) <= 7_200_000)
    }
  }

  @Test
  fun `nextAttemptAt clamps to expiresAt`() {
    assertEquals(3_000, RetryClassifier.nextAttemptAt(now = 1_000, backoffMs = 2_000, expiresAt = 10_000))
    assertEquals(10_000, RetryClassifier.nextAttemptAt(now = 1_000, backoffMs = 7_200_000, expiresAt = 10_000))
  }

  @Test
  fun `expiry is inclusive of the deadline`() {
    assertFalse(RetryClassifier.isExpired(4_999, 5_000))
    assertTrue(RetryClassifier.isExpired(5_000, 5_000))
  }

  @Test
  fun `policy overrides field by field`() {
    val defaults = RetryDefaults()
    assertEquals(RetryClassifier.Policy(1_000, 7_200_000, 0.2, listOf(404)), RetryClassifier.policy(defaults, null))
    assertEquals(
      RetryClassifier.Policy(50, 7_200_000, 0.2, emptyList()),
      RetryClassifier.policy(defaults, RetryOverride(baseMs = 50, maxMs = null, jitter = null, exempt = emptyList())),
    )
  }
}
