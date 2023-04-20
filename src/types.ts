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

export type UploadId = string;

export interface FileInfo {
  name: string;
  exists: boolean;
  size?: number;
  extension?: string;
  mimeType?: string;
}

export interface NotificationOptions {
  /**
   * Enable or diasable notifications. Works only on Android version < 8.0 Oreo. On Android versions >= 8.0 Oreo is required by Google's policy to display a notification when a background service run  { enabled: true }
   */
  enabled: boolean;
  /**
   * Autoclear notification on complete  { autoclear: true }
   */
  autoClear: boolean;
  /**
   * Sets android notificaion channel  { notificationChannel: "My-Upload-Service" }
   */
  notificationChannel: string;
  /**
   * Sets whether or not to enable the notification sound when the upload gets completed with success or error   { enableRingTone: true }
   */
  enableRingTone: boolean;
  /**
   * Sets notification progress title  { onProgressTitle: "Uploading" }
   */
  onProgressTitle: string;
  /**
   * Sets notification progress message  { onProgressMessage: "Uploading new video" }
   */
  onProgressMessage: string;
  /**
   * Sets notification complete title  { onCompleteTitle: "Upload finished" }
   */
  onCompleteTitle: string;
  /**
   * Sets notification complete message  { onCompleteMessage: "Your video has been uploaded" }
   */
  onCompleteMessage: string;
  /**
   * Sets notification error title   { onErrorTitle: "Upload error" }
   */
  onErrorTitle: string;
  /**
   * Sets notification error message   { onErrorMessage: "An error occured while uploading a video" }
   */
  onErrorMessage: string;
  /**
   * Sets notification cancelled title   { onCancelledTitle: "Upload cancelled" }
   */
  onCancelledTitle: string;
  /**
   * Sets notification cancelled message   { onCancelledMessage: "Video upload was cancelled" }
   */
  onCancelledMessage: string;
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
} & (AndroidOnlyUploadOptions | IOSOnlyUploadOptions) &
  (RawUploadOptions | MultipartUploadOptions);

type AndroidOnlyUploadOptions = {
  maxRetries?: number;
  notification?: Partial<NotificationOptions>;
};

type IOSOnlyUploadOptions = {
  /**
   * AppGroup defined in XCode for extensions. Necessary when trying to upload things via this library
   * in the context of ShareExtension.
   */
  appGroup?: string;
};

type RawUploadOptions = {
  type: 'raw';
};

type MultipartUploadOptions = {
  type: 'multipart';
  field: string;
  parameters?: {
    [index: string]: string;
  };
};

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
    callback: (data: EventData) => void,
  ): EventSubscription;
}
