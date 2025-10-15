package com.vydia.RNUploader2

import com.facebook.react.bridge.ReadableMap


object NotificationConfigs {
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

  fun update(opts: ReadableMap) {
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
  }
}

