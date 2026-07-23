package com.vydia.RNUploader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UploadOutcomeTest {

  @Test
  fun `2xx is accepted`() {
    assertTrue(UploadOutcome.isAccepted(200, listOf()))
    assertTrue(UploadOutcome.isAccepted(204, listOf()))
    assertTrue(UploadOutcome.isAccepted(299, listOf()))
  }

  @Test
  fun `non-2xx is not accepted by default`() {
    assertFalse(UploadOutcome.isAccepted(199, listOf()))
    assertFalse(UploadOutcome.isAccepted(300, listOf()))
    assertFalse(UploadOutcome.isAccepted(404, listOf()))
    assertFalse(UploadOutcome.isAccepted(500, listOf()))
  }

  @Test
  fun `non-2xx listed in acceptStatus is accepted`() {
    assertTrue(UploadOutcome.isAccepted(409, listOf(409)))
    assertTrue(UploadOutcome.isAccepted(404, listOf(404, 409)))
  }

  @Test
  fun `acceptStatus does not accept unlisted codes`() {
    assertFalse(UploadOutcome.isAccepted(500, listOf(409)))
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
