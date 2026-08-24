#import "RNFileUploader.h"

// Both spellings are needed to support use_frameworks! as well as static linking.
#if __has_include("react_native_background_upload-Swift.h")
#import "react_native_background_upload-Swift.h"
#else
#import <react_native_background_upload/react_native_background_upload-Swift.h>
#endif

#include <exception>

@interface RNFileUploader () <RNFileUploaderEventDelegate>
@end

@implementation RNFileUploader

- (instancetype)init
{
  self = [super init];
  if (self) {
    // Also forces RNBackgroundUpload.shared into existence, which recreates the
    // background URLSessions for this process.
    [RNBackgroundUpload setEventDelegate:self];
  }
  return self;
}

- (void)invalidate
{
  // Identity-checked: React Native dispatches invalidate asynchronously and
  // stops waiting after 10s, so the replacement module can register itself
  // first. Clearing unconditionally would kill events for the whole process.
  [RNBackgroundUpload clearEventDelegate:self];
}

+ (BOOL)requiresMainQueueSetup
{
  return NO;
}

// Without this, RN hands the module its process-wide shared TurboModule queue,
// where our synchronous journal and task-map disk I/O would stall unrelated
// native modules — and RN's own invalidate, which queues behind it. The legacy
// bridge gave every module its own queue; a TurboModule has to ask.
- (dispatch_queue_t)methodQueue
{
  static dispatch_queue_t queue;
  static dispatch_once_t onceToken;
  dispatch_once(&onceToken, ^{
    queue = dispatch_queue_create("ai.openspace.rnbgupload.module", DISPATCH_QUEUE_SERIAL);
  });

  return queue;
}

+ (NSString *)moduleName
{
  return @"RNFileUploader";
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativeRNFileUploaderSpecJSI>(params);
}

#pragma mark - Exported methods

// configure() carries the Android notification configuration. iOS background
// uploads have no library-owned notification. Thus there is nothing to save.
- (void)configure:(NSDictionary *)options
{
}

- (void)startUpload:(NSDictionary *)options
            resolve:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject
{
  [RNBackgroundUpload.shared startUpload:options resolve:resolve reject:reject];
}

- (void)startChunkedUpload:(NSDictionary *)options
                   resolve:(RCTPromiseResolveBlock)resolve
                    reject:(RCTPromiseRejectBlock)reject
{
  [RNBackgroundUpload.shared startChunkedUpload:options resolve:resolve reject:reject];
}

- (void)cancelUpload:(NSString *)id
             resolve:(RCTPromiseResolveBlock)resolve
              reject:(RCTPromiseRejectBlock)reject
{
  [RNBackgroundUpload.shared cancelUpload:id resolve:resolve reject:reject];
}

- (void)removeUpload:(NSString *)id
             resolve:(RCTPromiseResolveBlock)resolve
              reject:(RCTPromiseRejectBlock)reject
{
  [RNBackgroundUpload.shared removeUpload:id resolve:resolve reject:reject];
}

- (void)getUnacknowledgedEvents:(RCTPromiseResolveBlock)resolve
                         reject:(RCTPromiseRejectBlock)reject
{
  [RNBackgroundUpload.shared getUnacknowledgedEvents:resolve reject:reject];
}

- (void)ackEvents:(NSArray *)ids
          resolve:(RCTPromiseResolveBlock)resolve
           reject:(RCTPromiseRejectBlock)reject
{
  [RNBackgroundUpload.shared ackEvents:ids resolve:resolve reject:reject];
}

- (void)getAllUploads:(RCTPromiseResolveBlock)resolve
               reject:(RCTPromiseRejectBlock)reject
{
  [RNBackgroundUpload.shared getAllUploads:resolve reject:reject];
}

#pragma mark - RNFileUploaderEventDelegate

// Called synchronously on the URLSession delegate queue. That is safe and
// deliberate: the generated emitter locks its own state and dispatches each
// listener through the JS CallInvoker, so it is already thread-safe and already
// async onto the JS thread. Deferring to the main queue instead would open a
// window where the module's TurboModule is torn down before the block runs.
//
// The try/catch is required, not defensive: the generated emitOnX calls an
// std::function that is only installed when the TurboModule is constructed,
// which happens after this module's init has already registered as the delegate.
// An event in that gap throws std::bad_function_call, as does one emitted with no
// JS listeners attached. Both are harmless — the terminal outcome is already in
// the journal, so JS recovers it from getUnacknowledgedEvents.
- (void)safeEmit:(void (^)(RNFileUploader *emitter))block
{
  try {
    block(self);
  } catch (const std::exception &e) {
    // No listeners yet, or the runtime is gone — drop the live event.
  }
}

- (void)emitProgress:(NSDictionary *)body
{
  [self safeEmit:^(RNFileUploader *emitter) { [emitter emitOnProgress:body]; }];
}

- (void)emitCompleted:(NSDictionary *)body
{
  [self safeEmit:^(RNFileUploader *emitter) { [emitter emitOnCompleted:body]; }];
}

- (void)emitError:(NSDictionary *)body
{
  [self safeEmit:^(RNFileUploader *emitter) { [emitter emitOnError:body]; }];
}

- (void)emitCancelled:(NSDictionary *)body
{
  [self safeEmit:^(RNFileUploader *emitter) { [emitter emitOnCancelled:body]; }];
}

@end
