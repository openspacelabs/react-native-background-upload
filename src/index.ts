/**
 * Handles HTTP background file uploads from an iOS or Android device.
 */
import { Platform } from 'react-native';
import type { EventSubscription } from 'react-native';
import NativeRNFileUploader from './NativeRNFileUploader';
import {
  AddListener,
  ChunkedUploadOptions,
  ConfigureOptions,
  JournaledEvent,
  StartUploadOptions,
  UploadId,
  UploadSnapshot,
} from './types';
import { chunkPlan } from './chunkPlan';

export * from './types';
export * from './chunkPlan';

const fileURIPrefix = 'file://';

/**
 * One-time library configuration. Call it at app startup, before an upload
 * starts. Android keeps the notification configuration in native storage. Thus
 * a worker that WorkManager relaunches with no JS shows the same notification
 * text. The call is optional: a field that you do not configure keeps the
 * library default. Each call replaces the full configuration. The call does
 * nothing on iOS, because iOS has no library notification.
 */
const configure = ({ android }: ConfigureOptions): void => {
  NativeRNFileUploader.configure({ ...android });
};

const normalizePath = (path: string): string => {
  if (!path.startsWith(fileURIPrefix)) {
    path = fileURIPrefix + path;
  }
  // Android native takes a plain filesystem path. iOS takes a file:// URL.
  return Platform.OS === 'android' ? path.replace(fileURIPrefix, '') : path;
};

// Reject malformed chunked input before it crosses the bridge. Then native
// never creates a manifest for an upload that cannot complete.
const validateChunkedOptions = (options: ChunkedUploadOptions): void => {
  if (!options.id) {
    throw new Error('startUpload: a chunked upload requires a non-empty id');
  }
  if (!Array.isArray(options.parts) || options.parts.length === 0) {
    throw new Error('startUpload: parts must be a non-empty array');
  }
  // The parts must tile the file from byte 0. They must be sorted in
  // ascending order, with no gaps and no overlaps. Each plan from chunkPlan
  // obeys this by construction.
  let expectedStart = 0;
  options.parts.forEach(({ url, headers, range }, i) => {
    if (typeof url !== 'string' || url.length === 0) {
      throw new Error(`startUpload: parts[${i}].url must be a non-empty string`);
    }
    if (
      headers !== undefined &&
      (typeof headers !== 'object' ||
        headers === null ||
        Array.isArray(headers))
    ) {
      throw new Error(
        `startUpload: parts[${i}].headers must be a plain object when present`,
      );
    }
    if (
      !range ||
      !Number.isInteger(range.start) ||
      !Number.isInteger(range.end) ||
      range.start < 0 ||
      range.start >= range.end
    ) {
      throw new Error(
        `startUpload: parts[${i}].range must satisfy 0 <= start < end, got ${JSON.stringify(
          range,
        )}`,
      );
    }
    if (range.start !== expectedStart) {
      throw new Error(
        i === 0
          ? `startUpload: parts[0].range.start must be 0, got ${range.start}`
          : `startUpload: parts must be sorted ascending and tile the file with no gaps or overlaps — parts[${i}].range.start is ${range.start} but parts[${i - 1}].range.end is ${expectedStart}`,
      );
    }
    expectedStart = range.end;
  });
  if (!Number.isFinite(options.expiresAt) || options.expiresAt <= 0) {
    throw new Error(
      `startUpload: expiresAt must be a finite epoch-ms timestamp, got ${options.expiresAt}`,
    );
  }
};

/**
 * Starts an upload to an HTTP endpoint. The behavior depends on options.type.
 * 'raw' sends the whole file as one request body. 'chunked' sends the parts
 * that the consumer authored (see ChunkedUploadOptions). Returns a promise
 * that resolves to the upload's string id. Malformed chunked input throws
 * synchronously. Other bad options (for example, a missing or invalid url or
 * path) reject. Transport failures and HTTP error responses arrive later as
 * 'error' events.
 *
 * A new call with the same id and identical parts is never an error, at any
 * time. The library reconciles: it skips the parts that the server accepted,
 * and the other parts continue with the new call's headers. A call with a
 * different parts array is a recreate. The library accepts a recreate when
 * the upload is stopped, and replaces the bytes. It rejects a recreate while
 * the upload runs.
 */
const startUpload = (options: StartUploadOptions): Promise<UploadId> => {
  if (options.type === 'chunked') {
    validateChunkedOptions(options);
    const { path, android, ...rest } = options;
    return NativeRNFileUploader.startChunkedUpload({
      ...rest,
      ...android,
      path: normalizePath(path),
    });
  }

  const { path, android, ...rest } = options;
  return NativeRNFileUploader.startUpload({
    ...rest,
    ...android,
    path: normalizePath(path),
  });
};

/**
 * Releases an upload's native manifest and bytes. Each terminal outcome other
 * than an acknowledged 'completed' (expired, error, cancelled) keeps both.
 * This lets the consumer resume or recreate the upload. Call this function
 * when you want neither. On a raw upload id, it cancels the in-flight request
 * and deliberately emits no terminal event.
 */
const removeUpload = (uploadId: string): Promise<void> =>
  NativeRNFileUploader.removeUpload(uploadId);

/**
 * Cancels active upload by string ID of the upload.
 *
 * Upload ID is returned in a promise after a call to startUpload method,
 * use it to cancel started upload.
 * Event "cancelled" will be fired when upload is cancelled.
 * On iOS, resolves true if a matching in-flight upload was found and cancelled,
 * false if there was nothing to cancel. Android always resolves true — the
 * WorkManager cancel is fire-and-forget and does not report whether it matched.
 */
const cancelUpload = (cancelUploadId: string): Promise<boolean> =>
  NativeRNFileUploader.cancelUpload(cancelUploadId);

/**
 * Listens for one event type across all uploads. Use `data.id` to identify
 * the upload.
 * Events (id is always the upload ID):
 * progress - { id, progress: 0-100 }
 * error - { id, error, errorKind?, responseCode?, responseBody?, responseHeaders? }
 * cancelled - { id, cancelReason?: 'user' | 'system' }
 * completed - { id, responseCode, responseBody, responseHeaders?, eventId? }
 */
const addListener = ((
  eventType: 'progress' | 'error' | 'completed' | 'cancelled',
  // The payload shape varies per event; the public AddListener overloads carry
  // the precise contract, so the internal forwarder stays untyped.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  listener: (data: any) => void,
): EventSubscription => {
  switch (eventType) {
    case 'progress':
      return NativeRNFileUploader.onProgress(listener);
    case 'error':
      return NativeRNFileUploader.onError(listener);
    case 'cancelled':
      return NativeRNFileUploader.onCancelled(listener);
    case 'completed':
      return NativeRNFileUploader.onCompleted(listener);
    default:
      throw new Error(`Unknown upload event: ${eventType}`);
  }
}) as AddListener;

/**
 * Terminal events (completed/error/cancelled) are journaled natively before being
 * emitted, so they survive the app being killed or JS reloading. Read them on
 * startup, process each, then acknowledge — unacknowledged events are re-delivered
 * here on every call until you ack them.
 *
 * Note: `completed` fires only for 2xx (or a request's `accept` rules); other HTTP
 * responses arrive as `error` with `errorKind: 'http'` and the response attached.
 */
const getUnacknowledgedEvents = async (): Promise<JournaledEvent[]> =>
  (await NativeRNFileUploader.getUnacknowledgedEvents()) as JournaledEvent[];

/** Removes journaled events by eventId once you've processed them. */
const ackEvents = (eventIds: string[]): Promise<boolean> =>
  NativeRNFileUploader.ackEvents(eventIds);

/**
 * Enumerates uploads the OS still knows about, for reconciling in-flight work on
 * boot. Terminal outcomes come from getUnacknowledgedEvents (durable), not here:
 * on Android finished work is pruned after ~a day, and on iOS only live tasks are
 * listed.
 */
const getAllUploads = async (): Promise<UploadSnapshot[]> =>
  (await NativeRNFileUploader.getAllUploads()) as UploadSnapshot[];

const android = {
  /**
   * When the upload progress notification is pressed, it will open the app and fire this event.
   * Android only — never fires on iOS.
   * @param listener
   */
  addNotificationListener: (listener: () => void): EventSubscription =>
    NativeRNFileUploader.onNotification(() => listener()),
};

export default {
  configure,
  startUpload,
  cancelUpload,
  removeUpload,
  addListener,
  getUnacknowledgedEvents,
  ackEvents,
  getAllUploads,
  chunkPlan,
  android,
};
