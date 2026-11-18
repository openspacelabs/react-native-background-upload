package ai.openspace.backgroundupload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

// This file builds the progress notification and probes connectivity. The
// simple and chunked workers share it. All uploads share one notification,
// identified by the NotificationConfig that configure() saved. Its progress
// bar is the total across the uploads.

internal enum class Connectivity { NoWifi, NoInternet, Ok }

// This is synchronized to ensure consistent status across workers
@Synchronized
internal fun validateConnectivity(context: Context, wifiOnly: Boolean): Connectivity {
  val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  // activeNetwork is the system default network, not a network the process
  // is bound to (ConnectivityManager docs).
  val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
  // While the app binds the process to a network (bindProcessToNetwork), new
  // sockets and DNS use that network, also OkHttp's.
  val bound = manager.boundNetworkForProcess
  return connectivityOf(
    wifiOnly = wifiOnly,
    validated = capabilities?.hasCapability(NET_CAPABILITY_VALIDATED) == true,
    defaultWifi = capabilities?.hasTransport(TRANSPORT_WIFI) == true,
    boundWifi = bound?.let { manager.getNetworkCapabilities(it)?.hasTransport(TRANSPORT_WIFI) == true },
  )
}

/**
 * The network check. The default network must be validated. Wi-Fi only
 * also needs it to be Wi-Fi, and a process bound to a network that is not
 * Wi-Fi fails it: its sockets would send over that network. [boundWifi] is
 * null when the process is not bound. Wi-Fi only answers NoWifi, not
 * NoInternet: asking the user to join Wi-Fi is the more direct hint.
 */
internal fun connectivityOf(wifiOnly: Boolean, validated: Boolean, defaultWifi: Boolean, boundWifi: Boolean?): Connectivity = when {
  !wifiOnly -> if (validated) Connectivity.Ok else Connectivity.NoInternet
  validated && defaultWifi && boundWifi != false -> Connectivity.Ok
  else -> Connectivity.NoWifi
}

// Makes sure that the channel for the foreground notification exists. It makes
// the channel only when the channel is absent. Thus a channel that the consumer
// registered, with their own name and importance, always wins. When configure()
// never set a channel, we use a default LOW-importance channel, and no notifee
// setup is necessary.
internal fun ensureNotificationChannel(manager: NotificationManager, config: NotificationConfig) {
  // minSdk is 29, so NotificationChannel (API 26) is always available.
  if (manager.getNotificationChannel(config.notificationChannel) != null) return
  val channel = NotificationChannel(
    config.notificationChannel,
    "Uploads",
    NotificationManager.IMPORTANCE_LOW,
  )
  manager.createNotificationChannel(channel)
}

// builds the notification required to enable Foreground mode
internal fun buildUploadNotification(
  context: Context,
  config: NotificationConfig,
  connectivity: Connectivity,
): Notification {
  val channel = config.notificationChannel
  val progress = UploadProgress.total()
  val progress2Decimals = "%.2f".format(progress)
  val title = when (connectivity) {
    Connectivity.NoWifi -> config.notificationTitleNoWifi
    Connectivity.NoInternet -> config.notificationTitleNoInternet
    Connectivity.Ok -> config.notificationTitle
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

internal fun uploadForegroundInfo(config: NotificationConfig, notification: Notification): ForegroundInfo {
  val id = config.systemNotificationId
  // Starting Android 14, FOREGROUND_SERVICE_TYPE_DATA_SYNC is mandatory, otherwise app will crash
  return if (Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU)
    ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
  else
    ForegroundInfo(id, notification)
}

/**
 * Whether [error] means that the system refused to let this worker enter
 * foreground mode, because the app is in the background. Android 12 (API 31)
 * restricts foreground-service starts from the background, and WorkManager
 * relaunches workers exactly there (a reboot, or a quota resume). The Android
 * 15 dataSync time-limit denial surfaces as the same exception. The transfer
 * itself needs no foreground mode. It only loses process-priority protection.
 * Thus callers continue without it. They do not fail an upload that can run.
 * The function walks the causes, because setForeground can wrap the platform
 * exception.
 */
internal fun isForegroundStartDenied(error: Throwable): Boolean {
  if (Build.VERSION.SDK_INT < 31) return false
  // ServiceStartNotAllowedException (API 31) covers the two variants:
  // Foreground- and BackgroundServiceStartNotAllowedException.
  return generateSequence(error) { it.cause }
    .any { it is android.app.ServiceStartNotAllowedException }
}

private fun openAppIntent(context: Context): PendingIntent? {
  val intent = Intent(context, NotificationReceiver::class.java)
  val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  return PendingIntent.getBroadcast(context, "RNFileUpload-notification".hashCode(), intent, flags)
}
