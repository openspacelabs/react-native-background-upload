package com.vydia.RNUploader

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.facebook.react.bridge.ReadableMap


object UploadNotification {
  var id: Int = 0
    private set
  var title: String = "Uploading files"
    private set
  var titleNoInternet: String = "Waiting for internet connection"
    private set
  var titleNoWifi: String = "Waiting for WiFi connection"
    private set
  var channel: String = "File Uploads"
    private set
  var maxRetries: Int = 5
    private set

  private var activeUpload: Upload? = null

  @Synchronized
  fun setActiveUpload(upload: Upload?) {
    this.activeUpload = upload
  }

  @Synchronized
  fun getActiveUpload(): Upload? {
    return this.activeUpload
  }

  @Synchronized
  fun releaseActiveUpload(upload: Upload) {
    if (this.activeUpload?.id == upload.id) this.activeUpload = null
  }

  fun setOptions(opts: ReadableMap) {
    id = opts.getString("notificationId")?.hashCode()
      ?: throw MissingOptionException("notificationId")
    title = opts.getString("notificationTitle")
      ?: throw MissingOptionException("notificationTitle")
    titleNoInternet = opts.getString("notificationTitleNoInternet")
      ?: throw MissingOptionException("notificationTitleNoInternet")
    titleNoWifi = opts.getString("notificationTitleNoWifi")
      ?: throw MissingOptionException("notificationTitleNoWifi")
    channel = opts.getString("notificationChannel")
      ?: throw MissingOptionException("notificationChannel")
    maxRetries = if (opts.hasKey("maxRetries")) opts.getInt("maxRetries") else 5
  }

  // builds the notification required to enable Foreground mode
  fun build(context: Context): Notification {
    // since all workers share the same notification ID,
    // get the active upload so we don't overwrite the notification when multiple uploads are running
    val wifiOnly = getActiveUpload()?.wifiOnly ?: false
    val channel = channel
    val progress = UploadProgress.total()
    val progress2Decimals = "%.2f".format(progress)
    val title = when (Connectivity.fetch(context, wifiOnly)) {
      Connectivity.NoWifi -> titleNoWifi
      Connectivity.NoInternet -> titleNoInternet
      Connectivity.Ok -> title
    }

    // Custom layout for progress notification.
    // The default hides the % text. This one shows it on the right,
    // like most examples in various docs.
    val content = RemoteViews(context.packageName, R.layout.notification)
    content.setTextViewText(R.id.notification_title, title)
    content.setTextViewText(R.id.notification_progress, "${progress2Decimals}%")
    content.setProgressBar(R.id.notification_progress_bar, 100, progress.toInt(), false)

    return NotificationCompat.Builder(context, channel).run {
      // Starting Android 12, the notification shows up with a confusing delay of 10s.
      // This fixes that delay.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        foregroundServiceBehavior = Notification.FOREGROUND_SERVICE_IMMEDIATE

      // Required by android. Here we use the system's default upload icon
      setSmallIcon(android.R.drawable.stat_sys_upload)
      // These prevent the notification from being force-dismissed or dismissed when pressed
      setOngoing(true)
      setAutoCancel(false)
      // These help show the same custom content when the notification collapses and expands
      setCustomContentView(content)
      setCustomBigContentView(content)
      // opens the app when the notification is pressed
      setContentIntent(openAppIntent(context))
      build()
    }
  }

  fun update(context: Context) {
    val notification = build(context)
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.notify(id, notification)
  }
}


private fun openAppIntent(context: Context): PendingIntent? {
  val intent = Intent(context, NotificationReceiver::class.java)
  val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  return PendingIntent.getBroadcast(context, "RNFileUpload-notification".hashCode(), intent, flags)
}


