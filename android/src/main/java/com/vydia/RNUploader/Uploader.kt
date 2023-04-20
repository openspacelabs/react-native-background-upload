package com.vydia.RNUploader

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters

class Uploader


class UploadWorker(appContext: Context, workerParams: WorkerParameters) :
  CoroutineWorker(appContext, workerParams) {
  override suspend fun doWork(): Result {
    TODO("Not yet implemented")

  }

  private fun createForegroundInfo(progress: String): ForegroundInfo {
    val id = applicationContext.getString(R.string.notification_channel_id)
    val title = applicationContext.getString(R.string.notification_title)
    val cancel = applicationContext.getString(R.string.cancel_download)
    // This PendingIntent can be used to cancel the worker
    val intent = WorkManager.getInstance(applicationContext)
      .createCancelPendingIntent(getId())

    // Create a Notification channel if necessary
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      createChannel()
    }

    val notification = NotificationCompat.Builder(applicationContext, id)
      .setContentTitle(title)
      .setTicker(title)
      .setContentText(progress)
      .setSmallIcon(R.drawable.autofill_inline_suggestion_chip_background)
      .setOngoing(true)
      // Add the cancel action to the notification which can
      // be used to cancel the worker
      .addAction(android.R.drawable.ic_delete, cancel, intent)
      .build()

    notification

    return ForegroundInfo(notificationId, notification)
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private fun createChannel() {
    // Create a Notification channel
  }
}