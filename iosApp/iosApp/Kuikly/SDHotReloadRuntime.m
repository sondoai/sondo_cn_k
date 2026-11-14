#import "SDHotReloadRuntime.h"

#if DEBUG
#import <JavaScriptCore/JavaScriptCore.h>
#import <CommonCrypto/CommonDigest.h>

static NSError *SDJSError(NSString *message, NSString *stack) {
    return [NSError errorWithDomain:@"SondoHotReload" code:1
                          userInfo:@{NSLocalizedDescriptionKey:message ?: @"JavaScript 执行失败",
                                     @"stack":stack ?: @""}];
}

static id SDJSArgument(id object) {
    if ([object isKindOfClass:NSDictionary.class] || [object isKindOfClass:NSArray.class]) {
        NSData *data = [NSJSONSerialization dataWithJSONObject:object options:0 error:nil];
        return data ? [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] : @"";
    }
    return object ?: NSNull.null;
}

@interface SDHotReloadRuntime ()
@property (nonatomic, strong) JSContext *context;
@property (nonatomic, strong) JSValue *callKotlin;
@property (nonatomic, strong) JSValue *registerNative;
@property (nonatomic, strong) NSMutableDictionary<NSString *, SDHotReloadNativeCallback> *callbacks;
@property (nonatomic, strong, nullable) NSError *lastError;
@end

@implementation SDHotReloadRuntime

+ (NSString *)versionOfScript:(NSData *)script {
    unsigned char digest[CC_SHA256_DIGEST_LENGTH];
    CC_SHA256(script.bytes, (CC_LONG)script.length, digest);
    NSMutableString *key = [NSMutableString stringWithCapacity:64];
    for (NSUInteger i = 0; i < sizeof(digest); i++) [key appendFormat:@"%02x", digest[i]];
    return key;
}

+ (instancetype)runtimeWithScript:(NSData *)script expectedVersion:(NSString *)version error:(NSError **)error {
    if (![[self versionOfScript:script] isEqualToString:version]) {
        if (error) *error = SDJSError(@"iOS JS 产物的 SHA-256 与服务发布版本不符，已保留当前页面", nil);
        return nil;
    }
    return [self runtimeWithScript:script error:error];
}

+ (instancetype)runtimeWithScript:(NSData *)script error:(NSError **)error {
    static NSMapTable<NSString *, SDHotReloadRuntime *> *runtimes;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ runtimes = [NSMapTable strongToWeakObjectsMapTable]; });
    NSString *key = [self versionOfScript:script];
    @synchronized (runtimes) {
        SDHotReloadRuntime *runtime = [runtimes objectForKey:key];
        if (!runtime) {
            runtime = [[self alloc] initWithScript:script error:error];
            if (runtime) [runtimes setObject:runtime forKey:key];
        }
        return runtime;
    }
}

- (instancetype)initWithScript:(NSData *)script error:(NSError **)error {
    if (!(self = [super init])) return nil;
    NSString *source = [[NSString alloc] initWithData:script encoding:NSUTF8StringEncoding];
    if (source.length == 0) {
        if (error) *error = SDJSError(@"热重载 bundle 为空或不是 UTF-8", nil);
        return nil;
    }
    _callbacks = [NSMutableDictionary dictionary];
    _context = [JSContext new];
    __weak typeof(self) weakSelf = self;
    _context.exceptionHandler = ^(JSContext *context, JSValue *exception) {
        weakSelf.lastError = SDJSError(exception.toString, [exception[@"stack"] toString]);
    };
    _context[@"nativeLog"] = ^(id message) { NSLog(@"[Sondo][HotReload][JS] %@", message); };
    _context[@"callNative"] = ^id(NSInteger method, id pagerId, id a, id b, id c, id d, id e) {
        SDHotReloadRuntime *runtime = weakSelf;
        if (!runtime || ![pagerId isKindOfClass:NSString.class]) return nil;
        SDHotReloadNativeCallback callback = runtime.callbacks[pagerId];
        if (!callback) return nil;
        id result = callback(method, @[a ?: @"", b ?: @"", c ?: @"", d ?: @"", e ?: @""]);
        return SDJSArgument(result);
    };
    [_context evaluateScript:@"var window=globalThis;var self=globalThis;var global=globalThis;"
                              "var console={log:nativeLog,info:nativeLog,warn:nativeLog,error:nativeLog,debug:nativeLog};"];
    [_context evaluateScript:source withSourceURL:[NSURL URLWithString:@"sondo-hot-reload:///bundle.js"]];
    if (!_lastError) {
        JSValue *valid = [_context evaluateScript:
            @"typeof SondoHotReload==='object' && typeof SondoHotReload.callKotlinMethod==='function' && "
             "typeof SondoHotReload.com.tencent.kuikly.core.nvi.registerCallNative==='function'"];
        if (!valid.toBool && !_lastError) _lastError = SDJSError(@"bundle 缺少 Kuikly 的 JS 桥接导出", nil);
    }
    if (_lastError) {
        if (error) *error = _lastError;
        return nil;
    }
    _callKotlin = [_context evaluateScript:@"SondoHotReload.callKotlinMethod"];
    _registerNative = [_context evaluateScript:@"SondoHotReload.com.tencent.kuikly.core.nvi.registerCallNative"];
    return self;
}

- (BOOL)callMethod:(NSInteger)method arguments:(NSArray *)arguments
   nativeCallback:(SDHotReloadNativeCallback)callback error:(NSError **)error {
    @synchronized (self) {
        self.lastError = nil;
        self.context.exception = nil;
        NSString *pagerId = arguments.count && [arguments[0] isKindOfClass:NSString.class] ? arguments[0] : nil;
        if (pagerId && !self.callbacks[pagerId]) {
            self.callbacks[pagerId] = [callback copy];
            [self.registerNative callWithArguments:@[pagerId, self.context[@"callNative"]]];
        }
        NSMutableArray *params = [NSMutableArray arrayWithObject:@(method)];
        for (NSUInteger i = 0; i < 6; i++) {
            [params addObject:SDJSArgument(i < arguments.count ? arguments[i] : nil)];
        }
        if (!self.lastError) [self.callKotlin callWithArguments:params];
        if (self.lastError && error) *error = self.lastError;
        return self.lastError == nil;
    }
}

- (void)removePager:(NSString *)pagerId {
    @synchronized (self) {
        [self.callbacks removeObjectForKey:pagerId];
    }
}

@end
#endif
