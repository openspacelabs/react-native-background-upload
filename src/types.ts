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
  android?: AndroidOnlyUploadOptions;
  ios?: IOSOnlyUploadOptions;
} & (RawUploadOptions | MultipartUploadOptions);

type AndroidOnlyUploadOptions = {
  notificationId: string;
  notificationTitle: string;
  notificationChanel: string;
  maxRetries?: number;
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
