# Chunked uploads

Design for moving multi-part (chunked) file upload transport into this library.
Motivated by DIANA-H09 / RAD-12777 ("saved photos not published", 12k occurrences):
the consumer owning chunk files that a native job still needs is a structural
defect no amount of consumer-side care fixes. Status: approved design, phased
implementation (see Rollout).

## The problem

Today the consumer (Diana) splits a large file into chunk files, hands each to
`startUpload` as an independent upload, and reassembles the story from N
unrelated event streams. That split ownership causes every major failure class
we have seen in production:

- The consumer materializes every chunk up front (2× disk peak) and deletes the
  source immediately, so the chunk files are the only copy of the bytes. Any
  consumer-side cleanup that runs while a native job is pending destroys data
  permanently.
- The consumer cannot tell "part done" from "server said something else with a
  409", so it treats every 409 as success and deletes the bytes — including on
  the 409 meanings that are bugs.
- Retry is unbounded in time. The backend deletes unfinished multipart uploads
  31 days after creation (`UploadPartCleanup`: hourly, `created <= now - 31d`,
  incomplete). After that the upload is a zombie: part PUTs still return 2xx,
  but it can never publish. The consumer retries into the void forever.
- Resume is reconstructed from consumer state (redux insertion order), which
  works for exactly one of the three chunked flows and silently misassigns
  parts if the order invariant ever breaks.

## Design principles

**The library owns transport; the protocol arrives as data.** The consumer
authors the parts — URL, headers, byte range — once, at creation. The library
never constructs a URL, a `Content-Range` header, or a create/finalize request.
This keeps every OpenSpace-specific rule (1-indexed `partNum`, range format,
mime requirements, the create POST) in the consumer, and makes the
numParts-mismatch brick impossible: the count the server was told and the parts
the library sends derive from the same array. Data is also the only shape that
works with a dead app: the parts are persisted in the native manifest at
`startUpload`, so a worker rescheduled after reboot or a session reconciling
after relaunch can keep sending bytes without calling into JS. A per-part
callback could not (nothing to call into), and a URL template would put the
protocol inside the library.

**One reliability mechanism.** `startUpload` is idempotent against a durable
native manifest. Calling it again with the same id reconciles: parts already
accepted are skipped, the rest continue. Crash recovery, resume after a stop,
resume after expiry (salvage), and resume with fresh auth headers are all the
same call. There is no separate pause API — `cancelUpload` stops in-flight
requests, journals a `cancelled` terminal event, and keeps the manifest and
bytes; the next `startUpload` resumes.

**Order independence.** Neither OS lets an app guarantee the order background
requests reach the server (Apple DTS: "Sessions do not guarantee to execute
tasks in order"; WorkManager docs: "you do not have guarantees for the order in
which it runs"). The design never depends on order: every part carries an
absolute byte range, parts may complete in any order, and completion is
"all N parts accepted", never a sequence position.

**Bounded lifetime.** Every chunked upload carries a required `expiresAt`. When
it passes, the library stops, journals a terminal error with
`errorKind: 'expired'`, and keeps the bytes. Expiry replaces unbounded retry
counts: within the lifetime the library retries transient failures on a
backoff indefinitely; at the deadline it reports instead of hammering a server
that may have already given up.

## API

```ts
type ChunkedUploadOptions = {
  type: 'chunked';
  customUploadId: string;        // required: the consumer's durable id
  /**
   * The single source file. The library takes ownership: an O(1) rename into
   * its own directory at startUpload, deleted only after a 'completed'
   * terminal event is acknowledged. A consumer that needs to keep the file
   * copies it first — deliberately not a library mode.
   */
  path: string;
  /**
   * Authored once by the consumer. The library sends file bytes
   * [range.start, range.end) as the body of a PUT to `url` with `headers`
   * verbatim. It never derives or edits protocol fields.
   */
  parts: Array<{
    url: string;
    headers: Record<string, string>;   // includes Content-Range, Content-Type, auth
    range: { start: number; end: number };  // bytes, end exclusive
  }>;
  /**
   * Non-2xx responses to treat as part success. `bodyIncludes` narrows by
   * response-body substring — required for our backend, whose 409 carries five
   * different meanings distinguishable only by message; the two success
   * meanings both contain 'already completed'.
   */
  accept?: Array<{ status: number; bodyIncludes?: string }>;
  /** Epoch ms. Required. Past it: terminal error, errorKind 'expired'. */
  expiresAt: number;
  wifiOnly?: boolean;
  android?: Partial<AndroidOnlyUploadOptions>;
};

// New terminal-error kind:
type ErrorKind = 'http' | 'network' | 'file' | 'expired' | 'unknown';

// Explicit release of a finished-but-not-completed upload's manifest and bytes:
removeUpload(uploadId: string): Promise<void>;
```

A chunked upload is one logical upload: one id, one event stream. `progress` is
byte-weighted across parts. `completed` fires only when every part has been
accepted — which is exactly the server's auto-publish condition. An `error`
event carries the failing part's index and the response fields, journaled like
every other terminal event.

Re-calling `startUpload` with an existing id and the same parts **resumes** it:
the new call's headers and `expiresAt` replace the stored ones (this is how a
fresh auth token reaches parts that stalled on 401), accepted parts are
skipped, and `path` is ignored — once a manifest exists, the owned bytes are
the source of truth. A call with a *different* parts array is a **recreate**:
accepted only while the upload is not running (stalled on a terminal error,
expired, or cancelled), it keeps the owned bytes, replaces the parts, and
resets every part to unsent. The new ranges must tile the same total size.
This is the mechanism for re-uploading under a fresh server uploadId after the
old one dies — the consumer authors new part URLs and calls `startUpload`
again; nothing else is needed. While the upload is running, a differing parts
array is rejected: that is a consumer bug, not a recreate.

### Who splits the file

The split algorithm lives in the library: `chunkPlan(size, {min, max})`, a pure
deterministic function (greedy `max`-sized walk, tail absorbed when under
`min`; the defaults — 8MiB min, 20MiB max — match the OpenSpace server
constraints and can be overridden per call). The consumer invokes it rather
than the library doing so internally, and this is deliberate:

- The server must be told `numParts` in the create POST, that value is
  immutable, and a wrong value bricks the server upload permanently. The create
  POST is protocol, so it is the consumer's request — meaning the consumer
  needs the part count regardless of who computes the split. Calling
  `chunkPlan` once and deriving both the create POST (`parts.length`) and the
  `parts` array from the same result makes a mismatch structurally impossible.
  A library-internal split would put the same computation in two places again.
- Turning ranges into requests takes protocol knowledge only the consumer has
  (`?partNum=N`, `Content-Range`, mime, auth). A library-internal split would
  have to hand ranges back out through a per-part callback — the adapter shape
  this design already rejected — or teach the library the protocol.

The consumer's whole authoring job is a dozen lines: stat the file, `chunkPlan`
the size, map ranges to `{url, headers, range}`, pass the array.

## Byte ownership and deletion

- The library owns the bytes, always: it renames the source into its own
  directory at `startUpload`. Nothing the consumer does (cache sweeps, logout
  cleanup) can touch it, which deletes the H09 failure class instead of
  guarding it — and makes mid-upload file loss impossible rather than one more
  error to classify. There is deliberately no keep-my-file mode: all three
  chunked flows were checked, and none reads its source after upload (capture
  and OSTR delete it at creation today; lidar's lingers only until a generic
  2-day cache sweep, read by nothing). A future consumer that needs the file
  afterward copies it before calling.
- The bytes are deleted in exactly one case: a `completed` terminal event has
  been acknowledged (`ackEvents`). Every other terminal — expired, error,
  cancelled — keeps the manifest and bytes, because the consumer's recovery
  options (resume the same server upload, or recreate under a new one) need
  them. The consumer releases them explicitly with `removeUpload`.
- No pre-chunking, ever. Android streams each range straight from the source
  file (a `RandomAccessFile`-backed request body). iOS background sessions can
  only upload from a file, so the library materializes one temp file per
  *in-flight* part and deletes it on completion: transient disk is
  `concurrency × partSize` (~60MB at defaults) instead of a second full copy
  of the source. This also removes the consumer-side chunker (a synchronous
  bridge call today) and its silent-corruption hole (an unchecked `skip()`).

## Concurrency

Two knobs, both small and bounded:

- **Per chunked upload**: `concurrency` parts in flight at once, default **3**,
  never more than one in-flight request per part index (concurrent PUTs of the
  same `partNum` are verified unsafe server-side). The default follows
  vendor practice for S3-style multipart — AWS's iOS TransferUtility uploads 5
  parts concurrently inside a background `NSURLSession` by default; no vendor
  ships or recommends serial parts — held to the conservative end because our
  parts are 20MB (AWS's default is 5MB) and stream through our own app server.
- **Library-wide**: a target of at most **4** requests transmitting at once
  across all uploads, chunked and simple. This replaces today's accidental
  asymmetry — Android fully serial, iOS unlimited — but the two platforms can
  only enforce it with different strength:
  - Android: the worker semaphore widens from 1 to 4. Every request passes
    through our worker, so this is a hard cap.
  - iOS: consumers can still start any number of uploads — every task goes
    straight to the system daemon, which is what lets it run with the app
    dead. The knobs that remain: the chunked window keeps at most
    `concurrency` part tasks *enqueued* per upload (a real cap the library
    owns), and `httpMaximumConnectionsPerHost = 4` returns (v7 had 1; v8
    dropped it unreviewed) to limit simultaneous transmission. The connection
    cap is a backstop, not a guarantee: it applies per session (we run two —
    wifiOnly and default), and under HTTP/2 it caps connections rather than
    multiplexed requests. In practice it matters most for piles of simple
    uploads over HTTP/1.1; chunked concurrency is governed by the window.

On iOS the window is also a liveness decision. A background session only makes
progress on tasks already enqueued with the daemon; enqueueing the next part
requires waking the app, and the documented resume rate limiter doubles that
wake delay each time. So the library keeps the window's worth of part tasks
enqueued ahead (refilled on each completion wake), giving the daemon runway
while the app is dead — the same sliding-window shape AWS uses. Strictly
serial (window 1) would maximize wakes per byte and stall multi-part uploads
of terminated apps; it is deliberately not the default.

## Lifetime, expiry, and what the consumer does after

Within `expiresAt`, transient failures (network, 5xx) retry on exponential
backoff with no attempt cap — the deadline is the cap. A non-accepted HTTP
response that survives its bounded per-part retries journals an `error` with
the part detail and stalls the upload awaiting a `startUpload` resume (for
example with fresh headers after a 401).

At `expiresAt`, the library journals `errorKind: 'expired'` and stops. Expiry
burns nothing:

- The server keeps an unfinished upload until 31 days after creation, so an
  expired upload is *salvageable* until then — the consumer re-calls
  `startUpload` with the same parts and a later `expiresAt`, and only the
  unaccepted parts transfer.
- Past day 31 the server upload is unrecoverable, and the consumer recreates:
  new server uploadId, new parts array, full re-upload — possible because the
  bytes survived. Mechanically this is the recreate rule above: the same
  `startUpload` call with the newly authored parts.
- Both reactions are automatable in the consumer's terminal-event handler. The
  library cannot own them (part URLs embed the server uploadId, which is
  protocol), and it must not: an unconditional recreate-on-expiry would rebuild
  the very infinite-retry loop this design removes.

The consumer picks the lifetime; Diana passes 14 days — long enough for
offline jobsite stretches, and expiry then still leaves a 17-day salvage
window before the server's deadline.

## What the consumer keeps

The create/metadata POST (an ordinary single upload; the server accepts parts
before the create), token supply, grouping and UI, Sentry policy, the
post-expiry restart policy, and any diagnostic use of the server's progress
route (gated on `completed` first — its `chunks` array is empty for
never-started, published, and swept uploads alike). One known backend edge
stays a consumer-side diagnostic: if every part reports accepted but the
server's `completed` stays false after a full resume pass, the upload is stuck
server-side (a failed finalize) — report it, never loop on it.

## Simplifications to the existing surface

Diana is this library's only consumer, so unused or duplicated surface is cut
in the same major version rather than deprecated. Verified against Diana's
master before each cut:

- **Listeners are global-only**: `addListener(event, cb)`. Diana registers all
  four listeners globally, and its library mock throws on a scoped
  subscription — the per-upload filter was JS-side sugar with a consumer that
  forbids it.
- **`android.addNotificationListener` stays.** Diana's notification
  tap-routing uses it today (`uploadNotificationEpics.ts:35`) — review caught
  this after an earlier usage check missed the JS wrapper name. The
  payload-less signature is kept unchanged; an upload-id payload can be added
  when tap-routing needs to target a specific upload.
- **`android.maxRetries` is removed** (never passed). Retry policy belongs to
  the library, and chunked uploads are bounded by `expiresAt`, the better cap.
- **`acceptStatus: number[]` is replaced by the `accept` rules shape for all
  uploads**, chunked and simple. One concept, one native implementation.
  Nothing passes `acceptStatus` today, so there is no migration.
- **`startUpload` is idempotent for every upload, always.** Android simple
  uploads already were (`ExistingWorkPolicy.KEEP`); iOS simple uploads join;
  chunked is idempotent by design. Calling `startUpload` twice with the same
  id is never an error — which deletes `ios.getUploadStatus`, whose only use
  was the consumer's pre-dispatch dedupe working around the old behavior.
- **Notification text moves to a one-time `configure()`**, persisted natively
  so a headless worker relaunched by WorkManager (no JS running) can read it.
  Per-upload `android` options reduce to `noNotification`, which is
  behavioral — it decides foreground-service survival — rather than cosmetic.
  The worst misconfiguration is default text on a progress notification.
- **The chunked window (3) is a library constant**, not an option. If soak
  data argues for a different value, the constant changes, not the API.

## Rollout

Library, as a stack of small PRs: (1) this document; (2) the TurboModule spec,
types, accept rules, `chunkPlan`, and tests; (3) the Android worker;
(4) the iOS sliding window and relaunch reconciliation; (5) hardening — the
global cap on both platforms, example app, docs.

Gate before any of it ships in Diana: the v8 device matrix (background
continuation while suspended and terminated, relaunch on completion, force-quit
attribution, event delivery after a JS reload, minified Android), now partly
observable in production via the journal-drain and system-cancel Sentry
breadcrumbs that shipped 2026-08-21.

Diana adopts behind a flag (`chunked-upload-v2`), captures first, new uploads
only — in-flight legacy uploads drain on the old path. After soak: OSTR and
lidar, then delete `chunk.ts`, the native chunkers, the multipart transfer
branch, and the recovery epics.
