package ai.openspace.backgroundupload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The worker of a doorbell row ([WorkScheduler.ensureDoorbell]). WorkManager
 * runs it when the row's network is up. It sends nothing: on the queue
 * executor it starts every queued entry that waits for a network that fits
 * now ([QueueController.answerDoorbell]), and makes sure a doorbell stays for
 * the entries whose network does not fit yet. It also runs with no JS and
 * no module, so it builds its own controller over the shared stores.
 *
 * Returns retry when a WorkManager call failed or anything threw, so the
 * row runs again ([DoorbellRing]); it never fails.
 */
class DoorbellWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val rung = inputData.getString(WorkManagerScheduler.DOORBELL_NETWORK_KEY)
      ?.let { name -> RequiredNetwork.values().firstOrNull { it.name == name } }
    val answered = DoorbellRing.answered {
      // A headless run has no module, so it opens the log that its Diag calls write to.
      DiagnosticLog.get(context)
      val controller = QueueController(
        QueueStore.get(context),
        EventJournal.get(context),
        QueueSettingsStore.get(context),
        EventReporter,
        WorkManagerScheduler(context),
        networkFits = { wifiOnly -> networkFitsNow(context, wifiOnly) },
      )
      // Every queue change runs on this one thread; a blocking wait here is off the main thread.
      UploaderModule.queueExecutor.submit<Boolean> { controller.answerDoorbell(rung) }.get()
    }
    if (answered) Result.success() else Result.retry()
  }
}

/**
 * The doorbell's result, from its answer. Any error is a retry, never a
 * failure: WorkManager fails a FAILED row's successors without a run, so a
 * doorbell queued behind it would be lost. A cancellation (a stop) goes on.
 */
internal object DoorbellRing {
  fun answered(answer: () -> Boolean): Boolean =
    try {
      answer()
    } catch (stop: java.util.concurrent.CancellationException) {
      throw stop
    } catch (error: Throwable) {
      Diag.error("doorbell: the answer failed; it rings again", error)
      false
    }
}
