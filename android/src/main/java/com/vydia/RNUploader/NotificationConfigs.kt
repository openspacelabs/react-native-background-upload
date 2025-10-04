package com.vydia.RNUploader

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.facebook.react.bridge.ReadableMap
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.flow.first
import java.io.InputStream
import java.io.OutputStream


object NotificationConfigsSerializer : Serializer<NotificationConfigs> {
  override val defaultValue: NotificationConfigs = NotificationConfigs.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): NotificationConfigs {
    try {
      return NotificationConfigs.parseFrom(input)
    } catch (exception: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read proto.", exception)
    }
  }

  override suspend fun writeTo(
    t: NotificationConfigs,
    output: OutputStream) = t.writeTo(output)
}

// This is the recommended way to create a DataStore instance
// https://developer.android.com/topic/libraries/architecture/datastore#preferences-create
private val Context.vydiaNotificationConfigs: DataStore<NotificationConfigs> by dataStore(
  fileName = "settings.pb",
  serializer = NotificationConfigsSerializer
)

suspend fun fetchNotificationConfigs(context: Context): NotificationConfigs =
  context.vydiaNotificationConfigs.data.first()

suspend fun updateNotificationConfigs(opts: ReadableMap, context: Context) {
  val notificationId = opts.getString("notificationId")
    ?: throw MissingOptionException("notificationId")
  val notificationTitle = opts.getString("notificationTitle")
    ?: throw MissingOptionException("notificationTitle")
  val notificationTitleNoInternet = opts.getString("notificationTitleNoInternet")
    ?: throw MissingOptionException("notificationTitleNoInternet")
  val notificationTitleNoWifi = opts.getString("notificationTitleNoWifi")
    ?: throw MissingOptionException("notificationTitleNoWifi")
  val notificationChannel = opts.getString("notificationChannel")
    ?: throw MissingOptionException("notificationChannel")

  context.vydiaNotificationConfigs.updateData { currentSettings ->
    currentSettings.toBuilder().apply {
      this.id = notificationId.hashCode()
      this.title = notificationTitle
      this.titleNoInternet = notificationTitleNoInternet
      this.titleNoWifi = notificationTitleNoWifi
      this.channel = notificationChannel
    }.build()
  }
}
