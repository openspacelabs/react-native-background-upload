import Foundation
import os

/// os_signpost intervals around the work on the coordinator queue: each
/// enqueue, each issue, and each slice of part tasks. Instruments shows them
/// under Points of Interest, with the entry id and the part count. They
/// cost almost nothing when no tool records them.
enum Signpost {
  static let subsystem = "ai.openspace.rnbgupload"
  private static let poster = OSSignposter(subsystem: subsystem, category: .pointsOfInterest)

  /// Runs `body` inside the interval `name`. `detail` says what it works on.
  static func interval<T>(_ name: StaticString, _ detail: String, _ body: () throws -> T) rethrows -> T {
    let state = poster.beginInterval(name, id: poster.makeSignpostID(), "\(detail, privacy: .public)")
    defer { poster.endInterval(name, state) }
    return try body()
  }
}
