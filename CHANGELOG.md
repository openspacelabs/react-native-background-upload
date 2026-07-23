## 8.0.0

Reliability release. Terminal outcomes are now durable and accurately typed, and
the iOS module was rewritten in Swift.

Breaking:
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
- Minimum iOS deployment target is 15.1.
- Removed non-functional iOS code paths: multipart, `assets-library://`, `appGroup`.
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
