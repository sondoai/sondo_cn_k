#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/// 微信 OpenSDK（WechatOpenSDK-XCFramework）的 ObjC 适配层：只做授权登录，没有分享 / 支付 / 小程序。
///
/// SDK 调用全部在 .m 里（选择子对照参考工程 fluwx 6.0.2 的 `FluwxPlugin.m` / `FluwxDelegate.m`），
/// 头文件只暴露 Foundation / UIKit 类型。
///
/// 约定：`registerApp` 之前不会触碰任何微信 API（隐私合规）；除 `isWXAppInstalled` 外都只在主线程调用。
@interface SDWeChatBridge : NSObject

@property (class, nonatomic, readonly) SDWeChatBridge *shared;

/// 是否已成功 `registerApp`。
@property (atomic, readonly) BOOL isRegistered;

/// 已注册的 AppId（同时是回跳用的 URL scheme）；未注册时为空串。
@property (atomic, copy, readonly) NSString *appId;

/// 授权结果（`SendAuthResp`）。主线程回调。
@property (nonatomic, copy, nullable) void (^authResponseHandler)(NSInteger errCode, NSString *_Nullable code, NSString *_Nullable state);

/// 注册 AppId + Universal Link。成功后会补处理注册前缓存的回跳链接。
- (BOOL)registerAppId:(NSString *)appId universalLink:(NSString *)universalLink
    NS_SWIFT_NAME(register(appId:universalLink:));

/// 是否安装了微信（未注册时恒为 NO）。内部是 `canOpenURL`，尽量在主线程调用。
- (BOOL)isWXAppInstalled;

/// 拉起微信授权。completion 的参数表示是否成功拉起（主线程回调）。
- (void)sendAuthWithScope:(NSString *)scope state:(NSString *)state completion:(void (^)(BOOL launched))completion
    NS_SWIFT_NAME(sendAuth(scope:state:completion:));

/// 自定义 scheme 回跳（`application:openURL:` / `scene:openURLContexts:`）。未注册时先缓存微信的回跳。
- (BOOL)handleOpenURL:(NSURL *)url
    NS_SWIFT_NAME(handleOpen(url:));

/// Universal Link 回跳（`continueUserActivity`）。未注册时先缓存。
- (BOOL)handleUniversalLink:(NSUserActivity *)userActivity
    NS_SWIFT_NAME(handleUniversalLink(_:));

@end

NS_ASSUME_NONNULL_END
