package ai.openspace.backgroundupload

import androidx.work.WorkInfo.State.BLOCKED
import androidx.work.WorkInfo.State.CANCELLED
import androidx.work.WorkInfo.State.ENQUEUED
import androidx.work.WorkInfo.State.FAILED
import androidx.work.WorkInfo.State.RUNNING
import androidx.work.WorkInfo.State.SUCCEEDED
import androidx.work.ListenableWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// getAllUploads must return ONE row per upload id, although WorkManager can
// hold several rows for it (finished chains linger for roughly a day, and
// APPEND_OR_REPLACE resumes add rows). The state vocabulary is the same as
// iOS's.
class UploadStatesTest {

  @Test
  fun `a live row wins for a chunked upload`() {
    assertEquals("running", chunkedUploadState(listOf(CANCELLED, RUNNING), allAccepted = false))
    assertEquals("running", chunkedUploadState(listOf(RUNNING, BLOCKED), allAccepted = false))
    assertEquals("pending", chunkedUploadState(listOf(FAILED, ENQUEUED), allAccepted = false))
    assertEquals("pending", chunkedUploadState(listOf(BLOCKED), allAccepted = false))
  }

  @Test
  fun `with no live row the manifest speaks, never a lingering finished row`() {
    // A cancelled chunked upload keeps its manifest. Its truthful state is
    // stalled-awaiting-resume ("error"), not "cancelled". iOS's getAllUploads
    // never reports "cancelled" for a lingering upload.
    assertEquals("error", chunkedUploadState(listOf(CANCELLED), allAccepted = false))
    assertEquals("error", chunkedUploadState(listOf(FAILED), allAccepted = false))
    assertEquals("error", chunkedUploadState(emptyList(), allAccepted = false))
    assertEquals("completed", chunkedUploadState(listOf(SUCCEEDED), allAccepted = true))
    assertEquals("completed", chunkedUploadState(emptyList(), allAccepted = true))
    // The row that a run leaves after it journals a terminal error is
    // SUCCEEDED (see terminalErrorResult). The manifest, not the row, carries
    // the outcome.
    assertEquals("error", chunkedUploadState(listOf(SUCCEEDED), allAccepted = false))
  }

  @Test
  fun `a journaled terminal error still succeeds the row`() {
    // WorkManager marks the dependents of a FAILED prerequisite FAILED without
    // a run. Thus a resume appended during a failing run's teardown would
    // silently never run. The journal and the manifest are the outcome record,
    // never the row state.
    assertTrue(terminalErrorResult() is ListenableWorker.Result.Success)
  }

  @Test
  fun `cancel reports from the module only when no worker is running`() {
    assertTrue(cancelReportsFromModule(listOf(ENQUEUED)))
    // An appended chain's dependent is BLOCKED, not ENQUEUED. It is still
    // never-started, and it is still owed a module-side 'cancelled'.
    assertTrue(cancelReportsFromModule(listOf(BLOCKED)))
    assertTrue(cancelReportsFromModule(listOf(ENQUEUED, BLOCKED)))
    // A RUNNING worker's stop handler owns the report.
    assertFalse(cancelReportsFromModule(listOf(RUNNING)))
    assertFalse(cancelReportsFromModule(listOf(RUNNING, BLOCKED)))
    assertFalse(cancelReportsFromModule(emptyList()))
  }

  @Test
  fun `a queued successor suppresses another append`() {
    assertTrue(hasQueuedSuccessor(listOf(RUNNING, BLOCKED)))
    assertTrue(hasQueuedSuccessor(listOf(ENQUEUED)))
    assertFalse(hasQueuedSuccessor(listOf(RUNNING)))
    assertFalse(hasQueuedSuccessor(listOf(SUCCEEDED, FAILED, CANCELLED)))
    assertFalse(hasQueuedSuccessor(emptyList()))
  }

  @Test
  fun `a simple upload reports its live row first, then the most conclusive finished one`() {
    assertEquals("running", simpleUploadState(listOf(CANCELLED, RUNNING)))
    assertEquals("pending", simpleUploadState(listOf(SUCCEEDED, ENQUEUED)))
    assertEquals("completed", simpleUploadState(listOf(CANCELLED, SUCCEEDED)))
    assertEquals("error", simpleUploadState(listOf(CANCELLED, FAILED)))
    assertEquals("cancelled", simpleUploadState(listOf(CANCELLED)))
  }
}
