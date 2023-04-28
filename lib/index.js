var __awaiter = (this && this.__awaiter) || function (thisArg, _arguments, P, generator) {
    function adopt(value) { return value instanceof P ? value : new P(function (resolve) { resolve(value); }); }
    return new (P || (P = Promise))(function (resolve, reject) {
        function fulfilled(value) { try { step(generator.next(value)); } catch (e) { reject(e); } }
        function rejected(value) { try { step(generator["throw"](value)); } catch (e) { reject(e); } }
        function step(result) { result.done ? resolve(result.value) : adopt(result.value).then(fulfilled, rejected); }
        step((generator = generator.apply(thisArg, _arguments || [])).next());
    });
};
var __rest = (this && this.__rest) || function (s, e) {
    var t = {};
    for (var p in s) if (Object.prototype.hasOwnProperty.call(s, p) && e.indexOf(p) < 0)
        t[p] = s[p];
    if (s != null && typeof Object.getOwnPropertySymbols === "function")
        for (var i = 0, p = Object.getOwnPropertySymbols(s); i < p.length; i++) {
            if (e.indexOf(p[i]) < 0 && Object.prototype.propertyIsEnumerable.call(s, p[i]))
                t[p[i]] = s[p[i]];
        }
    return t;
};
/**
 * Handles HTTP background file uploads from an iOS or Android device.
 */
import { NativeModules, DeviceEventEmitter, Platform } from 'react-native';
const NativeModule = NativeModules.VydiaRNFileUploader || NativeModules.RNFileUploader;
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
const startUpload = (_a) => {
    var { path, android, ios } = _a, options = __rest(_a, ["path", "android", "ios"]);
    if (!path.startsWith(fileURIPrefix)) {
        path = fileURIPrefix + path;
    }
    if (Platform.OS === 'android') {
        path = path.replace(fileURIPrefix, '');
    }
    return NativeModule.startUpload(Object.assign(Object.assign(Object.assign(Object.assign({}, options), android), ios), { path }));
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
const cancelUpload = (cancelUploadId) => NativeModule.cancelUpload(cancelUploadId);
/**
 * Listens for the given event on the given upload ID (resolved from startUpload).
 * If you don't supply a value for uploadId, the event will fire for all uploads.
 * Events (id is always the upload ID):
 * progress - { id: string, progress: int (0-100) }
 * error - { id: string, error: string }
 * cancelled - { id: string, error: string }
 * completed - { id: string }
 */
const addListener = (eventType, uploadId, listener) => DeviceEventEmitter.addListener(eventPrefix + eventType, (data) => {
    if (!uploadId || !data || !data.id || data.id === uploadId) {
        listener(data);
    }
});
/**
 * Splits a parent file into {numChunks} chunks and place them into the specified directory.
 * Each chunk file will be named by its corresponding index (0, 1, 2,...).
 */
const chunkFile = (parentFilePath, chunks) => __awaiter(void 0, void 0, void 0, function* () {
    yield NativeModule.chunkFile(parentFilePath, chunks);
});
const ios = {
    /**
     * Directly check the state of a single upload task without using event listeners.
     * Note that this method has no way of distinguishing between a task being completed, errored, or non-existent.
     * They're all `undefined`. You will need to either rely on the listeners or
     * check with the API service you're using to upload.
     */
    getUploadStatus: (jobId) => __awaiter(void 0, void 0, void 0, function* () { return (yield NativeModule.getUploadStatus(jobId)) || { state: undefined }; }),
};
const android = {
    /**
     * When the upload progress notification is pressed, it will open the app and fire this event
     * @param listener
     */
    addNotificationListener: (listener) => DeviceEventEmitter.addListener(eventPrefix + 'notification', listener),
};
export default {
    startUpload,
    cancelUpload,
    addListener,
    chunkFile,
    ios,
    android,
};
//# sourceMappingURL=index.js.map