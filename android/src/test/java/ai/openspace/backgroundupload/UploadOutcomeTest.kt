package ai.openspace.backgroundupload

import ai.openspace.backgroundupload.UploadOutcome.AcceptRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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

  @Test
  fun `IOException with a missing file is a file error`() {
    assertEquals("file", UploadOutcome.errorKind(IOException("gone"), fileExists = false))
  }

  @Test
  fun `IOException with the file present is a network error`() {
    assertEquals("network", UploadOutcome.errorKind(IOException("reset"), fileExists = true))
  }

  @Test
  fun `a non-IO error is unknown`() {
    assertEquals("unknown", UploadOutcome.errorKind(RuntimeException("boom"), fileExists = true))
  }
}
