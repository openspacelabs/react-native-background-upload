import type { EventSubscription } from 'react-native';

/**
 * Any JSON value. `vars` and `data` must be JSON, because native persists them.
 * A vars type has to be a `type` alias, not an `interface`: only aliases get
 * the implicit index signature that this recursive type asks for. Fields must
 * be mutable arrays, not `readonly T[]`.
 */
export type Json =
  | string
  | number
  | boolean
  | null
  | Json[]
  | { [k: string]: Json };

export type Method = 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'GET';

/**
 * Why a request failed. `http` means the server answered and the status was
 * not accepted. `network` is a transport failure. `file` means the payload is
 * missing on disk, so a retry can never succeed. `expired` means `expiresAt`
 * passed. `truncated` means the response body hit the 1 MB cap, so the
 * `response` parser could not run. `unknown` covers a parser throw.
 */
export type ErrorKind =
  | 'http'
  | 'network'
  | 'file'
  | 'expired'
  | 'truncated'
  | 'unknown';

/** `user` for an explicit `cancel()`. `system` for an OS-initiated stop. */
export type CancelReason = 'user' | 'system';

/**
 * A non-2xx response to treat as success. `bodyIncludes` narrows the rule by
 * a response-body substring. This is necessary when one status has several
 * meanings, and only the message shows the difference.
 */
export type AcceptRule = { status: number; bodyIncludes?: string };

/**
 * One multipart/form-data field. A `path` part is a file. The library copies
 * the file into its own directory at `mutate()`.
 */
export type FormPart = { name: string; contentType: string } & (
  | { string: string }
  | { path: string; fileName?: string }
);

/**
 * One part of a chunked upload. The library sends the file bytes
 * [range.start, range.end) as the body of a request to `url`. `headers` merge
 * over the descriptor's headers. The range end is exclusive.
 */
export type Part = {
  url: string;
  headers?: Record<string, string>;
  range: { start: number; end: number };
};

export type RetryPolicy = {
  backoff: { baseMs: number; maxMs: number; jitter: number };
  /** HTTP statuses in the 4xx range that retry instead of settling. */
  terminalHttp: { exempt: number[] };
};

/** What `request(vars)` returns. Native persists it next to `vars`. */
export type RequestDescriptor = {
  /** Required unless `parts` is set. */
  url?: string;
  /** Default POST. With `parts` it applies to every part. */
  method?: Method;
  /** Merged over `configure().headers()`. Every part inherits the result. */
  headers?: Record<string, string>;
  /** JSON body. Exactly one of `data`, `form`, `file` must be set. */
  data?: Json;
  /** multipart/form-data body. */
  form?: FormPart[];
  /** Whole file body. Copied. Moved when `parts` is set. */
  file?: string;
  /** Chunked over `file`. Each part sends its own byte range. */
  parts?: Part[];
  accept?: AcceptRule[];
  /** Epoch ms. Default now + `lifetimeMs`. */
  expiresAt?: number;
  retry?: Partial<RetryPolicy>;
  android?: { noNotification?: boolean };
};

/**
 * The last response of a completed request. `status` is absent for a chunked
 * completion, because no single response represents N parts. `body` holds up
 * to 1 MB; `bodyTruncated` says whether the cap cut it.
 */
export type RawResponse = {
  status?: number;
  headers?: Record<string, string>;
  body?: string;
  bodyTruncated: boolean;
};

/**
 * `response` is set for `http` and `truncated`. `partIndex` is the index of
 * the failing part of a chunked upload.
 */
export type OutcomeError = {
  errorKind: ErrorKind;
  message: string;
  response?: RawResponse;
  partIndex?: number;
};

/**
 * Handler context. `at` is the native outcome time. `requestId` is the last
 * attempt's X-Request-Id.
 */
export type Meta = {
  id: string;
  key: string;
  at: number;
  attempts: number;
  requestId?: string;
};

export type Outcome =
  | { kind: 'completed'; response: RawResponse }
  | { kind: 'error'; error: OutcomeError }
  | { kind: 'cancelled'; cancelReason: CancelReason };

export type RequestState =
  | 'queued'
  | 'running'
  | 'awaiting-auth'
  | 'paused'
  | 'completed'
  | 'error'
  | 'cancelled';

/**
 * One row of the native queue, as `getRequests()` and `state` events carry it.
 * `vars` is `Json`, because the row does not know its definition. Narrow it
 * with a cast before reading a field: `(row.vars as { captureId?: string })`.
 */
export type RequestRow = {
  id: string;
  key: string;
  vars: Json;
  state: RequestState;
  bytesSent: number;
  totalBytes: number;
  attempts: number;
  updatedAt: number;
};

/**
 * A `state` event. `reason: 'unhandled-key'` reports an outcome whose key has
 * no definition. The row carries the entry's real state, and the library keeps
 * the entry unacknowledged.
 */
export type StateEvent = RequestRow & { reason?: 'unhandled-key' };

export type ProgressEvent = {
  id: string;
  bytesSent: number;
  totalBytes: number;
};

/** One HTTP attempt, before the library interprets it. Response body is capped at 4 KB. */
export type AttemptEvent = {
  id: string;
  key: string;
  requestId: string;
  attempt: number;
  url: string;
  method: Method;
  partIndex?: number;
  outcome: 'completed' | 'error' | 'cancelled';
  httpCode?: number;
  responseBody?: string;
  responseBodyTruncated?: boolean;
  responseHeaders?: Record<string, string>;
  errorKind?: ErrorKind;
  errorMessage?: string;
  cancelReason?: CancelReason;
  /** Native stamp, epoch ms. */
  at: number;
};

type DefinitionBase<V extends Json> = {
  /** Persisted with every entry, so rename it with care. */
  key: string;
  /** Runs one time, at `mutate()`. */
  request: (vars: V) => RequestDescriptor;
  onError?: (error: OutcomeError, vars: V, meta: Meta) => void | Promise<void>;
};

/** A definition with a parser. `onSuccess` receives what `response` returns. */
export type DefinitionWithResponse<V extends Json, T> = DefinitionBase<V> & {
  /** Parses the JSON body (`undefined` when there is none) before `onSuccess`. */
  response: (raw: unknown) => T;
  onSuccess?: (data: T, vars: V, meta: Meta) => void | Promise<void>;
};

/** A definition without a parser. `onSuccess` receives the `RawResponse`. */
export type DefinitionWithoutResponse<V extends Json> = DefinitionBase<V> & {
  response?: undefined;
  onSuccess?: (data: RawResponse, vars: V, meta: Meta) => void | Promise<void>;
};

/**
 * One request kind. `key` is persisted with every entry, so rename it with
 * care. `request` runs one time, at `mutate()`. `response` parses the JSON
 * body before `onSuccess`. Without `response`, `onSuccess` receives the
 * `RawResponse`, and the two shapes are kept apart so that an `onSuccess`
 * annotated with another type does not compile.
 */
export type Definition<V extends Json, T> =
  | DefinitionWithResponse<V, T>
  | DefinitionWithoutResponse<V>;

/**
 * What `define()` returns. `mutate()` resolves when native has persisted the
 * entry. When `V` is `null` (a `request` that takes no vars), `mutate()` takes
 * no arguments. `T` is carried so a `Defined` names the response type its
 * handlers see, even though `mutate()` itself does not use it.
 */
// eslint-disable-next-line @typescript-eslint/no-unused-vars
export type Defined<V extends Json, T> = {
  key: string;
  mutate: [V] extends [null]
    ? (vars?: null, options?: { id?: string }) => Promise<{ id: string }>
    : (vars: V, options?: { id?: string }) => Promise<{ id: string }>;
};

/**
 * `define()`. `V` infers from the `request` parameter and defaults to `null`
 * when `request` declares none. `T` infers from the `response` return type.
 * Without `response`, the handlers see the `RawResponse`.
 */
export interface Define {
  <V extends Json = null, T = RawResponse>(
    definition: DefinitionWithResponse<V, T>,
  ): Defined<V, T>;
  <V extends Json = null>(
    definition: DefinitionWithoutResponse<V>,
  ): Defined<V, RawResponse>;
}

/**
 * The text and the identity of the Android upload progress notification. Set
 * it one time with `configure()`. The library keeps it in native storage. Thus
 * a worker that WorkManager relaunches with no JS shows the same text. A field
 * that you omit keeps the library default.
 */
export type AndroidNotificationConfig = {
  /** All uploads share one notification. Its progress bar is the total. */
  notificationId: string;
  notificationTitle: string;
  notificationTitleNoWifi: string;
  notificationTitleNoInternet: string;
  notificationChannel: string;
};

export type ConfigureOptions = {
  /** Default 14 days. Sets the default `expiresAt` of every entry. */
  lifetimeMs?: number;
  /** Defaults: base 1 s, max 2 h, jitter 0.2, exempt [404]. */
  retry?: Partial<RetryPolicy>;
  /** Called at `mutate()`. The descriptor's headers merge over the result. */
  headers?: () => Record<string, string>;
  android?: Partial<AndroidNotificationConfig>;
};

export interface AddListener {
  (event: 'state', listener: (e: StateEvent) => void): EventSubscription;
  (event: 'progress', listener: (e: ProgressEvent) => void): EventSubscription;
  (event: 'attempt', listener: (e: AttemptEvent) => void): EventSubscription;
}

export type UploadClient = {
  configure: (options: ConfigureOptions) => void;
  define: Define;
  pause: () => Promise<void>;
  resume: () => Promise<void>;
  cancel: (id: string) => Promise<void>;
  setWifiOnly: (enabled: boolean) => Promise<void>;
  updateHeaders: (patch: Record<string, string>) => Promise<void>;
  getRequests: (filter?: { key?: string; id?: string }) => RequestRow[];
  addListener: AddListener;
  chunkPlan: (
    sizeBytes: number,
    opts?: { min?: number; max?: number },
  ) => Array<{ start: number; end: number }>;
  android: {
    addNotificationListener: (listener: () => void) => EventSubscription;
  };
};
