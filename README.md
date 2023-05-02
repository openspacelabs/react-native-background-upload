# react-native-background-upload

OpenSpace home-grown background uploader for React Native. on iOS it uses URLSession, on Android it uses CoroutineWorker and Ktor.

Documentation has been modified to reflect the changes made to this library.

# Installation

## 1. Install package

`yarn add react-native-background-upload`

Note: if you are installing on React Native < 0.47, use `react-native-background-upload@3.0.0` instead of `react-native-background-upload`

## 2. Link Native Code

### Autolinking (React Native >= 0.60)

##### iOS

`cd ./ios && pod install && cd ../`

## 3. Expo

To use this library with [Expo](https://expo.io) one must first detach (eject) the project and follow [step 2](#2-link-native-code) instructions. Additionally on iOS there is a must to add a Header Search Path to other dependencies which are managed using Pods. To do so one has to add `$(SRCROOT)/../../../ios/Pods/Headers/Public` to Header Search Path in `VydiaRNFileUploader` module using XCode.

# Usage

```js
import Upload from 'react-native-background-upload';

const options = {
  url: 'https://myservice.com/path/to/post',
  path: 'file://path/to/file/on/device',
  method: 'POST',
  type: 'raw',
  headers: {
    'content-type': 'application/octet-stream', // Customize content-type
    'my-custom-header': 's3headervalueorwhateveryouneed',
  },
  android: {
    notificationChannel: 'my-channel-id',
    notificationId: 'my-progress-notification',
    notificationTitle: 'Uploading...',
    notificationTitleNoWifi: 'Waiting for Wifi...',
    notificationTitleNoInternet: 'Waiting for Internet...',
  },
  useUtf8Charset: true,
};

Upload.addListener('progress', uploadId, (data) => {
  console.log(`Progress: ${data.progress}%`);
});
Upload.addListener('error', uploadId, (data) => {
  console.log(`Error: ${data.error}%`);
});
Upload.addListener('cancelled', uploadId, (data) => {
  console.log(`Cancelled!`);
});
Upload.addListener('completed', uploadId, (data) => {
  // data includes responseCode: number and responseBody: Object
  console.log('Completed!');
});
Upload.android.addNotificationListener(() => {
  console.log('Progress notification pressed!');
});

Upload.startUpload(options)
  .then((uploadId) => console.log('Upload started', uploadId))
  .catch((err) => console.log('Upload error!', err));
```

## Multipart Uploads

**🚧 COMING SOON**

Just set the `type` option to `multipart` and set the `field` option. Example:

```
const options = {
  url: 'https://myservice.com/path/to/post',
  path: 'file://path/to/file%20on%20device.png',
  method: 'POST',
  field: 'uploaded_media',
  type: 'multipart'
}
```

Note the `field` property is required for multipart uploads.

# API

## Top Level Functions

All top-level methods are available as named exports or methods on the default export.

### startUpload(options)

The primary method you will use, this starts the upload process.

Returns a promise with the string ID of the upload. Will reject if there is a connection problem, the file doesn't exist, or there is some other problem.

`options` is an object with following values:

_Note: You must provide valid URIs. react-native-background-upload does not escape the values you provide._

| Name             | Type                            | Required                        | Default                                                                                                                                                                                                      | Description                                                                                                                                                                                                                     | Example                                                               |
| ---------------- | ------------------------------- | ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| `url`            | string                          | Required                        |                                                                                                                                                                                                              | URL to upload to                                                                                                                                                                                                                | `https://myservice.com/path/to/post`                                  |
| `path`           | string                          | Required                        |                                                                                                                                                                                                              | File path on device                                                                                                                                                                                                             | `file://something/coming/from%20the%20device.png`                     |
| `type`           | 'raw' or 'multipart'            | Optional                        | `raw`                                                                                                                                                                                                        | Primary upload type.                                                                                                                                                                                                            |                                                                       |
| `method`         | string                          | Optional                        | `POST`                                                                                                                                                                                                       | HTTP method                                                                                                                                                                                                                     |                                                                       |
| `customUploadId` | string                          | Optional                        |                                                                                                                                                                                                              | `startUpload` returns a Promise that includes the upload ID, which can be used for future status checks. By default, the upload ID is automatically generated. This parameter allows a custom ID to use instead of the default. |                                                                       |
| `headers`        | object                          | Optional                        |                                                                                                                                                                                                              | HTTP headers                                                                                                                                                                                                                    | `{ 'Accept': 'application/json' }`                                    |
| `field`          | string                          | Required if `type: 'multipart'` |                                                                                                                                                                                                              | The form field name for the file. Only used when `type: 'multipart`                                                                                                                                                             | `uploaded-file`                                                       |
| `parameters`     | object                          | Optional                        |                                                                                                                                                                                                              | Additional form fields to include in the HTTP request. Only used when `type: 'multipart`                                                                                                                                        |                                                                       |
| `notification`   | Notification object (see below) | Optional                        |                                                                                                                                                                                                              | Android only.                                                                                                                                                                                                                   | `{ enabled: true, onProgressTitle: "Uploading...", autoClear: true }` |
| `useUtf8Charset` | boolean                         | Optional                        |                                                                                                                                                                                                              | Android only. Set to true to use `utf-8` as charset.                                                                                                                                                                            |                                                                       |
| `appGroup`       | string                          | Optional                        | iOS only. App group ID needed for share extensions to be able to properly call the library. See: https://developer.apple.com/documentation/foundation/nsfilemanager/1412643-containerurlforsecurityapplicati |

### Notification Object (Android Only)

Android forces us to display a progress notification to show overall upload progress.

| Name                          | Type   | Required | Description                                                      | Example                     |
| ----------------------------- | ------ | -------- | ---------------------------------------------------------------- | --------------------------- |
| `notificationChannel`         | string | Optional | Sets android notification channel                                | `background-upload-channel` |
| `notificationId`              | string | Optional | A custom ID for the notification                                 | `upload-progress`           |
| `notificationTitle`           | string | Optional | Sets the default title for the notification                      | `Uploading...`              |
| `notificationTitleNoWifi`     | string | Optional | Sets notification title for uploads awaiting wifi                | `Waiting for Wifi...`       |
| `notificationTitleNoInternet` | string | Optional | Sets notification title for uploads awaiting internet connection | `Waiting for Internet...`   |

### cancelUpload(uploadId)

Cancels an upload.

`uploadId` is the result of the Promise returned from `startUpload`

Returns a Promise that resolves to an boolean indicating whether the upload was cancelled.

### addListener(eventType, uploadId, listener)

Adds an event listener, possibly confined to a single upload.

`eventType` Event to listen for. Values: 'progress' | 'error' | 'completed' | 'cancelled'

`uploadId` The upload ID from `startUpload` to filter events for. If null, this will include all uploads.

`listener` Function to call when the event occurs.

Returns an [EventSubscription](https://github.com/facebook/react-native/blob/master/Libraries/vendor/emitter/EmitterSubscription.js). To remove the listener, call `remove()` on the `EventSubscription`.

### android.addNotificationListener(listener)

When the upload progress notification is pressed, it will open the app and fire this event.
There's no event data for this.

## Events

### progress

Event Data

| Name       | Type   | Required | Description           |
| ---------- | ------ | -------- | --------------------- |
| `id`       | string | Required | The ID of the upload. |
| `progress` | 0-100  | Required | Percentage completed. |

### error

Event Data

| Name    | Type   | Required | Description           |
| ------- | ------ | -------- | --------------------- |
| `id`    | string | Required | The ID of the upload. |
| `error` | string | Required | Error message.        |

### completed

Event Data

| Name              | Type   | Required | Description                     |
| ----------------- | ------ | -------- | ------------------------------- |
| `id`              | string | Required | The ID of the upload.           |
| `responseCode`    | string | Required | HTTP status code received       |
| `responseBody`    | string | Required | HTTP response body              |
| `responseHeaders` | string | Required | HTTP response headers (Android) |

### cancelled

Event Data

| Name | Type   | Required | Description           |
| ---- | ------ | -------- | --------------------- |
| `id` | string | Required | The ID of the upload. |

# FAQs

Does it support iOS camera roll assets?

> Yes, as of version 4.3.0.

Does it support multiple file uploads?

> Yes and No. It supports multiple concurrent uploads, but only a single upload per request. That should be fine for 90%+ of cases.

Why should I use this file uploader instead of others that I've Googled like [react-native-uploader](https://github.com/aroth/react-native-uploader)?

> This package has two killer features not found anywhere else (as of 12/16/2016). First, it works on both iOS and Android. Others are iOS only. Second, it supports background uploading. This means that users can background your app and the upload will continue. This does not happen with other uploaders.

# Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md).

# Common Issues

## Gratitude

Many thanks to the [Original Library](https://github.com/Vydia/react-native-background-upload) for the boilerplate and inspiration
