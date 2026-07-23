# react-native-background-upload

OpenSpace's background HTTP file uploader for React Native. On iOS it uses a
background `URLSession`; on Android it uses `WorkManager` (a `CoroutineWorker`)
with OkHttp. Uploads continue while the app is backgrounded and resume after it
is killed.

# Installation

```
yarn add react-native-background-upload
cd ios && pod install && cd ..
```

## iOS: background completion handler (required)

So uploads that finish while the app is terminated can relaunch it and be
journaled, add this to your `AppDelegate`:

```objc
#import <react_native_background_upload/react_native_background_upload-Swift.h>

- (void)application:(UIApplication *)application
handleEventsForBackgroundURLSession:(NSString *)identifier
  completionHandler:(void (^)(void))completionHandler {
  [RNFileUploader setBackgroundSessionCompletionHandler:completionHandler
                                          forIdentifier:identifier];
}
```

> The Swift header import name is the pod name with hyphens as underscores. If
> your app links pods as frameworks, use `@import react_native_background_upload;`
> instead of the `#import <...-Swift.h>` line.

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
  // Optional on Android — the library supplies notification defaults and creates
  // its own channel. Override any of these to customize.
  android: { notificationTitle: 'Uploading…' },
};

const uploadId = await Upload.startUpload(options);

Upload.addListener('progress', uploadId, ({ progress }) => {});
Upload.addListener('completed', uploadId, ({ responseCode, responseBody }) => {});
Upload.addListener('error', uploadId, ({ error, errorKind, responseCode }) => {});
Upload.addListener('cancelled', uploadId, ({ cancelReason }) => {});
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

### `startUpload(options): Promise<string>`
Starts an upload; resolves to its id. Rejects only on a bad option (missing/invalid
`url` or `path`) — transport failures and HTTP error responses arrive later as
`error` events, not a rejection.

| Option | Type | Notes |
| --- | --- | --- |
| `url` | string | Required. |
| `path` | string | Required. Local file path (`file://…`). URIs are not escaped for you. |
| `type` | `'raw'` | Only `raw` is supported. |
| `method` | string | Default `POST`. |
| `headers` | object | HTTP headers. |
| `customUploadId` | string | Defaults to a generated UUID. |
| `wifiOnly` | boolean | Wait for wifi before/while uploading. |
| `acceptStatus` | number[] | Non-2xx statuses to treat as success. |
| `android` | object | Optional. `notificationId/Title/TitleNoWifi/TitleNoInternet/Channel`, `maxRetries` (default 5). Sensible defaults + auto-created channel if omitted. |

### `cancelUpload(uploadId): Promise<boolean>`
Cancels an upload. Fires a `cancelled` event with `cancelReason: 'user'`.

### `addListener(eventType, uploadId | null, listener): EventSubscription`
Listen for `'progress' | 'error' | 'completed' | 'cancelled'`. Pass `null` for
`uploadId` to receive events for all uploads. Call `.remove()` on the result to
unsubscribe.

### `getUnacknowledgedEvents(): Promise<JournaledEvent[]>`
Terminal events not yet acknowledged, including ones that fired while JS was dead.

### `ackEvents(eventIds: string[]): Promise<boolean>`
Removes journaled events once processed.

### `getAllUploads(): Promise<UploadSnapshot[]>`
Uploads the OS still knows about, for boot-time reconciliation.

### `ios.getUploadStatus(uploadId)`
iOS-only live task state (`running | suspended | canceling`, plus byte counts), or
`undefined` if the task isn't active.

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
