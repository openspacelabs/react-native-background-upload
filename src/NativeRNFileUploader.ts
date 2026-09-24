import { type CodegenTypes, type TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

// Codegen TurboModule spec (New Architecture). The typed public API lives in
// ./types and is applied at the JS edge in ./index, ./registry and ./delivery.
// The dynamic-shaped payloads (the entry, queue rows, settled events, attempt
// events) are declared as UnsafeObject because codegen can't model index
// signatures, Partial<>, or unions. The JS layer casts them back to the
// precise ./types shapes.
//
// The comments on each method are the contract native must implement. The
// README's "API" and "Reliable delivery" sections and the plan's sections 5.4
// and 6 mirror them.
export interface Spec extends TurboModule {
  // Queue-wide settings: { lifetimeMs, retry, ...androidNotificationConfig }.
  // Android persists the notification config, so a headless WorkManager
  // relaunch (no JS) can read it. Each call replaces the full configuration.
  configure(options: CodegenTypes.UnsafeObject): void;
  // Persists { id, key, varsJson, descriptor } and schedules it. varsJson is
  // JSON.stringify(vars). descriptor.dataJson is JSON.stringify(data) and
  // replaces data; a bodiless request omits it. Both cross as strings
  // because React Native on iOS drops object keys whose value is null, so
  // { status: null } would arrive as {}. Native parses them. dataJson
  // "null" is the JSON body null, a real body, not an absent one.
  //
  // Resolves AFTER the row and every staged body copy are durably on disk
  // (tmp file + rename), never on the network. Staging copies a `file` body
  // and form `path` parts, so a large file takes longer. The caller may
  // delete its source file once mutate() resolves. Every failure path
  // rejects with a code: E_INVALID, E_RUNNING, E_FILE_MISSING, E_STORAGE.
  // E_INVALID is malformed input native cannot send: non-http(s) URL, header
  // names or values the platform HTTP client rejects, a GET with a body,
  // parts that do not tile the moved file.
  //
  // Same id, again. The body is data/form/file/parts, and a different url or
  // method is a different body.
  // - Same body: resume. New headers, expiresAt and vars replace the stored
  //   ones.
  // - Different body (data/form/file/parts) and the entry is NOT running
  //   (queued, paused, awaiting-auth, or settled): replace the descriptor,
  //   staged body, vars, headers and expiresAt, and reopen the entry. It
  //   settles once more.
  // - Different body, entry running: reject E_RUNNING.
  // - Cancelled-but-unacked entry: a fresh generation. ack forgets the entry
  //   only when the generation matches.
  // - Completed-but-unacked entry: re-emit the journaled outcome. Do not
  //   re-run.
  //
  // The resolved value is the entry's id. The JS layer does not read it.
  enqueue(entry: CodegenTypes.UnsafeObject): Promise<string>;
  // Whole-queue pause. No outcome is produced; live rows move to 'paused'.
  // A paused entry past expiresAt settles error/expired at resume.
  pause(): Promise<void>;
  resume(): Promise<void>;
  // Live entry: journal 'cancelled' (user), forget after its ack. Settled
  // entry: forget now: row, bytes, and its unacknowledged outcomes. Unknown
  // id: resolve, no-op.
  cancel(id: string): Promise<void>;
  // Persisted natively. Applies to queued and future entries.
  setWifiOnly(enabled: boolean): Promise<void>;
  // Merges the patch into every entry not yet forgotten and bumps a header
  // generation. The patch also replaces same-named headers a part carries. A 401/403 from an attempt issued under an older generation
  // re-issues at once instead of parking. Parking emits one 'state' event per
  // entry.
  updateHeaders(patch: CodegenTypes.UnsafeObject): Promise<void>;
  // Synchronous. Serialized from the in-memory index that native keeps
  // current on every state change, never from disk. Returns every entry not
  // yet forgotten: states queued, running, awaiting-auth, paused; completed
  // and cancelled until acked; error until cancel() or a same-id enqueue();
  // imported legacy rows. Rows carry `vars` as the object native stored, and
  // `nextAttemptAt` (epoch ms) while an entry waits out a backoff.
  getRequests(): CodegenTypes.UnsafeObject[];
  // Settled outcomes that JS has not acknowledged, in the onSettled shape,
  // including ones journaled while JS was dead. Internal: ./delivery drains
  // them after configure().
  getUnacknowledgedEvents(): Promise<CodegenTypes.UnsafeObject[]>;
  // Removes journaled outcomes by eventId. Resolves void. Idempotent; unknown
  // ids are ignored. An acknowledged 'completed' is the one moment native
  // deletes the entry's row and bytes.
  ackEvents(ids: string[]): Promise<void>;

  // Events. state carries a full RequestRow per transition. progress is
  // byte-weighted across a chunked upload's parts. notification is
  // Android-only (tapping the progress notification) and never fires on iOS.
  readonly onState: CodegenTypes.EventEmitter<CodegenTypes.UnsafeObject>;
  readonly onProgress: CodegenTypes.EventEmitter<{
    id: string;
    bytesSent: number;
    totalBytes: number;
  }>;
  // One HTTP attempt, in the AttemptEvent shape. outcome 'completed' is an
  // accepted response. Any other HTTP response is 'error' with errorKind
  // 'http'. A transport failure is 'error' with its own errorKind. Pause,
  // cancel, and supersede emit no attempt event. Live-only: never journaled,
  // never replayed.
  readonly onAttempt: CodegenTypes.EventEmitter<CodegenTypes.UnsafeObject>;
  // The journaled terminal outcome, emitted after the journal write, in the
  // ./delivery SettledEvent shape: { eventId, id, key, vars, at, attempts,
  // requestId?, deliveries, state, bytesSent?, totalBytes?, url, method,
  // partIndex? } plus the outcome fields. eventId is a UUID string native
  // mints. deliveries counts deliveries that reached a JS listener: 1 on the
  // first, +1 per replay; an outcome journaled while no listener exists
  // starts at 0 and is not emitted live. When the field is missing, the JS
  // layer treats it as 1. No ordering
  // guarantee between different ids; the JS layer orders one id's outcomes.
  // ./delivery routes it to the definition's handlers.
  readonly onSettled: CodegenTypes.EventEmitter<CodegenTypes.UnsafeObject>;
  readonly onNotification: CodegenTypes.EventEmitter<CodegenTypes.UnsafeObject>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RNFileUploader');
