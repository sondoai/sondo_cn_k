#import "SDJPushBridge.h"
#import <UserNotifications/UserNotifications.h>

// 与参考工程 jpush_flutter 3.5.8（JPushPlugin.m）相同的引入方式（JPush / JCore 的公开头文件）。
#if __has_include("JPUSHService.h")
#import "JPUSHService.h"
#elif __has_include(<JPush/JPUSHService.h>)
#import <JPush/JPUSHService.h>
#endif
#if __has_include("JGInforCollectionAuth.h")
#import "JGInforCollectionAuth.h"
#elif __has_include(<JCore/JGInforCollectionAuth.h>)
#import <JCore/JGInforCollectionAuth.h>
#endif

#pragma mark - 通知内容整理

/// 转成 NSJSONSerialization 能序列化的值。
static id SDJPJSONSafe(id value) {
    if (value == nil || value == [NSNull null]) {
        return [NSNull null];
    }
    if ([value isKindOfClass:[NSString class]]) {
        return value;
    }
    if ([value isKindOfClass:[NSNumber class]]) {
        double number = [(NSNumber *)value doubleValue];
        if (isnan(number) || isinf(number)) {
            return @0;
        }
        return value;
    }
    if ([value isKindOfClass:[NSDictionary class]]) {
        NSMutableDictionary<NSString *, id> *result = [NSMutableDictionary dictionary];
        [(NSDictionary *)value enumerateKeysAndObjectsUsingBlock:^(id key, id obj, BOOL *stop) {
            NSString *name = [key isKindOfClass:[NSString class]] ? (NSString *)key : [key description];
            if (name.length > 0) {
                result[name] = SDJPJSONSafe(obj);
            }
        }];
        return result;
    }
    if ([value isKindOfClass:[NSArray class]]) {
        NSMutableArray *result = [NSMutableArray array];
        for (id item in (NSArray *)value) {
            [result addObject:SDJPJSONSafe(item)];
        }
        return result;
    }
    if ([value isKindOfClass:[NSDate class]]) {
        return @((long long)llround([(NSDate *)value timeIntervalSince1970] * 1000.0));
    }
    if ([value isKindOfClass:[NSData class]]) {
        return [(NSData *)value base64EncodedStringWithOptions:0];
    }
    return [value description] ?: @"";
}

/// APNs userInfo → `{title, alert, extras}`。
/// extras = userInfo 去掉 `aps` 和极光自己的字段（`_j_*`、`actionIdentifier`），业务字段（jumpAddress / jumpParams …）原样保留。
static NSDictionary<NSString *, id> *SDJPPayload(NSDictionary *userInfo, NSString *title, NSString *body) {
    NSString *resolvedTitle = title ?: @"";
    NSString *resolvedBody = body ?: @"";

    id aps = userInfo[@"aps"];
    if ([aps isKindOfClass:[NSDictionary class]]) {
        id alert = ((NSDictionary *)aps)[@"alert"];
        if ([alert isKindOfClass:[NSString class]]) {
            if (resolvedBody.length == 0) {
                resolvedBody = (NSString *)alert;
            }
        } else if ([alert isKindOfClass:[NSDictionary class]]) {
            id alertTitle = ((NSDictionary *)alert)[@"title"];
            id alertBody = ((NSDictionary *)alert)[@"body"];
            if (resolvedTitle.length == 0 && [alertTitle isKindOfClass:[NSString class]]) {
                resolvedTitle = (NSString *)alertTitle;
            }
            if (resolvedBody.length == 0 && [alertBody isKindOfClass:[NSString class]]) {
                resolvedBody = (NSString *)alertBody;
            }
        }
    }

    NSMutableDictionary<NSString *, id> *extras = [NSMutableDictionary dictionary];
    [userInfo enumerateKeysAndObjectsUsingBlock:^(id key, id obj, BOOL *stop) {
        if (![key isKindOfClass:[NSString class]]) {
            return;
        }
        NSString *name = (NSString *)key;
        if ([name isEqualToString:@"aps"] || [name isEqualToString:@"actionIdentifier"] || [name hasPrefix:@"_j_"]) {
            return;
        }
        extras[name] = SDJPJSONSafe(obj);
    }];

    NSMutableDictionary<NSString *, id> *payload = [NSMutableDictionary dictionary];
    payload[@"title"] = resolvedTitle;
    payload[@"alert"] = resolvedBody;
    payload[@"extras"] = extras;
    id messageId = userInfo[@"_j_msgid"];
    if ([messageId isKindOfClass:[NSString class]] || [messageId isKindOfClass:[NSNumber class]]) {
        payload[@"msgId"] = [NSString stringWithFormat:@"%@", messageId];
    }
    return payload;
}

#pragma mark - SDJPushBridge

@interface SDJPushBridge () <JPUSHRegisterDelegate>

@property (atomic, assign, readwrite) BOOL didSetup;
/// 冷启动 / 极光初始化之前被点开的通知，等 Kuikly 侧来取。
@property (nonatomic, copy, nullable) NSDictionary<NSString *, id> *launchNotification;

- (UNNotificationPresentationOptions)sd_presentationOptionsForNotification:(UNNotification *)notification;
- (void)sd_handleNotificationResponse:(UNNotificationResponse *)response;

@end

#pragma mark - 极光初始化之前的通知点击

/// 极光要等用户同意隐私政策、授予通知权限之后才初始化，而「点通知冷启动」的那次点击只会在启动阶段投递一次：
/// 这里用一个不依赖任何三方 SDK 的系统 delegate 把它接住，存进 `SDJPushBridge`，
/// 之后由 Kuikly 侧通过 `getLaunchNotification` 取走。极光 `setup` 时会接管通知中心的 delegate。
@interface SDLaunchNotificationCatcher : NSObject <UNUserNotificationCenterDelegate>
@end

@implementation SDLaunchNotificationCatcher

+ (instancetype)sharedCatcher {
    static SDLaunchNotificationCatcher *instance;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        instance = [[SDLaunchNotificationCatcher alloc] init];
    });
    return instance;
}

+ (void)load {
    [[NSNotificationCenter defaultCenter] addObserverForName:UIApplicationDidFinishLaunchingNotification
                                                      object:nil
                                                       queue:nil
                                                  usingBlock:^(NSNotification *note) {
        // 非 scene 工程：launchOptions 里直接带着被点开的远程通知。
        id remote = note.userInfo[UIApplicationLaunchOptionsRemoteNotificationKey];
        if ([remote isKindOfClass:[NSDictionary class]]) {
            [[SDJPushBridge shared] noteLaunchRemoteNotification:(NSDictionary *)remote];
        }
        // scene 工程：点击通过 UNUserNotificationCenter 的 delegate 投递，必须在启动阶段就装上。
        // 宿主已经装了自己的 delegate 就不抢。
        UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
        if (center.delegate == nil) {
            center.delegate = [SDLaunchNotificationCatcher sharedCatcher];
        }
    }];
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
       willPresentNotification:(UNNotification *)notification
         withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completionHandler {
    completionHandler([[SDJPushBridge shared] sd_presentationOptionsForNotification:notification]);
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
didReceiveNotificationResponse:(UNNotificationResponse *)response
         withCompletionHandler:(void (^)(void))completionHandler {
    [[SDJPushBridge shared] sd_handleNotificationResponse:response];
    completionHandler();
}

@end

@implementation SDJPushBridge

+ (SDJPushBridge *)shared {
    static SDJPushBridge *instance;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        instance = [[SDJPushBridge alloc] init];
    });
    return instance;
}

#pragma mark - 初始化

- (BOOL)setupWithAppKey:(NSString *)appKey
                channel:(NSString *)channel
             production:(BOOL)production
                  debug:(BOOL)debug
          launchOptions:(nullable NSDictionary *)launchOptions
            deviceToken:(nullable NSData *)deviceToken {
    NSAssert([NSThread isMainThread], @"SDJPushBridge setup must run on the main thread");
    if (self.didSetup) {
        return YES;
    }
    NSCharacterSet *blank = [NSCharacterSet whitespaceAndNewlineCharacterSet];
    NSString *key = [appKey stringByTrimmingCharactersInSet:blank];
    if (key.length == 0) {
        return NO;
    }
    NSString *channelName = [channel stringByTrimmingCharactersInSet:blank];
    if (channelName.length == 0) {
        channelName = @"apple";
    }
    self.didSetup = YES;

    // 合规：极光要求先 setAuth 再 setup。
    [JGInforCollectionAuth JCollectionAuth:^(JGInforCollectionAuthItems * _Nonnull authInfo) {
        authInfo.isAuth = YES;
    }];

    if (debug) {
        [JPUSHService setDebugMode];
    } else {
        [JPUSHService setLogOFF];
    }

    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(sd_networkDidLogin:)
                                                 name:kJPFNetworkDidLoginNotification
                                               object:nil];

    // 注册 APNs，并由极光接管通知中心的 delegate（回调见下方 JPUSHRegisterDelegate）。
    JPUSHRegisterEntity *entity = [[JPUSHRegisterEntity alloc] init];
    entity.types = JPAuthorizationOptionAlert | JPAuthorizationOptionBadge | JPAuthorizationOptionSound;
    [JPUSHService registerForRemoteNotificationConfig:entity delegate:self];

    [JPUSHService setupWithOption:launchOptions
                           appKey:key
                          channel:channelName
                 apsForProduction:production];

    if (deviceToken.length > 0) {
        [JPUSHService registerDeviceToken:deviceToken];
    }
    [[UIApplication sharedApplication] registerForRemoteNotifications];
    return YES;
}

- (void)sd_networkDidLogin:(NSNotification *)notification {
    [self sd_emit:@"connected" payload:@{}];
}

#pragma mark - 对外接口

- (NSString *)registrationID {
    if (!self.didSetup) {
        return @"";
    }
    NSString *rid = [JPUSHService registrationID];
    return [rid isKindOfClass:[NSString class]] ? rid : @"";
}

- (void)registerDeviceToken:(NSData *)deviceToken {
    if (!self.didSetup || deviceToken.length == 0) {
        return;
    }
    [JPUSHService registerDeviceToken:deviceToken];
}

- (void)handleRemoteNotification:(NSDictionary *)userInfo {
    if (!self.didSetup || ![userInfo isKindOfClass:[NSDictionary class]]) {
        return;
    }
    [JPUSHService handleRemoteNotification:userInfo];
}

- (void)setBadge:(NSInteger)count {
    NSInteger badge = MAX(0, count);
    dispatch_block_t work = ^{
        [UIApplication sharedApplication].applicationIconBadgeNumber = badge;
        if (self.didSetup) {
            [JPUSHService setBadge:badge];
        }
    };
    if ([NSThread isMainThread]) {
        work();
    } else {
        dispatch_async(dispatch_get_main_queue(), work);
    }
}

- (nullable NSDictionary<NSString *, id> *)takeLaunchNotification {
    @synchronized (self) {
        NSDictionary<NSString *, id> *payload = _launchNotification;
        _launchNotification = nil;
        return payload;
    }
}

- (void)noteLaunchRemoteNotification:(nullable NSDictionary *)userInfo {
    if (![userInfo isKindOfClass:[NSDictionary class]] || userInfo.count == 0) {
        return;
    }
    NSDictionary<NSString *, id> *payload = SDJPPayload(userInfo, nil, nil);
    @synchronized (self) {
        if (_launchNotification == nil) {
            _launchNotification = [payload copy];
        }
    }
}

#pragma mark - 通知处理（极光 delegate 与启动阶段的系统 delegate 共用）

/// 前台收到通知时怎么展示：远程推送不弹系统横幅（Kuikly 侧弹应用内提示），本地通知照常展示。
- (UNNotificationPresentationOptions)sd_presentationOptionsForNotification:(UNNotification *)notification {
    UNNotificationRequest *request = notification.request;
    if (![request.trigger isKindOfClass:[UNPushNotificationTrigger class]]) {
        return UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionList | UNNotificationPresentationOptionSound;
    }
    NSDictionary *userInfo = request.content.userInfo ?: @{};
    if (self.didSetup) {
        [JPUSHService handleRemoteNotification:userInfo];
        [self sd_emit:@"notificationReceived" payload:SDJPPayload(userInfo, request.content.title, request.content.body)];
    }
    return UNNotificationPresentationOptionNone;
}

/// 用户点开了通知。
- (void)sd_handleNotificationResponse:(UNNotificationResponse *)response {
    if ([response.actionIdentifier isEqualToString:UNNotificationDismissActionIdentifier]) {
        return;
    }
    UNNotificationRequest *request = response.notification.request;
    if (![request.trigger isKindOfClass:[UNPushNotificationTrigger class]]) {
        return;
    }
    NSDictionary *userInfo = request.content.userInfo ?: @{};
    NSDictionary<NSString *, id> *payload = SDJPPayload(userInfo, request.content.title, request.content.body);
    if (self.didSetup) {
        [JPUSHService handleRemoteNotification:userInfo];
        [self sd_emit:@"notificationOpened" payload:payload];
    } else {
        // 极光还没初始化（冷启动点通知、或还没同意隐私政策）：留给 getLaunchNotification。
        @synchronized (self) {
            _launchNotification = [payload copy];
        }
    }
}

- (void)sd_emit:(NSString *)event payload:(NSDictionary<NSString *, id> *)payload {
    dispatch_block_t work = ^{
        void (^handler)(NSString *, NSDictionary<NSString *, id> *) = self.eventHandler;
        if (handler != nil) {
            handler(event, payload);
        }
    };
    if ([NSThread isMainThread]) {
        work();
    } else {
        dispatch_async(dispatch_get_main_queue(), work);
    }
}

#pragma mark - JPUSHRegisterDelegate

- (void)jpushNotificationCenter:(UNUserNotificationCenter *)center
        willPresentNotification:(UNNotification *)notification
          withCompletionHandler:(void (^)(NSInteger))completionHandler {
    completionHandler((NSInteger)[self sd_presentationOptionsForNotification:notification]);
}

- (void)jpushNotificationCenter:(UNUserNotificationCenter *)center
 didReceiveNotificationResponse:(UNNotificationResponse *)response
          withCompletionHandler:(void (^)(void))completionHandler {
    [self sd_handleNotificationResponse:response];
    completionHandler();
}

- (void)jpushNotificationCenter:(UNUserNotificationCenter *)center
    openSettingsForNotification:(UNNotification *)notification {
    // 没有应用内的通知设置页。
}

- (void)jpushNotificationAuthorization:(JPAuthorizationStatus)status withInfo:(NSDictionary *)info {
    // 通知权限由 SDPermissionModule 管，这里不处理。
}

@end
