## 8.0.0

Reliability release. Terminal outcomes are now durable and accurately typed, the
iOS module was rewritten in Swift, and the module is a New Architecture
TurboModule.

Breaking:
- **New Architecture only.** The module is now a codegen TurboModule on both
  platforms; the legacy bridge (`RCTEventEmitter` / `RCT_EXTERN_MODULE` on iOS,
  `ReactPackage` + `RCTDeviceEventEmitter` on Android) is gone, along with any
  reliance on RN's legacy-interop layer. Requires React Native >= 0.84 with the
  New Architecture enabled, and React >= 19.
- The iOS background-session handler moved from `RNFileUploader` to
  `RNBackgroundUpload`: `[RNBackgroundUpload setBackgroundSessionCompletionHandler:
  forIdentifier:]`. `RNFileUploader` is now the TurboModule and is intentionally
  unreachable from plain Objective-C (its generated header is Objective-C++ only).
  Update the AppDelegate snippet — see README.
- Events are delivered through the codegen event emitters rather than
  `DeviceEventEmitter`, so they are no longer visible under the raw
  `RNFileUploader-*` device-event names. The `Upload.addListener(...)` API is
  unchanged.
- `cancelUpload` on iOS now resolves `false` when no matching in-flight upload was
  found (it previously always resolved `true`).
- `completed` fires only for 2xx responses (plus a request's `acceptStatus`, e.g.
  `acceptStatus: [409]`). Every other HTTP response now emits an `error` with
  `errorKind: 'http'` and the full response attached (previously reported as
  `completed`).
- `error` events are typed: `errorKind: 'http' | 'network' | 'file' | 'unknown'`.
- Native module renamed to `RNFileUploader` on both platforms (was
  `VydiaRNFileUploader` on iOS); Android package is now `ai.openspace.backgroundupload`.
- iOS AppDelegate must forward `handleEventsForBackgroundURLSession` (see README).
- Removed the committed `lib/` build output; types are served from `src`
  (deep imports of `lib/*` break — import from the package root).
- Minimum iOS deployment target is 15.1; minimum Android SDK is 29. Minimum React
  Native is 0.84 (New Architecture), minimum React is 19.
- Removed non-functional iOS code paths: multipart, `assets-library://`, and the
  `appGroup` option (a no-op even before this — it mutated the session config after
  creation, which URLSession ignores; also removed from the TypeScript options).
- Removed the unexposed Android `stopAllUploads`.

Added:
- Durable native event journal: `getUnacknowledgedEvents()` / `ackEvents(ids)` —
  terminal events survive app death and JS reloads (at-least-once delivery).
- `getAllUploads()` on both platforms.
- `cancelled` events carry `cancelReason: 'user' | 'system'`.
- `responseHeaders` on completed events on iOS (was Android-only).
- iOS progress events throttled to 500ms; UUID default upload ids.
- Android `android` options are now optional — sensible notification defaults and
  a library-created notification channel.

Earlier releases: see the [releases](https://github.com/openspacelabs/react-native-background-upload/releases) page.
