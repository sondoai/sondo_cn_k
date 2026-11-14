#import "SDWeChatBridge.h"

// 与参考工程 fluwx 6.0.2（FluwxPlugin.m）相同的引入方式。
#if __has_include(<WechatOpenSDK/WXApi.h>)
#import <WechatOpenSDK/WXApi.h>
#import <WechatOpenSDK/WXApiObject.h>
#elif __has_include("WXApi.h")
#import "WXApi.h"
#import "WXApiObject.h"
#endif

@interface SDWeChatBridge () <WXApiDelegate>

@property (atomic, assign, readwrite) BOOL isRegistered;
@property (atomic, copy, readwrite) NSString *appId;
@property (nonatomic, copy) NSString *universalLink;

/// 注册前收到的微信回跳，注册成功后补处理（各只留最近一个）。
@property (nonatomic, strong, nullable) NSURL *cachedOpenURL;
@property (nonatomic, strong, nullable) NSUserActivity *cachedUserActivity;

@end

@implementation SDWeChatBridge

+ (SDWeChatBridge *)shared {
    static SDWeChatBridge *instance;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        instance = [[SDWeChatBridge alloc] init];
    });
    return instance;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _appId = @"";
        _universalLink = @"";
    }
    return self;
}

#pragma mark - 注册

- (BOOL)registerAppId:(NSString *)appId universalLink:(NSString *)universalLink {
    if (appId.length == 0 || universalLink.length == 0) {
        return NO;
    }
    if (self.isRegistered && [self.appId isEqualToString:appId] && [self.universalLink isEqualToString:universalLink]) {
        return YES;
    }
    BOOL registered = [WXApi registerApp:appId universalLink:universalLink];
    if (!registered) {
        return NO;
    }
    self.appId = appId;
    self.universalLink = universalLink;
    self.isRegistered = YES;

    // 注册成功后补处理之前缓存的回跳（文档：未注册时 handleOpenURL 可能失败）。
    NSURL *url = self.cachedOpenURL;
    self.cachedOpenURL = nil;
    if (url != nil && [self isWeChatCallbackURL:url]) {
        [WXApi handleOpenURL:url delegate:self];
    }
    NSUserActivity *activity = self.cachedUserActivity;
    self.cachedUserActivity = nil;
    if (activity != nil && [self isWeChatUniversalLink:activity]) {
        [WXApi handleOpenUniversalLink:activity delegate:self];
    }
    return YES;
}

- (BOOL)isWXAppInstalled {
    if (!self.isRegistered) {
        return NO;
    }
    return [WXApi isWXAppInstalled];
}

#pragma mark - 授权

- (void)sendAuthWithScope:(NSString *)scope state:(NSString *)state completion:(void (^)(BOOL))completion {
    if (!self.isRegistered) {
        completion(NO);
        return;
    }
    SendAuthReq *req = [[SendAuthReq alloc] init];
    req.scope = scope.length > 0 ? scope : @"snsapi_userinfo";
    req.state = state;
    [WXApi sendReq:req completion:^(BOOL success) {
        if ([NSThread isMainThread]) {
            completion(success);
        } else {
            dispatch_async(dispatch_get_main_queue(), ^{
                completion(success);
            });
        }
    }];
}

#pragma mark - 回跳

- (BOOL)handleOpenURL:(NSURL *)url {
    if (!self.isRegistered) {
        // 还不知道 AppId：微信的 scheme 都是 wx 开头，先留着，注册后再判断。
        if ([url.scheme.lowercaseString hasPrefix:@"wx"]) {
            self.cachedOpenURL = url;
        }
        return NO;
    }
    if (![self isWeChatCallbackURL:url]) {
        return NO;
    }
    return [WXApi handleOpenURL:url delegate:self];
}

- (BOOL)handleUniversalLink:(NSUserActivity *)userActivity {
    if (![userActivity.activityType isEqualToString:NSUserActivityTypeBrowsingWeb] || userActivity.webpageURL == nil) {
        return NO;
    }
    if (!self.isRegistered) {
        self.cachedUserActivity = userActivity;
        return NO;
    }
    if (![self isWeChatUniversalLink:userActivity]) {
        return NO;
    }
    return [WXApi handleOpenUniversalLink:userActivity delegate:self];
}

/// 自定义 scheme 回跳：scheme 等于 AppId。
- (BOOL)isWeChatCallbackURL:(NSURL *)url {
    NSString *scheme = url.scheme;
    return scheme.length > 0 && self.appId.length > 0 && [scheme caseInsensitiveCompare:self.appId] == NSOrderedSame;
}

/// Universal Link 回跳：以注册的 universalLink 开头（https://go.sondo.vip/wechat/…）。
- (BOOL)isWeChatUniversalLink:(NSUserActivity *)userActivity {
    NSString *link = userActivity.webpageURL.absoluteString;
    return link.length > 0 && self.universalLink.length > 0 && [link hasPrefix:self.universalLink];
}

#pragma mark - WXApiDelegate

- (void)onReq:(BaseReq *)req {
    // 只做授权登录，不处理微信主动发起的请求。
}

- (void)onResp:(BaseResp *)resp {
    if (![resp isKindOfClass:[SendAuthResp class]]) {
        return;
    }
    SendAuthResp *authResp = (SendAuthResp *)resp;
    NSInteger errCode = authResp.errCode;
    NSString *code = authResp.code;
    NSString *state = authResp.state;
    void (^handler)(NSInteger, NSString *, NSString *) = self.authResponseHandler;
    if (handler == nil) {
        return;
    }
    if ([NSThread isMainThread]) {
        handler(errCode, code, state);
    } else {
        dispatch_async(dispatch_get_main_queue(), ^{
            handler(errCode, code, state);
        });
    }
}

@end
