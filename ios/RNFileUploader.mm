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

// Each method is a one-line forward to the Swift engine, which hops onto its
// own serial queue and returns. getRequests is the one synchronous method: it
// reads the in-memory index under a lock.

- (void)configure:(NSDictionary *)options
{
  [RNBackgroundUpload.shared configure:options];
}

- (void)enqueue:(NSDictionary *)entry
        resolve:(RCTPromiseResolveBlock)resolve
         reject:(RCTPromiseRejectBlock)reject
{
  [RNBackgroundUpload.shared enqueue:entry resolve:resolve reject:reject];
}

- (void)pause:(RCTPromiseResolveBlock)resolve
       reject:(RCTPromiseRejectBlock)reject
{
  [RNBackgroundUpload.shared pause:resolve reject:reject];
}

- (void)resume:(RCTPromiseResolveBlock)resolve
        reject:(RCTPromiseRejectBlock)reject
{
  [RNBackgroundUpload.shared resume:resolve reject:reject];
}

- (void)cancel:(NSString *)id
       resolve:(RCTPromiseResolveBlock)resolve
        reject:(RCTPromiseRejectBlock)reject
{
  [RNBackgroundUpload.shared cancel:id resolve:resolve reject:reject];
}

- (void)setWifiOnly:(BOOL)enabled
            resolve:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject
{
  [RNBackgroundUpload.shared setWifiOnly:enabled resolve:resolve reject:reject];
}

- (void)updateHeaders:(NSDictionary *)patch
              resolve:(RCTPromiseResolveBlock)resolve
               reject:(RCTPromiseRejectBlock)reject
{
  [RNBackgroundUpload.shared updateHeaders:patch resolve:resolve reject:reject];
}

- (NSArray<NSDictionary *> *)getRequests
{
  return [RNBackgroundUpload.shared getRequests];
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

#pragma mark - RNFileUploaderEventDelegate

// Called on the engine's serial queue. That is safe and
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

- (void)emitState:(NSDictionary *)body
{
  [self safeEmit:^(RNFileUploader *m) { [m emitOnState:body]; }];
}

- (void)emitProgress:(NSDictionary *)body
{
  [self safeEmit:^(RNFileUploader *m) { [m emitOnProgress:body]; }];
}

- (void)emitAttempt:(NSDictionary *)body
{
  [self safeEmit:^(RNFileUploader *m) { [m emitOnAttempt:body]; }];
}

- (void)emitSettled:(NSDictionary *)body
{
  [self safeEmit:^(RNFileUploader *m) { [m emitOnSettled:body]; }];
}

@end
