#import "AppDelegate.h"

#import <React/RCTBundleURLProvider.h>
#import <ReactAppDependencyProvider/RCTAppDependencyProvider.h>
// The library's background-session class is Swift. The pods link as static
// libraries, and this file is Objective-C++, so `@import` is not available
// (C++ modules are off). CocoaPods copies the generated Swift header into
// "Swift Compatibility Header" under the pod's build directory. The app
// target adds that directory to HEADER_SEARCH_PATHS. The header uses
// RCTPromiseResolveBlock, so RCTBridgeModule.h comes first.
#import <React/RCTBridgeModule.h>
#import "react_native_background_upload-Swift.h"

// This matches Diana's AppDelegate: RCTAppDelegate with a dependency
// provider, plus the background-session hook that the library needs.

@implementation AppDelegate

- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions
{
  self.moduleName = @"RNBGUExample";
  // The codegen dependency provider. The New Architecture uses it to find
  // third-party TurboModules such as RNFileUploader.
  self.dependencyProvider = [RCTAppDependencyProvider new];
  // You can add your custom initial props in the dictionary below.
  // They will be passed down to the ViewController used by React Native.
  self.initialProps = @{};

  return [super application:application didFinishLaunchingWithOptions:launchOptions];
}

- (NSURL *)bundleURL
{
#if DEBUG
  return [[RCTBundleURLProvider sharedSettings] jsBundleURLForBundleRoot:@"index"];
#else
  return [[NSBundle mainBundle] URLForResource:@"main" withExtension:@"jsbundle"];
#endif
}

// The system calls this when it relaunches the app for events of a
// background URLSession, often with no JS running. The library recreates its
// sessions here, so completed uploads are journaled, and it calls the
// completion handler when the events are processed.
- (void)application:(UIApplication *)application
    handleEventsForBackgroundURLSession:(NSString *)identifier
                      completionHandler:(void (^)(void))completionHandler
{
  [RNBackgroundUpload setBackgroundSessionCompletionHandler:completionHandler
                                              forIdentifier:identifier];
}

@end
