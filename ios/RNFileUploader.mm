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
  [RNBackgroundUpload setEventDelegate:nil];
}

+ (BOOL)requiresMainQueueSetup
{
  return NO;
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

- (void)startUpload:(NSDictionary *)options
            resolve:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject
{
  [RNBackgroundUpload.shared startUpload:options resolve:resolve reject:reject];
}

- (void)cancelUpload:(NSString *)id
             resolve:(RCTPromiseResolveBlock)resolve
              reject:(RCTPromiseRejectBlock)reject
{
  [RNBackgroundUpload.shared cancelUpload:id resolve:resolve reject:reject];
}

- (void)getUploadStatus:(NSString *)id
                resolve:(RCTPromiseResolveBlock)resolve
                 reject:(RCTPromiseRejectBlock)reject
{
  [RNBackgroundUpload.shared getUploadStatus:id resolve:resolve reject:reject];
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

// Delegate callbacks arrive on the URLSession delegate queue, so hop to main
// before touching the emitter. Emitting with no listeners attached throws, which
// is expected and harmless: the terminal outcome is already in the journal and JS
// will pick it up via getUnacknowledgedEvents.
- (void)safeEmit:(void (^)(RNFileUploader *emitter))block
{
  __weak RNFileUploader *weakSelf = self;
  dispatch_async(dispatch_get_main_queue(), ^{
    RNFileUploader *strongSelf = weakSelf;
    if (strongSelf == nil) {
      return;
    }
    try {
      block(strongSelf);
    } catch (const std::exception &e) {
      // No listeners / runtime gone — drop the live event.
    }
  });
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
