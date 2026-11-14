#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/// 极光推送（JPush 6.2.6）的 ObjC 适配层。
///
/// SDK 调用全部在 .m 里（选择子对照参考工程 jpush_flutter 3.5.8 的 `JPushPlugin.m`），
/// 头文件只暴露 Foundation / UIKit 类型。
///
/// 约定：
/// - `setup` 之前不会触碰任何极光 API（隐私合规）；
/// - 前台收到远程推送一律不弹系统横幅（由 Kuikly 侧弹应用内提示）；
/// - 事件回调在主线程。
@interface SDJPushBridge : NSObject

@property (class, nonatomic, readonly) SDJPushBridge *shared;

/// 是否已经 `setup`。
@property (atomic, readonly) BOOL didSetup;

/// 事件：event ∈ `connected` / `notificationReceived` / `notificationOpened`，
/// payload 为 `{}`（connected）或 `{title, alert, extras}`。主线程回调。
@property (nonatomic, copy, nullable) void (^eventHandler)(NSString *event, NSDictionary<NSString *, id> *payload);

/// 先开启极光的采集授权（setAuth），再注册 APNs + 初始化极光。重复调用只生效一次。仅主线程调用。
/// deviceToken：setup 之前已经拿到的 APNs token（没有传 nil）。
/// 返回 NO 表示 appKey 为空、没有初始化。
- (BOOL)setupWithAppKey:(NSString *)appKey
                channel:(NSString *)channel
             production:(BOOL)production
                  debug:(BOOL)debug
          launchOptions:(nullable NSDictionary *)launchOptions
            deviceToken:(nullable NSData *)deviceToken
    NS_SWIFT_NAME(setup(appKey:channel:production:debug:launchOptions:deviceToken:));

/// 极光 Registration ID（未连上 / 未 setup 时为空串）。
- (NSString *)registrationID;

/// APNs device token 转交极光（未 setup 时忽略）。
- (void)registerDeviceToken:(NSData *)deviceToken
    NS_SWIFT_NAME(registerDeviceToken(_:));

/// `application:didReceiveRemoteNotification:fetchCompletionHandler:` 收到的推送，只做送达统计（未 setup 时忽略）。
- (void)handleRemoteNotification:(NSDictionary *)userInfo
    NS_SWIFT_NAME(handleRemoteNotification(_:));

/// 设置角标（应用图标 + 极光服务端；未 setup 时只改应用图标）。
- (void)setBadge:(NSInteger)count
    NS_SWIFT_NAME(setBadge(_:));

/// 取走「冷启动 / 极光初始化之前」被点开的那条通知 → `{title, alert, extras}`；没有返回 nil。读取后清空。
- (nullable NSDictionary<NSString *, id> *)takeLaunchNotification;

/// 把 `didFinishLaunchingWithOptions` 里的远程通知（非 scene 工程才有）记为启动通知。
- (void)noteLaunchRemoteNotification:(nullable NSDictionary *)userInfo
    NS_SWIFT_NAME(noteLaunchRemoteNotification(_:));

@end

NS_ASSUME_NONNULL_END
