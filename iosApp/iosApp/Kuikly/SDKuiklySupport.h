#import <UIKit/UIKit.h>
#import <OpenKuiklyIOSRender/KRBaseModule.h>
#import <OpenKuiklyIOSRender/KuiklyRenderView.h>

NS_ASSUME_NONNULL_BEGIN

/// Swift 侧拿不到（或不便拿到）的 Kuikly ObjC 细节统一收在这里：
/// - `args[KR_CALLBACK_KEY]` 是一个 ObjC block，Swift 里 `as? KuiklyRenderCallback` 永远失败，必须在 ObjC 侧取出；
/// - `hr_rootView` 是协议里的 optional 属性（KRBaseModule 里 @synthesize），Swift 直接访问类型很别扭。
@interface SDKuiklyArgs : NSObject

/// Kuikly 侧传来的参数（本工程约定恒为 JSON 字符串；无参时为 nil）。args 为模块方法收到的 NSDictionary。
+ (nullable NSString *)paramStringFromArgs:(nullable id)args NS_SWIFT_NAME(paramString(_:));

/// Kuikly 侧传来的回调（没有回调时为 nil）。可在任意线程调用；常驻回调可多次调用。
+ (nullable KuiklyRenderCallback)callbackFromArgs:(nullable id)args NS_SWIFT_NAME(callback(_:));

@end

@interface KRBaseModule (SDSupport)

/// 模块所属页面的根渲染视图（页面销毁后为 nil）。
@property (nonatomic, readonly, nullable) KuiklyRenderView *sd_rootView;

/// 模块所属页面的容器控制器（仅主线程调用；页面销毁或未上屏时为 nil）。
@property (nonatomic, readonly, nullable) UIViewController *sd_viewController;

/// 模块所属页面的 pageName（取不到时为空串）。
@property (nonatomic, readonly, copy) NSString *sd_pageName;

@end

NS_ASSUME_NONNULL_END
