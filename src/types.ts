import { EventSubscription } from 'react-native';

export interface EventData {
  id: string;
}

export interface ProgressData extends EventData {
  progress: number;
}

export type ErrorKind = 'http' | 'network' | 'file' | 'unknown';

export interface ErrorData extends EventData {
  error: string;
  errorKind?: ErrorKind;
  // Present when errorKind is 'http' (a non-accepted HTTP response).
  responseCode?: number;
  responseBody?: string;
  responseHeaders?: Record<string, string>;
}

export interface CompletedData extends EventData {
  eventId?: string;
  responseCode: number;
  responseBody: string;
  responseHeaders?: Record<string, string>;
}

export interface CancelledData extends EventData {
  cancelReason?: 'user' | 'system';
}

export type UploadId = string;

/**
 * A terminal event (completed/error/cancelled) journaled natively before being
 * emitted, so it survives app death and JS reloads. Read via
 * getUnacknowledgedEvents, process, then acknowledge via ackEvents.
 */
export interface JournaledEvent {
  eventId: string;
  id: UploadId;
  type: 'completed' | 'error' | 'cancelled';
  timestamp: number;
  responseCode?: number;
  responseBody?: string;
  responseBodyTruncated?: boolean;
  responseHeaders?: Record<string, string>;
  error?: string;
  errorKind?: ErrorKind;
  cancelReason?: 'user' | 'system';
}

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
  customUploadId?: string;
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

type AndroidOnlyUploadOptions = {
  notificationId: string;
  notificationTitle: string;
  notificationTitleNoWifi: string;
  notificationTitleNoInternet: string;
  notificationChannel: string;
  // Does not retry based on http code.
  // Only retry IO and other unknown issues.
  // Network failure does not count towards retries
  maxRetries?: number;
};

type RawUploadOptions = {
  type: 'raw';
};

// TODO support this to replace netq
// type MultipartUploadOptions = {
//   type: 'multipart';
//   field: string;
//   parameters?: {
//     [index: string]: string;
//   };
// };

export interface AddListener {
  (
    event: 'progress',
    uploadId: UploadId | null,
    callback: (data: ProgressData) => void,
  ): EventSubscription;

  (
    event: 'error',
    uploadId: UploadId | null,
    callback: (data: ErrorData) => void,
  ): EventSubscription;

  (
    event: 'completed',
    uploadId: UploadId | null,
    callback: (data: CompletedData) => void,
  ): EventSubscription;

  (
    event: 'cancelled',
    uploadId: UploadId | null,
    callback: (data: CancelledData) => void,
  ): EventSubscription;
}
