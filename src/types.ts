import { EventSubscription } from 'react-native';

export interface EventData {
  id: string;
}

export interface ProgressData extends EventData {
  progress: number;
}

/**
 * `expired` means that the upload's `expiresAt` time passed before the server
 * accepted every part. The library keeps the manifest and the bytes. Thus a
 * new `startUpload` call with a later deadline resumes the upload.
 */
export type ErrorKind = 'http' | 'network' | 'file' | 'expired' | 'unknown';

export type CancelReason = 'user' | 'system';

export type UploadId = string;

/**
 * Fields carried by every terminal event (`completed` / `error` / `cancelled`).
 *
 * The native side emits the journal entry itself, so a live terminal event is
 * the very same object `getUnacknowledgedEvents()` returns — `eventId` included,
 * which is what lets you `ackEvents([eventId])` immediately after handling a
 * live event instead of waiting to rediscover it on the next launch.
 */
export interface TerminalEventData extends EventData {
  eventId: string;
  type: 'completed' | 'error' | 'cancelled';
  /** Epoch milliseconds, stamped natively when the outcome occurred. */
  timestamp: number;
  /**
   * The response, when one was received. Absent for a transport failure (the
   * request never reached the server), so always narrow before using it.
   */
  responseCode?: number;
  responseBody?: string;
  /** True when `responseBody` hit the 64KB cap and was truncated. */
  responseBodyTruncated?: boolean;
  responseHeaders?: Record<string, string>;
}

/** A 2xx response, or one that matched an `accept` rule on the request. */
export interface CompletedData extends TerminalEventData {
  type: 'completed';
}

export interface ErrorData extends TerminalEventData {
  type: 'error';
  error: string;
  /**
   * Why it failed. `http` means the server responded and the status was not
   * accepted (the response fields above are populated). `file` means the payload
   * is missing or unreadable on disk, so retrying can never succeed.
   */
  errorKind?: ErrorKind;
  /**
   * Chunked uploads: the index into `parts` of the failing part, when one
   * part's response caused the error.
   */
  partIndex?: number;
}

export interface CancelledData extends TerminalEventData {
  type: 'cancelled';
  /** `user` for an explicit `cancelUpload`; `system` for an OS-initiated stop. */
  cancelReason?: CancelReason;
}

/**
 * A terminal event journaled natively before being emitted, so it survives app
 * death and JS reloads. Read via `getUnacknowledgedEvents`, process, then
 * acknowledge via `ackEvents`. Discriminate on `type`.
 */
export type JournaledEvent = CompletedData | ErrorData | CancelledData;

/** A snapshot of an upload the OS still knows about (from getAllUploads). */
export interface UploadSnapshot {
  id: UploadId;
  state: 'pending' | 'running' | 'completed' | 'error' | 'cancelled';
  /** iOS: bytes sent so far. Android: a chunked upload's accepted bytes. */
  bytesSent?: number;
  /** The total payload bytes. On iOS always; on Android for chunked uploads. */
  totalBytes?: number;
}

export type UploadOptions = {
  url: string;
  path: string;
  method: 'POST' | 'GET' | 'PUT' | 'PATCH' | 'DELETE';
  id?: string;
  headers?: {
    [index: string]: string;
  };
  // Whether the upload should wait for wifi before starting
  wifiOnly?: boolean;
  accept?: AcceptRule[];
  // Android options that change behavior. Notification text is not a
  // per-upload option. Set it one time with configure().
  android?: Partial<AndroidOnlyUploadOptions>;
} & RawUploadOptions;

/**
 * A non-2xx response to treat as success. `bodyIncludes` narrows the rule by
 * a response-body substring. This is necessary when one status has several
 * meanings, and only the message shows the difference (our backend's 409). A
 * non-2xx response that matches no rule emits an 'error' event with errorKind
 * 'http'.
 */
export type AcceptRule = {
  status: number;
  bodyIncludes?: string;
};

export type ChunkedUploadOptions = {
  type: 'chunked';
  /** Required. The consumer's durable id. */
  id: string;
  /**
   * The single source file. The library takes ownership: at startUpload it
   * renames the file into the library's own directory (an O(1) move). It
   * deletes the file only after you acknowledge a 'completed' terminal event.
   * If you must keep the file, copy it first. A keep-the-file mode is
   * deliberately not part of the library.
   */
  path: string;
  /**
   * The consumer authors this one time. The library sends the file bytes
   * [range.start, range.end) as the body of a PUT to `url`, with `headers`
   * unchanged. The library never derives or edits a protocol field.
   */
  parts: Array<{
    url: string;
    /** These headers include Content-Range, Content-Type, and auth. */
    headers: Record<string, string>;
    /** Byte offsets. The end is exclusive. */
    range: { start: number; end: number };
  }>;
  accept?: AcceptRule[];
  /** Epoch ms. Required. After this time: terminal error, errorKind 'expired'. */
  expiresAt: number;
  wifiOnly?: boolean;
  android?: Partial<AndroidOnlyUploadOptions>;
};

export type StartUploadOptions = UploadOptions | ChunkedUploadOptions;

export type AndroidOnlyUploadOptions = {
  /**
   * Uploads this file without a progress notification. Default false.
   *
   * The notification is what puts the upload's worker in foreground mode, which
   * is how it survives Doze and memory pressure, so a silent upload is easier
   * for the OS to defer or stop and re-run. Reserve it for payloads small enough
   * that a restart costs nothing, and keep it off for anything a user would
   * expect to see progress for.
   */
  noNotification?: boolean;
};

export type RawUploadOptions = {
  type: 'raw';
};

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
  android?: Partial<AndroidNotificationConfig>;
};

export interface AddListener {
  (
    event: 'progress',
    callback: (data: ProgressData) => void,
  ): EventSubscription;

  (event: 'error', callback: (data: ErrorData) => void): EventSubscription;

  (
    event: 'completed',
    callback: (data: CompletedData) => void,
  ): EventSubscription;

  (
    event: 'cancelled',
    callback: (data: CancelledData) => void,
  ): EventSubscription;
}
