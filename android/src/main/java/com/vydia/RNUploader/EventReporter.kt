package com.vydia.RNUploader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter
import com.google.gson.Gson
import com.google.gson.JsonObject

private const val TAG = "UploadReceiver"
private const val INTENT = "com.vydia.RNUploader.ACTION_UPLOAD_STATUS"
private const val INTENT_DATA = "data"

// Use a BroadcastReceiver here so it could wake up the app at completion
class EventsReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context?, intent: Intent?) {
    context ?: return
    intent ?: return

    if (intent.action != INTENT) return
    val json = intent.getStringExtra(INTENT_DATA) ?: return

    val eventType = Gson().fromJson(json, JsonObject::class.java).get("type").asString
    val event = when (EventType.valueOf(eventType)) {
      EventType.Progress -> Gson().fromJson(json, ProgressEvent::class.java)
      EventType.Error -> Gson().fromJson(json, ErrorEvent::class.java)
      EventType.Cancelled -> Gson().fromJson(json, CancelledEvent::class.java)
      EventType.Completed -> Gson().fromJson(json, CompletedEvent::class.java)
    }

    sendEvent(event.type, reactify(event))
  }
}

private fun reactify(event: Event) = Arguments.createMap().apply {
  putString("id", event.id)
  when (event) {
    is CompletedEvent -> {
      putInt("responseCode", event.code)
      putString("responseBody", event.body)
      putMap("responseHeaders", Arguments.makeNativeMap(event.headers.mapValues { entry ->
        entry.value.joinToString(", ")
      }))
    }
    is ProgressEvent -> putDouble("progress", event.progress.toDouble())
    is ErrorEvent -> putString("error", event.error.toString())
    is CancelledEvent -> Unit
  }
}


private fun sendEvent(type: EventType, params: WritableMap?) {
  val reactContext = UploaderModule.reactContext ?: return

  // Right after JS reloads, react instance might not be available yet
  if (!reactContext.hasActiveReactInstance()) return

  try {
    val jsModule = reactContext.getJSModule(RCTDeviceEventEmitter::class.java)
    jsModule.emit("RNFileUploader-${type.name.lowercase()}", params)
  } catch (exc: Throwable) {
    Log.e(TAG, "sendEvent() failed", exc)
  }
}

fun broadcast(context: Context, data: Event) {
  val json = Gson().toJson(data)
  val intent = Intent(INTENT).apply {
    setPackage(context.packageName)
    putExtra(INTENT_DATA, json)
  }
  context.sendBroadcast(intent)
}

enum class EventType { Progress, Error, Cancelled, Completed }

sealed class Event {
  abstract val id: String
  abstract val type: EventType
}

data class ProgressEvent(override val id: String, val progress: Long) : Event() {
  override val type = EventType.Progress
}

data class ErrorEvent(override val id: String, val error: Throwable) : Event() {
  override val type = EventType.Error
}

data class CancelledEvent(override val id: String) : Event() {
  override val type = EventType.Cancelled
}

data class CompletedEvent(
  override val id: String,
  val code: Int,
  val body: String,
  val headers: Map<String, List<String>>
) : Event() {
  override val type = EventType.Completed
}

