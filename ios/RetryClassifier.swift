import Foundation

/// Decides what one attempt's result means: the spec's retry, auth and
/// lifetime table (section 6.1). Pure: no I/O, no session state.
enum RetryClassifier {
  enum Class: Equatable {
    case accepted
    /// `rule` names the `transient` rule that excused the response; nil for the standard table or `exempt`.
    case transient(rule: Int?)
    case auth
    case terminalHttp
    /// The payload is gone. A retry can never succeed.
    case fileMissing
    case expired
  }

  struct Input {
    var statusCode: Int?
    var body: String?
    var error: NSError?
    var accept: [UploadOutcome.AcceptRule]
    var policy: RetryPolicy
    var fileExists: Bool
    var now: Double
    var expiresAt: Double
    /// The entry's count per `transient` rule so far.
    var hits: [Int] = []
  }

  /// A cancellation (NSURLErrorCancelled) never reaches here: the caller
  /// handles it from the task's recorded purpose.
  /// Past expiresAt only a transient result becomes `expired`; a real
  /// response keeps its own class.
  static func classify(_ i: Input) -> Class {
    let verdict = classifyResult(i)
    if case .transient = verdict, i.now >= i.expiresAt { return .expired }
    return verdict
  }

  private static func classifyResult(_ i: Input) -> Class {
    if i.error == nil, let code = i.statusCode,
       UploadOutcome.isAccepted(code, body: i.body, accept: i.accept) {
      return .accepted
    }
    if let error = i.error {
      // A file that exists but cannot be read now (iOS before first unlock)
      // is transient.
      if errorKind(for: error) == "file" { return i.fileExists ? .transient(rule: nil) : .fileMissing }
      return .transient(rule: nil)
    }
    guard let code = i.statusCode else { return .transient(rule: nil) }
    switch code {
    case 401, 403: return .auth
    case 408, 429, 500...599: return .transient(rule: nil)
    case 400...499:
      // A matched rule decides, even past its cap: exempt does not rescue it.
      if let index = UploadOutcome.matchesTransient(code, body: i.body, rules: i.policy.transient) {
        let used = index < i.hits.count ? i.hits[index] : 0
        if let cap = i.policy.transient[index].maxRetries, used >= cap { return .terminalHttp }
        return .transient(rule: index)
      }
      return i.policy.exempt.contains(code) ? .transient(rule: nil) : .terminalHttp
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
