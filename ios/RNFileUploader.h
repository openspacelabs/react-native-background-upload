#import <RNFileUploaderSpec/RNFileUploaderSpec.h>

// TurboModule shell (New Architecture). All upload behaviour lives in
// RNBackgroundUpload.swift; this class only adapts the codegen-generated spec to
// it and forwards events to the generated emitters.
//
// This header is Obj-C++ ONLY — the generated spec header above #errors in plain
// Obj-C — so the podspec marks it private. It must never reach a consumer's .m
// translation unit. Consumers that need the background-session handler import the
// Swift class instead (`@import react_native_background_upload;`).
@interface RNFileUploader : NativeRNFileUploaderSpecBase <NativeRNFileUploaderSpec>
@end
