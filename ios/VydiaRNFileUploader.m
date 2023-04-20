#import <Foundation/Foundation.h>
#import <MobileCoreServices/MobileCoreServices.h>
#import <React/RCTEventEmitter.h>
#import <React/RCTBridgeModule.h>
#import <Photos/Photos.h>
#import "Helper.h"

@interface VydiaRNFileUploader : RCTEventEmitter <RCTBridgeModule, NSURLSessionTaskDelegate>
@end

@implementation VydiaRNFileUploader

RCT_EXPORT_MODULE();

@synthesize bridge = _bridge;
static int uploadId = 0;


static NSString *BACKGROUND_SESSION_ID = @"ReactNativeBackgroundUpload";
static NSString *WIFI_ONLY_BACKGROUND_SESSION_ID = @"ReactNativeBackgroundUpload_WifiOnly";

NSURLSession *_urlSession = nil;
NSURLSession *_wifiOnlyUrlSession = nil;
NSMutableDictionary *_responsesData = nil;

+ (BOOL)requiresMainQueueSetup {
    return NO;
}

-(id) init {
    self = [super init];

    _responsesData = [NSMutableDictionary dictionary];

    // Initializes as early as possible to receive delegate events
    // sent from previously registered URLSessions
    [self urlSession];
    [self wifiOnlyUrlSession];
    return self;
}

- (NSArray<NSString *> *)supportedEvents {
    return @[
        @"RNFileUploader-progress",
        @"RNFileUploader-error",
        @"RNFileUploader-cancelled",
        @"RNFileUploader-completed"
    ];
}


/*
 Borrowed from http://stackoverflow.com/questions/2439020/wheres-the-iphone-mime-type-database
 */
- (NSString *)guessMIMETypeFromFileName: (NSString *)fileName {
    CFStringRef UTI = UTTypeCreatePreferredIdentifierForTag(kUTTagClassFilenameExtension, (__bridge CFStringRef)[fileName pathExtension], NULL);
    CFStringRef MIMEType = UTTypeCopyPreferredTagWithClass(UTI, kUTTagClassMIMEType);

    if (UTI) {
        CFRelease(UTI);
    }

    if (!MIMEType) {
        return @"application/octet-stream";
    }
    return (__bridge NSString *)(MIMEType);
}

/*
 Utility method to copy a PHAsset file into a local temp file, which can then be uploaded.
 */
- (void)copyAssetToFile: (NSString *)assetUrl completionHandler: (void(^)(NSString *__nullable tempFileUrl, NSError *__nullable error))completionHandler {
    NSURL *url = [NSURL URLWithString:assetUrl];
    PHAsset *asset = [PHAsset fetchAssetsWithALAssetURLs:@[url] options:nil].lastObject;
    if (!asset) {
        NSMutableDictionary* details = [NSMutableDictionary dictionary];
        [details setValue:@"Asset could not be fetched.  Are you missing permissions?" forKey:NSLocalizedDescriptionKey];
        completionHandler(nil,  [NSError errorWithDomain:@"RNUploader" code:5 userInfo:details]);
        return;
    }
    PHAssetResource *assetResource = [[PHAssetResource assetResourcesForAsset:asset] firstObject];
    NSString *pathToWrite = [NSTemporaryDirectory() stringByAppendingPathComponent:[[NSUUID UUID] UUIDString]];
    NSURL *pathUrl = [NSURL fileURLWithPath:pathToWrite];
    NSString *fileURI = pathUrl.absoluteString;

    PHAssetResourceRequestOptions *options = [PHAssetResourceRequestOptions new];
    options.networkAccessAllowed = YES;

    [[PHAssetResourceManager defaultManager] writeDataForAssetResource:assetResource toFile:pathUrl options:options completionHandler:^(NSError * _Nullable e) {
        if (e == nil) {
            completionHandler(fileURI, nil);
        }
        else {
            completionHandler(nil, e);
        }
    }];
}

/*
 * Starts a file upload.
 * Options are passed in as the first argument as a js hash:
 * {
 *   url: string.  url to post to.
 *   path: string.  path to the file on the device
 *   headers: hash of name/value header pairs
 * }
 *
 * Returns a promise with the string ID of the upload.
 */
RCT_EXPORT_METHOD(startUpload:(NSDictionary *)options resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject)
{
    int thisUploadId;
    @synchronized(self.class)
    {
        thisUploadId = uploadId++;
    }

    NSString *uploadUrl = options[@"url"];
    __block NSString *fileURI = options[@"path"];
    NSString *method = options[@"method"] ?: @"POST";
    NSString *uploadType = options[@"type"] ?: @"raw";
    NSString *fieldName = options[@"field"];
    NSString *customUploadId = options[@"customUploadId"];
    NSString *appGroup = options[@"appGroup"];
    NSDictionary *headers = options[@"headers"];
    NSDictionary *parameters = options[@"parameters"];
    BOOL wifiOnly = [options[@"wifiOnly"] boolValue];

    @try {
        NSURL *requestUrl = [NSURL URLWithString: uploadUrl];
        if (requestUrl == nil) {
            return reject(@"RN Uploader", @"URL not compliant with RFC 2396", nil);
        }

        NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:requestUrl];
        [request setHTTPMethod: method];

        [headers enumerateKeysAndObjectsUsingBlock:^(id  _Nonnull key, id  _Nonnull val, BOOL * _Nonnull stop) {
            if ([val respondsToSelector:@selector(stringValue)]) {
                val = [val stringValue];
            }
            if ([val isKindOfClass:[NSString class]]) {
                [request setValue:val forHTTPHeaderField:key];
            }
        }];


        // asset library files have to be copied over to a temp file.  they can't be uploaded directly
        if ([fileURI hasPrefix:@"assets-library"]) {
            dispatch_group_t group = dispatch_group_create();
            dispatch_group_enter(group);
            [self copyAssetToFile:fileURI completionHandler:^(NSString * _Nullable tempFileUrl, NSError * _Nullable error) {
                if (error) {
                    dispatch_group_leave(group);
                    reject(@"RN Uploader", @"Asset could not be copied to temp file.", nil);
                    return;
                }
                fileURI = tempFileUrl;
                dispatch_group_leave(group);
            }];
            dispatch_group_wait(group, DISPATCH_TIME_FOREVER);
        }

        NSURLSessionDataTask *uploadTask;

        NSURLSession *session = wifiOnly ? [self wifiOnlyUrlSession] : [self urlSession];
        if (appGroup != nil && ![appGroup isEqualToString:@""]) {
            session.configuration.sharedContainerIdentifier = appGroup;
        }

        if ([uploadType isEqualToString:@"multipart"]) {
            NSString *uuidStr = [[NSUUID UUID] UUIDString];
            [request setValue:[NSString stringWithFormat:@"multipart/form-data; boundary=%@", uuidStr] forHTTPHeaderField:@"Content-Type"];

            NSData *httpBody = [self createBodyWithBoundary:uuidStr path:fileURI parameters: parameters fieldName:fieldName];
            [request setHTTPBodyStream: [NSInputStream inputStreamWithData:httpBody]];
            [request setValue:[NSString stringWithFormat:@"%zd", httpBody.length] forHTTPHeaderField:@"Content-Length"];

            uploadTask = [session uploadTaskWithStreamedRequest:request];
        } else {
            if (parameters.count > 0) {
                reject(@"RN Uploader", @"Parameters supported only in multipart type", nil);
                return;
            }

            uploadTask = [session uploadTaskWithRequest:request fromFile:[NSURL URLWithString: fileURI]];
        }

        uploadTask.taskDescription = customUploadId ? customUploadId : [NSString stringWithFormat:@"%i", thisUploadId];

        [uploadTask resume];
        resolve(uploadTask.taskDescription);
    }
     @catch (NSException *exception) {
        if(exception.reason)
            reject(@"RN Uploader", exception.reason, nil);
        else
            reject(@"RN Uploader", exception.name, nil);
    }
}

/*
 * Cancels file upload
 * Accepts upload ID as a first argument, this upload will be cancelled
 * Event "cancelled" will be fired when upload is cancelled.
 */
RCT_EXPORT_METHOD(cancelUpload: (NSString *)cancelUploadId resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject) {
    NSMutableArray<NSURLSession *> *sessions = [NSMutableArray array];
    if(_urlSession) [sessions addObject:_urlSession];
    if(_wifiOnlyUrlSession) [sessions addObject:_wifiOnlyUrlSession];

    for (NSURLSession *session in sessions) {
        dispatch_semaphore_t sema = dispatch_semaphore_create(0);
        [session getTasksWithCompletionHandler:^(NSArray *dataTasks, NSArray *uploadTasks, NSArray *downloadTasks) {
            for (NSURLSessionTask *uploadTask in uploadTasks) {
                if ([uploadTask.taskDescription isEqualToString:cancelUploadId]){
                    // == checks if references are equal, while isEqualToString checks the string value
                    [uploadTask cancel];
                }
            }
            dispatch_semaphore_signal(sema);
        }];
        dispatch_semaphore_wait(sema, DISPATCH_TIME_FOREVER);
    }

    resolve([NSNumber numberWithBool:YES]);
}



/*
 * Retrieve status of an upload task
 */
RCT_EXPORT_METHOD(getUploadStatus: (NSString *)uploadId resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject) {
    NSMutableArray<NSURLSession *> *sessions = [NSMutableArray array];
    if(_urlSession) [sessions addObject:_urlSession];
    if(_wifiOnlyUrlSession) [sessions addObject:_wifiOnlyUrlSession];

    __block Boolean resolved = false;


    for (NSURLSession *session in sessions) {
        dispatch_semaphore_t sema = dispatch_semaphore_create(0);
        [session getTasksWithCompletionHandler:^(NSArray *dataTasks, NSArray *uploadTasks, NSArray *downloadTasks) {

            for (NSURLSessionTask *uploadTask in uploadTasks) {
                if (![uploadTask.taskDescription isEqualToString:uploadId]) continue;
                NSDictionary *result = @{
                    @"state": [Helper urlSessionTaskStateToString:[uploadTask state]],
                    @"bytesSent": [NSNumber numberWithUnsignedLongLong:[uploadTask countOfBytesSent]],
                    @"totalBytes": [NSNumber numberWithUnsignedLongLong:[uploadTask countOfBytesExpectedToSend]],
                };
                resolve(result);
                resolved = true;
                break;
            }

            dispatch_semaphore_signal(sema);
        }];

        dispatch_semaphore_wait(sema, DISPATCH_TIME_FOREVER);
        if(resolved) return;
    }

    resolve(NULL);
}

/*
 * Light-speed file chunking method
 * Can chunk a 2GB file in under 3s
 */
RCT_EXPORT_METHOD(chunkFile: (NSString *)parentFilePath
                  chunks: (NSArray<NSDictionary *> *)chunks
                  resolve: (RCTPromiseResolveBlock)resolve
                  reject: (RCTPromiseRejectBlock)reject
                  ) {
    __block NSError *error;

    // Create a readonly mem map reference to the file.
    // This does not load the whole file into memory,
    // but converts the file into a memory region.
    NSData *parentFile = [NSData dataWithContentsOfFile:parentFilePath options:NSDataReadingMappedAlways error:&error];
    if(error) {
        reject(@"ChunkFile", @"Failed to get parent file", error);
        return;
    };

    dispatch_queue_t queue = dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0);
    dispatch_group_t group = dispatch_group_create();


    for(int i = 0; i < chunks.count; i++) {
        NSDictionary * chunk = chunks[i];
        double rangeStart = [[chunk objectForKey:@"position"] doubleValue];
        double rangeLength = [[chunk objectForKey:@"size"] doubleValue];
        NSString * chunkPath = [chunk objectForKey:@"path"];


        dispatch_group_async(group, queue, ^{
            // This also doesn't load the file content into memory
            NSData *chunk = [parentFile subdataWithRange:NSMakeRange(rangeStart, rangeLength)];
            [chunk writeToFile:chunkPath options:0 error:&error];
        });
    }

    dispatch_group_wait(group, DISPATCH_TIME_FOREVER);

    if(error) {
        reject(@"ChunkFile", @"Failed to chunk file", error);
        return;
    };

    resolve([NSNumber numberWithBool:YES]);
}


- (NSData *)createBodyWithBoundary:(NSString *)boundary
                              path:(NSString *)path
                        parameters:(NSDictionary *)parameters
                         fieldName:(NSString *)fieldName {

    NSMutableData *httpBody = [NSMutableData data];

    // Escape non latin characters in filename
    NSString *escapedPath = [path stringByAddingPercentEncodingWithAllowedCharacters: NSCharacterSet.URLQueryAllowedCharacterSet];

    // resolve path
    NSURL *fileUri = [NSURL URLWithString: escapedPath];

    NSError* error = nil;
    NSData *data = [NSData dataWithContentsOfURL:fileUri options:NSDataReadingMappedAlways error: &error];

    if (data == nil) {
        NSLog(@"Failed to read file %@", error);
    }

    NSString *filename  = [path lastPathComponent];
    NSString *mimetype  = [self guessMIMETypeFromFileName:path];

    [parameters enumerateKeysAndObjectsUsingBlock:^(NSString *parameterKey, NSString *parameterValue, BOOL *stop) {
        [httpBody appendData:[[NSString stringWithFormat:@"--%@\r\n", boundary] dataUsingEncoding:NSUTF8StringEncoding]];
        [httpBody appendData:[[NSString stringWithFormat:@"Content-Disposition: form-data; name=\"%@\"\r\n\r\n", parameterKey] dataUsingEncoding:NSUTF8StringEncoding]];
        [httpBody appendData:[[NSString stringWithFormat:@"%@\r\n", parameterValue] dataUsingEncoding:NSUTF8StringEncoding]];
    }];

    [httpBody appendData:[[NSString stringWithFormat:@"--%@\r\n", boundary] dataUsingEncoding:NSUTF8StringEncoding]];
    [httpBody appendData:[[NSString stringWithFormat:@"Content-Disposition: form-data; name=\"%@\"; filename=\"%@\"\r\n", fieldName, filename] dataUsingEncoding:NSUTF8StringEncoding]];
    [httpBody appendData:[[NSString stringWithFormat:@"Content-Type: %@\r\n\r\n", mimetype] dataUsingEncoding:NSUTF8StringEncoding]];
    [httpBody appendData:data];
    [httpBody appendData:[@"\r\n" dataUsingEncoding:NSUTF8StringEncoding]];

    [httpBody appendData:[[NSString stringWithFormat:@"--%@--\r\n", boundary] dataUsingEncoding:NSUTF8StringEncoding]];

    return httpBody;
}

- (NSURLSession *)urlSession {
    if (_urlSession) return _urlSession;

    NSURLSessionConfiguration *sessionConfiguration = [NSURLSessionConfiguration backgroundSessionConfigurationWithIdentifier:BACKGROUND_SESSION_ID];

    [sessionConfiguration setDiscretionary:NO];
    [sessionConfiguration setAllowsCellularAccess:YES];
    [sessionConfiguration setHTTPMaximumConnectionsPerHost:1];

    if (@available(iOS 11.0, *)) {
        [sessionConfiguration setWaitsForConnectivity:YES];
    }

    if (@available(iOS 13.0, *)) {
        [sessionConfiguration setAllowsConstrainedNetworkAccess:YES];
        [sessionConfiguration setAllowsExpensiveNetworkAccess:YES];
    }

    _urlSession = [NSURLSession sessionWithConfiguration:sessionConfiguration delegate:self delegateQueue:nil];

    return _urlSession;
}

- (NSURLSession *)wifiOnlyUrlSession {
    if (_wifiOnlyUrlSession) return _wifiOnlyUrlSession;

    NSURLSessionConfiguration *sessionConfiguration = [NSURLSessionConfiguration backgroundSessionConfigurationWithIdentifier:WIFI_ONLY_BACKGROUND_SESSION_ID];

    [sessionConfiguration setDiscretionary:NO];
    [sessionConfiguration setAllowsCellularAccess:NO];
    [sessionConfiguration setHTTPMaximumConnectionsPerHost:1];

    if (@available(iOS 11.0, *)) {
        [sessionConfiguration setWaitsForConnectivity:YES];
    }

    if (@available(iOS 13.0, *)) {
        [sessionConfiguration setAllowsConstrainedNetworkAccess:NO];
        [sessionConfiguration setAllowsExpensiveNetworkAccess:NO];
    }

    _wifiOnlyUrlSession = [NSURLSession sessionWithConfiguration:sessionConfiguration delegate:self delegateQueue:nil];

    return _wifiOnlyUrlSession;
}

#pragma NSURLSessionTaskDelegate

- (void)URLSession:(NSURLSession *)session
              task:(NSURLSessionTask *)task
didCompleteWithError:(NSError *)error {
    NSMutableDictionary *data = [NSMutableDictionary dictionaryWithObjectsAndKeys:task.taskDescription, @"id", nil];
    NSURLSessionDataTask *uploadTask = (NSURLSessionDataTask *)task;
    NSHTTPURLResponse *response = (NSHTTPURLResponse *)uploadTask.response;
    if (response != nil) {
        [data setObject:[NSNumber numberWithInteger:response.statusCode] forKey:@"responseCode"];
    }
    //Add data that was collected earlier by the didReceiveData method
    NSMutableData *responseData = _responsesData[@(task.taskIdentifier)];
    if (responseData) {
        [_responsesData removeObjectForKey:@(task.taskIdentifier)];
        NSString *response = [[NSString alloc] initWithData:responseData encoding:NSUTF8StringEncoding];
        [data setObject:response forKey:@"responseBody"];
    } else {
        [data setObject:[NSNull null] forKey:@"responseBody"];
    }

    if (error == nil) {
        [self sendEventWithName:@"RNFileUploader-completed" body:data];
    }
    else {
        [data setObject:error.localizedDescription forKey:@"error"];
        if (error.code == NSURLErrorCancelled) {
            [self sendEventWithName:@"RNFileUploader-cancelled" body:data];
        } else {
            [self sendEventWithName:@"RNFileUploader-error" body:data];
        }
    }
}

- (void)URLSession:(NSURLSession *)session
              task:(NSURLSessionTask *)task
   didSendBodyData:(int64_t)bytesSent
    totalBytesSent:(int64_t)totalBytesSent
totalBytesExpectedToSend:(int64_t)totalBytesExpectedToSend {
    float progress = -1;
    //see documentation.  For unknown size it's -1 (NSURLSessionTransferSizeUnknown)
    if (totalBytesExpectedToSend > 0) {
        progress = 100.0 * (float)totalBytesSent / (float)totalBytesExpectedToSend;
    }

    NSDictionary *data = @{
        @"id": task.taskDescription,
        @"progress": [NSNumber numberWithFloat:progress]
    };

    [self sendEventWithName:@"RNFileUploader-progress" body:data];
}

- (void)URLSession:(NSURLSession *)session dataTask:(NSURLSessionDataTask *)dataTask didReceiveData:(NSData *)data {
    if (!data.length) {
        return;
    }
    //Hold returned data so it can be picked up by the didCompleteWithError method later
    NSMutableData *responseData = _responsesData[@(dataTask.taskIdentifier)];
    if (!responseData) {
        responseData = [NSMutableData dataWithData:data];
        _responsesData[@(dataTask.taskIdentifier)] = responseData;
    } else {
        [responseData appendData:data];
    }
}

- (void)URLSession:(NSURLSession *)session
              task:(NSURLSessionTask *)task
 needNewBodyStream:(void (^)(NSInputStream *bodyStream))completionHandler {

    NSInputStream *inputStream = task.originalRequest.HTTPBodyStream;

    if (completionHandler) {
        completionHandler(inputStream);
    }
}

@end
