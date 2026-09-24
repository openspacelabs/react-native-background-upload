import Foundation

/// Decides what one attempt's result means: the spec's retry, auth and
/// lifetime table (section 6.1). Pure: no I/O, no session state.
enum RetryClassifier {
  enum Class: Equatable {
    case accepted
    case transient
    case auth
    case terminalHttp
    /// The payload is gone. A retry can never succeed.
    case fileMissing
    /// The payload exists but cannot be read now (iOS before first unlock).
    case fileUnreadable
    case expired
  }

  struct Input {
    var statusCode: Int?
    var body: String?
    var error: NSError?
    var accept: [UploadOutcome.AcceptRule]
    var policy: RetryPolicy
    /// Changes nothing: a chunked definition sets `exempt: []`, so the
    /// terminal row applies through the policy. It is here so a test can pin
    /// the part-404 case.
    var isChunkedPart: Bool
    var fileExists: Bool
    var now: Double
    var expiresAt: Double
  }

  /// A cancellation (NSURLErrorCancelled) never reaches here: the caller
  /// handles it from the task's recorded purpose.
  static func classify(_ i: Input) -> Class {
    if i.error == nil, let code = i.statusCode,
       UploadOutcome.isAccepted(code, body: i.body, accept: i.accept) {
      return .accepted
    }
    if i.now >= i.expiresAt { return .expired }
    if let error = i.error {
      if errorKind(for: error) == "file" { return i.fileExists ? .fileUnreadable : .fileMissing }
      return .transient
    }
    guard let code = i.statusCode else { return .transient }
    switch code {
    case 401, 403: return .auth
    case 408, 429, 500...599: return .transient
    case 400...499: return i.policy.exempt.contains(code) ? .transient : .terminalHttp
    // 1xx and 3xx the session did not follow. The answer will not change.
    default: return .terminalHttp
    }
  }

  /// Jittered exponential backoff. `attempt` is 1-based: 1 gives about base.
  /// min(base * 2^(attempt-1), max), times (1 + jitter * (2r - 1)), >= 0.
  static func backoffMs(attempt: Int, policy: RetryPolicy, random: () -> Double) -> Int {
    let exponent = Double(min(max(attempt - 1, 0), 40))
    let raw = min(policy.baseMs * pow(2, exponent), policy.maxMs)
    let jittered = raw * (1 + policy.jitter * (2 * random() - 1))
    return Int(max(jittered, 0).rounded())
  }

  /// The errorKind taxonomy shared with Android: a missing or unreadable
  /// source file is 'file'; other URL-domain errors are 'network'; anything
  /// else is 'unknown'.
  static func errorKind(for error: NSError) -> String {
    switch (error.domain, error.code) {
    case (NSURLErrorDomain, NSURLErrorFileDoesNotExist),
         (NSURLErrorDomain, NSURLErrorCannotOpenFile),
         (NSURLErrorDomain, NSURLErrorNoPermissionsToReadFile),
         (NSCocoaErrorDomain, NSFileNoSuchFileError),
         (NSCocoaErrorDomain, NSFileReadNoSuchFileError),
         (NSCocoaErrorDomain, NSFileReadNoPermissionError):
      return "file"
    case (NSURLErrorDomain, _):
      return "network"
    default:
      return "unknown"
    }
  }

  static func isCancellation(_ error: NSError?) -> Bool {
    error?.domain == NSURLErrorDomain && error?.code == NSURLErrorCancelled
  }
}
