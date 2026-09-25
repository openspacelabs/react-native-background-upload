## 10.0.0

The library now owns a durable request queue. A consumer describes each
request kind one time with `define()`, enqueues instances with `mutate()`,
and receives every outcome through the definition's handlers, on this launch
or a later one. Outcomes are journaled natively before JS hears about them
and acknowledged only after the handler's promise resolves. See the README's
"Usage" and "Reliable delivery" sections. "Upgrading from v9" there covers
the migration.

Breaking:
- **`startUpload` and `getAllUploads` are removed.** `define()` + `mutate()`
  replace the first; the synchronous `getRequests(filter?)` replaces the
  second.
- **The v9 event names are removed.** `addListener` takes `'state'`,
  `'progress'`, or `'attempt'`. Terminal outcomes go to the definition's
  `onSuccess` / `onError`; a `cancelled` outcome calls no handler.
- **`getUnacknowledgedEvents` and `ackEvents` are internal.** The library
  drains the journal after `configure()` and acknowledges after each handler
  settles.
- **`cancelUpload` and `removeUpload` fold into `cancel(id)`.** A live entry
  settles `cancelled` and is forgotten after its ack; a settled entry is
  forgotten now, row and bytes.
- **`wifiOnly` moves from the upload options to the request descriptor.**
  `setWifiOnly(enabled)` on the queue, persisted natively, is the value for
  entries that omit it.
- **`progress` carries `{ id, bytesSent, totalBytes }`** instead of a
  percentage.
- **`configure()` must be called at boot, after every `define()`.** It starts
  the replay of journaled outcomes. Its `android` notification options are
  unchanged from v9.
- **`ErrorKind` gains `'truncated'`.** With a `response` parser set and a body
  over the 1 MB cap, `onError` fires with it instead of `onSuccess`.
- **Removed exports:** `startUpload`, `cancelUpload`, `removeUpload`,
  `getAllUploads`, the public `getUnacknowledgedEvents` / `ackEvents`, the
  `error` / `completed` / `cancelled` event names, and the
  `ProgressData`, `CompletedData`, `ErrorData`, `CancelledData`, `EventData`,
  `TerminalEventData`, `JournaledEvent`, `UploadSnapshot`, `UploadOptions`,
  `ChunkedUploadOptions`, `StartUploadOptions`, `AndroidOnlyUploadOptions`,
  `RawUploadOptions`, and `UploadId` types. The native `startChunkedUpload` is
  internal: a descriptor with `parts` routes to it.

Added:
- **`createUploadClient()`**: builds a client with its own definitions and
  settings. The default export is one client.
- **`define({ key, request, response?, onSuccess?, onError? })`**: `vars`
  infer from the `request` parameter, the handler data type from the
  `response` return. `response(raw, vars)` also receives the entry's `vars`;
  a one-argument parser such as `schema.parse` still fits. Without
  `response`, `onSuccess` receives the `RawResponse`. A duplicate key
  replaces the definition and warns in development. A `request` that takes
  no parameter gives a `mutate()` that takes no arguments.
- **`mutate(vars, { id? })`**: runs `request(vars)` once, merges the configured
  headers under the descriptor's, validates the descriptor (at most one of
  `data` / `form` / `file`; no body on a GET; `parts` only with `file`; parts
  must tile the file from 0; no field outside the descriptor shape, with a
  "did you mean" hint), defaults `expiresAt` to now + `lifetimeMs`, and
  resolves when the entry is durable: after the row and every staged body
  copy are on disk, so the caller may delete its source file then. `vars` is
  any JSON-serializable object, so generated API request types work as they
  are; `mutate()` rejects vars or `data` that do not serialize (a cycle, a
  function, a BigInt) and caps `vars` at `configure().maxVarsBytes`, 1 MB by
  default. `id` defaults to a UUID.
- **Same id, again.** The body is `data`, `form`, `file`, or `parts`, and a
  different `url` or `method` counts as a different body. Same body: resume,
  with new headers, `expiresAt`, and `vars`. Different body on an entry that
  is not running: replace and reopen; it settles once more. Different body
  on a running entry: reject `E_RUNNING`. Cancelled but not yet acknowledged:
  a fresh generation. Completed but not yet acknowledged: the journaled
  outcome is emitted again and nothing is re-sent.
- **Request bodies**: JSON (`data`), multipart (`form`), whole file (`file`),
  and chunked (`file` + `parts`). All under one entry shape and one id. A
  bodiless request (a GET, a DELETE, a POST whose meaning is in the URL) sets
  none. `data: null` sends the JSON body `null`.
- **`configure()` options**: `lifetimeMs` (default 14 days), `retry`
  (backoff base 1 s, max 2 h, jitter 0.2; `terminalHttp.exempt` default
  `[404]`), a `headers` provider that runs at `mutate()`, `maxVarsBytes`
  (default 1 MB), `enqueueTimeoutMs` (default 10 s: `mutate()` rejects with
  a named error and warns when the native write has not settled by then, so
  a native bug cannot hang a caller in silence), and the v9 `android`
  notification options.
- **Queue control**: `pause(scope?)` / `resume(scope?)`, `cancel(id)`,
  `setWifiOnly(enabled)`, and `updateHeaders(patch)` to re-auth entries
  parked on 401 or 403. Each `updateHeaders()` call bumps a header
  generation, so a 401 from an attempt sent under older headers re-issues at
  once instead of parking.
- **Key-scoped pause.** `pause()` and `resume()` take an optional
  `{ keys?: string[] }`. No scope is the whole queue; `{ keys }` pauses or
  resumes the entries of those definition keys, queued and future. An entry
  is paused while the whole queue or its key is paused, so resuming one
  scope does not resume an entry the other still pauses. Both states persist
  natively. A misspelled scope field, `keys: undefined`, or an empty key
  rejects, so a mistake cannot pause the whole queue. The native `pause` and
  `resume` now take a scope object, `{}` for the whole queue, because
  codegen has no optional arguments.
- **Per-request `wifiOnly`.** `RequestDescriptor.wifiOnly?: boolean`
  overrides `setWifiOnly()` for that entry and is persisted with it. An
  entry that omits it follows `setWifiOnly()`, including later toggles.
  Both are checked before each attempt.
- **`getRequests(filter?)`**: synchronous, from native's in-memory index.
  Returns every entry native has not yet forgotten, so completed and
  cancelled rows appear until their ack and `error` rows until `cancel()` or
  a same-id `mutate()`. `filter` is `{ key?, id? }`.
- **`RequestRow`**: `{ id, key, vars, state, bytesSent, totalBytes, attempts,
  updatedAt, nextAttemptAt? }`. `state` is `queued`, `running`,
  `awaiting-auth`, `paused`, `completed`, `error`, or `cancelled`.
  `nextAttemptAt` (epoch ms) is set while an entry waits out a retry
  backoff. `attempts` counts the current generation.
- **`Meta`** for handlers: `{ id, key, at, attempts, requestId?, deliveries }`.
  `deliveries` counts deliveries that reached a JS listener: 1 on the first,
  +1 per replay. A handler that keeps throwing sees it grow; the library
  never gives up on its own, so the app decides a poison policy.
- **Events**: `state` (a full `RequestRow` per transition, plus
  `reason: 'unhandled-key'` for an outcome whose key has no definition),
  `progress` (byte-weighted across a chunked upload's parts, throttled to
  1 s in the foreground), and `attempt` (one row per HTTP attempt with
  `requestId`, `httpCode`, a 4 KB response body, `responseHeaders`, and
  `errorKind`). `attempt.outcome` is `completed` or `error`; pause, cancel,
  and supersede emit none. `attempt` events are live-only, never journaled.
- **Delivery rules**: dedupe by event id; the outcomes of one id deliver in
  order, one handler at a time; an outcome for an id waits for that id's
  in-flight `mutate()`; an outcome whose key has no definition stays
  unacknowledged and reaches `state` listeners with `reason: 'unhandled-key'`;
  a handler that has not settled after 30 s logs a warning and keeps
  waiting; a malformed journal entry is dropped with a warning. No ordering
  is promised between different ids.
- **Error codes** on the native promises: `E_INVALID` (input native cannot
  send: a non-http(s) URL, a header name or value the platform HTTP client
  rejects, a GET with a body, parts that do not tile the file),
  `E_RUNNING`, `E_FILE_MISSING`, and `E_STORAGE`. `cancel()` rejects with
  `E_STORAGE` and changes nothing when the journal or the store cannot be
  written. `updateHeaders()` rejects with `E_INVALID` for a bad header name
  or value.
- **`X-Request-Id`** on every attempt, minted per attempt. `Meta.requestId`
  and `attempt.requestId` carry it.
- **`chunkPlan`** stays a module export and is also on the client.
- **Testing seam**: `createUploadClient({ native })` takes a fake native
  module, and `createFakeNative()` in `src/testing` builds one that keeps
  rows, journals outcomes, and acks, with `settle()`, `failNext()`,
  `seedRows()`, and `seedUnacknowledged()` to script native behavior. The
  real validation and delivery run on top of it.

Native:
- **Android queue.** One durable store under `files/rnbgupload-chunked/`,
  one directory per entry (`entry.json` plus the staged body), written
  tmp + fsync + rename. The journal of settled outcomes moves to
  `files/rnbgupload-settled/`. Queue settings (`wifiOnly`, `paused`, header
  generation, retry defaults) persist next to the entries. An in-memory
  index serves `getRequests()`. One WorkManager worker per id; a long
  backoff waits on a separate wake job so a same-id `mutate()` or
  `updateHeaders()` can run the entry at once. The backoff streak is stored
  on the entry, so the wait grows toward 2 h across runs. A run stopped by
  WorkManager's timeout takes one more backoff step instead of restarting
  at once. The global cap of 4 concurrent requests and the window of 3
  parts per chunked upload carry over from v9. A boot sweep repairs a settle
  whose entry save was lost, applies a cancel whose save was lost, and
  finishes a pause or resume cut short by process death. A settle whose
  journal write fails holds the record in memory and retries the write
  (5 s, doubling to 10 min). Response bodies are capped at 1 MB while they
  stream, counted in UTF-8 bytes and cut on a character boundary.
- **iOS queue.** The v9 chunked store is generalized in place into the queue
  store. Every body is staged to a file in the entry directory, because a
  background `URLSession` uploads from files only. Store, journal, and task
  map writes are tmp + fsync + rename, and the directories are excluded from
  backup. Retries are delayed session tasks (`earliestBeginDate`) for simple
  entries and for chunked parts, so a backoff keeps running while the app is
  suspended or dead. Each attempt owns its task through the task
  description, so a stale completion cannot settle a newer attempt. At
  relaunch, an entry whose task is gone but whose completion may still be
  pending waits up to 10 s for the replay before it re-issues, so a request
  the daemon finished while the app was dead is not sent twice; the
  background completion handler is released as soon as that replay lands.
  Two background sessions, one that allows cellular and one Wi-Fi only, each
  with `httpMaximumConnectionsPerHost = 4`. A system cancel, including a
  force-quit, is a transient failure: no outcome, the entry retries. A
  multipart body always uses its own `Content-Type`, because a caller's has
  no boundary. Response bodies are capped at 1 MB while they stream. The v9
  per-part budget of 3 HTTP rejections is gone; the retry policy decides.
- **v9 import, both platforms.** The first v10 launch imports each v9 journal
  entry as a read-only settled row with key `legacy`, the v9 upload id, and
  0/0 bytes. Nothing is delivered for them: the app reads them with
  `getRequests({ key: 'legacy' })` and calls `cancel(id)`. In-flight v9 work
  is cancelled (Android WorkManager rows, iOS session tasks). v9 chunked
  manifests and their bytes stay: a same-id `mutate()` with the same parts
  resumes from the accepted parts, and one with different parts starts over
  on the kept bytes. Wi-Fi only starts off.
- **Platform limits.** Android, API 31 and later: a WorkManager run started
  from the background usually cannot start its foreground service and then
  has JobScheduler's limit of about 10 minutes; a single body that does not
  finish in that time restarts from byte 0 after a backoff, so large bodies
  need `parts`. Android: the store and journal hold auth headers and staged
  bodies, so the host app sets `android:allowBackup="false"` or excludes the
  two directories. iOS: no global in-flight cap, and the AppDelegate
  `handleEventsForBackgroundURLSession` hook is required for outcomes to be
  journaled when the system relaunches the app.

Fixed:
- iOS: a chunked part's retry backoff no longer stalls while the app is
  dead. v9 ran the cooldown on an in-process timer.
- iOS: task-map keys whose completion never arrives are pruned at the end of
  the relaunch grace wait and when their entry is gone. v9 kept them forever.

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
