#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/// 页面出现 / 消失 / 销毁时发出（主线程，object 为 nil）。SDPageStack 用它重新同步页面栈镜像。
/// Swift 里的名字是 `Notification.Name.SDKuiklyPageStackDidChange`。
FOUNDATION_EXTERN NSString *const SDKuiklyPageStackDidChangeNotification;

/// Kuikly 页面容器：一个控制器承载一个 Kuikly Pager（`@Page("name")`）。
///
/// - 向 Kuikly 注入宿主参数：`debug`(1/0)、`apiEnv`、`showTokenEditor`(1/0)、`appVersionName`、`appVersionCode`；
/// - 转发 viewWillAppear / viewDidAppear / viewWillDisappear / viewDidDisappear 给 Kuikly delegator；
/// - 页面转场由 pageData 的 `sdTransition`（"bottom" | "fade" | "none"）与 `sdTranslucent`（1 = 透明模态）决定，
///   具体动画在 SDNavigationController / SDPageStack 里实现；
/// - 系统返回（侧滑）经 `handleSystemBack` 先交给 Kuikly 的 BackPressHandler。
@interface KuiklyViewController : UIViewController

- (instancetype)initWithPageName:(NSString *)pageName pageData:(nullable NSDictionary *)pageData;

/// Kotlin 侧 `@Page("xxx")` 的名字。
@property (nonatomic, copy, readonly) NSString *pageName;

/// 打开页面时传入的原始参数（不含宿主注入的字段）。
@property (nonatomic, copy, readonly) NSDictionary *pageData;

/// 入场转场："bottom" | "fade" | "none"，默认空串（系统 push 动画）。
@property (nonatomic, copy, readonly) NSString *sdTransition;

/// 是否透明背景页（以 overFullScreen 模态盖在上一页之上）。
@property (nonatomic, assign, readonly) BOOL sdTranslucent;

/// 是否允许系统侧滑关闭本页（SDNavModule.setSwipeBackEnabled）。默认 YES。
@property (nonatomic, assign) BOOL swipeBackEnabled;

/// 状态栏样式（SDAppModule.setStatusBarStyle）。默认 UIStatusBarStyleLightContent。
@property (nonatomic, assign) UIStatusBarStyle statusBarStyle;

/// 是否被一个透明模态页盖住。盖住期间向 Kuikly 发送「页面不可见」，与 Android 透明 Activity 行为一致。
@property (nonatomic, assign) BOOL coveredByTranslucentPage;

/// 系统返回手势：先询问 Kuikly（BackPressHandler），未被消费且允许侧滑时关闭本页。
- (void)handleSystemBack;

/// 向 Kotlin Pager 发送事件（pager.addPagerEventObserver 接收）。
- (void)sendEvent:(NSString *)event data:(nullable NSDictionary *)data;

@end

NS_ASSUME_NONNULL_END
