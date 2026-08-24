# react-native-background-upload

OpenSpace's background HTTP file uploader for React Native. On iOS it uses a
background `URLSession`; on Android it uses `WorkManager` (a `CoroutineWorker`)
with OkHttp. Uploads continue while the app is backgrounded and resume after it
is killed.

# Installation

**Requires React Native ≥ 0.84 with the New Architecture enabled, and React ≥ 19.**
This is a codegen TurboModule; it does not support the legacy bridge.

```
yarn add react-native-background-upload
cd ios && pod install && cd ..
```

`pod install` is required after installing — it runs codegen to generate the native
spec this module implements.

> The package ships TypeScript source with no build step, so it resolves through Metro
> (and `tsc`) but not through plain Node. If you import it from a non-Metro context —
> a script, or Jest without a transform — add it to your `transformIgnorePatterns`
> allowlist or mock it.

## iOS: background completion handler (required)

So uploads that finish while the app is terminated can relaunch it and be
journaled, add this to your `AppDelegate`:

```objc
#import <react_native_background_upload/react_native_background_upload-Swift.h>

- (void)application:(UIApplication *)application
handleEventsForBackgroundURLSession:(NSString *)identifier
  completionHandler:(void (^)(void))completionHandler {
  [RNBackgroundUpload setBackgroundSessionCompletionHandler:completionHandler
                                             forIdentifier:identifier];
}
```

> The Swift header import name is the pod name with hyphens as underscores. If
> your app links pods as frameworks, use `@import react_native_background_upload;`
> instead of the `#import <...-Swift.h>` line.

This hook is load-bearing beyond just calling the completion handler: it is what
brings the library's background `URLSession` back to life in a process the system
relaunched with no JS running, so queued completions get journaled. `RNFileUploader`
is the TurboModule and is deliberately not reachable from plain Objective-C — its
generated header is Objective-C++ only — so the handler lives on `RNBackgroundUpload`.

# Usage

```js
import Upload from 'react-native-background-upload';

// Optional. Call one time at app startup to set the Android notification text.
// The library keeps the text in native storage. Thus a worker relaunched with
// no JS shows the same text. If you do not call configure(), the library uses
// default text and makes its own channel. The call does nothing on iOS.
Upload.configure({ android: { notificationTitle: 'Uploading…' } });

// Listeners are global. Every event carries the upload's id.
Upload.addListener('progress', ({ id, progress }) => {});
// responseCode/responseBody are set for simple uploads only. A chunked
// 'completed' carries neither, because no single response represents N parts.
Upload.addListener('completed', ({ id, responseCode, responseBody }) => {});
Upload.addListener('error', ({ id, error, errorKind, responseCode }) => {});
Upload.addListener('cancelled', ({ id, cancelReason }) => {});

const uploadId = await Upload.startUpload({
  type: 'raw',
  url: 'https://myservice.com/path/to/post',
  path: 'file://path/to/file/on/device',
  method: 'POST',
  headers: { 'content-type': 'application/octet-stream' },
  // Optional. Non-2xx responses to treat as success (for example, an
  // idempotent create that conflicts). Each other non-2xx response is an
  // 'error' with errorKind 'http'.
  accept: [{ status: 409, bodyIncludes: 'already completed' }],
});
```

## Chunked uploads

A `type: 'chunked'` upload sends one file as many part requests but stays one
logical upload: one id, one event stream, byte-weighted `progress`, and
`completed` only when every part has been accepted. You author the parts — URL,
headers, byte range — once, at creation; the library owns the transport and
never constructs or edits a protocol field. Author the ranges with `chunkPlan`
so the part count you tell your server and the parts the library sends derive
from the same array:

```js
const size = (await stat(path)).size;
const ranges = Upload.chunkPlan(size, { min: 8 * 2 ** 20, max: 20 * 2 ** 20 });
// Tell your server ranges.length parts, then:
await Upload.startUpload({
  type: 'chunked',
  id: myDurableId, // required
  path, // see file ownership below
  parts: ranges.map((range, i) => ({
    url: partUrl(i + 1),
    headers: {
      Authorization: token,
      'Content-Type': 'application/octet-stream',
      'Content-Range': `bytes ${range.start}-${range.end - 1}/${size}`,
    },
    range, // bytes, end exclusive
  })),
  accept: [{ status: 409, bodyIncludes: 'already completed' }],
  expiresAt: Date.now() + 14 * 24 * 60 * 60 * 1000, // required, epoch ms
});
```

**File ownership.** The library takes the file: an O(1) rename into its own
directory at `startUpload`. Nothing your app does afterward (cache sweeps,
logout cleanup) can destroy the bytes mid-upload. The file is deleted in
exactly one case — a `completed` event has been acknowledged via `ackEvents`.
Copy the file first if you need it afterward.

**Resume is re-calling `startUpload`.** The parts are persisted in a native
manifest, so crash recovery, resume after `cancelUpload`, resume after expiry,
and refreshing auth headers are all the same call: `startUpload` again with the
same id and the same part ranges/URLs. Parts already accepted are skipped; the
rest continue with the new call's headers and `expiresAt` (this is how a fresh
token reaches parts that stalled on 401). Once a manifest exists, `path` is
ignored — the library's owned bytes are the source of truth.

**Recreate is the same call with different parts.** When the old server upload
is dead (for example, swept server-side), author fresh part URLs and call
`startUpload` with the same id and the new parts array. The owned bytes are
kept, the parts are replaced, and every part resets to unsent; the new ranges
must tile the same total size. A recreate is accepted only while the upload is
not running — stalled on a terminal error, expired, or cancelled. While it is
running, a differing parts array is rejected: that is a consumer bug, not a
recreate.

**Lifetime.** Within `expiresAt`, transient failures (network, 5xx) retry on
exponential backoff with no attempt cap. Past it, the library journals an
`error` with `errorKind: 'expired'` and stops — keeping the manifest and bytes,
so you can resume the same server upload with a later `expiresAt`, or recreate
under a new one. When neither is wanted, release them with `removeUpload`.

Choosing a value: `expiresAt` is when your app *hears about* a stuck upload,
not when data is lost — bytes survive expiry. Pick something well inside your
backend's own cleanup horizon so expiry fires while the server upload is still
resumable, and generous enough for real offline stretches. The OpenSpace
backend prunes incomplete multipart uploads 31 days after creation
(`UploadPartCleanup`); Diana passes 14 days, leaving a 17-day window where an
expired upload can still resume the same server uploadId.

# Reliable delivery

Terminal events (`completed` / `error` / `cancelled`) are journaled natively
*before* they are emitted, so they survive app death, JS reloads, and background
relaunches. Events stay in the journal until you acknowledge them. Drain it on
every app start:

```js
const events = await Upload.getUnacknowledgedEvents();
for (const e of events) {
  // e: { eventId, id, type, timestamp, responseCode?, responseBody?,
  //      responseHeaders?, error?, errorKind?, cancelReason? }
  handleOutcome(e);
}
await Upload.ackEvents(events.map((e) => e.eventId));

// Then reconcile anything still in flight:
const live = await Upload.getAllUploads(); // [{ id, state, ... }]
```

Notes:
- **`completed` fires only for 2xx** (or a response matching the request's
  `accept` rules). Every other HTTP response is an `error` with
  `errorKind: 'http'` and the response attached — a 400 is an error, not a
  completion.
- `errorKind` is `'http' | 'network' | 'file' | 'expired' | 'unknown'`. Retry
  transport failures; treat client errors as terminal; `expired` means a chunked
  upload's `expiresAt` passed (see Chunked uploads for recovery).
- `cancelReason` distinguishes a user cancel (`'user'`) from a system kill
  (`'system'`).
- Duplicate journal entries for one upload id are possible if the process dies at
  the wrong moment (Android may re-run the worker) — dedupe by `id`, keep latest.
- Android: `getAllUploads()` reflects only live/recent work (WorkManager prunes
  finished work after ~a day). The journal is the source of truth for outcomes.

# API

All methods are on the default export.

### `configure(options): void`
One-time setup — call at app startup. `options.android` sets the upload
notification's text and identity:
`notificationId/Title/TitleNoWifi/TitleNoInternet/Channel`. The config is
persisted natively, so a worker relaunched by WorkManager with no JS running
shows the same text. Optional: omitted fields keep the library defaults (each
call replaces the whole config). A no-op on iOS, which has no library
notification.

### `startUpload(options): Promise<string>`
Starts an upload; resolves to its id. Discriminated on `options.type`: `'raw'`
sends the whole file as one request body, `'chunked'` sends the authored parts
(see Chunked uploads). Rejects (or, for malformed chunked input, throws
synchronously) only on bad options — transport failures and HTTP error responses
arrive later as `error` events, not a rejection.

**Idempotent for every upload, always.** Calling `startUpload` again with an id
that is already pending or running is never an error: a raw upload resolves with
the same id instead of starting a duplicate; a chunked upload reconciles — parts
already accepted are skipped, the rest continue with the new call's headers. No
pre-dispatch dedupe is needed on your side.

Options for `type: 'raw'`:

| Option | Type | Notes |
| --- | --- | --- |
| `url` | string | Required. |
| `path` | string | Required. Local file path (`file://…`). URIs are not escaped for you. |
| `method` | string | Default `POST`. |
| `headers` | object | HTTP headers. |
| `id` | string | Defaults to a generated UUID. |
| `wifiOnly` | boolean | Wait for wifi before/while uploading. |
| `accept` | AcceptRule[] | Non-2xx responses to treat as success — see Accept rules. |
| `android` | object | Optional. `noNotification` (default false) — see Silent uploads. Notification text is set once via `configure()`, not per upload. |

Options for `type: 'chunked'`:

| Option | Type | Notes |
| --- | --- | --- |
| `id` | string | Required — your durable id. |
| `path` | string | Required. The library takes ownership of the file — see Chunked uploads. |
| `parts` | array | Required. `{ url, headers, range: { start, end } }` per part; ranges in bytes, end exclusive. Sent verbatim as PUTs. |
| `expiresAt` | number | Required, epoch ms. Past it: terminal `error` with `errorKind: 'expired'`. |
| `accept` | AcceptRule[] | See Accept rules. |
| `wifiOnly` | boolean | Wait for wifi before/while uploading. |
| `android` | object | Same as raw. |

#### Accept rules

`accept: Array<{ status: number, bodyIncludes?: string }>` — non-2xx responses
to treat as success, for both upload types. `bodyIncludes` narrows a rule by
response-body substring, for servers where one status carries several meanings
distinguishable only by message. A response matching a rule completes the
request (for chunked, marks the part accepted); any other non-2xx is an `error`
with `errorKind: 'http'`.

#### Silent uploads (Android)

`android: { noNotification: true }` uploads a file without posting a progress
notification, so the shade only shows the uploads a user actually asked to watch.

That notification is also the worker's foreground-service notification, so a
silent upload runs as an ordinary background worker instead. The OS is then free
to defer it, or to stop it mid-flight and let WorkManager re-run it later. Keep
the notification for anything that takes real time to upload; reserve
`noNotification` for small payloads a restart would cost nothing.

All uploads share one notification (identified by the configured
`notificationId`), and its progress bar reports every in-flight upload — silent
ones included.

### `cancelUpload(uploadId): Promise<boolean>`
Cancels an upload. Fires a `cancelled` event with `cancelReason: 'user'`. For a
chunked upload this cancels in-flight requests but keeps the manifest and bytes —
the next `startUpload` with the same id resumes it (there is no separate pause
API).

### `removeUpload(uploadId): Promise<void>`
Releases an upload's native manifest and bytes. Every terminal outcome other
than an acked `completed` (expired, error, cancelled) keeps both so you can
resume or recreate; call this once neither is wanted.

### `chunkPlan(sizeBytes, { min?, max? }): Array<{ start, end }>`
Splits a byte count into contiguous, end-exclusive ranges: a deterministic
greedy walk of `max`-sized chunks (default 20MB), with a final remainder smaller
than `min` (default 8MB) absorbed into the previous chunk. A file smaller than
`min` is a single chunk. Pure and deterministic on purpose: call it once and
derive both your server's part count and the `parts` array from the same result,
so the two can never disagree.

### `addListener(eventType, listener): EventSubscription`
`addListener(event: 'progress' | 'error' | 'completed' | 'cancelled', callback)`.
Listeners are global — there is no per-upload subscription; every event carries
the upload's `id`, so discriminate on it. Call `.remove()` on the result to
unsubscribe.

### `getUnacknowledgedEvents(): Promise<JournaledEvent[]>`
Terminal events not yet acknowledged, including ones that fired while JS was dead.

### `ackEvents(eventIds: string[]): Promise<boolean>`
Removes journaled events once processed.

### `getAllUploads(): Promise<UploadSnapshot[]>`
Uploads the OS still knows about, for boot-time reconciliation.

### `android.addNotificationListener(listener)`
Fires when the Android progress notification is pressed. No event data.

# Events

| Event | Data |
| --- | --- |
| `progress` | `{ id, progress: 0-100 }` |
| `completed` | `{ id, responseCode?, responseBody?, responseHeaders?, eventId? }` — response fields on simple uploads only; a chunked `completed` carries none (no single response represents N parts) |
| `error` | `{ id, error, errorKind?, partIndex?, responseCode?, responseBody?, responseHeaders? }` |
| `cancelled` | `{ id, cancelReason?: 'user' | 'system' }` |

# Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md).
