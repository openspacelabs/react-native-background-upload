/**
 * Handles HTTP background file uploads from an iOS or Android device.
 */
import { NativeModules, DeviceEventEmitter, Platform } from 'react-native';
import {
  AddListener,
  JournaledEvent,
  UploadId,
  UploadOptions,
  UploadSnapshot,
} from './types';

export * from './types';

const NativeModule = NativeModules.RNFileUploader;
const eventPrefix = 'RNFileUploader-';
const fileURIPrefix = 'file://';

// for iOS, register event listeners or else they don't fire on DeviceEventEmitter
if (Platform.OS === 'ios') {
  NativeModule.addListener(eventPrefix + 'progress');
  NativeModule.addListener(eventPrefix + 'error');
  NativeModule.addListener(eventPrefix + 'cancelled');
  NativeModule.addListener(eventPrefix + 'completed');
}

/**
 * Starts uploading a file to an HTTP endpoint. See UploadOptions for the full
 * option set (url, path, method, headers, wifiOnly, acceptStatus, android, ios).
 * Returns a promise resolving to the upload's string id. Rejects only on a bad
 * option (e.g. missing/invalid url or path); transport failures and HTTP error
 * responses surface later as 'error' events, not a rejection here.
 */
const startUpload = ({
  path,
  android,
  ios,
  ...options
}: UploadOptions): Promise<UploadId> => {
  if (!path.startsWith(fileURIPrefix)) {
    path = fileURIPrefix + path;
  }

  if (Platform.OS === 'android') {
    path = path.replace(fileURIPrefix, '');
  }

  return NativeModule.startUpload({ ...options, ...android, ...ios, path });
};

/**
 * Cancels active upload by string ID of the upload.
 *
 * Upload ID is returned in a promise after a call to startUpload method,
 * use it to cancel started upload.
 * Event "cancelled" will be fired when upload is cancelled.
 * Returns a promise with boolean true if operation was successfully completed.
 * Will reject if there was an internal error or ID format is invalid.
 */
const cancelUpload = (cancelUploadId: string): Promise<boolean> =>
  NativeModule.cancelUpload(cancelUploadId);

/**
 * Listens for the given event on the given upload ID (resolved from startUpload).
 * If you don't supply a value for uploadId, the event will fire for all uploads.
 * Events (id is always the upload ID):
 * progress - { id, progress: 0-100 }
 * error - { id, error, errorKind?, responseCode?, responseBody?, responseHeaders? }
 * cancelled - { id, cancelReason?: 'user' | 'system' }
 * completed - { id, responseCode, responseBody, responseHeaders?, eventId? }
 */
const addListener: AddListener = (eventType, uploadId, listener) =>
  DeviceEventEmitter.addListener(eventPrefix + eventType, (data) => {
    if (!uploadId || !data || !data.id || data.id === uploadId) {
      listener(data);
    }
  });

/**
 * Terminal events (completed/error/cancelled) are journaled natively before being
 * emitted, so they survive the app being killed or JS reloading. Read them on
 * startup, process each, then acknowledge — unacknowledged events are re-delivered
 * here on every call until you ack them.
 *
 * Note: `completed` fires only for 2xx (or a request's `acceptStatus`); other HTTP
 * responses arrive as `error` with `errorKind: 'http'` and the response attached.
 */
const getUnacknowledgedEvents = (): Promise<JournaledEvent[]> =>
  NativeModule.getUnacknowledgedEvents();

/** Removes journaled events by eventId once you've processed them. */
const ackEvents = (eventIds: string[]): Promise<boolean> =>
  NativeModule.ackEvents(eventIds);

/**
 * Enumerates uploads the OS still knows about, for reconciling in-flight work on
 * boot. Terminal outcomes come from getUnacknowledgedEvents (durable), not here:
 * on Android finished work is pruned after ~a day, and on iOS only live tasks are
 * listed.
 */
const getAllUploads = (): Promise<UploadSnapshot[]> =>
  NativeModule.getAllUploads();

const ios = {
  /**
   * Directly check the state of a single upload task without using event listeners.
   * Note that this method has no way of distinguishing between a task being completed, errored, or non-existent.
   * They're all `undefined`. You will need to either rely on the listeners or
   * check with the API service you're using to upload.
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
  > => await NativeModule.getUploadStatus?.(jobId),
};

const android = {
  /**
   * When the upload progress notification is pressed, it will open the app and fire this event
   * @param listener
   */
  addNotificationListener: (listener: () => void) =>
    DeviceEventEmitter.addListener(eventPrefix + 'notification', listener),
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
