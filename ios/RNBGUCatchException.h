#import <Foundation/Foundation.h>

// Swift cannot catch an Objective-C exception. One that unwinds through a Swift
// frame ends the process. This runs `block` in an Objective-C @try and hands the
// exception back to Swift as a value.
//
// Keep this header Foundation-only. It is public, so it is part of the module
// umbrella that this module's Swift and plain Obj-C consumers both import.

NS_ASSUME_NONNULL_BEGIN

/// Runs `block`. Returns the exception it raised, or nil when it returned.
FOUNDATION_EXPORT NSException *_Nullable RNBGUCatchException(NS_NOESCAPE void (^block)(void));

NS_ASSUME_NONNULL_END
