/**
 * Handles HTTP background file uploads from an iOS or Android device.
 */
import { NativeModules, DeviceEventEmitter, Platform } from 'react-native';
import { AddListener, UploadId, UploadOptions } from 'types';

const NativeModule =
  NativeModules.VydiaRNFileUploader || NativeModules.RNFileUploader;
const eventPrefix = 'RNFileUploader-';
const fileURIPrefix = 'file://';

// for IOS, register event listeners or else they don't fire on DeviceEventEmitter
if (NativeModules.VydiaRNFileUploader) {
  NativeModule.addListener(eventPrefix + 'progress');
  NativeModule.addListener(eventPrefix + 'error');
  NativeModule.addListener(eventPrefix + 'cancelled');
  NativeModule.addListener(eventPrefix + 'completed');
}

/**
 * Starts uploading a file to an HTTP endpoint.
 * Options object:
  ```
  {
    url: string.  url to post to.
    path: string.  path to the file on the device
    headers: hash of name/value header pairs
    method: HTTP method to use.  Default is "POST"
    notification: hash for customizing tray notifiaction
      enabled: boolean to enable/disabled notifications, true by default.
  }
  ```
 * Returns a promise with the string ID of the upload.  Will reject if there is a connection problem, the file doesn't exist, or there is some other problem.
 * It is recommended to add listeners in the .then of this promise.
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
 * progress - { id: string, progress: int (0-100) }
 * error - { id: string, error: string }
 * cancelled - { id: string, error: string }
 * completed - { id: string }
 */
const addListener: AddListener = (eventType, uploadId, listener) =>
  DeviceEventEmitter.addListener(eventPrefix + eventType, (data) => {
    if (!uploadId || !data || !data.id || data.id === uploadId) {
      listener(data);
    }
  });

/**
 * Splits a parent file into {numChunks} chunks and place them into the specified directory.
 * Each chunk file will be named by its corresponding index (0, 1, 2,...).
 */
const chunkFile = async (
  parentFilePath: string,
  chunks: {
    /** Byte position of the chunk */
    position: number;
    /** Byte length of the chunk */
    size: number;
    /** Where the chunk will be exported to */
    path: string;
  }[],
) => {
  await NativeModule.chunkFile(parentFilePath, chunks);
};

const ios = {
  /**
   * Directly check the state of a single upload task without using event listeners.
   * Note that this method has no way of distinguishing between a task being completed, errored, or non-existent.
   * They're all `undefined`. You will need to either rely on the listeners or
   * check with the API service you're using to upload.
   */
  getUploadStatus: async (
    jobId: string,
  ): Promise<{
    state: 'running' | 'suspended' | 'canceling' | undefined;
    bytesSent?: number;
    totalBytes?: number;
  }> => (await NativeModule.getUploadStatus(jobId)) || { state: undefined },
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
  chunkFile,
  ios,
  android,
};
