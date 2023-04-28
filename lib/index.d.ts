import { AddListener, UploadOptions } from './types';
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
    android: {
        /**
         * When the upload progress notification is pressed, it will open the app and fire this event
         * @param listener
         */
        addNotificationListener: (listener: () => void) => import("react-native").EmitterSubscription;
    };
};
export default _default;
