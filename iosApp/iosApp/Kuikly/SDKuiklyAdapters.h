#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/// 页面路由的实际执行者（Swift 的 SDPageStack 实现）。KRRouterModule 的 open / close 最终落到这里。
@protocol SDPageRouting <NSObject>

/// 打开一个 Kuikly 页面。controller 为发起页面的容器（可能为 nil）。任意线程。
- (void)openPageWithName:(NSString *)pageName
                pageData:(nullable NSDictionary *)pageData
          fromController:(nullable UIViewController *)controller NS_SWIFT_NAME(openPage(name:pageData:from:));

/// 关闭 controller 对应的页面。任意线程。
- (void)closePageOfController:(nullable UIViewController *)controller NS_SWIFT_NAME(closePage(of:));

@end

/// Kuikly 渲染器要求宿主提供的适配器（图片 / 字体 / 颜色 / 日志 / 路由）统一在这里注册。
///
/// 必须在创建第一个 Kuikly 页面之前调用 `+installWithRouter:`（AppDelegate.didFinishLaunching）。
@interface SDKuiklyAdapters : NSObject

/// 注册全部适配器。重复调用只生效一次。
+ (void)installWithRouter:(id<SDPageRouting>)router NS_SWIFT_NAME(install(router:));

/// 当前路由执行者。
@property (class, nonatomic, readonly, nullable) id<SDPageRouting> router;

/// 宿主主窗口（弱引用）。Kuikly 页面创建时用它取 safeAreaInsets / 窗口尺寸。
@property (class, nonatomic, nullable) UIWindow *hostWindow;

/// 定位 shared 模块随包资源（`shared/src/commonMain/assets/**`）。
/// relativePath 形如 `common/images/nav/home.png`；找不到返回 nil。
+ (nullable NSURL *)assetURLForRelativePath:(NSString *)relativePath NS_SWIFT_NAME(assetURL(relativePath:));

/// 按「业务字体族名 + 字重」取字体（族名与 Flutter pubspec 一致，如 Poppins / Montserrat / Anton）。
/// 族名不在映射表里时返回 nil。cssWeight 取 100…900。
+ (nullable UIFont *)fontWithFamily:(NSString *)family
                               size:(CGFloat)size
                          cssWeight:(NSInteger)cssWeight NS_SWIFT_NAME(font(family:size:cssWeight:));

/// 清空本地图片（assets / 本地文件）的内存缓存；网络图片缓存由 SDImageCache 管理。
+ (void)clearLocalImageMemoryCache;

@end

NS_ASSUME_NONNULL_END
