package ai.openspace.backgroundupload

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

class ForegroundTaskTest {
  @get:Rule
  val tmp = TemporaryFolder()

  @After
  fun tearDown() {
    Diag.log = null
  }

  /** Holds each task until the test runs it, as the queue executor would later. */
  private class HeldExecutor : Executor {
    val tasks = mutableListOf<Runnable>()

    override fun execute(command: Runnable) {
      tasks += command
    }

    fun runAll() {
      val now = tasks.toList()
      tasks.clear()
      now.forEach { it.run() }
    }
  }

  @Test
  fun `a return to the foreground calls retryNow once, on the queue executor`() {
    val executor = HeldExecutor()
    val calls = mutableListOf<String>()
    val task = ForegroundTask(executor, { calls += "save" }, { calls += "retryNow" }) { 0L }
    task.onForeground()
    assertEquals(emptyList<String>(), calls) // nothing on the calling (UI) thread
    executor.runAll()
    assertEquals(listOf("save", "retryNow"), calls)
  }

  @Test
  fun `a failed settings save still runs retryNow, and neither throws on the executor`() {
    val file = File(tmp.newFolder(), "diagnostics.json")
    Diag.log = DiagnosticLog(file)
    val executor = HeldExecutor()
    var retried = false
    val task = ForegroundTask(executor, { throw IllegalStateException("settings") }, retryNow = {
      retried = true
      throw IllegalStateException("work")
    }, elapsedClock = { 0L })
    task.onForeground()
    executor.runAll()
    assertEquals(true, retried)
    val errors = DiagnosticLog(file).drain()
    assertEquals(
      listOf("could not save the settings on return to the foreground", "retryNow on return to the foreground failed"),
      errors.map { it.message },
    )
  }

  @Test
  fun `retryNow runs at most once per 30 s, the settings save every time`() {
    val executor = HeldExecutor()
    var now = 1_000_000L
    val calls = mutableListOf<String>()
    val task = ForegroundTask(executor, { calls += "save" }, { calls += "retryNow" }) { now }
    task.onForeground()
    executor.runAll()
    now += 29_999 // a permission dialog, a picker, a share sheet
    task.onForeground()
    executor.runAll()
    now += 1
    task.onForeground()
    executor.runAll()
    assertEquals(listOf("save", "retryNow", "save", "save", "retryNow"), calls)
  }
}
