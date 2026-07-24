# Phase 0: Reliable Upload Statuses Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make upload outcomes impossible to lose and impossible to misread — a native, durable event journal with acknowledgment, a query API for live uploads, correct success/error classification (`completed` = accepted statuses only), distinguishable cancel reasons, and iOS background-relaunch handling (v8.0.0, breaking changes coordinated with Diana).

**Architecture:** Terminal events (completed/error/cancelled) are written to a native on-disk journal *before* being emitted to JS, and deleted only when JS acknowledges them; delivery becomes at-least-once instead of at-most-once. A `getAllUploads()` query enumerates live uploads (WorkManager work on Android, session tasks on iOS) so consumers can reconcile on boot instead of guessing. Outcomes are classified natively (2xx/`acceptStatus` → completed; other HTTP → typed error with the response attached) instead of pushing success determination onto every consumer. The event API keeps its names, but semantics are corrected where the old ones were wrong — Diana adopts in one coordinated bump.

**Tech Stack:** Kotlin (WorkManager 2.8.1, OkHttp 4.10, Gson 2.8.9 — all existing deps), Objective-C (background NSURLSession), TypeScript. Tests: JUnit 4 (plain JVM) for Android journal logic, Jest for the JS surface, on-device checklist via the example app + `example/server`.

**RFC / context:** https://github.com/openspacelabs/diana/issues/8875. Research notes in `.claude/work/831bb775-1eba-45d6-b429-ad2951acda24/`.

## Global Constraints

- **Best practices and official platform documentation take precedence over existing code** (Dylan, 2026-07-23: "we don't need to be married to anything in the current library"). Breaking changes are allowed — Diana is the sole consumer and pins a git tag, so every break ships with a coordinated Diana change (listed in "After Phase 0"). Event names (`RNFileUploader-*`) and native module names are kept this phase only to limit churn; they rename at the Phase 1 package split.
- Outcome classification (breaking): `completed` fires only for 2xx or per-request `acceptStatus` codes; other HTTP responses emit `error` with `errorKind: 'http'` and the full response attached. Transport failures are `errorKind: 'network'`, missing files `'file'`.
- No new runtime dependencies. JUnit is test-only. Gson/OkHttp/WorkManager stay at current versions.
- Objective-C only on iOS (no Swift); podspec globs `ios/*.{h,m}`.
- Kotlin `jvmTarget = "17"` as configured in `android/build.gradle`.
- Version bump to **8.0.0** (breaking: completed = accepted statuses only; android options optional; `lib/` removed). Branch: `dylan/phase0-reliable-uploads`.
- Every commit must leave the example app buildable. Run `yarn lint:ci` before pushing; do not poll CI after push.
- Response bodies in the journal are capped at **64 KB** with a `responseBodyTruncated` flag.
- Journal event `type` values: `'completed' | 'error' | 'cancelled'`. Cancel `reason` values: `'user' | 'system'`.
- Platform-API claims in this plan were fact-checked against official docs — see `.claude/work/831bb775-1eba-45d6-b429-ad2951acda24/07-platform-facts.md`. Two findings shaped the design: **iOS `taskDescription` has no documented persistence guarantee across process death** (hence the `RNBGUTaskMap` sidecar in Task 5 — Apple DTS guidance is to persist task metadata externally keyed by `taskIdentifier`), and **WorkManager auto-prunes finished work after ~1 day**, so `getAllUploads()` only sees recent terminal work — the journal is the source of truth for outcomes.

## Decision gate (read first)

**Resolved 2026-07-22: BUILD — execute Tasks 1–11 as written.** The candidate wrapper target (`@kesha-antonov/react-native-background-downloader` v4.5.8) was verified at source level (`.claude/work/831bb775-1eba-45d6-b429-ad2951acda24/06-kesha-source-verification.md`). Its iOS upload path is sound (background NSURLSession, `uploadTaskWithRequest:fromFile:`, AppDelegate hook), but two dealbreakers: (1) **Android uploads run on a plain thread pool with `HttpURLConnection`** — no WorkManager, no foreground service, uploads die with the process, "resume" restarts from byte 0; (2) **no completion journal on either platform** — an upload that completes while the app is dead emits one fire-and-forget event, deletes its persisted config, and its outcome/response is unrecoverable (`getExistingUploadTasks()` cannot see it). Wrapping it would regress the two guarantees this library exists to provide. No device spike needed; Task 0 below is kept for the record.

---

### Task 0: Build-vs-wrap decision gate

**Files:** none (decision task).

**Interfaces:**
- Produces: a go/no-go decision recorded at the top of this plan. "GO (build)" → execute Tasks 1–11 as written. "WRAP" → stop; the native tasks are re-planned as a wrapper (JS contract in Task 9 is unchanged).

- [x] **Step 1: Read the source-verification report**

Read `.claude/work/831bb775-1eba-45d6-b429-ad2951acda24/06-kesha-source-verification.md`. It answers, with code quotes: does the kesha-antonov iOS upload path use `backgroundSessionConfigurationWithIdentifier` + `uploadTaskWithRequest:fromFile:`; does Android survive process death; is there any dead-JS event persistence.

- [x] **Step 2: Apply the decision rule**

- If the report's verdict is **BUILD** (uploads don't truly survive termination, or no meaningful advantage over our fork): proceed with Tasks 1–11. No device spike needed.
- If **WRAP or PARTIAL**: run a half-day device spike before writing native code — upload a 500 MB file from the example app, force-quit mid-upload, verify resumption/completion delivery on relaunch, on both platforms. Only a passing spike flips this plan to wrapper mode.

- [x] **Step 3: Record the decision**

Decision: BUILD — 2026-07-22 — kesha-antonov Android uploads don't survive process death (plain thread pool, no WorkManager) and neither platform journals completions; wrapping would regress both core guarantees.

---

### Task 1: Android `EventJournal` (durable terminal-event store)

**Files:**
- Create: `android/src/main/java/com/vydia/RNUploader/EventJournal.kt`
- Modify: `android/build.gradle` (test dependency)
- Test: `android/src/test/java/com/vydia/RNUploader/EventJournalTest.kt`

**Interfaces:**
- Consumes: nothing (pure Kotlin + Gson + java.io; no Android runtime classes → plain JVM tests).
- Produces: `EventJournal.get(context: Context): EventJournal`, `Entry` data class, `append(entry)`, `unacknowledged(): List<Entry>`, `ack(eventIds: List<String>)`. Constructor `EventJournal(dir: File)` is public for tests. `EventJournal.MAX_BODY_BYTES = 64 * 1024`.

- [ ] **Step 1: Add the test dependency**

In `android/build.gradle`, inside the existing `dependencies { }` block, add:

```groovy
    testImplementation 'junit:junit:4.13.2'
```

- [ ] **Step 2: Write the failing tests**

Create `android/src/test/java/com/vydia/RNUploader/EventJournalTest.kt`:

```kotlin
package com.vydia.RNUploader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EventJournalTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private fun entry(id: String, uploadId: String = "u1") = EventJournal.Entry(
    eventId = id,
    uploadId = uploadId,
    type = "completed",
    timestamp = System.currentTimeMillis(),
    responseCode = 200,
    responseBody = "ok",
    responseHeaders = mapOf("x-a" to "b"),
  )

  @Test
  fun `append then read returns the entry`() {
    val journal = EventJournal(tmp.newFolder())
    journal.append(entry("e1"))
    val events = journal.unacknowledged()
    assertEquals(1, events.size)
    assertEquals("e1", events[0].eventId)
    assertEquals(200, events[0].responseCode)
    assertEquals("ok", events[0].responseBody)
  }

  @Test
  fun `ack removes only the acked entry`() {
    val journal = EventJournal(tmp.newFolder())
    journal.append(entry("e1"))
    journal.append(entry("e2"))
    journal.ack(listOf("e1"))
    assertEquals(listOf("e2"), journal.unacknowledged().map { it.eventId })
  }

  @Test
  fun `entries survive a new journal instance over the same dir`() {
    val dir = tmp.newFolder()
    EventJournal(dir).append(entry("e1"))
    assertEquals(1, EventJournal(dir).unacknowledged().size)
  }

  @Test
  fun `oversized body is truncated and flagged`() {
    val journal = EventJournal(tmp.newFolder())
    val big = "x".repeat(EventJournal.MAX_BODY_BYTES + 100)
    journal.append(entry("e1").copy(responseBody = big))
    val read = journal.unacknowledged()[0]
    assertTrue(read.responseBodyTruncated)
    assertTrue(read.responseBody!!.length <= EventJournal.MAX_BODY_BYTES)
  }

  @Test
  fun `corrupt file is skipped, not fatal`() {
    val dir = tmp.newFolder()
    val journal = EventJournal(dir)
    journal.append(entry("e1"))
    java.io.File(dir, "garbage.json").writeText("{not json")
    assertEquals(1, journal.unacknowledged().size)
  }

  @Test
  fun `entries are ordered by timestamp`() {
    val journal = EventJournal(tmp.newFolder())
    journal.append(entry("late").copy(timestamp = 2000))
    journal.append(entry("early").copy(timestamp = 1000))
    assertEquals(listOf("early", "late"), journal.unacknowledged().map { it.eventId })
  }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd example/RNBGUExample/android && ./gradlew :react-native-background-upload:testDebugUnitTest --tests '*EventJournalTest*'`
Expected: FAIL — `Unresolved reference: EventJournal` (compilation error).
(The example app's `settings.gradle` maps `:react-native-background-upload` to the repo's `android/` dir, so this runs against live source. Run `yarn --cwd example/RNBGUExample install` first if `node_modules` is missing.)

> **Review addendum (2026-07-23):** during review we hardened the journal beyond the
> original block below. The committed `EventJournal.kt` is authoritative; deltas from the
> code shown here:
> 1. Constructor takes `maxEntries: Int = MAX_ENTRIES` (`MAX_ENTRIES = 1000`); `append`
>    calls `pruneToMax()` which drops the oldest `.json` files (by `lastModified`) beyond
>    the cap — a runaway guard for a broken/unadopted ack loop.
> 2. `append` wraps its write in try/catch and returns on failure instead of throwing —
>    a journal-write failure (e.g. disk full) must never propagate into the worker, where
>    it would be misclassified as a retryable error and re-run a completed upload.
> 3. `Entry` includes a `toWritableMap()` member (originally listed under Task 2) and an
>    `errorKind` field.
> Two extra tests cover the cap and the non-throwing guarantee (8 tests total).

- [ ] **Step 4: Implement `EventJournal`**

Create `android/src/main/java/com/vydia/RNUploader/EventJournal.kt`:

```kotlin
package com.vydia.RNUploader

import android.content.Context
import com.google.gson.Gson
import java.io.File

// Durable record of terminal upload events (completed / error / cancelled).
// Written BEFORE the event is emitted to JS; deleted only when JS acknowledges.
// One JSON file per event named <eventId>.json — atomic-ish via tmp+rename.
class EventJournal(private val dir: File) {

  data class Entry(
    val eventId: String,
    val uploadId: String,
    val type: String, // completed | error | cancelled
    val timestamp: Long,
    val responseCode: Int? = null,
    val responseBody: String? = null,
    val responseBodyTruncated: Boolean = false,
    val responseHeaders: Map<String, String>? = null,
    val error: String? = null,
    val errorKind: String? = null, // http | network | file | unknown
    val cancelReason: String? = null, // user | system
  )

  companion object {
    const val MAX_BODY_BYTES = 64 * 1024
    private val gson = Gson()

    @Volatile
    private var instance: EventJournal? = null

    // Worker may run in a process where React never initialized, so the
    // journal must be reachable from a bare Context, not the module.
    fun get(context: Context): EventJournal =
      instance ?: synchronized(this) {
        instance
          ?: EventJournal(File(context.filesDir, "rnbgupload-events")).also { instance = it }
      }
  }

  init {
    dir.mkdirs()
  }

  @Synchronized
  fun append(entry: Entry) {
    val body = entry.responseBody
    val bounded =
      if (body != null && body.length > MAX_BODY_BYTES)
        entry.copy(responseBody = body.substring(0, MAX_BODY_BYTES), responseBodyTruncated = true)
      else entry
    val tmp = File(dir, "${entry.eventId}.tmp")
    tmp.writeText(gson.toJson(bounded))
    tmp.renameTo(File(dir, "${entry.eventId}.json"))
  }

  @Synchronized
  fun unacknowledged(): List<Entry> =
    (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
      .mapNotNull { f ->
        runCatching { gson.fromJson(f.readText(), Entry::class.java) }.getOrNull()
      }
      .filter { it.eventId != null } // corrupt-but-parseable guard
      .sortedBy { it.timestamp }

  @Synchronized
  fun ack(eventIds: List<String>) {
    eventIds.forEach { File(dir, "$it.json").delete() }
  }
}
```

Note: the cap counts UTF-16 chars, not bytes — acceptable for a safety cap; do not "fix" it to byte-accurate splitting (risks cutting surrogate pairs).
Note: Gson instantiates the data class via `Unsafe`, so a parsed file missing `eventId` yields a null field despite the non-null type — hence the explicit `it.eventId != null` filter.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd example/RNBGUExample/android && ./gradlew :react-native-background-upload:testDebugUnitTest --tests '*EventJournalTest*'`
Expected: `BUILD SUCCESSFUL`, 6 tests passing.

- [ ] **Step 6: Commit**

```bash
git add android/src/main/java/com/vydia/RNUploader/EventJournal.kt \
        android/src/test/java/com/vydia/RNUploader/EventJournalTest.kt \
        android/build.gradle
git commit -m "feat(android): durable terminal-event journal with ack"
```

---

### Task 2: Journal wiring in `UploadWorker` + module `getUnacknowledgedEvents`/`ackEvents`

**Files:**
- Modify: `android/src/main/java/com/vydia/RNUploader/UploadWorker.kt:153-171`
- Modify: `android/src/main/java/com/vydia/RNUploader/UploaderModule.kt`
- Modify: `android/src/main/java/com/vydia/RNUploader/EventReporter.kt`
- Modify: `android/src/main/java/com/vydia/RNUploader/Upload.kt` (acceptStatus option)

**Interfaces:**
- Consumes: `EventJournal` from Task 1.
- Produces: React methods `getUnacknowledgedEvents(promise)` and `ackEvents(eventIds: ReadableArray, promise)`; `Upload.acceptStatus: List<Int>` option; outcome classification (`completed` = 2xx/acceptStatus, otherwise `error` with `errorKind`); `EventReporter.cancelled(uploadId, reason: String)`, `EventReporter.error(uploadId, error, kind)`, `EventReporter.httpError(uploadId, response)`; journal entries written before every terminal emit. Task 3 depends on the `reason` parameter.

- [ ] **Step 1: Journal terminal outcomes in the worker**

First, add the `acceptStatus` option to `Upload.kt` — a field on the data class plus its parsing in `fromReadableMap`:

```kotlin
  val acceptStatus: List<Int>,   // add to the data class fields
```

```kotlin
      // add to fromReadableMap
      acceptStatus = map.getArray("acceptStatus")?.let { arr ->
        (0 until arr.size()).map { i -> arr.getInt(i) }
      } ?: listOf(),
```

Then in `UploadWorker.kt`, rename the `handleSuccess(response)` call inside `doWork` (line ~105) to `handleResponse(response)`, and replace `handleSuccess`, `handleError`, and `checkAndHandleCancellation` (lines 153–171) with:

```kotlin
  // HTTP response received. "completed" only for 2xx or per-request acceptStatus
  // codes (axios validateStatus semantics — a 400 is an error, not a completion);
  // anything else is a terminal error carrying the full response.
  private fun handleResponse(response: UploadResponse) {
    UploadProgress.complete(upload.id)
    val accepted = response.code in 200..299 || upload.acceptStatus.contains(response.code)
    EventJournal.get(context).append(
      EventJournal.Entry(
        eventId = java.util.UUID.randomUUID().toString(),
        uploadId = upload.id,
        type = if (accepted) "completed" else "error",
        timestamp = System.currentTimeMillis(),
        responseCode = response.code,
        responseBody = response.body,
        responseHeaders = response.headers,
        errorKind = if (accepted) null else "http",
        error = if (accepted) null else "HTTP ${response.code}",
      )
    )
    if (accepted) EventReporter.success(upload.id, response)
    else EventReporter.httpError(upload.id, response)
  }

  private fun handleError(error: Throwable) {
    UploadProgress.remove(upload.id)
    val kind = when {
      error is IOException &&
        runCatching { !File(upload.path).exists() }.getOrDefault(false) -> "file"
      error is IOException -> "network"
      else -> "unknown"
    }
    EventJournal.get(context).append(
      EventJournal.Entry(
        eventId = java.util.UUID.randomUUID().toString(),
        uploadId = upload.id,
        type = "error",
        timestamp = System.currentTimeMillis(),
        error = error.message ?: "Unknown exception",
        errorKind = kind,
      )
    )
    EventReporter.error(upload.id, error, kind)
  }

  // Check if cancelled by user or new worker with same ID
  // Worker won't rerun, perform teardown
  private fun checkAndHandleCancellation(): Boolean {
    if (!isStopped) return false

    val reason = if (UserCancellations.consume(upload.id)) "user" else "system"
    UploadProgress.remove(upload.id)
    EventJournal.get(context).append(
      EventJournal.Entry(
        eventId = java.util.UUID.randomUUID().toString(),
        uploadId = upload.id,
        type = "cancelled",
        timestamp = System.currentTimeMillis(),
        cancelReason = reason,
      )
    )
    EventReporter.cancelled(upload.id, reason)
    return true
  }
```

(`UserCancellations` is created in Task 3; to keep this task compiling on its own, create the stub now — Task 3 fills in the caller.)

Create `android/src/main/java/com/vydia/RNUploader/UserCancellations.kt`:

```kotlin
package com.vydia.RNUploader

// Upload ids the JS side explicitly cancelled. Consulted by the worker to
// distinguish user cancels from system kills. Same-process only: a user
// cancel always originates from live JS, so the set never needs to persist.
object UserCancellations {
  private val ids = mutableSetOf<String>()

  @Synchronized
  fun mark(id: String) {
    ids.add(id)
  }

  @Synchronized
  fun consume(id: String): Boolean = ids.remove(id)
}
```

- [ ] **Step 2: Add `reason` to the cancelled event**

In `EventReporter.kt`, replace the `cancelled` and `error` functions and add `httpError`:

```kotlin
  fun cancelled(uploadId: String, reason: String) =
    sendEvent("cancelled", Arguments.createMap().apply {
      putString("id", uploadId)
      putString("cancelReason", reason)
    })

  fun error(uploadId: String, exception: Throwable, kind: String) =
    sendEvent("error", Arguments.createMap().apply {
      putString("id", uploadId)
      putString("error", exception.message ?: "Unknown exception")
      putString("errorKind", kind)
    })

  fun httpError(uploadId: String, response: UploadResponse) =
    sendEvent("error", Arguments.createMap().apply {
      putString("id", uploadId)
      putString("error", "HTTP ${response.code}")
      putString("errorKind", "http")
      putInt("responseCode", response.code)
      putString("responseBody", response.body)
      putMap("responseHeaders", Arguments.makeNativeMap(response.headers))
    })
```

- [ ] **Step 3: Expose journal methods on the module**

In `UploaderModule.kt`, add imports `com.facebook.react.bridge.Arguments`, `com.facebook.react.bridge.ReadableArray`, `com.facebook.react.bridge.WritableMap`, then add:

```kotlin
  @ReactMethod
  fun getUnacknowledgedEvents(promise: Promise) {
    try {
      val events = EventJournal.get(reactApplicationContext).unacknowledged()
      val arr = Arguments.createArray()
      events.forEach { arr.pushMap(it.toWritableMap()) }
      promise.resolve(arr)
    } catch (exc: Throwable) {
      Log.e(TAG, exc.message, exc)
      promise.reject(exc)
    }
  }

  @ReactMethod
  fun ackEvents(eventIds: ReadableArray, promise: Promise) {
    try {
      val ids = (0 until eventIds.size()).mapNotNull { eventIds.getString(it) }
      EventJournal.get(reactApplicationContext).ack(ids)
      promise.resolve(true)
    } catch (exc: Throwable) {
      Log.e(TAG, exc.message, exc)
      promise.reject(exc)
    }
  }
```

And add the map conversion as a **member function of the `Entry` data class** in `EventJournal.kt` (a member — not an extension declared inside `EventJournal`, which would not resolve from `UploaderModule`'s scope):

```kotlin
  data class Entry(
    /* …fields exactly as in Task 1… */
  ) {
    fun toWritableMap(): com.facebook.react.bridge.WritableMap =
      com.facebook.react.bridge.Arguments.createMap().apply {
        putString("eventId", eventId)
        putString("id", uploadId)
        putString("type", type)
        putDouble("timestamp", timestamp.toDouble())
        responseCode?.let { putInt("responseCode", it) }
        responseBody?.let { putString("responseBody", it) }
        putBoolean("responseBodyTruncated", responseBodyTruncated)
        responseHeaders?.let {
          putMap("responseHeaders", com.facebook.react.bridge.Arguments.makeNativeMap(it))
        }
        error?.let { putString("error", it) }
        errorKind?.let { putString("errorKind", it) }
        cancelReason?.let { putString("cancelReason", it) }
      }
  }
```

This keeps the JUnit tests green: the JVM resolves the React classes lazily at first invocation, and the tests never call `toWritableMap`.

- [ ] **Step 4: Build to verify compilation**

Run: `cd example/RNBGUExample/android && ./gradlew :react-native-background-upload:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Re-run journal tests**

Run: `cd example/RNBGUExample/android && ./gradlew :react-native-background-upload:testDebugUnitTest`
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add android/src/main/java/com/vydia/RNUploader/
git commit -m "feat(android): journal terminal events; expose get/ack; cancel reason"
```

---

### Task 3: Android cancel intent, notification defaults + auto channel, remove `stopAllUploads`

**Files:**
- Modify: `android/src/main/java/com/vydia/RNUploader/UploaderModule.kt:83-109`
- Modify: `android/src/main/java/com/vydia/RNUploader/Upload.kt` (notification fields get defaults)
- Modify: `android/src/main/java/com/vydia/RNUploader/UploadWorker.kt` (ensure channel)

**Interfaces:**
- Consumes: `UserCancellations.mark(id)` from Task 2.
- Produces: `cancelUpload` marks user intent before cancelling; `stopAllUploads` deleted (never exposed in JS, no consumers — YAGNI); all five `notification*` options become optional with defaults; the library creates its notification channel itself (breaking: consumers no longer need notifee/channel plumbing — Task 9 makes the `android` block optional in TS).

- [ ] **Step 1: Mark user cancels and delete dead method**

In `UploaderModule.kt`, replace `cancelUpload` and delete `stopAllUploads` entirely:

```kotlin
  /*
   * Cancels file upload
   * Accepts upload ID as a first argument, this upload will be cancelled
   * Event "cancelled" will be fired when upload is cancelled.
   */
  @ReactMethod
  fun cancelUpload(uploadId: String, promise: Promise) {
    try {
      UserCancellations.mark(uploadId)
      workManager.cancelUniqueWork(uploadId)
      promise.resolve(true)
    } catch (exc: Throwable) {
      exc.printStackTrace()
      Log.e(TAG, exc.message, exc)
      promise.reject(exc)
    }
  }
```

- [ ] **Step 2: Notification defaults + library-owned channel**

In `Upload.kt`'s `fromReadableMap`, replace the five throwing `notification*` parses with defaults (`url` and `path` keep `MissingOptionException`):

```kotlin
      notificationId = (map.getString(Upload::notificationId.name)
        ?: "react-native-background-upload").hashCode(),
      notificationTitle = map.getString(Upload::notificationTitle.name)
        ?: "Uploading…",
      notificationTitleNoInternet = map.getString(Upload::notificationTitleNoInternet.name)
        ?: "Waiting for connection…",
      notificationTitleNoWifi = map.getString(Upload::notificationTitleNoWifi.name)
        ?: "Waiting for Wi-Fi…",
      notificationChannel = map.getString(Upload::notificationChannel.name)
        ?: "background-upload",
```

In `UploadWorker.kt`, add (imports `android.app.NotificationChannel`) and call it in `doWork` immediately before `setForeground(getForegroundInfo())`:

```kotlin
  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    // Idempotent per official docs: creating an existing channel is a no-op.
    val channel = NotificationChannel(
      upload.notificationChannel,
      "Uploads",
      NotificationManager.IMPORTANCE_LOW
    )
    notificationManager.createNotificationChannel(channel)
  }
```

Consumers that want a custom channel name/importance can still create the channel themselves first (creation elsewhere wins — same id is a no-op here) and pass `notificationChannel`.

- [ ] **Step 3: Build**

Run: `cd example/RNBGUExample/android && ./gradlew :react-native-background-upload:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add android/src/main/java/com/vydia/RNUploader/
git commit -m "feat(android): user-cancel intent; notification defaults + auto channel; drop stopAllUploads"
```

---

### Task 4: Android `getAllUploads()`

**Files:**
- Modify: `android/src/main/java/com/vydia/RNUploader/UploaderModule.kt` (startUpload + new method)

**Interfaces:**
- Consumes: WorkManager `getWorkInfosByTag` (verified: returns work in all states; finished records are pruned eventually, so the journal — not this call — is the source of truth for terminal outcomes).
- Produces: React method `getAllUploads(promise)` resolving `[{ id: string, state: 'pending'|'running'|'completed'|'error'|'cancelled' }]`; work requests tagged `"RNFileUploaderId:<uploadId>"`.

- [ ] **Step 1: Tag work with the upload id**

In `UploaderModule.kt` companion object add:

```kotlin
    const val ID_TAG_PREFIX = "RNFileUploaderId:"
```

In the private `startUpload`, add the id tag to the request builder:

```kotlin
    val request = OneTimeWorkRequestBuilder<UploadWorker>()
      .addTag(WORKER_TAG)
      .addTag(ID_TAG_PREFIX + upload.id)
      .setInputData(workDataOf(UploadWorker.Input.Params.name to data))
      .build()
```

- [ ] **Step 2: Add the query method**

Add to `UploaderModule.kt` (import `androidx.work.WorkInfo`):

```kotlin
  /**
   * Enumerates uploads WorkManager still knows about. Finished work is
   * auto-pruned by WorkManager after roughly ONE DAY — terminal outcomes must
   * be read from getUnacknowledgedEvents(), not from this method.
   */
  @ReactMethod
  fun getAllUploads(promise: Promise) {
    try {
      val infos = workManager.getWorkInfosByTag(WORKER_TAG).get()
      val arr = Arguments.createArray()
      for (info in infos) {
        val id = info.tags.firstOrNull { it.startsWith(ID_TAG_PREFIX) }
          ?.removePrefix(ID_TAG_PREFIX) ?: continue
        val map = Arguments.createMap()
        map.putString("id", id)
        map.putString(
          "state",
          when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "pending"
            WorkInfo.State.RUNNING -> "running"
            WorkInfo.State.SUCCEEDED -> "completed"
            WorkInfo.State.FAILED -> "error"
            WorkInfo.State.CANCELLED -> "cancelled"
          }
        )
        arr.pushMap(map)
      }
      promise.resolve(arr)
    } catch (exc: Throwable) {
      Log.e(TAG, exc.message, exc)
      promise.reject(exc)
    }
  }
```

(`.get()` blocks the NativeModules thread briefly; the query is local-database-only. Acceptable — matches the existing module's synchronous style.)

- [ ] **Step 3: Build**

Run: `cd example/RNBGUExample/android && ./gradlew :react-native-background-upload:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add android/src/main/java/com/vydia/RNUploader/UploaderModule.kt
git commit -m "feat(android): getAllUploads via id-tagged WorkManager query"
```

---

> **PIVOT (2026-07-23): iOS is now a Swift REWRITE, not edits to the Obj-C.**
> Decided with Dylan: the three Obj-C files (<500 lines) are old and hard to read, Diana
> already builds Swift under static linkage and runs new arch, and Dylan will own this. So
> Tasks 5–8 below are superseded — the Obj-C code blocks remain only as a behavior reference
> for what the Swift must reproduce. The rewrite:
> - `RNFileUploader.swift` — `@objc(VydiaRNFileUploader)` class : `RCTEventEmitter`, the two
>   background `URLSession`s, the delegate, all exported methods, classification, throttle,
>   bg-relaunch handler. `RNFileUploader.m` — ~30-line `RCT_EXTERN_MODULE`/`RCT_EXTERN_METHOD`
>   shim (unavoidable on the bridge). `EventJournal.swift` + `TaskMap.swift` — `Codable`,
>   with the cap / backup-exclusion / non-throwing-write mitigations. Delete the old
>   `VydiaRNFileUploader.m`, `Helper.{h,m}`.
> - Keep the JS contract identical: module name `VydiaRNFileUploader`, events `RNFileUploader-*`.
> - Target iOS **15.1** (Diana's floor); podspec bumped from 9.0, `swift_version` + `React-Core`
>   dependency + `DEFINES_MODULE` added; example app deployment target bumped to 15.1.
> - **Correctness invariant:** the background `NSURLSession` delegate API is load-bearing and
>   has no modern replacement — port it verbatim (session ids, `discretionary=NO`,
>   `HTTPMaximumConnectionsPerHost=1`, `waitsForConnectivity`, `uploadTask(with:fromFile:)`).
>   Do NOT use `async` `URLSession.upload` (no background support). Journal stays synchronous
>   (serial queue), NOT an actor, to preserve "journal before emit" ordering.
> - Verification: `xcodebuild` per checkpoint + on-device Task 11 (no plain-unit-test path).
>   Started with an isolated Swift interop probe (`RNBGUProbe.{swift,m}`) to prove the pod/Swift
>   setup compiles and registers before the real port; probe is deleted afterward.

### Task 5: iOS `RNBGUEventJournal` + journal wiring

**Files:**
- Create: `ios/RNBGUEventJournal.h`
- Create: `ios/RNBGUEventJournal.m`
- Create: `ios/RNBGUTaskMap.h`
- Create: `ios/RNBGUTaskMap.m`
- Modify: `ios/VydiaRNFileUploader.m:369-399` (didCompleteWithError) and `startUpload`

**Interfaces:**
- Consumes: nothing new.
- Produces: `+[RNBGUEventJournal append:]` (takes `NSDictionary` containing `eventId`), `+[RNBGUEventJournal unacknowledged]` → `NSArray<NSDictionary *>`, `+[RNBGUEventJournal ack:]`; `RNBGUTaskMap` persisted `sessionId:taskIdentifier → {id, acceptStatus}` sidecar (`setMeta:forKey:`, `metaForKey:`, `removeKey:`); uploader helpers `-taskMapKeyFor:task:`, `-uploadIdFor:task:`, `-acceptStatusFor:task:`; `didCompleteWithError` classifies (2xx/acceptStatus → completed, other HTTP → error with `errorKind: http`, transport → `errorKind: network`) and journals before emitting; completed events gain `eventId` + `responseHeaders`; cancelled events gain `cancelReason`. Tasks 6 and 8 use the helpers; Task 6 exports the journal to JS.

**Why the task map exists:** Apple documents `taskDescription` only as an uninterpreted app string — there is NO guarantee it survives process death, and DTS guidance is to persist task metadata externally keyed by `taskIdentifier` (which is stable for a background session's tasks across relaunch, unique per session). `taskDescription` stays the primary id; the map is the durable fallback so a relaunch can never orphan an event under an unknown id.

- [ ] **Step 1: Create the journal class**

`ios/RNBGUEventJournal.h`:

```objc
#import <Foundation/Foundation.h>

// Durable record of terminal upload events. Written before emitting to JS,
// deleted only on explicit acknowledgment from JS. One JSON file per event.
// Bounded by kMaxEntries (drops oldest) as a runaway guard; excluded from
// device backups. Mirrors the Android EventJournal contract.
@interface RNBGUEventJournal : NSObject
+ (void)append:(NSDictionary *)event; // event MUST contain @"eventId"
+ (NSArray<NSDictionary *> *)unacknowledged;
+ (void)ack:(NSArray<NSString *> *)eventIds;
@end
```

`ios/RNBGUEventJournal.m`:

```objc
#import "RNBGUEventJournal.h"

static NSString *const kJournalDirName = @"RNFileUploaderEvents";
// Runaway guard, mirrors Android's EventJournal.MAX_ENTRIES. Only fires if JS
// never drains the journal via ack; drops the oldest entries when exceeded.
static const NSUInteger kMaxEntries = 1000;

@implementation RNBGUEventJournal

+ (dispatch_queue_t)queue {
    static dispatch_queue_t q;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        q = dispatch_queue_create("com.vydia.rnbgupload.journal", DISPATCH_QUEUE_SERIAL);
    });
    return q;
}

+ (NSURL *)dirURL {
    NSURL *appSupport = [[NSFileManager.defaultManager URLsForDirectory:NSApplicationSupportDirectory
                                                               inDomains:NSUserDomainMask] firstObject];
    NSURL *dir = [appSupport URLByAppendingPathComponent:kJournalDirName isDirectory:YES];
    [NSFileManager.defaultManager createDirectoryAtURL:dir
                           withIntermediateDirectories:YES
                                            attributes:nil
                                                 error:nil];
    // Application Support is backed up to iCloud/iTunes by default; the journal
    // is transient device-local state, so exclude it from backups.
    NSURL *mutableDir = dir;
    [mutableDir setResourceValue:@YES forKey:NSURLIsExcludedFromBackupKey error:nil];
    return dir;
}

+ (void)append:(NSDictionary *)event {
    NSString *eventId = event[@"eventId"];
    if (!eventId) return;
    dispatch_sync([self queue], ^{
        NSError *err = nil;
        NSData *data = [NSJSONSerialization dataWithJSONObject:event options:0 error:&err];
        if (!data) return;
        // writeToURL returns BOOL (never throws), so a failed journal write can
        // never propagate into the upload delegate and re-trigger the upload.
        NSURL *file = [[self dirURL] URLByAppendingPathComponent:[eventId stringByAppendingString:@".json"]];
        [data writeToURL:file atomically:YES];
        [self pruneToMax];
    });
}

// Caller already holds the serial queue. Prune oldest by content-modification
// date beyond kMaxEntries.
+ (void)pruneToMax {
    NSArray<NSURLResourceKey> *keys = @[NSURLContentModificationDateKey];
    NSArray<NSURL *> *files = [NSFileManager.defaultManager contentsOfDirectoryAtURL:[self dirURL]
                                                          includingPropertiesForKeys:keys
                                                                             options:0
                                                                               error:nil];
    NSMutableArray<NSURL *> *jsonFiles = [NSMutableArray array];
    for (NSURL *f in files) {
        if ([f.pathExtension isEqualToString:@"json"]) [jsonFiles addObject:f];
    }
    if (jsonFiles.count <= kMaxEntries) return;
    [jsonFiles sortUsingComparator:^NSComparisonResult(NSURL *a, NSURL *b) {
        NSDate *da = nil, *db = nil;
        [a getResourceValue:&da forKey:NSURLContentModificationDateKey error:nil];
        [b getResourceValue:&db forKey:NSURLContentModificationDateKey error:nil];
        return [(da ?: NSDate.distantPast) compare:(db ?: NSDate.distantPast)];
    }];
    NSUInteger toRemove = jsonFiles.count - kMaxEntries;
    for (NSUInteger i = 0; i < toRemove; i++) {
        [NSFileManager.defaultManager removeItemAtURL:jsonFiles[i] error:nil];
    }
}

+ (NSArray<NSDictionary *> *)unacknowledged {
    __block NSMutableArray *events = [NSMutableArray array];
    dispatch_sync([self queue], ^{
        NSArray<NSURL *> *files = [NSFileManager.defaultManager contentsOfDirectoryAtURL:[self dirURL]
                                                              includingPropertiesForKeys:nil
                                                                                 options:0
                                                                                   error:nil];
        for (NSURL *file in files) {
            if (![file.pathExtension isEqualToString:@"json"]) continue;
            NSData *data = [NSData dataWithContentsOfURL:file];
            if (!data) continue;
            id parsed = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
            if ([parsed isKindOfClass:[NSDictionary class]] && parsed[@"eventId"]) {
                [events addObject:parsed];
            }
        }
        [events sortUsingComparator:^NSComparisonResult(NSDictionary *a, NSDictionary *b) {
            return [(a[@"timestamp"] ?: @0) compare:(b[@"timestamp"] ?: @0)];
        }];
    });
    return events;
}

+ (void)ack:(NSArray<NSString *> *)eventIds {
    dispatch_sync([self queue], ^{
        for (NSString *eventId in eventIds) {
            NSURL *file = [[self dirURL] URLByAppendingPathComponent:[eventId stringByAppendingString:@".json"]];
            [NSFileManager.defaultManager removeItemAtURL:file error:nil];
        }
    });
}

@end
```

- [ ] **Step 2: Create the persisted task map**

`ios/RNBGUTaskMap.h`:

```objc
#import <Foundation/Foundation.h>

// Durable sessionId:taskIdentifier -> uploadId mapping. taskDescription has no
// documented persistence guarantee across process death; this map does.
@interface RNBGUTaskMap : NSObject
+ (void)setMeta:(NSDictionary *)meta forKey:(NSString *)key; // meta: {id, acceptStatus}
+ (NSDictionary * _Nullable)metaForKey:(NSString *)key;
+ (void)removeKey:(NSString *)key;
@end
```

`ios/RNBGUTaskMap.m`:

```objc
#import "RNBGUTaskMap.h"

static NSString *const kTaskMapFile = @"RNFileUploaderTaskMap.json";

@implementation RNBGUTaskMap

+ (dispatch_queue_t)queue {
    static dispatch_queue_t q;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        q = dispatch_queue_create("com.vydia.rnbgupload.taskmap", DISPATCH_QUEUE_SERIAL);
    });
    return q;
}

+ (NSURL *)fileURL {
    NSURL *appSupport = [[NSFileManager.defaultManager URLsForDirectory:NSApplicationSupportDirectory
                                                               inDomains:NSUserDomainMask] firstObject];
    [NSFileManager.defaultManager createDirectoryAtURL:appSupport
                           withIntermediateDirectories:YES
                                            attributes:nil
                                                 error:nil];
    return [appSupport URLByAppendingPathComponent:kTaskMapFile];
}

// Callers hold the serial queue via dispatch_sync; these two are queue-private.
+ (NSMutableDictionary *)readMap {
    NSData *data = [NSData dataWithContentsOfURL:[self fileURL]];
    if (!data) return [NSMutableDictionary dictionary];
    id parsed = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
    return [parsed isKindOfClass:[NSDictionary class]]
        ? [parsed mutableCopy]
        : [NSMutableDictionary dictionary];
}

+ (void)writeMap:(NSDictionary *)map {
    NSData *data = [NSJSONSerialization dataWithJSONObject:map options:0 error:nil];
    if (data) [data writeToURL:[self fileURL] atomically:YES];
}

+ (void)setMeta:(NSDictionary *)meta forKey:(NSString *)key {
    if (!meta || !key) return;
    dispatch_sync([self queue], ^{
        NSMutableDictionary *map = [self readMap];
        map[key] = meta;
        [self writeMap:map];
    });
}

+ (NSDictionary *)metaForKey:(NSString *)key {
    __block NSDictionary *result = nil;
    dispatch_sync([self queue], ^{
        id value = [self readMap][key];
        if ([value isKindOfClass:[NSDictionary class]]) result = value;
    });
    return result;
}

+ (void)removeKey:(NSString *)key {
    dispatch_sync([self queue], ^{
        NSMutableDictionary *map = [self readMap];
        [map removeObjectForKey:key];
        [self writeMap:map];
    });
}

@end
```

Add the uploader helpers to `VydiaRNFileUploader.m` (with `#import "RNBGUTaskMap.h"`):

```objc
- (NSString *)taskMapKeyFor:(NSURLSession *)session task:(NSURLSessionTask *)task {
    return [NSString stringWithFormat:@"%@:%lu",
            session.configuration.identifier ?: @"",
            (unsigned long)task.taskIdentifier];
}

// taskDescription is the primary id; the persisted map is the fallback for
// tasks observed after a relaunch where taskDescription may not have survived.
- (NSString *)uploadIdFor:(NSURLSession *)session task:(NSURLSessionTask *)task {
    return task.taskDescription
        ?: [RNBGUTaskMap metaForKey:[self taskMapKeyFor:session task:task]][@"id"]
        ?: @"unknown";
}

- (NSArray *)acceptStatusFor:(NSURLSession *)session task:(NSURLSessionTask *)task {
    NSArray *accept = [RNBGUTaskMap metaForKey:[self taskMapKeyFor:session task:task]][@"acceptStatus"];
    return [accept isKindOfClass:[NSArray class]] ? accept : @[];
}
```

And record the mapping in `startUpload`, immediately after the taskDescription assignment:

```objc
        [RNBGUTaskMap setMeta:@{
            @"id": uploadTask.taskDescription,
            @"acceptStatus": options[@"acceptStatus"] ?: @[],
        } forKey:[self taskMapKeyFor:session task:uploadTask]];
```

(The map persists `acceptStatus` alongside the id so classification still works for tasks that complete after a relaunch, when the original `startUpload` options are gone.)

- [ ] **Step 3: Journal terminal events in `didCompleteWithError`**

In `VydiaRNFileUploader.m`, add `#import "RNBGUEventJournal.h"` and a static set near the other statics:

```objc
static NSMutableSet<NSString *> *_userCancelledIds = nil;
```

Initialize it in `init` alongside `_responsesData`:

```objc
    _userCancelledIds = [NSMutableSet set];
```

Replace the entire `didCompleteWithError` delegate method (lines 369–399) with:

```objc
- (void)URLSession:(NSURLSession *)session
              task:(NSURLSessionTask *)task
didCompleteWithError:(NSError *)error {
    NSString *uploadId = [self uploadIdFor:session task:task];
    NSMutableDictionary *data = [NSMutableDictionary dictionaryWithObjectsAndKeys:uploadId, @"id", nil];
    NSURLSessionDataTask *uploadTask = (NSURLSessionDataTask *)task;
    NSHTTPURLResponse *response = (NSHTTPURLResponse *)uploadTask.response;
    if (response != nil) {
        [data setObject:[NSNumber numberWithInteger:response.statusCode] forKey:@"responseCode"];
        NSMutableDictionary *headers = [NSMutableDictionary dictionary];
        [response.allHeaderFields enumerateKeysAndObjectsUsingBlock:^(id key, id val, BOOL *stop) {
            headers[[key description]] = [val description];
        }];
        [data setObject:headers forKey:@"responseHeaders"];
    }
    //Add data that was collected earlier by the didReceiveData method
    NSMutableData *responseData = _responsesData[@(task.taskIdentifier)];
    if (responseData) {
        [_responsesData removeObjectForKey:@(task.taskIdentifier)];
        NSString *body = [[NSString alloc] initWithData:responseData encoding:NSUTF8StringEncoding] ?: @"";
        // Journal cap: 64KB, flag truncation
        static const NSUInteger kMaxBodyChars = 64 * 1024;
        if (body.length > kMaxBodyChars) {
            [data setObject:[body substringToIndex:kMaxBodyChars] forKey:@"responseBody"];
            [data setObject:@YES forKey:@"responseBodyTruncated"];
        } else {
            [data setObject:body forKey:@"responseBody"];
        }
    } else {
        [data setObject:[NSNull null] forKey:@"responseBody"];
    }

    [data setObject:[[NSUUID UUID] UUIDString] forKey:@"eventId"];
    [data setObject:@([[NSDate date] timeIntervalSince1970] * 1000) forKey:@"timestamp"];

    NSString *eventName;
    if (error == nil) {
        // "completed" only for 2xx or per-request acceptStatus codes (axios
        // validateStatus semantics); other HTTP responses are terminal errors.
        NSInteger status = response ? response.statusCode : 0;
        NSArray *accept = [self acceptStatusFor:session task:task];
        if ((status >= 200 && status < 300) || [accept containsObject:@(status)]) {
            eventName = @"completed";
        } else {
            eventName = @"error";
            [data setObject:@"http" forKey:@"errorKind"];
            [data setObject:[NSString stringWithFormat:@"HTTP %ld", (long)status] forKey:@"error"];
        }
    } else {
        [data setObject:error.localizedDescription forKey:@"error"];
        if (error.code == NSURLErrorCancelled) {
            eventName = @"cancelled";
            BOOL userCancelled;
            @synchronized (_userCancelledIds) {
                userCancelled = [_userCancelledIds containsObject:uploadId];
                [_userCancelledIds removeObject:uploadId];
            }
            [data setObject:(userCancelled ? @"user" : @"system") forKey:@"cancelReason"];
        } else {
            eventName = @"error";
            [data setObject:@"network" forKey:@"errorKind"];
        }
    }

    // Journal BEFORE emitting: the emit is best-effort (JS may be dead),
    // the journal entry is the durable record until JS acks it.
    NSMutableDictionary *journalEntry = [data mutableCopy];
    [journalEntry setObject:eventName forKey:@"type"];
    if (journalEntry[@"responseBody"] == [NSNull null]) [journalEntry removeObjectForKey:@"responseBody"];
    [RNBGUEventJournal append:journalEntry];
    [RNBGUTaskMap removeKey:[self taskMapKeyFor:session task:task]];

    [self _sendEventWithName:[@"RNFileUploader-" stringByAppendingString:eventName] body:data];
}
```

- [ ] **Step 4: Build the example app**

Run: `cd example/RNBGUExample && yarn install && (cd ios && pod install) && yarn ios` (builds and launches on a simulator; alternatively build `ios/RNBGUExample.xcworkspace` in Xcode).
Expected: build succeeds. New files are picked up by the podspec glob `ios/*.{h,m}` — no pbxproj edits needed for consumers; `pod install` regenerates the pod target.

- [ ] **Step 5: Commit**

```bash
git add ios/RNBGUEventJournal.h ios/RNBGUEventJournal.m ios/RNBGUTaskMap.h ios/RNBGUTaskMap.m ios/VydiaRNFileUploader.m
git commit -m "feat(ios): journal terminal events; persisted task map; response headers; cancel reason"
```

---

### Task 6: iOS `getUnacknowledgedEvents` / `ackEvents` / `getAllUploads` exports

**Files:**
- Modify: `ios/VydiaRNFileUploader.m` (new RCT_EXPORT_METHODs; extend `cancelUpload`)

**Interfaces:**
- Consumes: `RNBGUEventJournal` (Task 5), `_userCancelledIds` (Task 5).
- Produces: `getUnacknowledgedEvents()`, `ackEvents(ids)`, `getAllUploads()` — same JS names as Android (Task 2/4). `getAllUploads` returns live session tasks only: `[{ id, state: 'running'|'pending', bytesSent, totalBytes }]`.

- [ ] **Step 1: Add the export methods**

Add to `VydiaRNFileUploader.m`:

```objc
RCT_EXPORT_METHOD(getUnacknowledgedEvents:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject) {
    resolve([RNBGUEventJournal unacknowledged]);
}

RCT_EXPORT_METHOD(ackEvents:(NSArray<NSString *> *)eventIds resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject) {
    [RNBGUEventJournal ack:eventIds];
    resolve(@YES);
}

/*
 * Enumerates upload tasks the OS sessions still know about (running/suspended).
 * Terminal outcomes are NOT visible here — read getUnacknowledgedEvents for those.
 */
RCT_EXPORT_METHOD(getAllUploads:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject) {
    NSMutableArray<NSURLSession *> *sessions = [NSMutableArray array];
    if (_urlSession) [sessions addObject:_urlSession];
    if (_wifiOnlyUrlSession) [sessions addObject:_wifiOnlyUrlSession];

    NSMutableArray *result = [NSMutableArray array];
    dispatch_group_t group = dispatch_group_create();

    for (NSURLSession *session in sessions) {
        dispatch_group_enter(group);
        [session getTasksWithCompletionHandler:^(NSArray *dataTasks, NSArray *uploadTasks, NSArray *downloadTasks) {
            for (NSURLSessionTask *task in uploadTasks) {
                NSString *taskUploadId = [self uploadIdFor:session task:task];
                if ([taskUploadId isEqualToString:@"unknown"]) continue;
                NSString *state = task.state == NSURLSessionTaskStateRunning ? @"running" : @"pending";
                @synchronized (result) {
                    [result addObject:@{
                        @"id": taskUploadId,
                        @"state": state,
                        @"bytesSent": @(task.countOfBytesSent),
                        @"totalBytes": @(task.countOfBytesExpectedToSend),
                    }];
                }
            }
            dispatch_group_leave(group);
        }];
    }

    dispatch_group_notify(group, dispatch_get_main_queue(), ^{
        resolve(result);
    });
}
```

- [ ] **Step 2: Track user cancels in `cancelUpload` and fix its task matching**

At the top of the existing `cancelUpload` method body (before the sessions loop), add:

```objc
    @synchronized (_userCancelledIds) {
        [_userCancelledIds addObject:cancelUploadId];
    }
```

In the same method, the matching line inside the tasks loop compares `taskDescription` directly — a task observed after a relaunch may have lost it. Replace:

```objc
                if ([uploadTask.taskDescription isEqualToString:cancelUploadId]){
```

with:

```objc
                if ([[self uploadIdFor:session task:uploadTask] isEqualToString:cancelUploadId]){
```

- [ ] **Step 3: Build**

Same command as Task 5 Step 3. Expected: build succeeds.

- [ ] **Step 4: Commit**

```bash
git add ios/VydiaRNFileUploader.m
git commit -m "feat(ios): expose journal get/ack and getAllUploads; track user cancels"
```

---

### Task 7: iOS background-relaunch completion handler

**Files:**
- Create: `ios/VydiaRNFileUploader.h`
- Modify: `ios/VydiaRNFileUploader.m` (class extension removal + delegate method + static handler store)
- Modify: `README.md` (AppDelegate instructions — full text in Task 10)

**Interfaces:**
- Consumes: nothing new.
- Produces: `+[VydiaRNFileUploader setBackgroundSessionCompletionHandler:forIdentifier:]` for AppDelegates; `URLSessionDidFinishEventsForBackgroundURLSession:` implemented. Session identifiers in play: `ReactNativeBackgroundUpload`, `ReactNativeBackgroundUpload_WifiOnly`.

- [ ] **Step 1: Create the public header**

`ios/VydiaRNFileUploader.h`:

```objc
#import <Foundation/Foundation.h>
#import <React/RCTEventEmitter.h>
#import <React/RCTBridgeModule.h>

@interface VydiaRNFileUploader : RCTEventEmitter <RCTBridgeModule, NSURLSessionTaskDelegate>

/**
 * Call from AppDelegate's application:handleEventsForBackgroundURLSession:completionHandler:
 * so iOS can relaunch the app for uploads that finish while it is terminated.
 * The handler is invoked after all pending session events have been delivered
 * (and journaled), on the main queue.
 */
+ (void)setBackgroundSessionCompletionHandler:(void (^)(void))handler
                                forIdentifier:(NSString *)identifier;

@end
```

In `VydiaRNFileUploader.m`, delete the inline `@interface VydiaRNFileUploader : RCTEventEmitter …` block (lines 8–9) and replace with `#import "VydiaRNFileUploader.h"`.

- [ ] **Step 2: Store and fire the handler**

In `VydiaRNFileUploader.m`, add near the other statics:

```objc
static NSMutableDictionary<NSString *, void (^)(void)> *_bgCompletionHandlers = nil;
```

Add the class method and delegate method:

```objc
+ (void)setBackgroundSessionCompletionHandler:(void (^)(void))handler
                                forIdentifier:(NSString *)identifier {
    @synchronized (self) {
        if (!_bgCompletionHandlers) _bgCompletionHandlers = [NSMutableDictionary dictionary];
        _bgCompletionHandlers[identifier] = [handler copy];
    }
}

- (void)URLSessionDidFinishEventsForBackgroundURLSession:(NSURLSession *)session {
    NSString *identifier = session.configuration.identifier;
    if (!identifier) return;
    void (^handler)(void);
    @synchronized ([self class]) {
        handler = _bgCompletionHandlers[identifier];
        [_bgCompletionHandlers removeObjectForKey:identifier];
    }
    if (handler) {
        dispatch_async(dispatch_get_main_queue(), handler);
    }
}
```

Known limitation to note in the README (Task 10): the sessions are recreated inside the module's `init`, which runs when the React bridge starts. RN apps initialize the bridge during a background relaunch, so events are delivered and journaled; if a host app defers bridge startup in background, events wait in `nsurlsessiond` until the next real launch and are journaled then. Nothing is lost either way — the journal is the contract.

- [ ] **Step 3: Build**

Same build as Task 5 Step 3. Expected: build succeeds.

- [ ] **Step 4: Commit**

```bash
git add ios/VydiaRNFileUploader.h ios/VydiaRNFileUploader.m
git commit -m "feat(ios): background-relaunch completion handler support"
```

---

### Task 8: iOS progress throttle, UUID ids, dead-code removal

**Files:**
- Modify: `ios/VydiaRNFileUploader.m`

**Interfaces:**
- Consumes: nothing new.
- Produces: progress events throttled to 500ms per task (parity with Android); default upload ids are UUIDs; `startUpload` loses the multipart/PHAsset/appGroup branches (never functional for background use; TS types already forbid them).

- [ ] **Step 1: Throttle progress events**

Add near the other statics:

```objc
static NSMutableDictionary<NSString *, NSNumber *> *_lastProgressAt = nil;
```

Initialize in `init`: `_lastProgressAt = [NSMutableDictionary dictionary];`

Replace the body of `didSendBodyData` (lines 401–418) with:

```objc
    float progress = -1;
    if (totalBytesExpectedToSend > 0) {
        progress = 100.0 * (float)totalBytesSent / (float)totalBytesExpectedToSend;
    }

    // Throttle to 500ms per task (parity with Android). Always deliver 100%.
    NSString *taskId = [self uploadIdFor:session task:task];
    NSTimeInterval now = [[NSDate date] timeIntervalSince1970];
    @synchronized (_lastProgressAt) {
        NSNumber *last = _lastProgressAt[taskId];
        if (progress < 100 && last && now - last.doubleValue < 0.5) return;
        _lastProgressAt[taskId] = @(now);
    }

    NSDictionary *data = @{ @"id": taskId, @"progress": [NSNumber numberWithFloat:progress] };
    [self _sendEventWithName:@"RNFileUploader-progress" body:data];
```

Also remove the task's entry in `didCompleteWithError` — add one line right after the `data` dictionary is created:

```objc
    @synchronized (_lastProgressAt) { [_lastProgressAt removeObjectForKey:uploadId]; }
```

(`uploadId` is the resolved id local added to `didCompleteWithError` in Task 5.)

- [ ] **Step 2: UUID default ids**

In `startUpload`, delete the `static int uploadId` counter usage (lines 124–128 and 201) and replace the taskDescription assignment:

```objc
        uploadTask.taskDescription = customUploadId ?: [[NSUUID UUID] UUIDString];
```

Delete the `static int uploadId = 0;` declaration (line 16).

- [ ] **Step 3: Delete dead branches**

In `startUpload`:
- Delete the `assets-library` copy block (lines 160–174) and the `copyAssetToFile:` method (lines 81–109) and the `#import <Photos/Photos.h>`.
- Delete the multipart branch: the `if ([uploadType isEqualToString:@"multipart"]) { … } else { … }` collapses to the raw path only; reject non-raw types explicitly:

```objc
        if (uploadType != nil && ![uploadType isEqualToString:@"raw"]) {
            return reject(@"RN Uploader", @"Only type: 'raw' is supported", nil);
        }
        if (parameters.count > 0) {
            return reject(@"RN Uploader", @"'parameters' requires multipart, which is not supported", nil);
        }
        NSURLSessionDataTask *uploadTask = (NSURLSessionDataTask *)[session uploadTaskWithRequest:request
                                                                                          fromFile:[NSURL URLWithString:fileURI]];
```

- Delete `createBodyWithBoundary:…` (lines 281–319), `guessMIMETypeFromFileName:` (lines 67–79), the `#import <MobileCoreServices/MobileCoreServices.h>`, and the `appGroup` handling (lines 136, 179–181 — it was a no-op: NSURLSession copies its configuration at creation, so mutating it later never had any effect).
- Delete the now-unused local variables (`fieldName`, `appGroup`, `parameters` stays only for the rejection check above).

- [ ] **Step 4: Build and smoke-test**

Build as in Task 5 Step 3, then run the example app on a simulator, press Upload against `example/server` (start it with `yarn --cwd example/server install && yarn --cwd example/server start`; it listens on port **3000**. Set `UPLOAD_URL` in `example/RNBGUExample/App.tsx` to `http://localhost:3000/upload`).
Expected: progress fires at ~2/sec max; completed event includes `eventId`, `responseHeaders`.

- [ ] **Step 5: Commit**

```bash
git add ios/VydiaRNFileUploader.m
git commit -m "feat(ios): throttle progress, UUID ids, remove dead multipart/PHAsset/appGroup code"
```

---

### Task 9: JS API surface + types + Jest harness

**Files:**
- Modify: `src/index.ts`, `src/types.ts`
- Create: `jest.config.js`, `babel.config.js`, `src/__tests__/index.test.ts`
- Modify: `package.json` (test script + devDeps)

**Interfaces:**
- Consumes: native methods from Tasks 2/4/6 (`getUnacknowledgedEvents`, `ackEvents`, `getAllUploads`).
- Produces (public API, all additive):

```ts
export interface JournaledEvent {
  eventId: string;
  id: string; // upload id
  type: 'completed' | 'error' | 'cancelled';
  timestamp: number;
  responseCode?: number;
  responseBody?: string;
  responseBodyTruncated?: boolean;
  responseHeaders?: Record<string, string>;
  error?: string;
  errorKind?: 'http' | 'network' | 'file' | 'unknown';
  cancelReason?: 'user' | 'system';
}
export interface UploadSnapshot {
  id: string;
  state: 'pending' | 'running' | 'completed' | 'error' | 'cancelled';
  bytesSent?: number;   // iOS only
  totalBytes?: number;  // iOS only
}
getUnacknowledgedEvents(): Promise<JournaledEvent[]>
ackEvents(eventIds: string[]): Promise<boolean>
getAllUploads(): Promise<UploadSnapshot[]>
// CompletedData gains: responseHeaders?: Record<string, string>; eventId?: string
// cancelled EventData gains: cancelReason?: 'user' | 'system'
// ErrorData gains: errorKind + responseCode/responseBody/responseHeaders (http errors)
// UploadOptions gains: acceptStatus?: number[]; android becomes optional
```

- [ ] **Step 1: Add Jest tooling**

In `package.json` devDependencies add `"jest": "^29.7.0"`, `"babel-jest": "^29.7.0"`, `"@babel/preset-typescript": "^7.24.0"` and a script `"test": "jest"`.

Create `babel.config.js` (root; the example app has its own config and Metro reads from the example dir, so this only affects Jest):

```js
module.exports = {
  presets: [['@babel/preset-env', {targets: {node: 'current'}}], '@babel/preset-typescript'],
};
```

Create `jest.config.js`:

```js
module.exports = {
  testEnvironment: 'node',
  testMatch: ['**/__tests__/**/*.test.ts'],
};
```

Run: `yarn install`

- [ ] **Step 2: Write the failing test**

Create `src/__tests__/index.test.ts`:

```ts
// Jest only allows out-of-scope variables in mock factories when their names
// start with "mock" — do not rename these.
const mockStartUpload = jest.fn(async () => 'id-1');
const mockGetUnacknowledgedEvents = jest.fn(async () => [
  {eventId: 'e1', id: 'u1', type: 'completed', timestamp: 1, responseCode: 200},
]);
const mockAckEvents = jest.fn(async () => true);
const mockGetAllUploads = jest.fn(async () => [{id: 'u1', state: 'running'}]);

jest.mock('react-native', () => ({
  Platform: {OS: 'ios'},
  DeviceEventEmitter: {addListener: jest.fn()},
  NativeModules: {
    VydiaRNFileUploader: {
      addListener: jest.fn(),
      startUpload: mockStartUpload,
      getUnacknowledgedEvents: mockGetUnacknowledgedEvents,
      ackEvents: mockAckEvents,
      getAllUploads: mockGetAllUploads,
    },
  },
}));

import Upload from '../index';

describe('journal + query API', () => {
  it('exposes getUnacknowledgedEvents', async () => {
    const events = await Upload.getUnacknowledgedEvents();
    expect(events[0].eventId).toBe('e1');
  });

  it('exposes ackEvents', async () => {
    await Upload.ackEvents(['e1']);
    expect(mockAckEvents).toHaveBeenCalledWith(['e1']);
  });

  it('exposes getAllUploads', async () => {
    const uploads = await Upload.getAllUploads();
    expect(uploads[0].state).toBe('running');
  });

  it('still prefixes file paths in startUpload', async () => {
    await Upload.startUpload({
      url: 'https://x',
      path: '/tmp/f.bin',
      method: 'POST',
      type: 'raw',
      android: {
        notificationId: 'n',
        notificationTitle: 't',
        notificationTitleNoWifi: 'w',
        notificationTitleNoInternet: 'i',
        notificationChannel: 'c',
      },
    });
    expect(mockStartUpload).toHaveBeenCalledWith(
      expect.objectContaining({path: 'file:///tmp/f.bin'}),
    );
  });
});
```

- [ ] **Step 3: Run to verify failure**

Run: `yarn test`
Expected: FAIL — `Upload.getUnacknowledgedEvents is not a function`.

- [ ] **Step 4: Implement the JS surface**

In `src/types.ts` add (and extend `CompletedData`/the cancelled overload):

```ts
export interface JournaledEvent {
  eventId: string;
  id: string;
  type: 'completed' | 'error' | 'cancelled';
  timestamp: number;
  responseCode?: number;
  responseBody?: string;
  responseBodyTruncated?: boolean;
  responseHeaders?: Record<string, string>;
  error?: string;
  errorKind?: 'http' | 'network' | 'file' | 'unknown';
  cancelReason?: 'user' | 'system';
}

export interface UploadSnapshot {
  id: string;
  state: 'pending' | 'running' | 'completed' | 'error' | 'cancelled';
  bytesSent?: number;
  totalBytes?: number;
}

export interface CancelledData extends EventData {
  cancelReason?: 'user' | 'system';
}
```

Replace `ErrorData` with (HTTP errors now arrive here, carrying the full response):

```ts
export interface ErrorData extends EventData {
  error: string;
  errorKind?: 'http' | 'network' | 'file' | 'unknown';
  responseCode?: number;
  responseBody?: string;
  responseHeaders?: Record<string, string>;
}
```

In `UploadOptions`, add `acceptStatus` and make the `android` block optional (all its fields optional too — native now supplies defaults and creates the channel):

```ts
  /**
   * Non-2xx HTTP statuses to treat as successful completion, e.g. [409] when
   * retries make duplicate-create conflicts expected. Everything else non-2xx
   * emits an 'error' event with errorKind 'http' and the response attached.
   */
  acceptStatus?: number[];
  android?: Partial<AndroidOnlyUploadOptions>;
```

Change `CompletedData` to:

```ts
export interface CompletedData extends EventData {
  eventId?: string;
  responseCode: number;
  responseBody: string;
  responseHeaders?: Record<string, string>;
}
```

Change the `cancelled` overload in `AddListener` to use `CancelledData`.

In `src/index.ts` add:

```ts
/**
 * Terminal events (completed/error/cancelled) are journaled natively before
 * being emitted, so they survive the app being killed. Read them on startup,
 * process them, then acknowledge — unacked events are re-delivered here forever.
 */
const getUnacknowledgedEvents = (): Promise<JournaledEvent[]> =>
  NativeModule.getUnacknowledgedEvents();

const ackEvents = (eventIds: string[]): Promise<boolean> =>
  NativeModule.ackEvents(eventIds);

/**
 * Uploads the OS still knows about. On iOS this is live session tasks only;
 * terminal outcomes come from getUnacknowledgedEvents, not from here.
 */
const getAllUploads = (): Promise<UploadSnapshot[]> => NativeModule.getAllUploads();
```

Import the new types, and replace the default export with:

```ts
export default {
  startUpload,
  cancelUpload,
  addListener,
  getUnacknowledgedEvents,
  ackEvents,
  getAllUploads,
  ios,
  android,
};
```

- [ ] **Step 5: Run tests + typecheck**

Run: `yarn test && yarn tsc --noEmit`
Expected: 4 tests PASS; typecheck clean. (The `typecheck` script replaces `build` in Task 10.)

- [ ] **Step 6: Commit**

```bash
git add src/ jest.config.js babel.config.js package.json yarn.lock
git commit -m "feat(js): journal get/ack, getAllUploads, typed errors, acceptStatus"
```

---

### Task 10: Packaging, CI, README, CHANGELOG

**Files:**
- Modify: `package.json`, `.github/workflows/node.yml`, `README.md`, `CHANGELOG.md`

**Interfaces:**
- Consumes: everything prior.
- Produces: v7.6.0; CI runs lint + jest + android unit tests + a `lib/` drift check.

- [ ] **Step 1: Version + prepack safety**

In `package.json`: set `"version": "8.0.0"`. Delete the committed `lib/` directory (`git rm -r lib`) — it's a hand-regenerated build artifact that has already drifted once. Metro consumes TS source directly (`"main": "src/index"`), and TypeScript reads types from source too: set `"typings": "src/index.ts"` and drop the `tsc-alias` build in favor of `"typecheck": "tsc --noEmit"` (nothing consumes emitted JS anymore). Diana's one deep import (`react-native-background-upload/lib/types` in `fileTransfers/models.ts`) moves to the root import on adoption.

- [ ] **Step 2: CI**

Replace the `node-lint-tests` job steps in `.github/workflows/node.yml` after "install node_modules" with:

```yaml
    - name: node lint
      run: yarn lint:ci

    - name: js tests
      run: yarn test

    - name: typecheck
      run: yarn typecheck

    - name: setup java
      uses: actions/setup-java@v4
      with:
        distribution: temurin
        java-version: 17

    - name: android unit tests
      run: |
        cd example/RNBGUExample/android
        ./gradlew :react-native-background-upload:testDebugUnitTest
```

- [ ] **Step 3: README**

Make these edits (keep the rest):
- Intro: replace "on Android it uses CoroutineWorker and Ktor" with "on Android it uses WorkManager (CoroutineWorker) and OkHttp".
- Delete the "Multipart Uploads — COMING SOON" section, the `useUtf8Charset` row, the `field`/`parameters`/`appGroup` rows, and the stale `notification` object table. Replace the notification table with:

```markdown
### `android` options (required)

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| `notificationChannel` | string | Yes | Existing notification channel id (create it with notifee or similar before uploading) |
| `notificationId` | string | Yes | Stable id for the progress notification |
| `notificationTitle` | string | Yes | Title while uploading |
| `notificationTitleNoWifi` | string | Yes | Title while waiting for wifi (`wifiOnly: true`) |
| `notificationTitleNoInternet` | string | Yes | Title while waiting for connectivity |
| `maxRetries` | number | No (default 5) | Retry budget for non-network errors |
```
- Add an **"iOS Setup (AppDelegate)"** section:

````markdown
### iOS: background completion handler (required)

Add to your `AppDelegate.mm` so uploads that finish while the app is terminated
relaunch it and get journaled:

```objc
#import <VydiaRNFileUploader.h>

- (void)application:(UIApplication *)application
handleEventsForBackgroundURLSession:(NSString *)identifier
  completionHandler:(void (^)(void))completionHandler {
  [VydiaRNFileUploader setBackgroundSessionCompletionHandler:completionHandler
                                               forIdentifier:identifier];
}
```
````

- Add a **"Reliable delivery (v7.6+)"** section:

````markdown
## Reliable delivery (v7.6+)

Terminal events (`completed` / `error` / `cancelled`) are journaled natively
*before* being emitted, so they survive app death, JS reloads, and background
relaunches. Events stay in the journal until you acknowledge them.

On every app start:

```js
const events = await Upload.getUnacknowledgedEvents();
for (const e of events) {
  // e: { eventId, id, type, timestamp, responseCode?, responseBody?, responseHeaders?, error?, cancelReason? }
  handleOutcome(e);
}
await Upload.ackEvents(events.map((e) => e.eventId));

// Then reconcile anything still in flight:
const live = await Upload.getAllUploads();
```

Notes:
- **Breaking in v8:** `completed` fires only for 2xx (plus any per-request `acceptStatus`
  codes). Other HTTP responses emit `error` with `errorKind: 'http'` and the full
  response (`responseCode`, `responseBody`, `responseHeaders`) attached.
- `errorKind` distinguishes HTTP errors, transport failures (`'network'`), and missing
  files (`'file'`) — retry transport failures, don't retry client errors.
- `cancelReason` distinguishes user cancels (`'user'`) from system kills (`'system'`).
- Duplicate journal entries per upload id are possible if the process dies at
  exactly the wrong moment (Android re-runs the worker); dedupe by `id`, keep latest.
- Android: `getAllUploads()` sees finished work for only ~1 day (WorkManager
  auto-pruning) — read outcomes from the journal, use `getAllUploads()` for live state.
- iOS: in rare cases the OS fails to deliver the response body for a background
  upload (known platform quirk) — `responseCode` is always authoritative;
  treat `responseBody` as best-effort.
````

- [ ] **Step 4: CHANGELOG**

Prepend to `CHANGELOG.md`:

```markdown
## 8.0.0

Breaking:
- `completed` now fires only for 2xx responses (plus per-request `acceptStatus` codes,
  e.g. `acceptStatus: [409]`). Other HTTP responses emit `error` with
  `errorKind: 'http'` and the full response attached.
- `error` events are typed: `errorKind: 'http' | 'network' | 'file' | 'unknown'`.
- Removed committed `lib/` output; typings now point at `src/index.ts`
  (deep imports of `lib/*` break — import from the package root).
- iOS: removed non-functional multipart, PHAsset (`assets-library://`), and `appGroup`
  code paths. Android: removed unexposed `stopAllUploads`.

Added:
- Native durable event journal: `getUnacknowledgedEvents()` / `ackEvents(ids)` — terminal
  events survive app death and JS reloads (at-least-once delivery).
- `getAllUploads()` on both platforms.
- `cancelled` events carry `cancelReason: 'user' | 'system'`.
- iOS: `handleEventsForBackgroundURLSession` support (see README AppDelegate setup),
  `responseHeaders` on completed events, progress throttled to 500ms, UUID default ids,
  persisted task map so ids survive relaunch.
- Android: the `android` options block is now optional — sensible notification defaults,
  and the library creates its notification channel itself.
```

- [ ] **Step 5: Lint, commit, tag**

```bash
yarn lint:ci && yarn test
git rm -r lib
git add package.json .github/workflows/node.yml README.md CHANGELOG.md
git commit -m "chore: v8.0.0 — docs, CI (jest + android tests + typecheck), drop committed lib/"
git tag v8.0.0   # after PR merge, not on the branch
```

---

### Task 11: On-device E2E verification (manual checklist)

**Files:**
- Modify: `example/RNBGUExample/App.tsx` (debug buttons)

**Interfaces:**
- Consumes: the full new API.
- Produces: a verified release. **Do not mark this plan complete without this task — the whole point is behavior when the app dies, which only devices can prove.**

- [ ] **Step 1: Add journal debug buttons to the example app**

In `example/RNBGUExample/App.tsx`, next to the existing Cancel button, add:

```tsx
<Button
  title="Dump journal"
  onPress={async () => {
    const events = await Upload.getUnacknowledgedEvents();
    console.log('journal', JSON.stringify(events, null, 2));
  }}
/>
<Button
  title="Ack all"
  onPress={async () => {
    const events = await Upload.getUnacknowledgedEvents();
    await Upload.ackEvents(events.map((e) => e.eventId));
    console.log('acked', events.length);
  }}
/>
<Button
  title="Live uploads"
  onPress={async () => {
    console.log('live', JSON.stringify(await Upload.getAllUploads(), null, 2));
  }}
/>
```

- [ ] **Step 2: Happy path (both platforms)**

Start `example/server` (`yarn --cwd example/server start`), point `UPLOAD_URL` at it (use your machine's LAN IP for physical devices). Upload → wait for completion → "Dump journal" shows one `completed` entry with `responseCode`, `responseBody`, `responseHeaders`, `eventId` → "Ack all" → "Dump journal" shows `[]`.

- [ ] **Step 3: Death tests**

| # | Scenario | Steps | Expected |
|---|---|---|---|
| 1 | Android force-stop mid-upload | Start big upload → App Info → Force stop → relaunch | WorkManager re-runs; after completion, journal has `completed` (dedupe by id if two entries) |
| 2 | Android JS-dead completion | Start upload → immediately background the app → wait for completion notification → open app | Journal contains the `completed` event even though no live event fired |
| 3 | iOS suspend | Start big upload → home button → wait → reopen | Upload continued (background session); completion journaled + emitted on resume |
| 4 | iOS force-quit | Start big upload → swipe-kill → relaunch | `cancelled` event with `cancelReason: 'system'` in journal (iOS cancels background tasks on force-quit) |
| 5 | iOS terminated-by-system relaunch | Simulate: `xcrun simctl` can't force this reliably — use a real device, start upload, trigger memory pressure or wait for system termination, or verify via the AppDelegate handler being called (add a breakpoint/log) | Journal has `completed`; AppDelegate handler invoked |
| 6 | User cancel (both) | Start upload → Cancel button | `cancelled` with `cancelReason: 'user'` in journal and live event |
| 7 | HTTP error (both) | Point UPLOAD_URL at a 404 route | `error` with `errorKind: 'http'`, `responseCode: 404`, body attached — live event AND journal. Repeat with `acceptStatus: [404]` → `completed` |
| 8 | Progress throttle (iOS) | Big file, watch console | ≤ ~2 progress logs/sec |

For every death scenario (rows 1–5), additionally assert: the journal entry's `id` equals the upload id `startUpload` returned before the kill. This validates the `RNBGUTaskMap` fallback on iOS (where `taskDescription` persistence is not guaranteed by Apple) and the id-tag round-trip on Android.

- [ ] **Step 4: Record results + commit**

Append pass/fail per row to this file under the table; fix anything that fails before tagging.

```bash
git add example/RNBGUExample/App.tsx docs/superpowers/plans/2026-07-22-phase0-reliable-uploads.md
git commit -m "test: journal debug buttons + e2e verification results"
```

---

## After Phase 0: Diana adoption (separate plan, diana repo)

Not part of this plan — listed so the payoff is explicit. Once Diana pins v8.0.0:
1. On `main.initialize`: `getUnacknowledgedEvents()` → feed each into the existing completed/error/cancelled listener logic → `ackEvents`. Replaces the blind `app-killed` marking (`fileTransfers/slice/index.ts:372-392`) with real outcomes.
2. Use `cancelReason` to delete the user-cancel inference (`slice/index.ts:320-349`).
3. `getAllUploads()` replaces the iOS `getUploadStatus` pre-start dedupe and most of `uploadRecoveryEpics`' server reconciliation.
4. Add the AppDelegate handler to Diana's `AppDelegate.mm`.
5. Pass `acceptStatus: [409]` on creates (and `[404]` on delete-role transfers) to keep today's completion rules, then delete the status-mapping logic in `backgroundUploadListenerEpics.ts:86-148` and use `errorKind` in place of Diana's string-parsed error taxonomy.
6. Replace the `react-native-background-upload/lib/types` deep import in `fileTransfers/models.ts` with the root import (`lib/` no longer exists).
7. Delete the notifee channel-creation retry loop in `backgroundUploadUtils.ts` — the library creates its channel itself; the `android` options block becomes optional.

## Phases 1–2 outline (design-level, not yet planned)

- **Phase 1 — TransferQueue (JS, in this library):** durable queue on a storage adapter, single retry authority with terminal-vs-retryable taxonomy (diana#8235), foreground-first as the queue's first step, `prepareRequest` send-time hook, `request()` returning `{kind:'response'} | {kind:'queued'}`, named `onSettled` handlers. Diana's slice becomes an adapter.
- **Phase 2 — chains/groups/chunked uploads:** `chain({id, steps})` persisted and auto-resumed (replaces diana#8782's plumbing), groups with aggregate status, and chunked multipart uploads moved into the library. (The `completed`=2xx-only break originally slated for a later v8 ships in Phase 0 instead.)

  **Chunked-upload design (validated 2026-07-23 against Diana + backend; motivated by DIANA-H09 / RAD-12777; details in `.claude/work/.../14-chunking-in-library-verdict.md`).** Verdict: move the chunk transport into the library, keep the OpenSpace server contract in a consumer-supplied protocol adapter. The transport is already generic in Diana (`utils/fileTransfer/chunk.ts` has zero OpenSpace knowledge, reused by capture/OSTR/lidar), and the server contract is bespoke (not standard S3/tus), so it must be pluggable.
  - **Library owns:** the native chunker (today it lives in the Diana *app* — `ios/FileSystem/FileSystem.swift`, `FileSystemModule.kt`; must be native for multi-GB files, so relocate it into the library's native layer); deterministic ≥8MB/≤20MB splitting (determinism required for positional resume); Content-Range/partNum PUT loop (parts may go concurrently/any order); retry/backoff; resume by diffing server progress; completion detection; and **payload storage + lifecycle** — own source+chunks in the library's own dir until all parts are server-confirmed, then delete (Diana can't prune them → kills DIANA-H09). Lazy per-part chunking caps disk at ~1 chunk vs today's 2× peak. Source-delete stays opt-in (lidar keeps its canonical source).
  - **Consumer supplies (4-hook adapter):** (1) `partRequest(index, range, total) → {url, method, headers}` incl. Content-Range (`start-end/total`, no `bytes ` prefix) and per-part `acceptStatus: [409]` (re-PUT of a completed part returns 409 = success — ties to the Phase 0 acceptStatus primitive); (2) `create/metadata(numParts) → request` (no explicit finalize endpoint exists); (3) `completedParts() → number[]` from the server progress endpoint; (4) `authorize(request)` send-time token injection. Session/match (capture grouping) and publish-observation stay in Diana.
  - **Why it matters:** the server never force-completes — one missing part means the capture is never published, forever. Library-owned payload + reliable resume makes the client actually drive all N parts home, which is the structural fix for the DIANA-H09 class.

Phase 1 planning should start only after Diana adoption of Phase 0 proves the journal semantics in production.

## Appendix: when to write a new library instead of updating this one

The fork has exactly one consumer (Diana) and a dead upstream, so this is not a compatibility question — it's about how much native code survives and packaging hygiene.

**Stay in this repo through Phase 0** regardless: the changes are additive to a native core that already works in production, history stays bisectable against the code being fixed, and Diana's git-tag pin means zero migration cost.

**The decision point is the Phase 1 boundary**, and the trigger conditions for "new library" are:
1. ~~The Task 0 spike says WRAP~~ — resolved 2026-07-22: verdict was BUILD (see Task 0), so this trigger is off the table for now. It would only reopen if a future library ships durable Android uploads + completion journaling.
2. Phase 1 makes the TransferQueue the primary public API — at that point the queue (~2,000+ lines of new TS) dwarfs the ~1,200 lines of native code, the old surface is a compat shim, and a clean `@openspacelabs/`-scoped package drops real liabilities: the `react-native-background-upload` name shadows a dead npm package (accidental `npm install` fetches Vydia's abandoned code), the native module is still named `VydiaRNFileUploader`, and the repo URL in package.json still points at Vydia.
3. A new-architecture (TurboModule/Nitro) rewrite becomes necessary for RN upgrades — if native must be rewritten anyway, rewrite it under the new name.

Rule of thumb: **update while changes are additive to a working native core; start new the moment the native core itself is being replaced** — whether by a wrapper (spike wins) or by the queue becoming the product (Phase 1 ships).
