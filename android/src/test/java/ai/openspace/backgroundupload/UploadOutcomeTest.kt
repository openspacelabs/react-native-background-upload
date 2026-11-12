package ai.openspace.backgroundupload

import ai.openspace.backgroundupload.UploadOutcome.AcceptRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.google.gson.Gson
import java.io.File

class UploadOutcomeTest {

  @Test
  fun `2xx is accepted`() {
    assertTrue(UploadOutcome.isAccepted(200, "", listOf()))
    assertTrue(UploadOutcome.isAccepted(204, "", listOf()))
    assertTrue(UploadOutcome.isAccepted(299, "", listOf()))
  }

  @Test
  fun `non-2xx is not accepted by default`() {
    assertFalse(UploadOutcome.isAccepted(199, "", listOf()))
    assertFalse(UploadOutcome.isAccepted(300, "", listOf()))
    assertFalse(UploadOutcome.isAccepted(404, "", listOf()))
    assertFalse(UploadOutcome.isAccepted(500, "", listOf()))
  }

  @Test
  fun `a status-only rule accepts its status regardless of body`() {
    val rules = listOf(AcceptRule(409))
    assertTrue(UploadOutcome.isAccepted(409, "anything", rules))
    assertTrue(UploadOutcome.isAccepted(409, null, rules))
  }

  @Test
  fun `rules do not accept unlisted codes`() {
    assertFalse(UploadOutcome.isAccepted(500, "", listOf(AcceptRule(409))))
  }

  // The backend's 409 carries several meanings, and only the message shows the
  // difference. bodyIncludes is what separates the success meanings from the
  // bug meanings.
  @Test
  fun `bodyIncludes narrows a rule to matching bodies`() {
    val rules = listOf(AcceptRule(409, bodyIncludes = "already completed"))
    assertTrue(UploadOutcome.isAccepted(409, "part already completed", rules))
    assertFalse(UploadOutcome.isAccepted(409, "part number mismatch", rules))
    assertFalse(UploadOutcome.isAccepted(409, null, rules))
  }

  @Test
  fun `any matching rule accepts`() {
    val rules = listOf(
      AcceptRule(409, bodyIncludes = "already completed"),
      AcceptRule(208),
    )
    assertTrue(UploadOutcome.isAccepted(208, "", rules))
    assertTrue(UploadOutcome.isAccepted(409, "already completed", rules))
    assertFalse(UploadOutcome.isAccepted(410, "already completed", rules))
  }

  /**
   * The repo-root vector iOS reads too. Gradle runs these tests from the
   * module's android/ directory; walk up to find it.
   */
  private fun identityVector(): File {
    var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
    while (dir != null) {
      val f = File(dir, "test-vectors/transient-rule-identity.json")
      if (f.exists()) return f
      dir = dir.parentFile
    }
    error("test-vectors/transient-rule-identity.json not found above ${System.getProperty("user.dir")}")
  }

  private class Vector(val identities: List<Case>) {
    class Case(val rule: UploadOutcome.TransientRule, val identity: String)
  }

  @Test
  fun `a transient rule's identity matches the shared vector`() {
    val vector = Gson().fromJson(identityVector().readText(Charsets.UTF_8), Vector::class.java)
    assertTrue(vector.identities.size >= 8)
    for (case in vector.identities) assertEquals(case.identity, case.rule.identity)
    // Two rules share an identity only when status and bodyIncludes are equal.
    val byIdentity = vector.identities.groupBy({ it.identity }, { it.rule.status to it.rule.bodyIncludes })
    for ((identity, rules) in byIdentity) assertEquals(identity, 1, rules.toSet().size)
  }

  @Test
  fun `the first matching transient rule wins`() {
    val any400 = UploadOutcome.TransientRule(400)
    val stopped = UploadOutcome.TransientRule(400, "stopped", 3)
    assertEquals(stopped, UploadOutcome.matchesTransient(400, "body stopped", listOf(stopped, any400)))
    assertEquals(any400, UploadOutcome.matchesTransient(400, "body stopped", listOf(any400, stopped)))
    assertEquals(any400, UploadOutcome.matchesTransient(400, null, listOf(stopped, any400)))
    assertEquals(null, UploadOutcome.matchesTransient(404, "stopped", listOf(stopped, any400)))
  }
}
