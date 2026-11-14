#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// 友盟统计（UMCommon 7.6.8 + UMDevice）的 ObjC 适配层。
///
/// SDK 调用全部在 .m 里（选择子对照参考工程 umeng_common_sdk 1.3.1 的 `UmengCommonSdkPlugin.m`），
/// 头文件只暴露 Foundation 类型。
///
/// 约定：`start` 之前不会触碰任何友盟 API（隐私合规），其余方法在未初始化时直接丢弃。
@interface SDUmengBridge : NSObject

/// 是否已经初始化。
@property (class, atomic, readonly) BOOL isStarted;

/// 初始化 + 手动页面统计模式。重复调用只生效一次；appKey 为空返回 NO。
+ (BOOL)startWithAppKey:(NSString *)appKey channel:(NSString *)channel
    NS_SWIFT_NAME(start(appKey:channel:));

/// 自定义事件。attributes 的值只接受 NSString / NSNumber，其它类型会被转成字符串。
+ (void)trackEvent:(NSString *)name attributes:(nullable NSDictionary<NSString *, id> *)attributes
    NS_SWIFT_NAME(track(event:attributes:));

+ (void)beginPage:(NSString *)name
    NS_SWIFT_NAME(beginPage(_:));

+ (void)endPage:(NSString *)name
    NS_SWIFT_NAME(endPage(_:));

+ (void)signInWithUserId:(NSString *)userId
    NS_SWIFT_NAME(signIn(userId:));

+ (void)signOff;

@end

NS_ASSUME_NONNULL_END
