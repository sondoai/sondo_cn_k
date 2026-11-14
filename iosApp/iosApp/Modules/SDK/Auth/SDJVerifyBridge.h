#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/// 极光认证（JVerification）回参：已经是给 Kuikly 的最终形状 `{ok, ...}`，Swift 侧原样透传。
typedef void (^SDJVerifyResultHandler)(NSDictionary<NSString *, id> *result);

/// 极光一键登录（JVerification 3.4.7）的 ObjC 适配层。
///
/// 第三方 SDK 的调用全部留在 .m 里（选择子逐个对照参考工程 `third_party/jverify/ios/Classes/JverifyPlugin.m`），
/// 头文件只暴露 Foundation / UIKit 类型，bridging header 不需要引入极光的头文件。
///
/// 约定：
/// - 所有方法都可在任意线程调用，内部切到主线程；回调一律在主线程；
/// - `setup` 之前不会触碰任何极光 API（隐私合规），其余方法在未 setup 时直接回 `not_setup`；
/// - `loginAuth` 的 completion 恰好回调一次。
@interface SDJVerifyBridge : NSObject

@property (class, nonatomic, readonly) SDJVerifyBridge *shared;

/// 是否已经调用过 `setup`。
@property (nonatomic, readonly) BOOL didSetup;

/// 用户点了协议第一行里的条款名（《使用条款》《隐私政策》…）。授权页留在原地，由上层决定怎么打开。
/// presenter 是当前授权页的控制器（取不到时为 nil）。主线程回调。
@property (nonatomic, copy, nullable) void (^privacyLinkHandler)(NSString *name, NSString *url, UIViewController *_Nullable presenter);

/// 初始化。→ `{ok, code, error?, timedOut?}`（8000 = 成功；15 秒没有回调按成功处理，与参考工程一致）。
- (void)setupWithAppKey:(NSString *)appKey
                channel:(NSString *)channel
                  debug:(BOOL)debug
              timeoutMs:(NSInteger)timeoutMs
             completion:(SDJVerifyResultHandler)completion
    NS_SWIFT_NAME(setup(appKey:channel:debug:timeoutMs:completion:));

/// 当前网络环境是否支持一键登录。→ `{ok, enable, operator?}`
- (void)checkVerifyEnableWithCompletion:(SDJVerifyResultHandler)completion
    NS_SWIFT_NAME(checkVerifyEnable(completion:));

/// 预取号。→ `{ok, code, error?}`（7000 = 成功）
- (void)preLoginWithTimeoutMs:(NSInteger)timeoutMs
                   completion:(SDJVerifyResultHandler)completion
    NS_SWIFT_NAME(preLogin(timeoutMs:completion:));

/// 拉起底部弹层样式的授权页。params 即 Kuikly 侧 `jverifyLoginAuth` 的 JSON：
/// `{title, subtitle?, loginButtonText, otherLoginText?, wechatLoginText?, privacyPrefix, privacySuffix,
///   privacySeparator?, privacyItems:[{name, url}], privacyUncheckedToast, timeoutMs}`
/// → `{ok, action:"token"|"wechat"|"sms"|"cancel"|"unsupported"|"error", token?, operator?, code?, error?, shown}`
- (void)loginAuthWithParams:(NSDictionary<NSString *, id> *)params
                  presenter:(nullable UIViewController *)presenter
                 completion:(SDJVerifyResultHandler)completion
    NS_SWIFT_NAME(loginAuth(params:presenter:completion:));

/// 关闭授权页；进行中的 `loginAuth` 以 `cancel` 结束。
- (void)dismiss;

@end

NS_ASSUME_NONNULL_END
