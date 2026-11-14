#import "KuiklyViewController.h"
#import "SDKuiklyAdapters.h"
#import <OpenKuiklyIOSRender/KuiklyRenderViewControllerBaseDelegator.h>
#import <OpenKuiklyIOSRender/KuiklyRenderContextProtocol.h>
#if DEBUG
#import "SDHotReload.h"
#endif

NSString *const SDKuiklyPageStackDidChangeNotification = @"SDKuiklyPageStackDidChangeNotification";

/// Kotlin 业务代码编译出的 framework 名（shared/build.gradle.kts: framework.baseName）。
static NSString *const kSDKuiklyFrameworkName = @"shared";

@interface KuiklyViewController () <KuiklyRenderViewControllerBaseDelegatorDelegate>

@property (nonatomic, strong) KuiklyRenderViewControllerBaseDelegator *delegator;

@end

@implementation KuiklyViewController {
    BOOL _visible;
    BOOL _handlingBack;
}

#pragma mark - init

- (instancetype)initWithPageName:(NSString *)pageName pageData:(nullable NSDictionary *)pageData {
    if (self = [super initWithNibName:nil bundle:nil]) {
        _pageName = [pageName isKindOfClass:[NSString class]] ? [pageName copy] : @"";
        _pageData = [pageData isKindOfClass:[NSDictionary class]] ? [pageData copy] : @{};

        id transition = _pageData[@"sdTransition"];
        _sdTransition = [transition isKindOfClass:[NSString class]] ? [transition copy] : @"";

        id translucent = _pageData[@"sdTranslucent"];
        _sdTranslucent = [translucent respondsToSelector:@selector(intValue)] && [translucent intValue] == 1;

        _swipeBackEnabled = YES;
        _statusBarStyle = UIStatusBarStyleLightContent;

        Class delegatorClass = KuiklyRenderViewControllerBaseDelegator.class;
#if DEBUG
        if (SDHotReload.shared.enabled) delegatorClass = SDHotReloadDelegator.class;
#endif
        _delegator = [[delegatorClass alloc] initWithPageName:_pageName
                                                                                pageData:[self sd_mergedPageData]];
        _delegator.delegate = self;
    }
    return self;
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    dispatch_async(dispatch_get_main_queue(), ^{
        [[NSNotificationCenter defaultCenter] postNotificationName:SDKuiklyPageStackDidChangeNotification object:nil];
    });
}

#pragma mark - host params

/// 宿主注入给 shared 的运行环境参数（AppEnv.initFrom 读取）。
- (NSDictionary *)sd_mergedPageData {
    NSMutableDictionary *data = [_pageData mutableCopy];
    NSBundle *bundle = [NSBundle mainBundle];

#if DEBUG
    data[@"debug"] = @1;
#else
    data[@"debug"] = @0;
#endif

    NSString *apiEnv = @"prod";
    id envValue = [bundle objectForInfoDictionaryKey:@"SDApiEnv"];
    if ([envValue isKindOfClass:[NSString class]]) {
        NSString *env = [(NSString *)envValue lowercaseString];
        if ([env isEqualToString:@"local"] || [env isEqualToString:@"test"] || [env isEqualToString:@"prod"]) {
            apiEnv = env;
        }
    }
    data[@"apiEnv"] = apiEnv;

    BOOL showTokenEditor = NO;
    id editorValue = [bundle objectForInfoDictionaryKey:@"SDShowTokenEditor"];
    if ([editorValue isKindOfClass:[NSNumber class]]) {
        showTokenEditor = [(NSNumber *)editorValue boolValue];
    } else if ([editorValue isKindOfClass:[NSString class]]) {
        NSString *text = [(NSString *)editorValue lowercaseString];
        showTokenEditor = [text isEqualToString:@"yes"] || [text isEqualToString:@"true"] || [text isEqualToString:@"1"];
    }
    data[@"showTokenEditor"] = showTokenEditor ? @1 : @0;

    id versionName = [bundle objectForInfoDictionaryKey:@"CFBundleShortVersionString"];
    data[@"appVersionName"] = [versionName isKindOfClass:[NSString class]] ? versionName : @"";

    id versionCode = [bundle objectForInfoDictionaryKey:@"CFBundleVersion"];
    NSInteger code = [versionCode isKindOfClass:[NSString class]] ? [(NSString *)versionCode integerValue] : 0;
    data[@"appVersionCode"] = @(code);

    return data;
}

#pragma mark - view lifecycle

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = _sdTranslucent ? [UIColor clearColor] : [UIColor blackColor];
#if DEBUG
    if (SDHotReload.shared.enabled) {
        [NSNotificationCenter.defaultCenter addObserver:self selector:@selector(sd_hotReload:)
                                                   name:SDHotReloadDidUpdateNotification object:nil];
    }
#endif
    [_delegator viewDidLoadWithView:self.view];
}

#if DEBUG
- (void)sd_hotReload:(NSNotification *)notification {
    BOOL isRoot = [self.pageName isEqualToString:@"launch"];
    if (isRoot != [notification.userInfo[@"rootOnly"] boolValue] || !self.isViewLoaded) return;
    BOOL appeared = _visible && !_coveredByTranslucentPage;
    if (appeared) {
        [_delegator viewWillDisappear];
        [_delegator viewDidDisappear];
    }
    [_delegator.renderView removeFromSuperview];
    _delegator = nil;
    // 加载失败时的 loading/error view 也属于旧执行环境。
    for (UIView *subview in self.view.subviews.copy) [subview removeFromSuperview];
    _handlingBack = NO;
    _delegator = [[SDHotReloadDelegator alloc] initWithPageName:_pageName pageData:[self sd_mergedPageData]];
    _delegator.delegate = self;
    [_delegator viewDidLoadWithView:self.view];
    [_delegator viewDidLayoutSubviews];
    if (appeared) {
        [_delegator viewWillAppear];
        [_delegator viewDidAppear];
    }
}
#endif

- (void)viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];
    [_delegator viewDidLayoutSubviews];
}

- (void)viewWillAppear:(BOOL)animated {
    [super viewWillAppear:animated];
    [self.navigationController setNavigationBarHidden:YES animated:NO];
    if (!_coveredByTranslucentPage) {
        [_delegator viewWillAppear];
    }
}

- (void)viewDidAppear:(BOOL)animated {
    [super viewDidAppear:animated];
    _visible = YES;
    if (!_coveredByTranslucentPage) {
        [_delegator viewDidAppear];
    }
    [[NSNotificationCenter defaultCenter] postNotificationName:SDKuiklyPageStackDidChangeNotification object:nil];
}

- (void)viewWillDisappear:(BOOL)animated {
    [super viewWillDisappear:animated];
    if (!_coveredByTranslucentPage) {
        [_delegator viewWillDisappear];
    }
}

- (void)viewDidDisappear:(BOOL)animated {
    [super viewDidDisappear:animated];
    _visible = NO;
    if (!_coveredByTranslucentPage) {
        [_delegator viewDidDisappear];
    }
    [[NSNotificationCenter defaultCenter] postNotificationName:SDKuiklyPageStackDidChangeNotification object:nil];
}

#pragma mark - covered by translucent page

- (void)setCoveredByTranslucentPage:(BOOL)coveredByTranslucentPage {
    if (_coveredByTranslucentPage == coveredByTranslucentPage) {
        return;
    }
    _coveredByTranslucentPage = coveredByTranslucentPage;
    if (!_visible) {
        return;
    }
    if (coveredByTranslucentPage) {
        [_delegator viewWillDisappear];
        [_delegator viewDidDisappear];
    } else {
        [_delegator viewWillAppear];
        [_delegator viewDidAppear];
    }
}

#pragma mark - status bar / orientation

- (void)setStatusBarStyle:(UIStatusBarStyle)statusBarStyle {
    _statusBarStyle = statusBarStyle;
    [self setNeedsStatusBarAppearanceUpdate];
}

- (UIStatusBarStyle)preferredStatusBarStyle {
    return _statusBarStyle;
}

- (BOOL)prefersStatusBarHidden {
    return NO;
}

- (UIInterfaceOrientationMask)supportedInterfaceOrientations {
    return UIInterfaceOrientationMaskPortrait;
}

- (BOOL)shouldAutorotate {
    return NO;
}

#pragma mark - back press / events

- (void)handleSystemBack {
    if (_handlingBack) {
        return;
    }
    _handlingBack = YES;

    __weak typeof(self) weakSelf = self;
    __block BOOL finished = NO;
    void (^finish)(BOOL) = ^(BOOL consumed) {
        if (finished) {
            return;
        }
        finished = YES;
        __strong typeof(weakSelf) strongSelf = weakSelf;
        if (strongSelf == nil) {
            return;
        }
        strongSelf->_handlingBack = NO;
        // Kuikly 侧没有注册返回拦截（未消费），且页面允许系统返回：关闭本页。
        if (!consumed && strongSelf.swipeBackEnabled) {
            [[SDKuiklyAdapters router] closePageOfController:strongSelf];
        }
    };

    [_delegator onBackPressedWithCompletion:^(BOOL consumed) {
        finish(consumed);
    }];

    // 兜底：Kuikly 侧迟迟不回调（页面正在销毁等）时只复位状态，不关闭页面。
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(1.5 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        finish(YES);
    });
}

- (void)sendEvent:(NSString *)event data:(nullable NSDictionary *)data {
    if (event.length == 0) {
        return;
    }
    [_delegator sendWithEvent:event data:data ?: @{}];
}

#pragma mark - KuiklyRenderViewControllerBaseDelegatorDelegate

- (void)fetchContextCodeWithPageName:(NSString *)pageName resultCallback:(KuiklyContextCodeCallback)callback {
#if DEBUG
    if (SDHotReload.shared.enabled) {
        [SDHotReload.shared fetchScript:^(NSData *script, NSError *error) {
            if (callback) callback(script, error);
        }];
        return;
    }
#endif
    if (callback) {
        callback(kSDKuiklyFrameworkName, nil);
    }
}

- (UIView *)createLoadingView {
    UIView *loadingView = [[UIView alloc] init];
    loadingView.backgroundColor = _sdTranslucent ? [UIColor clearColor] : [UIColor blackColor];
    return loadingView;
}

- (UIView *)createErrorView {
    UIView *errorView = [[UIView alloc] init];
    errorView.backgroundColor = _sdTranslucent ? [UIColor clearColor] : [UIColor blackColor];
    return errorView;
}

- (UIWindow *)viewControllerHostWindow {
    // 页面创建时（viewDidLoad）视图还没挂到 window 上，回落到宿主主窗口，
    // 保证 Kuikly 首帧拿到正确的 safeAreaInsets / 窗口尺寸。
    UIWindow *window = self.isViewLoaded ? self.view.window : nil;
    return window ?: [SDKuiklyAdapters hostWindow];
}

- (void)onUnhandledException:(NSString *)exReason stack:(NSString *)callstackStr mode:(KuiklyContextMode)mode {
    NSLog(@"[Sondo][Kuikly] unhandled exception on page %@: %@\n%@", _pageName, exReason, callstackStr);
}

- (void)onPageLoadComplete:(BOOL)isSucceed error:(nullable NSError *)error mode:(KuiklyContextMode)mode {
    if (!isSucceed) {
        NSLog(@"[Sondo][Kuikly] page %@ load failed: %@", _pageName, error);
    }
}

@end
