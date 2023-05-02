import { EventSubscription } from 'react-native';
export interface EventData {
    id: string;
}
export interface ProgressData extends EventData {
    progress: number;
}
export interface ErrorData extends EventData {
    error: string;
}
export interface CompletedData extends EventData {
    responseCode: number;
    responseBody: string;
}
export declare type UploadId = string;
export declare type UploadOptions = {
    url: string;
    path: string;
    method: 'POST' | 'GET' | 'PUT' | 'PATCH' | 'DELETE';
    customUploadId?: string;
    headers?: {
        [index: string]: string;
    };
    wifiOnly?: boolean;
    android: AndroidOnlyUploadOptions;
    ios?: IOSOnlyUploadOptions;
} & RawUploadOptions;
declare type AndroidOnlyUploadOptions = {
    notificationId: string;
    notificationTitle: string;
    notificationTitleNoWifi: string;
    notificationTitleNoInternet: string;
    notificationChannel: string;
    maxRetries?: number;
};
declare type IOSOnlyUploadOptions = {
    /**
     * AppGroup defined in XCode for extensions. Necessary when trying to upload things via this library
     * in the context of ShareExtension.
     */
    appGroup?: string;
};
declare type RawUploadOptions = {
    type: 'raw';
};
export interface AddListener {
    (event: 'progress', uploadId: UploadId | null, callback: (data: ProgressData) => void): EventSubscription;
    (event: 'error', uploadId: UploadId | null, callback: (data: ErrorData) => void): EventSubscription;
    (event: 'completed', uploadId: UploadId | null, callback: (data: CompletedData) => void): EventSubscription;
    (event: 'cancelled', uploadId: UploadId | null, callback: (data: EventData) => void): EventSubscription;
}
export {};
