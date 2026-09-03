## 9.0.0

Chunked uploads move into the library: one file, many part requests, one upload
id and event stream. The consumer authors the parts (URL, headers, byte range)
once; the library owns transport, the bytes, and resume. See the README's
"Chunked uploads" section.

Breaking:
- **Listeners are global-only.** `addListener(event, uploadId, callback)` is
  gone; use `addListener(event, callback)` and discriminate on `data.id`.
- **`acceptStatus: number[]` is replaced by `accept` rules** on all uploads:
  `accept: [{ status, bodyIncludes? }]`. `bodyIncludes` narrows by
  response-body substring, for statuses that carry several meanings.
- **`customUploadId` is renamed to `id`** on all upload types.
- **`android.maxRetries` is removed.** Retry policy belongs to the library;
  chunked uploads are bounded by `expiresAt` instead.
- **`ios.getUploadStatus` is removed.** Its only use was pre-dispatch dedupe,
  which idempotent `startUpload` (below) makes unnecessary.
- **Per-upload notification text is removed.** Set it once with `configure()`;
  it is persisted natively so a worker relaunched by WorkManager with no JS
  running shows the same text. Per-upload `android` options reduce to
  `noNotification`.

Added:
- **Chunked uploads**: `startUpload({ type: 'chunked', id, path,
  parts, accept?, expiresAt, wifiOnly? })`. The library takes ownership of the
  file at `startUpload` and deletes it only after a `completed` event is
  acknowledged; every other terminal outcome keeps the manifest and bytes for
  resume or recreate.
- **`startUpload` is idempotent for every upload, always.** Re-calling with a
  running id is never an error; for chunked uploads it reconciles — accepted
  parts are skipped, the rest continue with the new call's headers (how a fresh
  auth token reaches parts stalled on 401). On iOS the guarantee is race-free:
  concurrent same-id calls are serialized natively, so they can never enqueue a
  duplicate task.
- **Recreate under the same id**: calling `startUpload` with an existing id and
  a *different* parts array replaces the parts over the owned bytes — every
  part resets to unsent, and the new ranges must tile the same total size.
  Accepted only while the upload is not running (stalled on a terminal error,
  expired, or cancelled); rejected while it runs. This is how a consumer
  re-uploads under a fresh server uploadId after the old one dies.
- **`expiresAt` / `errorKind: 'expired'`**: a chunked upload past its required
  deadline journals a terminal `error` and stops, keeping the bytes. Within the
  deadline, transient failures retry on backoff with no attempt cap.
- **`removeUpload(uploadId)`** releases a kept manifest and bytes.
- **`configure(options)`** one-time setup (Android notification text/identity).
- **`chunkPlan(sizeBytes, { min?, max? })`**: the deterministic range splitter,
  exposed so the part count told to the server and the parts array derive from
  one result.
- Concurrency: on Android a hard cap of 4 concurrent requests across all
  uploads — every request passes the shared transfer semaphore (previously
  fully serial), with a chunked upload's parts windowed inside it. On iOS a
  per-session connection-level backstop: `httpMaximumConnectionsPerHost = 4`
  (previously 1), with chunked parts bounded by the per-upload window of 3.

Fixed:
- Android jobs enqueued by a v8 build replay safely after upgrading. WorkManager
  can hand a v9 worker a job serialized by v8 (`acceptStatus`, no `accept`);
  the worker now normalizes the legacy shape instead of crashing after the file
  has fully transmitted and re-sending it on every retry.

## 8.1.0

Added `android.noNotification`, which uploads a file without posting a progress
notification. An app that uploads housekeeping payloads alongside user-visible
ones can now keep the notification shade for the ones a user asked to watch.

The notification doubles as the upload worker's foreground-service notification,
so a silent upload runs as an ordinary background worker: the OS may defer it, or
stop it mid-flight for WorkManager to re-run. It suits small payloads a restart
costs nothing; media files should keep their notification. Default is `false`, so
existing uploads are unaffected, including jobs enqueued by 8.0.0 and re-run
after the upgrade.

## 8.0.0

Reliability release. Terminal outcomes are now durable and accurately typed, the
iOS module was rewritten in Swift, and the module is a New Architecture
TurboModule.

Breaking:
- **New Architecture only.** The module is now a codegen TurboModule on both
  platforms; the legacy bridge (`RCTEventEmitter` / `RCT_EXTERN_MODULE` on iOS,
  `ReactPackage` + `RCTDeviceEventEmitter` on Android) is gone, along with any
  reliance on RN's legacy-interop layer. Requires React Native >= 0.84 with the
  New Architecture enabled, and React >= 19.
- The iOS background-session handler moved from `RNFileUploader` to
  `RNBackgroundUpload`: `[RNBackgroundUpload setBackgroundSessionCompletionHandler:
  forIdentifier:]`. `RNFileUploader` is now the TurboModule and is intentionally
  unreachable from plain Objective-C (its generated header is Objective-C++ only).
  Update the AppDelegate snippet — see README.
- Events are delivered through the codegen event emitters rather than
  `DeviceEventEmitter`, so they are no longer visible under the raw
  `RNFileUploader-*` device-event names. The `Upload.addListener(...)` API is
  unchanged.
- `cancelUpload` on iOS now resolves `false` when no matching in-flight upload was
  found (it previously always resolved `true`). Android still always resolves `true`.
- Terminal event payloads are typed as the journal entry they actually are. The
  natives emit the journaled entry itself, so `CompletedData` / `ErrorData` /
  `CancelledData` now declare the `eventId`, `type` and `timestamp` they were always
  sending, plus `responseBodyTruncated`. `eventId` in particular means you can
  `ackEvents([eventId])` straight after handling a live event. `JournaledEvent` is
  now a union discriminated on `type`.
- `CompletedData.responseCode` and `.responseBody` are optional. They were declared
  required but are absent when a task completes without an HTTP response, so reading
  them unguarded could throw.
- The `cancelled` payload no longer carries `error` (it used to hold the cancellation
  error string). Use `cancelReason` instead.
- iOS `progress` reports `0` instead of `-1` when the total length is unknown,
  matching Android and the documented 0-100 range.
- iOS `getAllUploads` reports `cancelled` and `completed` states instead of
  collapsing everything non-running into `pending`.
- `completed` fires only for 2xx responses (plus a request's `acceptStatus`, e.g.
  `acceptStatus: [409]`). Every other HTTP response now emits an `error` with
  `errorKind: 'http'` and the full response attached (previously reported as
  `completed`).
- `error` events are typed: `errorKind: 'http' | 'network' | 'file' | 'unknown'`.
- Native module renamed to `RNFileUploader` on both platforms (was
  `VydiaRNFileUploader` on iOS); Android package is now `ai.openspace.backgroundupload`.
- iOS AppDelegate must forward `handleEventsForBackgroundURLSession` (see README).
- Removed the committed `lib/` build output; types are served from `src`
  (deep imports of `lib/*` break — import from the package root).
- Minimum iOS deployment target is 15.1; minimum Android SDK is 29. Minimum React
  Native is 0.84 (New Architecture), minimum React is 19.
- Removed non-functional iOS code paths: multipart, `assets-library://`, and the
  `appGroup` option (a no-op even before this — it mutated the session config after
  creation, which URLSession ignores; also removed from the TypeScript options).
- Removed the unexposed Android `stopAllUploads`.

Added:
- Durable native event journal: `getUnacknowledgedEvents()` / `ackEvents(ids)` —
  terminal events survive app death and JS reloads (at-least-once delivery).
- `getAllUploads()` on both platforms.
- `cancelled` events carry `cancelReason: 'user' | 'system'`.
- `responseHeaders` on completed events on iOS (was Android-only).
- iOS progress events throttled to 500ms; UUID default upload ids.
- Android `android` options are now optional — sensible notification defaults and
  a library-created notification channel.

Earlier releases: see the [releases](https://github.com/openspacelabs/react-native-background-upload/releases) page.
