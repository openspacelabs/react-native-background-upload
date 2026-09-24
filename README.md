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

The library owns a durable queue of HTTP requests: JSON bodies, multipart
forms, whole files, and chunked files. You describe each request kind one
time with `define()`, enqueue instances with `mutate()`, and receive every
outcome through the definition's handlers. Outcomes survive app death,
because the native side journals them before it tells JS.

```ts
import { createUploadClient } from 'react-native-background-upload';

export const uploads = createUploadClient();

// One definition per request kind. `request` runs one time, at mutate().
// `vars` is any JSON-serializable object, at most 1 MB by default; native
// persists it next to the entry. Generated API request types work as they are.
type AddCommentVars = { siteId: string; noteId: string; comment: string };
export const addComment = uploads.define({
  key: 'note.comment.add', // persisted with every entry; rename with care
  request: ({ siteId, noteId, comment }: AddCommentVars) => ({
    url: `https://api.example.com/sites/${siteId}/notes/${noteId}/comments`,
    data: { comment }, // JSON body. Default method is POST.
  }),
  // Parses the JSON body before onSuccess. Zod users pass schema.parse. The
  // parser also receives the entry's vars as a second argument.
  response: (raw) => (raw as { content: Comment[] }).content,
  onSuccess: (content, { noteId }, meta) => {
    // Runs after the server accepted the request, possibly on a later launch.
    store.dispatch(commentsLoaded({ noteId, content }));
  },
  onError: (error, vars, meta) => {
    // error.errorKind: 'http' | 'network' | 'file' | 'expired' | 'truncated' | 'unknown'
  },
});

// At boot, after every define() call. Replay of journaled outcomes starts here.
uploads.configure({
  headers: () => ({ Authorization: `Bearer ${currentToken()}` }),
  android: { notificationTitle: 'Uploading', notificationChannel: 'uploads' },
});

// Anywhere. Resolves when the entry and its staged body are on disk, never
// on the network. You may delete a source file after this resolves.
const { id } = await addComment.mutate(
  { siteId, noteId, comment },
  { id: localCommentId }, // optional; makes a re-dispatch idempotent
);
```

Handlers must be idempotent. The library acknowledges an outcome only after
the handler's promise resolves, so a crash before that point redelivers the
outcome at the next launch.

## The request descriptor

`request(vars)` returns a plain object. Set at most one body kind. A GET sets
none, and `mutate()` rejects a GET with a body. A DELETE, or a POST whose
meaning is in the URL, may also set none.
A field outside this table makes `mutate()` reject and name the field, because
TypeScript does not flag a misspelled key on an inferred arrow return.

| Field | Notes |
| --- | --- |
| `url` | Required unless `parts` is set. |
| `method` | `POST` (default), `PUT`, `PATCH`, `DELETE`, `GET`. With `parts` it applies to every part. |
| `headers` | Merged over `configure().headers()`, names matched without regard to case. Every chunked part inherits the result. |
| `data` | JSON body. Any JSON-serializable value. `null` sends the JSON body `null`; omit `data` for no body. |
| `form` | `multipart/form-data`: `[{ name, contentType, string }]` or `[{ name, contentType, path, fileName? }]`. File parts are copied. |
| `file` | Whole file body. Copied. Moved when `parts` is set. |
| `parts` | Chunked over `file`: `[{ url, headers?, range: { start, end } }]`, bytes, end exclusive, tiling the file from 0. |
| `accept` | Non-2xx responses to treat as success: `[{ status, bodyIncludes? }]`. |
| `expiresAt` | Epoch ms. Default now + `lifetimeMs` (14 days). Past it: `error` with `errorKind: 'expired'`. |
| `retry` | Per-request override of the `configure()` retry defaults. |
| `android` | `{ noNotification?: boolean }`. See Silent uploads. |

`vars` and `data` cross to native as JSON strings, and native parses them.
React Native on iOS drops object keys whose value is `null`, so a string is
the only form in which `{ status: null }` arrives intact.

### Chunked uploads

A descriptor with `file` and `parts` sends one file as many part requests but
stays one entry: one id, byte-weighted `progress`, and `completed` only when
every part is accepted. Author the ranges with `chunkPlan` so the part count
you tell your server and the parts the library sends derive from one array.

```ts
type CaptureFileVars = { path: string; size: number; uploadId: string };

const captureFile = uploads.define({
  key: 'capture.file',
  request: ({ path, size, uploadId }: CaptureFileVars) => {
    const ranges = uploads.chunkPlan(size, { min: 8 * 2 ** 20, max: 20 * 2 ** 20 });
    return {
      method: 'PUT',
      file: path, // moved into the library directory
      // Derive each part URL from its index. The part count you tell the
      // server and the parts sent here then come from the one chunkPlan call.
      parts: ranges.map((range, i) => ({
        url: partUrl(uploadId, i + 1), // part numbers are 1-indexed
        headers: { 'Content-Range': `${range.start}-${range.end - 1}/${size}` },
        range,
      })),
      accept: [{ status: 409, bodyIncludes: 'already completed' }],
      // A 404 on a part means the server-side multipart is gone. Make it
      // terminal so onError can recreate under a fresh server upload id.
      retry: { terminalHttp: { exempt: [] } },
    };
  },
  onError: (error, vars) => { /* recreate: mutate() again with new parts */ },
});
```

**File ownership.** A chunked `file` is moved into the library's directory at
`mutate()`; a single `file` body and every `form` part path are copied. Bytes
are deleted after a `completed` outcome is acknowledged, or on `cancel()`.
Nothing else deletes them.

**Same id, again.** The body is `data`, `form`, `file`, or `parts`, and a
different `url` or `method` counts as a different body. `mutate()` with an
id that exists:

- Same body: resume. New headers, `expiresAt`, and `vars` replace the stored
  ones.
- Different body (`data`, `form`, `file`, or `parts`) and the entry is not
  running (queued, paused, awaiting-auth, or settled): the descriptor, staged
  body, `vars`, headers, and `expiresAt` are replaced and the entry reopens.
  It settles once more.
- Different body, entry running: `mutate()` rejects with `E_RUNNING`.
- A cancelled entry whose outcome is not yet acknowledged: a fresh
  generation. The old outcome's ack forgets the entry only when the
  generation matches.
- A completed entry whose outcome is not yet acknowledged: the journaled
  outcome is emitted again. The request does not run again.

### Silent uploads (Android)

`android: { noNotification: true }` runs the request without a progress
notification. That notification is also the worker's foreground-service
notification, so a silent request runs as an ordinary background worker and
the OS may defer or restart it. Reserve it for small payloads.

# Reliable delivery

1. **Write-ahead.** Entry, descriptor, and staged body persist before any
   attempt. `mutate()` resolves after the row and every staged body copy are
   durably on disk (temp file plus rename), so the caller may delete its
   source file then. A native failure rejects with a code: `E_INVALID`,
   `E_RUNNING`, `E_FILE_MISSING`, or `E_STORAGE`. `E_INVALID` is malformed
   input native cannot send: a non-http(s) URL, header names or values the
   platform HTTP client rejects, a GET with a body, or parts that do not
   tile the moved file.
2. **Journal before emit, ack after the handler.** Every terminal outcome is
   journaled natively, then delivered. The library acknowledges after the
   handler's promise resolves. A rejection, or app death before the ack,
   redelivers at the next launch. A handler that has not settled after 30 s
   gets a console warning and keeps waiting. `Meta.deliveries` counts
   deliveries that reached a JS listener: 1 on the first, +1 per replay. An
   outcome journaled while no listener exists starts at 0 and is not emitted
   live. A handler that keeps throwing sees the number grow. The library
   never gives up on its own; the app decides a poison policy from that
   number.
3. **One outcome per settle cycle.** `pause()` produces none. A paused entry
   past `expiresAt` settles `error` with `errorKind: 'expired'` at
   `resume()`. A same-id `mutate()` on a settled entry reopens it, and it
   settles once more.
4. **Never before `mutate()` resolves.** Delivery for an id waits for the
   caller's promise.
5. **Replay starts after `configure()`.** Outcomes journaled by a dead session
   deliver then. Call every `define()` first.
6. **Unknown key is loud.** An outcome whose key has no definition stays
   unacknowledged and reaches `state` listeners with `reason: 'unhandled-key'`.
7. **Completed entries are forgotten after ack.** Row and bytes go. An `error`
   or `expired` entry keeps both until `cancel()` or a same-id `mutate()`.
8. **Ordering holds per id only.** The outcomes of one id are delivered in
   order. There is no ordering guarantee between different ids.
9. **`attempt` events are live-only.** They are never journaled or replayed.

Retry classes:

| Attempt outcome | Action |
| --- | --- |
| 2xx, or an `accept` rule matches | settle `completed` |
| network failure, 5xx, 408, 429 | exponential backoff (base 1 s, max 2 h, jitter 0.2) until `expiresAt` |
| 401, 403 | park as `awaiting-auth`; resume on `updateHeaders()` |
| other 4xx not in `retry.terminalHttp.exempt` | settle `error` with `errorKind: 'http'` |
| 4xx in `exempt` (default `[404]`) | as transient |
| payload missing on disk | settle `error` with `errorKind: 'file'` |
| `expiresAt` passed | settle `error` with `errorKind: 'expired'`; bytes kept |
| response body over 1 MB with a `response` parser | `onError` with `errorKind: 'truncated'`; the entry is `completed` |

Every attempt sends an `X-Request-Id` header, minted per attempt. `Meta.requestId`
carries the last one.

# API

`createUploadClient()` returns a client. The default export is one client;
an app needs one.

### `define(definition): { key, mutate }`

```ts
type Definition<V extends object | null, T> =
  | {
      key: string;
      request: (vars: V) => RequestDescriptor;
      response: (raw: unknown, vars: V) => T; // JSON-parsed body, or undefined when there is none
      onSuccess?: (data: T, vars: V, meta: Meta) => void | Promise<void>;
      onError?: (error: OutcomeError, vars: V, meta: Meta) => void | Promise<void>;
    }
  | {
      key: string;
      request: (vars: V) => RequestDescriptor;
      response?: undefined;
      onSuccess?: (data: RawResponse, vars: V, meta: Meta) => void | Promise<void>;
      onError?: (error: OutcomeError, vars: V, meta: Meta) => void | Promise<void>;
    };
```

`V` infers from the `request` parameter annotation, `T` from the `response`
return type. `response` also receives the entry's `vars`, for a parser that
needs the request context; a one-argument parser such as `schema.parse` is
assignable as it is. Without `response`, `onSuccess` receives the `RawResponse`
(`{ status?, headers?, body?, bodyTruncated }`), and an `onSuccess` annotated
with any other type is a compile error. `V` is any object or `null`, so a
generated API request type works as it is. `mutate()` rejects vars and
`data` that do not serialize: a cycle, a function, a BigInt, or a value that
`JSON.stringify` turns into a primitive. Methods on a class instance are
dropped. A `request` that declares no parameter gives `V = null`, and
`mutate()` then takes no arguments. When `response` is set and
the body was truncated, `onError` gets `errorKind: 'truncated'`. When
`response` throws, `onError` gets `errorKind: 'unknown'` with the thrown
message; the entry still settles as completed. A key that is already defined
is replaced, with a warning in development. A `cancelled` outcome calls no
handler.

`Meta` is `{ id, key, at, attempts, requestId?, deliveries }`; `at` is the
native outcome time. `attempts` counts attempts in the current generation; a
same-id `mutate()` that reopens the entry starts a new one. `deliveries`
counts deliveries that reached a JS listener: 1 on the first, +1 per replay.
An outcome journaled while no listener exists starts at 0 and is not emitted
live, so its first delivery, at the boot replay, is 1.

### `mutate(vars, { id? }): Promise<{ id }>`

Runs `request(vars)` once, merges `configure().headers()` under the
descriptor's headers, validates the descriptor, defaults `expiresAt`, and
persists the entry. Resolves with the id after the row and every staged body
copy are on disk. Rejects on a malformed descriptor, an unknown descriptor
field, a GET with a body, input native cannot send (`E_INVALID`), a missing
file (`E_FILE_MISSING`), a storage failure (`E_STORAGE`), a running entry
with a different body (`E_RUNNING`), or `vars` over
`maxVarsBytes` (1 MB by default). Only `vars` are capped. `id` defaults to a UUID. For a definition
whose `request` takes no vars, call `mutate()` with no arguments; native stores
`null`.

### `configure(options): void`

Call one time at boot, after every `define()`. Starts replay of journaled
outcomes. A second call updates the settings and does not replay again.

| Option | Notes |
| --- | --- |
| `lifetimeMs` | Default `expiresAt` distance. Default 14 days. |
| `maxVarsBytes` | Cap on the JSON length of `vars`. Default 1 MB. `mutate()` rejects above it. JS-side only. |
| `retry` | `{ backoff?: { baseMs, maxMs, jitter }, terminalHttp?: { exempt } }`. Each of the two objects is optional, but one you give must be complete. Defaults 1 s, 2 h, 0.2, `[404]`. |
| `headers` | `() => Record<string, string>`, called at `mutate()`. The descriptor merges over it. |
| `enqueueTimeoutMs` | Default 10 s. `mutate()` rejects and warns when native enqueue has not settled by then. Enqueue includes the time to stage a copy of a `file` body and of form `path` parts, so a large file takes longer. A timeout means native did not answer, not that the request failed. A watchdog for a native bug, not a tuning knob. |
| `android` | Notification text and identity: `notificationId/Title/TitleNoWifi/TitleNoInternet/Channel`. Persisted natively. |

### `pause(): Promise<void>` and `resume(): Promise<void>`
Whole-queue pause. No outcome is produced; live rows show `paused`. A paused
entry past `expiresAt` settles `error` with `errorKind: 'expired'` at
`resume()`.

### `cancel(id): Promise<void>`
A live entry settles `cancelled` with reason `user` and is forgotten after
its ack. A settled entry is forgotten now: row, bytes, and its
unacknowledged outcomes. An unknown id resolves and does nothing. If the
journal or the store cannot be written, `cancel()` rejects with `E_STORAGE` and changes
nothing; the caller may call again.

### `setWifiOnly(enabled): Promise<void>`
Persisted natively. Applies to queued and future entries.

### `updateHeaders(patch): Promise<void>`
Merges the patch into the headers of every entry not yet forgotten and
resumes the entries parked on `awaiting-auth`. The patch also replaces
same-named headers a part carries. This is how a fresh token
reaches requests that stalled on 401. Each call bumps a header generation: a
401 or 403 from an attempt issued under an older generation re-issues at once
instead of parking. Parking emits one `state` event per entry.

### `getRequests(filter?): RequestRow[]`
Synchronous, from native's in-memory index, so it works offline. Returns
every entry native has not yet forgotten: `queued`, `running`,
`awaiting-auth`, and `paused` entries; `completed` and `cancelled` entries
until their ack; `error` entries until `cancel()` or a same-id `mutate()`;
and imported legacy rows. `filter` is `{ key?, id? }`. A row is
`{ id, key, vars, state, bytesSent, totalBytes, attempts, updatedAt, nextAttemptAt? }`;
`nextAttemptAt` (epoch ms) is set while the entry waits out a retry backoff.
`vars` is typed `Json`, because a row does not know its definition. Narrow
it before reading a field, for example to cancel every entry of one capture:

```ts
uploads
  .getRequests({ key: 'capture.file' })
  .filter((row) => (row.vars as { captureId?: string }).captureId === captureId)
  .forEach((row) => uploads.cancel(row.id));
```

### `chunkPlan(sizeBytes, { min?, max? }): Array<{ start, end }>`
Splits a byte count into contiguous, end-exclusive ranges: a deterministic
greedy walk of `max`-sized chunks (default 20 MB), with a final remainder
smaller than `min` (default 8 MB) absorbed into the previous chunk. A file
smaller than `min` is a single chunk. Also a module export.

### `addListener(event, listener): EventSubscription`
See Events. Listeners are global; every event carries the entry's `id`. Call
`.remove()` on the result to unsubscribe.

### `android.addNotificationListener(listener)`
Fires when the Android progress notification is pressed. No event data.

# Events

| Event | Data |
| --- | --- |
| `state` | A full `RequestRow`, one per transition, plus `reason: 'unhandled-key'` for an outcome whose key has no definition. A consumer's reducer is one upsert. |
| `progress` | `{ id, bytesSent, totalBytes }`, byte-weighted across a chunked upload's parts. |
| `attempt` | One HTTP attempt: `{ id, key, requestId, attempt, url, method, partIndex?, outcome, httpCode?, responseBody? (4 KB cap), responseBodyTruncated?, responseHeaders?, errorKind?, errorMessage?, at }`. `outcome` is `completed` for an accepted response (2xx or a matching `accept` rule). Any other HTTP response is `error` with `errorKind: 'http'`; a transport failure is `error` with its own `errorKind`. Pause, cancel, and supersede emit no attempt event. Live-only; never journaled or replayed. |

Terminal outcomes do not appear here. They go to the definition's handlers.

# Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md).
