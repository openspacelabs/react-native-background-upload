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
  android,
};
