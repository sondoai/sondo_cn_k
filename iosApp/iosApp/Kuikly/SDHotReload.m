#import "SDHotReload.h"

#if DEBUG
#import "SDHotReloadRuntime.h"
#import <OpenKuiklyIOSRender/KuiklyContextParam.h>
#import <UIKit/UIKit.h>

NSNotificationName const SDHotReloadDidUpdateNotification = @"SDHotReloadDidUpdateNotification";
static const NSInteger SDHotReloadMode = 2;

@interface SDHotReloadContext : NSObject <KuiklyRenderContextProtocol>
@property (atomic, assign) BOOL isDestroying;
@property (nonatomic, strong) SDHotReloadRuntime *runtime;
@property (nonatomic, copy) NSString *pagerId;
@property (nonatomic, copy) KuiklyRenderNativeMethodCallback nativeCallback;
@property (nonatomic, copy) OnUnhandledExceptionBlock onExceptionBlock;
@property (nonatomic, strong) NSError *loadError;
@end

@implementation SDHotReloadContext
- (instancetype)initWithContext:(id)contextCode contextParam:(KuiklyContextParam *)contextParam {
    if ((self = [super init])) {
        NSError *error;
        _runtime = [SDHotReloadRuntime runtimeWithScript:contextCode error:&error];
        _loadError = error;
    }
    return self;
}
- (void)registerCallNativeWtihCallback:(KuiklyRenderNativeMethodCallback)callback {
    self.nativeCallback = callback;
}
- (void)callWithMethod:(KuiklyRenderContextMethod)method args:(NSArray *)args {
    if (self.isDestroying && (method == KuiklyRenderContextMethodFireCallback ||
                             method == KuiklyRenderContextMethodLayoutView)) return;
    if (args.count && [args[0] isKindOfClass:NSString.class]) self.pagerId = args[0];
    __weak typeof(self) weakSelf = self;
    NSError *error = self.loadError;
    if (!error) {
        [self.runtime callMethod:method arguments:args nativeCallback:^id(NSInteger nativeMethod, NSArray *nativeArgs) {
            SDHotReloadContext *context = weakSelf;
            return context.nativeCallback ? context.nativeCallback(nativeMethod, nativeArgs) : nil;
        } error:&error];
    }
    if (error) {
        NSLog(@"[Sondo][HotReload] %@", error);
        if (self.onExceptionBlock) self.onExceptionBlock(error.localizedDescription, error.userInfo[@"stack"] ?: @"", SDHotReloadMode);
    }
}
- (void)willDestroy {
    self.isDestroying = YES;
    if (self.pagerId) [self.runtime removePager:self.pagerId];
    self.nativeCallback = nil;
    self.runtime = nil;
}
@end

@interface SDHotReloadContextMode : KuiklyBaseContextMode
@end
@implementation SDHotReloadContextMode
- (instancetype)init {
    if ((self = [super initFrameworkMode])) self.modeId = SDHotReloadMode;
    return self;
}
- (id<KuiklyRenderContextProtocol>)createContextHandlerWithContextCode:(id)code contextParam:(KuiklyContextParam *)param {
    return [[SDHotReloadContext alloc] initWithContext:code contextParam:param];
}
@end

@implementation SDHotReloadDelegator
- (KuiklyBaseContextMode *)createContextMode:(id)contextCode {
    return [contextCode isKindOfClass:NSData.class] ? [SDHotReloadContextMode new] : [super createContextMode:contextCode];
}
@end

@interface SDHotReload ()
@property (nonatomic, strong) NSURL *baseURL;
@property (nonatomic, strong) NSData *script;
@property (nonatomic, strong) SDHotReloadRuntime *validatedRuntime;
@property (nonatomic, copy) NSString *version;
@property (nonatomic, strong) NSTimer *timer;
@property (nonatomic, strong) NSMutableArray *waiters;
@property (nonatomic, assign) BOOL fetching;
@property (nonatomic, copy) NSString *lastErrorMessage;
@end

@implementation SDHotReload
+ (instancetype)shared {
    static SDHotReload *instance;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ instance = [SDHotReload new]; });
    return instance;
}
- (instancetype)init {
    if (!(self = [super init])) return nil;
    NSString *address = NSProcessInfo.processInfo.environment[@"SONDO_HOT_RELOAD_URL"];
    if (!address.length) address = [NSBundle.mainBundle objectForInfoDictionaryKey:@"SDHotReloadURL"];
    if ([address isKindOfClass:NSString.class]) {
        NSURL *url = [NSURL URLWithString:address];
        if (([url.scheme isEqual:@"http"] || [url.scheme isEqual:@"https"]) && url.host.length && !url.query && !url.fragment) {
            _baseURL = url;
        }
    }
    _waiters = [NSMutableArray array];
    return self;
}
- (BOOL)enabled { return self.baseURL != nil; }
- (void)fetchScript:(void (^)(NSData *, NSError *))completion {
    NSAssert(NSThread.isMainThread, @"HotReload 客户端需在主线程使用");
    if (self.script) { completion(self.script, nil); return; }
    [self.waiters addObject:[completion copy]];
    if (!self.timer) {
        __weak typeof(self) weakSelf = self;
        self.timer = [NSTimer timerWithTimeInterval:1 repeats:YES block:^(NSTimer *timer) { [weakSelf poll]; }];
        [NSRunLoop.mainRunLoop addTimer:self.timer forMode:NSRunLoopCommonModes];
    }
    [self poll];
}
- (void)finishWithError:(NSError *)error {
    self.fetching = NO;
    if (error && ![self.lastErrorMessage isEqual:error.localizedDescription]) {
        NSLog(@"[Sondo][HotReload] %@", error.localizedDescription);
        self.lastErrorMessage = error.localizedDescription;
    }
    NSArray *waiters = [self.waiters copy];
    [self.waiters removeAllObjects];
    for (void (^completion)(NSData *, NSError *) in waiters) completion(self.script, error);
}
- (void)request:(NSURL *)url completion:(void (^)(NSData *, NSError *))completion {
    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url
                                                         cachePolicy:NSURLRequestReloadIgnoringLocalCacheData timeoutInterval:5];
    [request setValue:@"no-cache" forHTTPHeaderField:@"Cache-Control"];
    [[NSURLSession.sharedSession dataTaskWithRequest:request completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
        if (!error && (![response isKindOfClass:NSHTTPURLResponse.class] || ((NSHTTPURLResponse *)response).statusCode != 200)) {
            error = [NSError errorWithDomain:@"SondoHotReload" code:2 userInfo:@{NSLocalizedDescriptionKey:@"热重载服务尚未提供可用产物"}];
        }
        dispatch_async(dispatch_get_main_queue(), ^{ completion(data, error); });
    }] resume];
}
- (void)poll {
    if (!self.enabled || self.fetching || UIApplication.sharedApplication.applicationState == UIApplicationStateBackground) return;
    self.fetching = YES;
    [self request:[self.baseURL URLByAppendingPathComponent:@"version"] completion:^(NSData *data, NSError *error) {
        NSDictionary *info = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
        NSString *version = [info isKindOfClass:NSDictionary.class] && [info[@"iosVersion"] isKindOfClass:NSString.class] ? info[@"iosVersion"] : nil;
        if (error || version.length == 0) {
            [self finishWithError:error ?: [NSError errorWithDomain:@"SondoHotReload" code:3 userInfo:@{NSLocalizedDescriptionKey:@"等待 iOS JS 产物编译成功"}]];
            return;
        }
        if ([version isEqual:self.version]) { [self finishWithError:nil]; return; }
        NSURLComponents *url = [NSURLComponents componentsWithURL:[self.baseURL URLByAppendingPathComponent:@"ios/bundle.js"] resolvingAgainstBaseURL:NO];
        url.queryItems = @[[NSURLQueryItem queryItemWithName:@"version" value:version]];
        [self request:url.URL completion:^(NSData *script, NSError *downloadError) {
            NSError *validationError = downloadError;
            SDHotReloadRuntime *runtime = script && !downloadError ? [SDHotReloadRuntime runtimeWithScript:script expectedVersion:version error:&validationError] : nil;
            if (!runtime) {
                [self finishWithError:validationError ?: [NSError errorWithDomain:@"SondoHotReload" code:4
                    userInfo:@{NSLocalizedDescriptionKey:@"热重载服务返回了空的 iOS JS 产物"}]];
                return;
            }
            BOOL reloadExisting = self.script != nil || self.waiters.count == 0;
            self.script = script;
            self.validatedRuntime = runtime;
            self.version = version;
            self.lastErrorMessage = nil;
            [self finishWithError:nil];
            if (reloadExisting) {
                // 根页面先创建，让 AppHost 等跨页面服务在其他页面重建前完成注册。
                for (NSNumber *rootOnly in @[@YES, @NO]) {
                    [NSNotificationCenter.defaultCenter postNotificationName:SDHotReloadDidUpdateNotification
                                                                      object:self userInfo:@{@"rootOnly":rootOnly}];
                }
            }
            NSLog(@"[Sondo][HotReload] 已加载 iOS JS 版本 %@（进程未重启，页面状态重建）", version);
        }];
    }];
}
@end
#endif
