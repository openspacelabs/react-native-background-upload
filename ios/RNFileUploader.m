#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

// Bridge registration for the Swift RNFileUploader (RCTEventEmitter subclass).
// The RCT_EXTERN_* macros are Objective-C only, so this small shim is required on
// the legacy bridge; addListener/removeListeners come from RCTEventEmitter itself.
// No JS-name argument => the module is exported under its class name,
// RNFileUploader, matching NativeModules.RNFileUploader.
@interface RCT_EXTERN_MODULE(RNFileUploader, RCTEventEmitter)

RCT_EXTERN_METHOD(startUpload:(NSDictionary *)options
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(cancelUpload:(NSString *)cancelUploadId
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(getUploadStatus:(NSString *)uploadId
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(getUnacknowledgedEvents:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(ackEvents:(NSArray *)eventIds
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(getAllUploads:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

@end
