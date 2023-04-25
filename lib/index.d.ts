import { AddListener, UploadId, UploadOptions } from './types';
export declare const startUpload: ({ path, android, ios, ...options }: UploadOptions) => Promise<UploadId>;
/**
 * Cancels active upload by string ID of the upload.
 *
 * Upload ID is returned in a promise after a call to startUpload method,
 * use it to cancel started upload.
 * Event "cancelled" will be fired when upload is cancelled.
 * Returns a promise with boolean true if operation was successfully completed.
 * Will reject if there was an internal error or ID format is invalid.
 */
export declare const cancelUpload: (cancelUploadId: string) => Promise<boolean>;
/**
 * Listens for the given event on the given upload ID (resolved from startUpload).
 * If you don't supply a value for uploadId, the event will fire for all uploads.
 * Events (id is always the upload ID):
 * progress - { id: string, progress: int (0-100) }
 * error - { id: string, error: string }
 * cancelled - { id: string, error: string }
 * completed - { id: string }
 */
export declare const addListener: AddListener;
/**
 * Splits a parent file into {numChunks} chunks and place them into the specified directory.
 * Each chunk file will be named by its corresponding index (0, 1, 2,...).
 */
export declare const chunkFile: (parentFilePath: string, chunks: {
    /** Byte position of the chunk */
    position: number;
    /** Byte length of the chunk */
    size: number;
    /** Where the chunk will be exported to */
    path: string;
}[]) => Promise<void>;
declare const _default: {
    startUpload: ({ path, android, ios, ...options }: UploadOptions) => Promise<string>;
    cancelUpload: (cancelUploadId: string) => Promise<boolean>;
    addListener: AddListener;
    chunkFile: (parentFilePath: string, chunks: {
        /** Byte position of the chunk */
        position: number;
        /** Byte length of the chunk */
        size: number;
        /** Where the chunk will be exported to */
        path: string;
    }[]) => Promise<void>;
    ios: {
        /**
         * Directly check the state of a single upload task without using event listeners.
         * Note that this method has no way of distinguishing between a task being completed, errored, or non-existent.
         * They're all `undefined`. You will need to either rely on the listeners or
         * check with the API service you're using to upload.
         */
        getUploadStatus: (jobId: string) => Promise<{
            state: "running" | "suspended" | "canceling";
            bytesSent?: number;
            totalBytes?: number;
        }>;
    };
};
export default _default;
