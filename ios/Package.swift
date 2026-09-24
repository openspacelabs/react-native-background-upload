// swift-tools-version:5.9
// Host-side unit tests for the pure half of the iOS module: `cd ios && swift test`.
// The CocoaPods build ignores this file (see exclude_files in the podspec).
// RNBackgroundUpload.swift and the .mm import React and UIKit, so they are
// not part of this package.
import PackageDescription

let package = Package(
  name: "RNBGUCore",
  platforms: [.macOS(.v12)],
  targets: [
    .target(
      name: "RNBGUCore",
      path: ".",
      exclude: ["Tests", "RNBackgroundUpload.swift", "RNFileUploader.h", "RNFileUploader.mm", ".gitignore"],
      sources: [
        "BodyStaging.swift",
        "ChunkedCoordinator.swift",
        "ChunkedEngine.swift",
        "ChunkedManifestV9.swift",
        "EnqueueParser.swift",
        "EventJournal.swift",
        "Events.swift",
        "FileIO.swift",
        "JSONText.swift",
        "LegacyImport.swift",
        "ProgressThrottle.swift",
        "QueueCoordinator.swift",
        "QueueCoordinator+Enqueue.swift",
        "QueueCoordinator+Outcomes.swift",
        "QueueCoordinator+Reconcile.swift",
        "QueueCoordinator+Simple.swift",
        "QueueEntry.swift",
        "QueueSettings.swift",
        "QueueStore.swift",
        "RequestIndex.swift",
        "RetryClassifier.swift",
        "TaskMap.swift",
        "Transport.swift",
        "UploadOutcome.swift",
      ]),
    .testTarget(name: "RNBGUCoreTests", dependencies: ["RNBGUCore"], path: "Tests"),
  ]
)
