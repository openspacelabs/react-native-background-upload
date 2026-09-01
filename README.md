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

const options = {
  url: 'https://myservice.com/path/to/post',
  path: 'file://path/to/file/on/device',
  method: 'POST',
  type: 'raw',
  headers: { 'content-type': 'application/octet-stream' },
  // Optional. Treat these non-2xx statuses as success (e.g. an idempotent
  // create that conflicts). Any other non-2xx is an 'error' with errorKind 'http'.
  acceptStatus: [409],
};

// Optional. Call one time at app startup to set the Android notification text.
// The library keeps the text in native storage. Thus a worker relaunched with
// no JS shows the same text. If you do not call configure(), the library uses
// default text and makes its own channel. The call does nothing on iOS.
Upload.configure({ android: { notificationTitle: 'Uploading…' } });

const uploadId = await Upload.startUpload(options);

Upload.addListener('progress', ({ id, progress }) => {});
Upload.addListener('completed', ({ id, responseCode, responseBody }) => {});
Upload.addListener('error', ({ id, error, errorKind, responseCode }) => {});
Upload.addListener('cancelled', ({ id, cancelReason }) => {});
```

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
- **`completed` fires only for 2xx** (or a request's `acceptStatus`). Every other
  HTTP response is an `error` with `errorKind: 'http'` and the response attached —
  a 400 is an error, not a completion.
- `errorKind` is `'http' | 'network' | 'file' | 'unknown'`. Retry transport
  failures; treat client errors as terminal.
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
Starts an upload; resolves to its id. Rejects only on a bad option (missing/invalid
`url` or `path`) — transport failures and HTTP error responses arrive later as
`error` events, not a rejection. Idempotent for a given `id`: calling
it again while that upload is pending or running resolves with the same id instead
of starting a duplicate.

| Option | Type | Notes |
| --- | --- | --- |
| `url` | string | Required. |
| `path` | string | Required. Local file path (`file://…`). URIs are not escaped for you. |
| `type` | `'raw'` | Only `raw` is supported. |
| `method` | string | Default `POST`. |
| `headers` | object | HTTP headers. |
| `id` | string | Defaults to a generated UUID. |
| `wifiOnly` | boolean | Wait for wifi before/while uploading. |
| `acceptStatus` | number[] | Non-2xx statuses to treat as success. |
| `android` | object | Optional. `noNotification` (default false) — see Silent uploads. Notification text is set once via `configure()`, not per upload. |

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
Cancels an upload. Fires a `cancelled` event with `cancelReason: 'user'`.

### `addListener(eventType, listener): EventSubscription`
Listen for `'progress' | 'error' | 'completed' | 'cancelled'` across all uploads;
every event carries the upload's `id`. Call `.remove()` on the result to
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
| `completed` | `{ id, responseCode, responseBody, responseHeaders?, eventId? }` |
| `error` | `{ id, error, errorKind?, responseCode?, responseBody?, responseHeaders? }` |
| `cancelled` | `{ id, cancelReason?: 'user' | 'system' }` |

# Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md).
