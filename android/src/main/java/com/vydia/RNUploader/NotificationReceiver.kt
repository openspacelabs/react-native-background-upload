package com.vydia.RNUploader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// there's no way to directly open the app from the notification without reloading it,
// so we use a BroadcastReceiver to listen to the notification intent
class NotificationReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context?, intent: Intent?) {
    context ?: return
    val packageName = context.packageName
    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
    context.startActivity(launchIntent)
    EventReporter.notification()
  }
}
