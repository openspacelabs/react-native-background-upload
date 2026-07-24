/**
 * Handles HTTP background file uploads from an iOS or Android device.
 */
import { Platform } from 'react-native';
import type { EventSubscription } from 'react-native';
import NativeRNFileUploader from './NativeRNFileUploader';
import {
  AddListener,
  JournaledEvent,
  UploadId,
  UploadOptions,
  UploadSnapshot,
} from './types';

export * from './types';

const fileURIPrefix = 'file://';

/**
 * Starts uploading a file to an HTTP endpoint. See UploadOptions for the full
 * option set (url, path, method, headers, wifiOnly, acceptStatus, android).
 * Returns a promise resolving to the upload's string id. Rejects only on a bad
 * option (e.g. missing/invalid url or path); transport failures and HTTP error
 * responses surface later as 'error' events, not a rejection here.
 */
const startUpload = ({
  path,
  android,
  ...options
}: UploadOptions): Promise<UploadId> => {
  if (!path.startsWith(fileURIPrefix)) {
    path = fileURIPrefix + path;
  }

  if (Platform.OS === 'android') {
    path = path.replace(fileURIPrefix, '');
  }

  return NativeRNFileUploader.startUpload({ ...options, ...android, path });
};

/**
 * Cancels active upload by string ID of the upload.
 *
 * Upload ID is returned in a promise after a call to startUpload method,
 * use it to cancel started upload.
 * Event "cancelled" will be fired when upload is cancelled.
 * Resolves true if a matching in-flight upload was found and cancelled, false if
 * there was nothing to cancel.
 */
const cancelUpload = (cancelUploadId: string): Promise<boolean> =>
  NativeRNFileUploader.cancelUpload(cancelUploadId);

/**
 * Listens for the given event on the given upload ID (resolved from startUpload).
 * If you don't supply a value for uploadId, the event will fire for all uploads.
 * Events (id is always the upload ID):
 * progress - { id, progress: 0-100 }
 * error - { id, error, errorKind?, responseCode?, responseBody?, responseHeaders? }
 * cancelled - { id, cancelReason?: 'user' | 'system' }
 * completed - { id, responseCode, responseBody, responseHeaders?, eventId? }
 */
const addListener = ((
  eventType: 'progress' | 'error' | 'completed' | 'cancelled',
  uploadId: UploadId | null,
  // The payload shape varies per event; the public AddListener overloads carry
  // the precise contract, so the internal forwarder stays untyped.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  listener: (data: any) => void,
): EventSubscription => {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const forMatchingUpload = (data: any) => {
    if (!uploadId || !data || !data.id || data.id === uploadId) {
      listener(data);
    }
  };

  switch (eventType) {
    case 'progress':
      return NativeRNFileUploader.onProgress(forMatchingUpload);
    case 'error':
      return NativeRNFileUploader.onError(forMatchingUpload);
    case 'cancelled':
      return NativeRNFileUploader.onCancelled(forMatchingUpload);
    case 'completed':
      return NativeRNFileUploader.onCompleted(forMatchingUpload);
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
 * Note: `completed` fires only for 2xx (or a request's `acceptStatus`); other HTTP
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

const ios = {
  /**
   * Directly check the state of a single upload task without using event listeners.
   * Note that this method has no way of distinguishing between a task being completed, errored, or non-existent.
   * They're all `undefined`. You will need to either rely on the listeners or
   * check with the API service you're using to upload.
   *
   * Android always resolves `undefined`.
   */
  getUploadStatus: async (
    jobId: string,
  ): Promise<
    | {
        state: 'running' | 'suspended' | 'canceling';
        bytesSent: number;
        totalBytes: number;
      }
    | undefined
  > =>
    ((await NativeRNFileUploader.getUploadStatus(jobId)) as
      | {
          state: 'running' | 'suspended' | 'canceling';
          bytesSent: number;
          totalBytes: number;
        }
      | null) ?? undefined,
};

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
  startUpload,
  cancelUpload,
  addListener,
  getUnacknowledgedEvents,
  ackEvents,
  getAllUploads,
  ios,
  android,
};
