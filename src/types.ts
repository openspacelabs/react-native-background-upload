import { EventSubscription } from 'react-native';

export interface EventData {
  id: string;
}

export interface ProgressData extends EventData {
  progress: number;
}

export type ErrorKind = 'http' | 'network' | 'file' | 'unknown';

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

/** A 2xx response, or one whose status was listed in the request's `acceptStatus`. */
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
  bytesSent?: number; // iOS only
  totalBytes?: number; // iOS only
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
  // Non-2xx statuses to treat as a successful completion (e.g. [409] when
  // duplicate-create conflicts are expected). Anything else non-2xx emits an
  // 'error' event with errorKind 'http'.
  acceptStatus?: number[];
  // Optional: the library supplies notification defaults and creates its own channel.
  android?: Partial<AndroidOnlyUploadOptions>;
} & RawUploadOptions;

export type AndroidOnlyUploadOptions = {
  notificationId: string;
  notificationTitle: string;
  notificationTitleNoWifi: string;
  notificationTitleNoInternet: string;
  notificationChannel: string;
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
