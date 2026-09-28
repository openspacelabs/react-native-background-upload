#import "RNBGUCatchException.h"

NSException *_Nullable RNBGUCatchException(NS_NOESCAPE void (^block)(void))
{
  @try {
    block();
    return nil;
  } @catch (NSException *exception) {
    return exception;
  }
}
