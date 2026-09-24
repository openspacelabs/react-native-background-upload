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

// v10 slice 1 ships the JS layer alone. Every queue method rejects with this
// code until slice 3 builds the iOS queue and executor.
static NSString *const kNotImplemented = @"E_NOT_IMPLEMENTED";

static void RejectNotImplemented(RCTPromiseRejectBlock reject, NSString *method)
{
  reject(kNotImplemented,
         [NSString stringWithFormat:@"RNFileUploader.%@: the iOS queue is not built yet", method],
         nil);
}

// configure() carries { lifetimeMs, retry, ...androidNotificationConfig }. iOS
// background uploads have no library-owned notification, and slice 3 persists
// the queue settings. Thus there is nothing to save yet.
- (void)configure:(NSDictionary *)options
{
}

- (void)enqueue:(NSDictionary *)entry
        resolve:(RCTPromiseResolveBlock)resolve
         reject:(RCTPromiseRejectBlock)reject
{
  RejectNotImplemented(reject, @"enqueue");
}

- (void)pause:(RCTPromiseResolveBlock)resolve
       reject:(RCTPromiseRejectBlock)reject
{
  RejectNotImplemented(reject, @"pause");
}

- (void)resume:(RCTPromiseResolveBlock)resolve
        reject:(RCTPromiseRejectBlock)reject
{
  RejectNotImplemented(reject, @"resume");
}

- (void)cancel:(NSString *)id
       resolve:(RCTPromiseResolveBlock)resolve
        reject:(RCTPromiseRejectBlock)reject
{
  RejectNotImplemented(reject, @"cancel");
}

- (void)setWifiOnly:(BOOL)enabled
            resolve:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject
{
  RejectNotImplemented(reject, @"setWifiOnly");
}

- (void)updateHeaders:(NSDictionary *)patch
              resolve:(RCTPromiseResolveBlock)resolve
               reject:(RCTPromiseRejectBlock)reject
{
  RejectNotImplemented(reject, @"updateHeaders");
}

// Synchronous. The live rows of the v10 queue. Slice 3 serializes them from
// the in-memory index; until then the queue is empty.
- (NSArray<NSDictionary *> *)getRequests
{
  return @[];
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

// The v9 Swift engine still reports through the delegate. The v10 spec has no
// per-outcome emitters and a different progress shape ({ id, bytesSent,
// totalBytes }), so until slice 3 rewires the engine to onState/onProgress/
// onSettled, the live v9 payloads are dropped here. Terminal outcomes are
// journaled first, so nothing durable is lost.
- (void)emitProgress:(NSDictionary *)body
{
}

- (void)emitCompleted:(NSDictionary *)body
{
}

- (void)emitError:(NSDictionary *)body
{
}

- (void)emitCancelled:(NSDictionary *)body
{
}

@end
