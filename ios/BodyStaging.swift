import Foundation

/// The staged body of one entry: a file inside the entry directory. A
/// background URLSession uploads from a file only, so every kind gets one,
/// a bodiless request included (0 bytes).
struct StagedBody: Equatable {
  let kind: QueueEntry.BodyKind
  let relativePath: String
  let contentType: String?
  let forceContentType: Bool
  let totalBytes: Int64
  /// true when the file already existed (an adopted blob). A failed enqueue
  /// must not delete it.
  let adopted: Bool
}

enum StagingError: Error, Equatable {
  /// A source file is gone. Rejects E_FILE_MISSING.
  case fileMissing(String)
  /// The parts do not tile the file. Rejects E_INVALID; JS validates the
  /// plan, so this is a size mismatch between the plan and the real file.
  case invalid(String)
  case io(String)
}

enum BodyStaging {
  static let bodyPrefix = "body-"
  static let blobPrefix = "blob-"

  /// Stages `body` into `dir` under a fresh name, tmp + fsync + rename. Every
  /// check that can reject runs before the caller's file is touched.
  ///
  /// `fallbackBlob` is an existing blob in `dir` that a chunked body may keep
  /// when its source file is gone: the bytes a crash left between the move
  /// and the entry save, a v9 blob, or the current entry's blob on a replace.
  static func stage(_ body: ParsedEnqueue.Body, parts: [QueueEntry.Part], into dir: URL,
                    fallbackBlob: String?, fm: FileManager = .default) throws -> StagedBody {
    do {
      try fm.createDirectory(at: dir, withIntermediateDirectories: true)
    } catch {
      throw StagingError.io("cannot create the entry directory: \(error.localizedDescription)")
    }
    switch body {
    case .none:
      let name = uniqueName(bodyPrefix)
      try write(Data(), dir.appendingPathComponent(name))
      return StagedBody(kind: .none, relativePath: name, contentType: nil,
                        forceContentType: false, totalBytes: 0, adopted: false)

    case .data(let json):
      let name = uniqueName(bodyPrefix)
      let data = Data(json.utf8)
      try write(data, dir.appendingPathComponent(name))
      return StagedBody(kind: .data, relativePath: name, contentType: "application/json",
                        forceContentType: false, totalBytes: Int64(data.count), adopted: false)

    case .form(let fields):
      for f in fields {
        if let path = f.path, !fm.fileExists(atPath: fileURL(path).path) {
          throw StagingError.fileMissing(path)
        }
      }
      let name = uniqueName(bodyPrefix)
      let boundary = "rnbgu-" + UUID().uuidString
      let dest = dir.appendingPathComponent(name)
      let tmp = FileIO.tmpURL(for: dest)
      do {
        let size = try writeMultipart(fields, boundary: boundary, to: tmp, fm: fm)
        try FileIO.rename(tmp, onto: dest)
        return StagedBody(kind: .form, relativePath: name,
                          contentType: "multipart/form-data; boundary=\(boundary)",
                          forceContentType: true, totalBytes: size, adopted: false)
      } catch let e as StagingError {
        try? fm.removeItem(at: tmp)
        throw e
      } catch {
        try? fm.removeItem(at: tmp)
        throw StagingError.io("cannot write the form body: \(error.localizedDescription)")
      }

    case .file(let path):
      let src = fileURL(path)
      guard fm.fileExists(atPath: src.path) else { throw StagingError.fileMissing(path) }
      let name = uniqueName(bodyPrefix)
      let dest = dir.appendingPathComponent(name)
      let tmp = FileIO.tmpURL(for: dest)
      do {
        try? fm.removeItem(at: tmp)
        try fm.copyItem(at: src, to: tmp)
        try FileHandle(forUpdating: tmp).synchronizeAndClose()
        try FileIO.rename(tmp, onto: dest)
      } catch {
        try? fm.removeItem(at: tmp)
        // The source vanished between the check and the copy.
        if !fm.fileExists(atPath: src.path) { throw StagingError.fileMissing(path) }
        throw StagingError.io("cannot copy the file body: \(error.localizedDescription)")
      }
      return StagedBody(kind: .file, relativePath: name, contentType: nil,
                        forceContentType: false, totalBytes: FileIO.size(dest) ?? 0, adopted: false)

    case .parts(let path):
      let src = fileURL(path)
      if fm.fileExists(atPath: src.path) {
        let size = FileIO.size(src) ?? 0
        try requireTiling(parts, size: size)
        let name = uniqueName(blobPrefix)
        do {
          // An O(1) rename on the same volume. Across volumes FileManager copies.
          try fm.moveItem(at: src, to: dir.appendingPathComponent(name))
        } catch {
          throw StagingError.io("cannot move the chunked file: \(error.localizedDescription)")
        }
        return StagedBody(kind: .parts, relativePath: name, contentType: nil,
                          forceContentType: false, totalBytes: size, adopted: false)
      }
      if let fallbackBlob, let size = FileIO.size(dir.appendingPathComponent(fallbackBlob)) {
        try requireTiling(parts, size: size)
        return StagedBody(kind: .parts, relativePath: fallbackBlob, contentType: nil,
                          forceContentType: false, totalBytes: size, adopted: true)
      }
      throw StagingError.fileMissing(path)
    }
  }

  /// Writes a multipart/form-data body. String fields are written as they
  /// are; file fields are streamed in 1 MB reads. Returns the byte count.
  static func writeMultipart(_ fields: [ParsedEnqueue.FormField], boundary: String, to url: URL,
                             fm: FileManager = .default) throws -> Int64 {
    try? fm.removeItem(at: url)
    guard fm.createFile(atPath: url.path, contents: nil) else {
      throw StagingError.io("cannot create the form body")
    }
    let out = try FileHandle(forWritingTo: url)
    defer { try? out.close() }
    var total: Int64 = 0
    func put(_ data: Data) throws {
      try out.write(contentsOf: data)
      total += Int64(data.count)
    }
    for f in fields {
      var head = "--\(boundary)\r\nContent-Disposition: form-data; name=\"\(quote(f.name))\""
      if let path = f.path {
        let fileName = f.fileName ?? fileURL(path).lastPathComponent
        head += "; filename=\"\(quote(fileName))\""
      }
      head += "\r\nContent-Type: \(f.contentType)\r\n\r\n"
      try put(Data(head.utf8))
      if let s = f.string {
        try put(Data(s.utf8))
      } else if let path = f.path {
        let src = fileURL(path)
        guard let reader = try? FileHandle(forReadingFrom: src) else {
          throw StagingError.fileMissing(path)
        }
        defer { try? reader.close() }
        while let chunk = try reader.read(upToCount: 1 << 20), !chunk.isEmpty {
          try put(chunk)
        }
      }
      try put(Data("\r\n".utf8))
    }
    try put(Data("--\(boundary)--\r\n".utf8))
    try out.synchronize()
    return total
  }

  /// Accepts a `file://` URL and a plain path. The JS layer forwards the
  /// path as the caller wrote it.
  static func fileURL(_ pathOrURL: String) -> URL {
    if pathOrURL.hasPrefix("file://") {
      if let u = URL(string: pathOrURL), u.isFileURL { return u }
      return URL(fileURLWithPath: String(pathOrURL.dropFirst("file://".count)))
    }
    return URL(fileURLWithPath: pathOrURL)
  }

  static func uniqueName(_ prefix: String) -> String {
    prefix + UUID().uuidString.lowercased()
  }

  static func requireTiling(_ parts: [QueueEntry.Part], size: Int64) throws {
    guard QueueEntry.tilesExactly(parts, size: size) else {
      throw StagingError.invalid("parts must tile exactly [0, \(size)), the size of the file")
    }
  }

  // Quotes and line breaks in a field name or file name would end the header.
  // Percent-encode them, as browsers do.
  private static func quote(_ s: String) -> String {
    s.replacingOccurrences(of: "\"", with: "%22")
      .replacingOccurrences(of: "\r", with: "%0D")
      .replacingOccurrences(of: "\n", with: "%0A")
  }

  private static func write(_ data: Data, _ url: URL) throws {
    do {
      try FileIO.writeAtomically(data, to: url)
    } catch {
      throw StagingError.io("cannot write the body: \(error.localizedDescription)")
    }
  }
}

private extension FileHandle {
  func synchronizeAndClose() throws {
    defer { try? close() }
    try synchronize()
  }
}
