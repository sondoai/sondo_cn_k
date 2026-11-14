#import "SDJVerifyBridge.h"

// 与参考工程 third_party/jverify/ios/Classes/JverifyPlugin.m 相同的引入方式（JVerification / JCore 的公开头文件）。
#if __has_include("JVERIFICATIONService.h")
#import "JVERIFICATIONService.h"
#elif __has_include(<JVerification/JVERIFICATIONService.h>)
#import <JVerification/JVERIFICATIONService.h>
#endif
#if __has_include("JGInforCollectionAuth.h")
#import "JGInforCollectionAuth.h"
#elif __has_include(<JCore/JGInforCollectionAuth.h>)
#import <JCore/JGInforCollectionAuth.h>
#endif

#pragma mark - 常量（数值取自参考工程 jverify_login.dart 的 JverifySheetLayout，单位 pt，纵向从弹窗顶边算起）

static const CGFloat kSDJVCornerRadius = 24;
static const CGFloat kSDJVSidePadding = 20;
static const CGFloat kSDJVHeaderArtSize = 110;
static const CGFloat kSDJVTitleTop = 43;
static const CGFloat kSDJVSubtitleTop = 79;
static const CGFloat kSDJVNumberTop = 150;
static const CGFloat kSDJVSloganTop = 194;
static const CGFloat kSDJVButtonTop = 230;
static const CGFloat kSDJVButtonHeight = 50;
static const CGFloat kSDJVButtonGap = 16;
static const CGFloat kSDJVButtonTextSize = 14;
static const CGFloat kSDJVButtonIconSize = 20;
static const CGFloat kSDJVOneClickIconGap = 8;
static const CGFloat kSDJVOtherWaysIconGap = 10;
static const CGFloat kSDJVLoadingSize = 16;
static const CGFloat kSDJVLoadingGap = 8;
static const CGFloat kSDJVClauseGap = 30;
static const CGFloat kSDJVClauseLineHeight = 16;
static const CGFloat kSDJVClauseTextSize = 10;
static const CGFloat kSDJVCheckboxSize = 12;
static const CGFloat kSDJVBottomPadding = 30;
static const CGFloat kSDJVBackgroundAlpha = 0.7;

/// 整行按钮的文案前垫两个全角空格，给左边 20 的图标和间距让出位置（与参考工程一致）。
static NSString *const kSDJVButtonIconSpace = @"　　";

static NSString *const kSDJVActionSms = @"sms";
static NSString *const kSDJVActionWechat = @"wechat";

#pragma mark - 小工具

/// 0xAARRGGBB → UIColor。
static UIColor *SDJVColorARGB(uint32_t argb) {
    return [UIColor colorWithRed:((argb >> 16) & 0xFF) / 255.0
                           green:((argb >> 8) & 0xFF) / 255.0
                            blue:(argb & 0xFF) / 255.0
                           alpha:((argb >> 24) & 0xFF) / 255.0];
}

static NSString *SDJVString(id value) {
    if ([value isKindOfClass:[NSString class]]) {
        return (NSString *)value;
    }
    if ([value isKindOfClass:[NSNumber class]]) {
        return [(NSNumber *)value stringValue];
    }
    return @"";
}

static NSString *SDJVTrimmed(id value) {
    return [SDJVString(value) stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
}

static NSInteger SDJVInteger(id value, NSInteger fallback) {
    if ([value isKindOfClass:[NSNumber class]]) {
        return [(NSNumber *)value integerValue];
    }
    if ([value isKindOfClass:[NSString class]] && [(NSString *)value length] > 0) {
        return [(NSString *)value integerValue];
    }
    return fallback;
}

static CGFloat SDJVTextWidth(NSString *text, UIFont *font) {
    if (text.length == 0) {
        return 0;
    }
    return ceil([text sizeWithAttributes:@{NSFontAttributeName: font}].width * 100.0) / 100.0;
}

static void SDJVOnMain(dispatch_block_t block) {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_async(dispatch_get_main_queue(), block);
    }
}

/// 两端圆角的整行按钮底图：只拉伸中间，圆角不变形。
static UIImage *_Nullable SDJVPillImage(NSString *name) {
    UIImage *image = [UIImage imageNamed:name];
    if (image == nil) {
        return nil;
    }
    CGFloat cap = floor(MIN(image.size.width, image.size.height) / 2.0);
    if (cap <= 0 || image.size.width <= cap * 2) {
        return image;
    }
    return [image resizableImageWithCapInsets:UIEdgeInsetsMake(0, cap, 0, cap) resizingMode:UIImageResizingModeStretch];
}

#pragma mark - 弹层内容与布局

/// 协议第一行的一段文字。
@interface SDJVClausePart : NSObject
@property (nonatomic, copy) NSString *text;
@property (nonatomic, assign) BOOL isLink;
@property (nonatomic, copy) NSString *name;
@property (nonatomic, copy) NSString *url;
@end

@implementation SDJVClausePart
@end

/// 从 Kuikly 的 JSON 入参整理出来的弹层内容。
@interface SDJVSheetContent : NSObject
@property (nonatomic, copy) NSString *title;
@property (nonatomic, copy) NSString *subtitle;
@property (nonatomic, copy) NSString *loginTitle;
@property (nonatomic, copy) NSString *smsTitle;      // 空串 = 不显示
@property (nonatomic, copy) NSString *wechatTitle;   // 空串 = 不显示
@property (nonatomic, copy) NSArray<SDJVClausePart *> *clauseParts;
@property (nonatomic, copy) NSString *firstLine;     // clauseParts 拼起来的整句
@property (nonatomic, copy) NSString *uncheckedHint;
@property (nonatomic, assign) NSInteger timeoutMs;

+ (instancetype)contentWithParams:(NSDictionary<NSString *, id> *)params;
/// 一键登录下面的整行按钮个数（短信登录、微信登录）。
- (NSInteger)extraButtonCount;
@end

@implementation SDJVSheetContent

+ (instancetype)contentWithParams:(NSDictionary<NSString *, id> *)params {
    SDJVSheetContent *content = [[SDJVSheetContent alloc] init];
    content.title = SDJVTrimmed(params[@"title"]);
    content.subtitle = SDJVTrimmed(params[@"subtitle"]);
    content.loginTitle = SDJVTrimmed(params[@"loginButtonText"]);
    if (content.loginTitle.length == 0) {
        content.loginTitle = @"一键登录";
    }
    content.smsTitle = SDJVTrimmed(params[@"otherLoginText"]);
    content.wechatTitle = SDJVTrimmed(params[@"wechatLoginText"]);
    content.uncheckedHint = SDJVTrimmed(params[@"privacyUncheckedToast"]);
    if (content.uncheckedHint.length == 0) {
        content.uncheckedHint = @"请点击同意协议";
    }

    NSInteger timeout = SDJVInteger(params[@"timeoutMs"], 10000);
    if (timeout <= 0) {
        timeout = 10000;
    }
    content.timeoutMs = MAX(3000, MIN(timeout, 10000));

    // 协议第一行：prefix +《条款1》+ 分隔 +《条款2》+ suffix
    NSString *prefix = SDJVString(params[@"privacyPrefix"]);
    NSString *suffix = SDJVString(params[@"privacySuffix"]);
    NSString *lastSeparator = SDJVString(params[@"privacySeparator"]);
    if (lastSeparator.length == 0) {
        lastSeparator = @"和";
    }

    NSMutableArray<NSDictionary *> *items = [NSMutableArray array];
    id rawItems = params[@"privacyItems"];
    if ([rawItems isKindOfClass:[NSArray class]]) {
        for (id raw in (NSArray *)rawItems) {
            if (![raw isKindOfClass:[NSDictionary class]]) {
                continue;
            }
            NSString *name = SDJVTrimmed(((NSDictionary *)raw)[@"name"]);
            NSString *url = SDJVTrimmed(((NSDictionary *)raw)[@"url"]);
            if (name.length == 0) {
                continue;
            }
            [items addObject:@{@"name": name, @"url": url}];
        }
    }

    NSMutableArray<SDJVClausePart *> *parts = [NSMutableArray array];
    NSMutableString *line = [NSMutableString string];
    void (^append)(NSString *, BOOL, NSString *, NSString *) = ^(NSString *text, BOOL isLink, NSString *name, NSString *url) {
        if (text.length == 0) {
            return;
        }
        SDJVClausePart *part = [[SDJVClausePart alloc] init];
        part.text = text;
        part.isLink = isLink;
        part.name = name;
        part.url = url;
        [parts addObject:part];
        [line appendString:text];
    };

    if (items.count > 0) {
        append(prefix, NO, @"", @"");
        for (NSUInteger index = 0; index < items.count; index++) {
            NSDictionary *item = items[index];
            if (index > 0) {
                append(index == items.count - 1 ? lastSeparator : @"、", NO, @"", @"");
            }
            NSString *name = item[@"name"];
            NSString *display = [name hasPrefix:@"《"] ? name : [NSString stringWithFormat:@"《%@》", name];
            append(display, YES, name, item[@"url"]);
        }
        append(suffix, NO, @"", @"");
    }
    content.clauseParts = parts;
    content.firstLine = line;
    return content;
}

- (NSInteger)extraButtonCount {
    return (self.smsTitle.length > 0 ? 1 : 0) + (self.wechatTitle.length > 0 ? 1 : 0);
}

@end

/// 底部弹层的尺寸（JverifySheetLayout 的 iOS 分支）。
typedef struct {
    CGFloat width;          // 弹窗宽 = 屏幕宽
    CGFloat screenHeight;
    CGFloat buttonWidth;
    CGFloat buttonLeft;
    CGFloat clauseTop;
    CGFloat height;         // 弹窗高（含底部安全区和压到屏幕外的一截圆角）
    CGFloat offsetCenterY;  // 弹窗中心相对屏幕中心的纵向偏移
    CGFloat sheetTop;       // 弹窗顶边在屏幕上的纵坐标
} SDJVSheetLayout;

static CGFloat SDJVButtonTopAt(NSInteger index) {
    return kSDJVButtonTop + index * (kSDJVButtonHeight + kSDJVButtonGap);
}

static SDJVSheetLayout SDJVMakeLayout(NSInteger extraButtons, CGFloat bottomInset) {
    CGSize screen = [UIScreen mainScreen].bounds.size;
    SDJVSheetLayout layout;
    layout.width = round(screen.width > 0 ? screen.width : 375);
    layout.screenHeight = screen.height > 0 ? screen.height : 812;
    layout.buttonWidth = MAX(220, MIN(layout.width - kSDJVSidePadding * 2, 420));
    layout.buttonLeft = floor((layout.width - layout.buttonWidth) / 2);
    layout.clauseTop = SDJVButtonTopAt(extraButtons) + kSDJVButtonHeight + kSDJVClauseGap;
    CGFloat contentHeight = layout.clauseTop + kSDJVClauseLineHeight * 2 + kSDJVBottomPadding;
    // 四个角同一圆角，多出的一截压到屏幕外，只露出上圆角。
    CGFloat hiddenBelow = kSDJVCornerRadius;
    layout.height = contentHeight + round(MAX(0, bottomInset)) + hiddenBelow;
    layout.offsetCenterY = round((layout.screenHeight - layout.height) / 2) + hiddenBelow;
    layout.sheetTop = layout.screenHeight / 2 + layout.offsetCenterY - layout.height / 2;
    return layout;
}

/// 按钮上图标的左边：文案前垫了两个全角空格，文字仍由按钮自己居中；图标排在可见文字的左边，中间隔 gap。
static CGFloat SDJVButtonIconLeft(SDJVSheetLayout layout, NSString *label, UIFont *font, CGFloat gap) {
    CGFloat spaceWidth = SDJVTextWidth(kSDJVButtonIconSpace, font);
    CGFloat titleWidth = SDJVTextWidth([kSDJVButtonIconSpace stringByAppendingString:label], font);
    CGFloat labelStart = (layout.width - titleWidth) / 2 + spaceWidth;
    return labelStart - gap - kSDJVButtonIconSize;
}

/// 勾选框和第一行文字作为一个整体水平居中时，第一行文字的起点（勾选框在文字左边 5 的位置）。
static CGFloat SDJVClauseTextLeft(SDJVSheetLayout layout, CGFloat lineWidth) {
    CGFloat checkboxSpace = kSDJVCheckboxSize + 5;
    CGFloat left = (layout.width - checkboxSpace - lineWidth) / 2 + checkboxSpace;
    return MAX(left, kSDJVSidePadding + checkboxSpace);
}

#pragma mark - SDJVerifyBridge

@interface SDJVerifyBridge () <UIGestureRecognizerDelegate>

@property (nonatomic, assign, readwrite) BOOL didSetup;
@property (nonatomic, assign) BOOL setupSucceeded;
@property (nonatomic, assign) NSUInteger setupGeneration;
@property (nonatomic, strong, nullable) NSMutableArray<SDJVerifyResultHandler> *setupCompletions;

/// 当前这次授权（nil = 没有进行中的授权）。
@property (nonatomic, copy, nullable) SDJVerifyResultHandler authCompletion;
@property (nonatomic, assign) NSUInteger authGeneration;
/// 这次授权的弹窗是否出现过。
@property (nonatomic, assign) BOOL authPageShown;
/// 屏幕上是否还有授权页（出现后、收到“已关闭”之前）。
@property (nonatomic, assign) BOOL authPageOpen;
/// 协议勾选框的当前状态。
@property (nonatomic, assign) BOOL privacyChecked;
@property (nonatomic, copy) NSString *uncheckedHint;

/// 贴底弹窗顶边在屏幕上的纵坐标，用来区分弹窗外的空白处和弹窗顶部的拖动区。
@property (nonatomic, assign) CGFloat sheetTop;
/// 当前授权页的控制器，在它上面弹提示 / 打开条款。
@property (nonatomic, weak) UIViewController *sheetController;
/// 自定义控件 → 动作（按钮：sms / wechat；文字：SDJVClausePart）。
@property (nonatomic, strong) NSMapTable<UIView *, id> *widgetActions;

@end

@implementation SDJVerifyBridge

+ (SDJVerifyBridge *)shared {
    static SDJVerifyBridge *instance;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        instance = [[SDJVerifyBridge alloc] init];
    });
    return instance;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _uncheckedHint = @"";
        _widgetActions = [NSMapTable weakToStrongObjectsMapTable];
    }
    return self;
}

#pragma mark - 初始化

- (void)setupWithAppKey:(NSString *)appKey
                channel:(NSString *)channel
                  debug:(BOOL)debug
              timeoutMs:(NSInteger)timeoutMs
             completion:(SDJVerifyResultHandler)completion {
    SDJVOnMain(^{
        NSString *key = SDJVTrimmed(appKey);
        if (key.length == 0) {
            completion(@{@"ok": @NO, @"code": @(-1), @"error": @"empty_app_key"});
            return;
        }
        // SDK 是单例，初始化成功后不再 setup。
        if (self.setupSucceeded) {
            completion(@{@"ok": @YES, @"code": @8000});
            return;
        }
        if (self.setupCompletions != nil) {
            [self.setupCompletions addObject:[completion copy]];
            return;
        }
        self.setupCompletions = [NSMutableArray arrayWithObject:[completion copy]];
        NSUInteger generation = ++self.setupGeneration;
        self.didSetup = YES;

        [JVERIFICATIONService setDebug:debug];
        // 合规：用户点一键登录（且已同意隐私政策）才开启采集。
        [JGInforCollectionAuth JCollectionAuth:^(JGInforCollectionAuthItems * _Nonnull authInfo) {
            authInfo.isAuth = YES;
        }];

        JVAuthConfig *config = [[JVAuthConfig alloc] init];
        config.appKey = key;
        NSString *channelName = SDJVTrimmed(channel);
        if (channelName.length > 0) {
            config.channel = channelName;
        }
        config.timeout = timeoutMs > 0 ? timeoutMs : 10000;

        __weak typeof(self) weakSelf = self;
        config.authBlock = ^(NSDictionary *result) {
            NSInteger code = SDJVInteger(result[@"code"], 0);
            NSString *message = SDJVString(result[@"content"]);
            dispatch_async(dispatch_get_main_queue(), ^{
                [weakSelf finishSetupWithCode:code message:message timedOut:NO generation:generation];
            });
        };
        [JVERIFICATIONService setupWithConfig:config];

        // 部分机型没有 setup 回调：15 秒后按成功继续，交给 checkVerifyEnable / 授权页去判断。
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(15 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            [weakSelf finishSetupWithCode:8000 message:@"" timedOut:YES generation:generation];
        });
    });
}

- (void)finishSetupWithCode:(NSInteger)code
                    message:(NSString *)message
                   timedOut:(BOOL)timedOut
                 generation:(NSUInteger)generation {
    if (generation != self.setupGeneration) {
        return;
    }
    NSArray<SDJVerifyResultHandler> *completions = [self.setupCompletions copy];
    if (completions == nil) {
        // 超时之后才到的真实回调：只更新状态，失败的话下次 setup 会重试。
        if (!timedOut) {
            self.setupSucceeded = (code == 8000);
        }
        return;
    }
    self.setupCompletions = nil;
    BOOL ok = (code == 8000);
    self.setupSucceeded = ok;

    NSMutableDictionary<NSString *, id> *result = [NSMutableDictionary dictionary];
    result[@"ok"] = @(ok);
    result[@"code"] = @(code);
    if (timedOut) {
        result[@"timedOut"] = @YES;
    }
    if (!ok) {
        result[@"error"] = message.length > 0 ? message : [NSString stringWithFormat:@"setup_failed_%ld", (long)code];
    }
    for (SDJVerifyResultHandler handler in completions) {
        handler(result);
    }
}

#pragma mark - 网络环境 / 预取号

- (void)checkVerifyEnableWithCompletion:(SDJVerifyResultHandler)completion {
    SDJVOnMain(^{
        if (!self.didSetup) {
            completion(@{@"ok": @NO, @"enable": @NO, @"error": @"not_setup"});
            return;
        }
        [JVERIFICATIONService checkVerifyEnable:^(BOOL isSupport, NSString * _Nonnull operatorType) {
            NSString *operatorName = SDJVString(operatorType);
            dispatch_async(dispatch_get_main_queue(), ^{
                completion(@{@"ok": @YES, @"enable": @(isSupport), @"operator": operatorName});
            });
        }];
    });
}

- (void)preLoginWithTimeoutMs:(NSInteger)timeoutMs completion:(SDJVerifyResultHandler)completion {
    SDJVOnMain(^{
        if (!self.didSetup) {
            completion(@{@"ok": @NO, @"code": @(-1), @"error": @"not_setup"});
            return;
        }
        // 超时时间单位 ms，合法范围 3000~10000。
        NSTimeInterval timeout = MAX(3000, MIN(timeoutMs > 0 ? timeoutMs : 5000, 10000));
        [JVERIFICATIONService preLoginWithEnableSms:NO timeout:timeout completion:^(NSDictionary *res) {
            NSInteger code = SDJVInteger(res[@"code"], -1);
            NSString *message = SDJVString(res[@"content"]);
            dispatch_async(dispatch_get_main_queue(), ^{
                BOOL ok = (code == 7000);
                NSMutableDictionary<NSString *, id> *result = [NSMutableDictionary dictionary];
                result[@"ok"] = @(ok);
                result[@"code"] = @(code);
                if (!ok) {
                    result[@"error"] = message.length > 0 ? message : [NSString stringWithFormat:@"prelogin_failed_%ld", (long)code];
                }
                completion(result);
            });
        }];
    });
}

#pragma mark - 授权页

- (void)loginAuthWithParams:(NSDictionary<NSString *, id> *)params
                  presenter:(nullable UIViewController *)presenter
                 completion:(SDJVerifyResultHandler)completion {
    SDJVOnMain(^{
        if (!self.didSetup) {
            completion(@{@"ok": @NO, @"action": @"error", @"error": @"not_setup", @"shown": @NO});
            return;
        }
        if (self.authCompletion != nil) {
            completion(@{@"ok": @NO, @"action": @"error", @"error": @"busy", @"shown": @NO});
            return;
        }
        NSUInteger generation = ++self.authGeneration;
        self.authCompletion = completion;
        self.authPageShown = NO;
        self.privacyChecked = NO;

        SDJVSheetContent *content = [SDJVSheetContent contentWithParams:params ?: @{}];
        self.uncheckedHint = content.uncheckedHint;

        __weak typeof(self) weakSelf = self;
        __weak UIViewController *weakPresenter = presenter;

        // 弹窗出现前的兜底计时：SDK 自己有取号超时，这里只防止回调丢失把登录入口一直占住。
        // 弹窗出现后不再计时：停多久都留在屏幕上，由用户登录、改走其他方式或关闭来结束。
        NSTimeInterval watchdog = MAX(25.0, content.timeoutMs / 1000.0 + 10.0);
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(watchdog * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            __strong typeof(weakSelf) strongSelf = weakSelf;
            if (strongSelf == nil || ![strongSelf isAuthActive:generation] || strongSelf.authPageShown) {
                return;
            }
            // 先结束这次尝试，关页触发的取消或迟到的 6000 都不能再改变结果。
            [strongSelf finishAuth:generation action:@"error" ok:NO extra:@{@"error": @"timeout"}];
            [strongSelf dismissAuthPage];
        });

        [JVERIFICATIONService checkVerifyEnable:^(BOOL isSupport, NSString * _Nonnull operatorType) {
            dispatch_async(dispatch_get_main_queue(), ^{
                __strong typeof(weakSelf) strongSelf = weakSelf;
                if (strongSelf == nil || ![strongSelf isAuthActive:generation]) {
                    return;
                }
                if (!isSupport) {
                    [strongSelf finishAuth:generation action:@"unsupported" ok:NO extra:@{@"error": @"verify_not_enabled"}];
                    return;
                }
                [strongSelf presentWhenPreviousPageClosed:generation content:content presenter:weakPresenter attempt:0];
            });
        }];
    });
}

- (BOOL)isAuthActive:(NSUInteger)generation {
    return generation == self.authGeneration && self.authCompletion != nil;
}

/// 等上一个弹窗关完再拉下一个，最多等 1 秒。
/// 关页动画没走完就再拉，SDK 会把旧页的取消算到新的这一次上，表现为点了没反应。
- (void)presentWhenPreviousPageClosed:(NSUInteger)generation
                              content:(SDJVSheetContent *)content
                            presenter:(nullable UIViewController *)presenter
                              attempt:(NSInteger)attempt {
    if (![self isAuthActive:generation]) {
        return;
    }
    if (self.authPageOpen && attempt < 10) {
        __weak typeof(self) weakSelf = self;
        __weak UIViewController *weakPresenter = presenter;
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.1 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            [weakSelf presentWhenPreviousPageClosed:generation content:content presenter:weakPresenter attempt:attempt + 1];
        });
        return;
    }
    self.authPageOpen = NO;
    [self presentAuthPage:generation content:content presenter:presenter];
}

- (void)presentAuthPage:(NSUInteger)generation
                content:(SDJVSheetContent *)content
              presenter:(nullable UIViewController *)presenter {
    UIViewController *host = presenter;
    if (host == nil || host.view.window == nil) {
        host = [self topViewController];
    }
    if (host == nil) {
        [self finishAuth:generation action:@"error" ok:NO extra:@{@"error": @"no_presenter"}];
        return;
    }

    CGFloat bottomInset = 0;
    UIWindow *window = host.view.window;
    if (window != nil) {
        bottomInset = window.safeAreaInsets.bottom;
    }
    SDJVSheetLayout layout = SDJVMakeLayout([content extraButtonCount], bottomInset);
    self.sheetTop = layout.sheetTop;
    self.sheetController = nil;
    [self.widgetActions removeAllObjects];

    JVUIConfig *config = [self uiConfigWithContent:content layout:layout];
    __weak typeof(self) weakSelf = self;
    [JVERIFICATIONService customUIWithConfig:config customViews:^(UIView *customAreaView) {
        [weakSelf installCustomViewsOn:customAreaView content:content layout:layout];
    }];

    [JVERIFICATIONService getAuthorizationWithController:host enableSms:NO hide:YES animated:YES timeout:content.timeoutMs completion:^(NSDictionary *res) {
        NSInteger code = SDJVInteger(res[@"code"], -1);
        NSString *token = SDJVTrimmed(res[@"loginToken"]);
        NSString *message = SDJVString(res[@"content"]);
        NSString *operatorName = SDJVString(res[@"operator"]);
        dispatch_async(dispatch_get_main_queue(), ^{
            [weakSelf handleAuthResultCode:code token:token message:message operatorName:operatorName generation:generation];
        });
    } actionBlock:^(NSInteger type, NSString *message) {
        dispatch_async(dispatch_get_main_queue(), ^{
            [weakSelf handlePageEvent:type generation:generation];
        });
    }];
}

/// 6000 拿到 loginToken，6002 用户关掉授权页，2016 当前网络不能认证，其余算失败。
- (void)handleAuthResultCode:(NSInteger)code
                       token:(NSString *)token
                     message:(NSString *)message
                operatorName:(NSString *)operatorName
                  generation:(NSUInteger)generation {
    if (![self isAuthActive:generation]) {
        return;
    }
    if (code == 6000) {
        if (token.length > 0) {
            [self finishAuth:generation action:@"token" ok:YES extra:@{@"token": token, @"operator": operatorName, @"code": @(code)}];
        } else {
            // 6000 但没有 loginToken：按失败处理，避免拿空串去换票。
            [self finishAuth:generation action:@"error" ok:NO extra:@{@"code": @(code), @"error": @"empty_token"}];
            [self dismissAuthPage];
        }
        return;
    }
    if (code == 6002) {
        [self finishAuth:generation action:@"cancel" ok:YES extra:@{@"code": @(code)}];
        return;
    }
    NSString *error = message.length > 0 ? message : [NSString stringWithFormat:@"login_auth_failed_%ld", (long)code];
    if (code == 2016) {
        [self finishAuth:generation action:@"unsupported" ok:NO extra:@{@"code": @(code), @"error": error}];
    } else {
        [self finishAuth:generation action:@"error" ok:NO extra:@{@"code": @(code), @"error": error}];
    }
    [self dismissAuthPage];
}

/// 授权页事件：2 打开，1 关闭，6 勾选协议，7 取消勾选。
- (void)handlePageEvent:(NSInteger)type generation:(NSUInteger)generation {
    if (type == 1) {
        self.authPageOpen = NO;
        if (generation == self.authGeneration && self.authPageShown && self.authCompletion != nil) {
            // 弹窗已关。结果回调通常紧跟着到；等不到就按用户关闭处理，登录入口不能一直挂着。
            __weak typeof(self) weakSelf = self;
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.3 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
                [weakSelf finishAuth:generation action:@"cancel" ok:YES extra:@{}];
            });
        }
        return;
    }
    if (type == 2) {
        self.authPageOpen = YES;
        if (![self isAuthActive:generation]) {
            // 这次授权已经结束（超时 / 被 jverifyDismiss 取消）之后弹窗才出来：没有人等结果了，直接关掉。
            [self dismissAuthPage];
            return;
        }
        self.authPageShown = YES;
        return;
    }
    if (generation != self.authGeneration) {
        return;
    }
    if (type == 6) {
        self.privacyChecked = YES;
    } else if (type == 7) {
        self.privacyChecked = NO;
    }
}

/// 结束这次授权；每次授权只会回调一次。
- (void)finishAuth:(NSUInteger)generation
            action:(NSString *)action
                ok:(BOOL)ok
             extra:(NSDictionary<NSString *, id> *)extra {
    if (generation != self.authGeneration) {
        return;
    }
    SDJVerifyResultHandler completion = self.authCompletion;
    if (completion == nil) {
        return;
    }
    self.authCompletion = nil;
    NSMutableDictionary<NSString *, id> *result = [NSMutableDictionary dictionary];
    result[@"ok"] = @(ok);
    result[@"action"] = action;
    result[@"shown"] = @(self.authPageShown);
    [result addEntriesFromDictionary:extra];
    completion(result);
}

/// 改走微信或短信、点空白处或下滑关闭：先定结果再关弹窗，关页带出的 6002 不会再改结果。
- (void)leaveWithAction:(NSString *)action {
    [self finishAuth:self.authGeneration action:action ok:YES extra:@{}];
    [self dismissAuthPage];
}

- (void)dismissAuthPage {
    [JVERIFICATIONService dismissLoginControllerAnimated:YES completion:^{
    }];
}

- (void)dismiss {
    SDJVOnMain(^{
        if (!self.didSetup) {
            return;
        }
        [self finishAuth:self.authGeneration action:@"cancel" ok:YES extra:@{}];
        [self dismissAuthPage];
    });
}

#pragma mark - 授权页 UI 配置（对照 JverifyPlugin.m 的 setCustomUIWithUIConfig: 与 jverify_login.dart 的 buildJverifyAuthUi）

- (JVUIConfig *)uiConfigWithContent:(SDJVSheetContent *)content layout:(SDJVSheetLayout)layout {
    JVUIConfig *config = [[JVUIConfig alloc] init];
    config.autoLayout = YES;
    config.shouldAutorotate = NO;
    config.dismissAnimationFlag = YES;
    config.modalTransitionStyle = UIModalTransitionStyleCoverVertical;

    // 背景：圆角由弹窗自己裁，背景只要纯色。
    UIImage *background = [UIImage imageNamed:@"jv_auth_bg"];
    if (background != nil) {
        config.authPageBackgroundImage = background;
    }

    // 导航栏隐藏
    config.navCustom = YES;
    config.navColor = SDJVColorARGB(0xFF1C1C1C);
    UIImage *backImage = [UIImage imageNamed:@"jv_nav_back"];
    if (backImage != nil) {
        config.navReturnImg = backImage;
    }

    // logo 不显示
    config.logoHidden = YES;

    // 号码 / 运营商说明：沿用参考工程的纵向偏移写法
    config.numFieldOffsetY = kSDJVNumberTop;
    config.numberColor = SDJVColorARGB(0xFFFFFFFF);
    config.numberFont = [UIFont systemFontOfSize:28];
    config.sloganOffsetY = kSDJVSloganTop;
    config.sloganTextColor = SDJVColorARGB(0x66FFFFFF);
    config.sloganFont = [UIFont systemFontOfSize:10];

    // 一键登录按钮：水平居中，左右各留 20（收在 220~420 之间），高 50
    UIFont *loginFont = [UIFont boldSystemFontOfSize:kSDJVButtonTextSize];
    JVLayoutConstraint *buttonX = [JVLayoutConstraint constraintWithAttribute:NSLayoutAttributeCenterX relatedBy:NSLayoutRelationEqual toItem:JVLayoutItemSuper attribute:NSLayoutAttributeCenterX multiplier:1 constant:0];
    JVLayoutConstraint *buttonY = [JVLayoutConstraint constraintWithAttribute:NSLayoutAttributeTop relatedBy:NSLayoutRelationEqual toItem:JVLayoutItemSuper attribute:NSLayoutAttributeTop multiplier:1 constant:kSDJVButtonTop];
    JVLayoutConstraint *buttonW = [JVLayoutConstraint constraintWithAttribute:NSLayoutAttributeWidth relatedBy:NSLayoutRelationEqual toItem:JVLayoutItemNone attribute:NSLayoutAttributeWidth multiplier:1 constant:layout.buttonWidth];
    JVLayoutConstraint *buttonH = [JVLayoutConstraint constraintWithAttribute:NSLayoutAttributeHeight relatedBy:NSLayoutRelationEqual toItem:JVLayoutItemNone attribute:NSLayoutAttributeHeight multiplier:1 constant:kSDJVButtonHeight];
    config.logBtnConstraints = @[buttonX, buttonY, buttonW, buttonH];
    config.logBtnHorizontalConstraints = config.logBtnConstraints;
    config.logBtnText = [kSDJVButtonIconSpace stringByAppendingString:content.loginTitle];
    config.logBtnFont = loginFont;
    config.logBtnTextColor = SDJVColorARGB(0xFF0A0A0A);
    UIImage *normalImage = SDJVPillImage(@"jv_btn_normal");
    UIImage *pressedImage = SDJVPillImage(@"jv_btn_pressed");
    UIImage *unableImage = SDJVPillImage(@"jv_btn_unable");
    if (normalImage != nil && pressedImage != nil && unableImage != nil) {
        config.logBtnImgs = @[normalImage, pressedImage, unableImage];
    }

    // 点登录后的 loading：垂直居中在按钮里，排在按钮图标的左侧，不和图标叠在一起
    CGFloat loadingLeft = SDJVButtonIconLeft(layout, content.loginTitle, loginFont, kSDJVOneClickIconGap) - kSDJVLoadingGap - kSDJVLoadingSize;
    loadingLeft = MAX(layout.buttonLeft + 12, MIN(round(loadingLeft), layout.width));
    CGFloat loadingTop = kSDJVButtonTop + floor((kSDJVButtonHeight - kSDJVLoadingSize) / 2);
    JVLayoutConstraint *loadingX = [JVLayoutConstraint constraintWithAttribute:NSLayoutAttributeLeft relatedBy:NSLayoutRelationEqual toItem:JVLayoutItemSuper attribute:NSLayoutAttributeLeft multiplier:1 constant:loadingLeft];
    JVLayoutConstraint *loadingY = [JVLayoutConstraint constraintWithAttribute:NSLayoutAttributeTop relatedBy:NSLayoutRelationEqual toItem:JVLayoutItemSuper attribute:NSLayoutAttributeTop multiplier:1 constant:loadingTop];
    JVLayoutConstraint *loadingW = [JVLayoutConstraint constraintWithAttribute:NSLayoutAttributeWidth relatedBy:NSLayoutRelationEqual toItem:JVLayoutItemNone attribute:NSLayoutAttributeWidth multiplier:1 constant:kSDJVLoadingSize];
    JVLayoutConstraint *loadingH = [JVLayoutConstraint constraintWithAttribute:NSLayoutAttributeHeight relatedBy:NSLayoutRelationEqual toItem:JVLayoutItemNone attribute:NSLayoutAttributeHeight multiplier:1 constant:kSDJVLoadingSize];
    config.loadingConstraints = @[loadingX, loadingY, loadingW, loadingH];
    config.loadingHorizontalConstraints = config.loadingConstraints;

    // 协议：默认不勾选。
    // 设计稿的顺序是“我已阅读并同意《使用条款》和《隐私政策》，以及《运营商条款》”，但 SDK 固定把运营商条款排在
    // 自定义条款前面。做法与参考工程相同：第一行文字照样交给 SDK，用来撑出宽度和换行，但颜色全透明；
    // 看得见的第一行由自定义文字盖在同一位置。SDK 只负责勾选框和第二行的运营商条款。
    UIFont *clauseFont = [UIFont systemFontOfSize:kSDJVClauseTextSize];
    BOOL hasOwnClause = content.firstLine.length > 0;
    CGFloat firstLineWidth = 0;
    for (SDJVClausePart *part in content.clauseParts) {
        firstLineWidth += SDJVTextWidth(part.text, clauseFont);
    }
    CGFloat clauseTextLeft = SDJVClauseTextLeft(layout, firstLineWidth);
    // 文字框从勾选框右侧排到右边距，左对齐；左边距取到让勾选框和第一行整体居中。
    CGFloat privacyOffsetX = MAX(8, round(clauseTextLeft - kSDJVCheckboxSize - 10));
    CGFloat privacyLeftSpace = privacyOffsetX + kSDJVCheckboxSize + 5 + 5;
    // 右边只留侧边距：文字框比第一行宽一些不影响显示（左对齐），但能避免透明的第一行被挤得提前换行。
    CGFloat privacyRightSpace = MIN(privacyOffsetX, kSDJVSidePadding);
    CGFloat privacyWidth = MAX(120, layout.width - (privacyLeftSpace + privacyRightSpace));
    NSString *measureText = hasOwnClause
        ? [NSString stringWithFormat:@"%@\n《中国移动统一认证服务条款》", content.firstLine]
        : @"登录即同意《中国移动统一认证服务条款》并使用本机号码登录";
    CGSize privacySize = [measureText boundingRectWithSize:CGSizeMake(privacyWidth, CGFLOAT_MAX)
                                                   options:NSStringDrawingUsesLineFragmentOrigin
                                                attributes:@{NSFontAttributeName: clauseFont}
                                                   context:nil].size;
    CGFloat privacyHeight = ceil(privacySize.height) + 1;
    // 协议按底边定位：顶边比第一行自定义文字的顶边低 2，两者的文字正好对齐。
    CGFloat privacyBottomInset = layout.height - (layout.clauseTop + 2 + privacyHeight);

    JVLayoutConstraint *privacyX = [JVLayoutConstraint constraintWithAttribute:NSLayoutAttributeLeft relatedBy:NSLayoutRelationEqual toItem:JVLayoutItemSuper attribute:NSLayoutAttributeLeft multiplier:1 constant:privacyLeftSpace];
    JVLayoutConstraint *privacyY = [JVLayoutConstraint constraintWithAttribute:NSLayoutAttributeBottom relatedBy:NSLayoutRelationEqual toItem:JVLayoutItemSuper attribute:NSLayoutAttributeBottom multiplier:1 constant:-privacyBottomInset];
    JVLayoutConstraint *privacyW = [JVLayoutConstraint constraintWithAttribute:NSLayoutAttributeWidth relatedBy:NSLayoutRelationEqual toItem:JVLayoutItemNone attribute:NSLayoutAttributeWidth multiplier:1 constant:privacyWidth];
    JVLayoutConstraint *privacyH = [JVLayoutConstraint constraintWithAttribute:NSLayoutAttributeHeight relatedBy:NSLayoutRelationEqual toItem:JVLayoutItemNone attribute:NSLayoutAttributeHeight multiplier:1 constant:privacyHeight];
    config.privacyConstraints = @[privacyX, privacyY, privacyW, privacyH];
    config.privacyHorizontalConstraints = config.privacyConstraints;

    // 勾选框贴着第一行，上边只让 1，和第一行文字的中线对齐；在文字左边 5 的位置。
    JVLayoutConstraint *boxX = [JVLayoutConstraint constraintWithAttribute:NSLayoutAttributeRight relatedBy:NSLayoutRelationEqual toItem:JVLayoutItemPrivacy attribute:NSLayoutAttributeLeft multiplier:1 constant:-5];
    JVLayoutConstraint *boxY = [JVLayoutConstraint constraintWithAttribute:NSLayoutAttributeTop relatedBy:NSLayoutRelationEqual toItem:JVLayoutItemPrivacy attribute:NSLayoutAttributeTop multiplier:1 constant:1];
    JVLayoutConstraint *boxW = [JVLayoutConstraint constraintWithAttribute:NSLayoutAttributeWidth relatedBy:NSLayoutRelationEqual toItem:JVLayoutItemNone attribute:NSLayoutAttributeWidth multiplier:1 constant:kSDJVCheckboxSize];
    JVLayoutConstraint *boxH = [JVLayoutConstraint constraintWithAttribute:NSLayoutAttributeHeight relatedBy:NSLayoutRelationEqual toItem:JVLayoutItemNone attribute:NSLayoutAttributeHeight multiplier:1 constant:kSDJVCheckboxSize];
    config.checkViewConstraints = @[boxX, boxY, boxW, boxH];
    config.checkViewHorizontalConstraints = config.checkViewConstraints;
    config.checkViewHidden = NO;
    config.privacyState = NO;
    UIImage *uncheckedImage = [UIImage imageNamed:@"jv_check_off"];
    if (uncheckedImage != nil) {
        config.uncheckedImg = uncheckedImage;
    }
    UIImage *checkedImage = [UIImage imageNamed:@"jv_check_on"];
    if (checkedImage != nil) {
        config.checkedImg = checkedImage;
    }

    // 运营商条款用系统浏览器打开，不进 SDK 自带的条款页。下面的导航栏配置只在 SDK 回退到自带页时生效。
    config.openPrivacyInBrowser = YES;
    if (hasOwnClause) {
        config.appPrivacys = @[[content.firstLine stringByAppendingString:@"\n"], @""];
    }
    config.privacyTextAlignment = NSTextAlignmentLeft;
    config.privacyShowBookSymbol = YES;
    config.privacyTextFontSize = kSDJVClauseTextSize;
    config.textVerAlignment = JVVerAlignmentMiddle;
    UIColor *clauseBaseColor = hasOwnClause ? SDJVColorARGB(0x00FFFFFF) : SDJVColorARGB(0x66FFFFFF);
    config.appPrivacyColor = @[clauseBaseColor, SDJVColorARGB(0xFF7F58F7)];

    // 没勾选协议就点一键登录：弹提示，弹窗留在原地。
    __weak typeof(self) weakSelf = self;
    config.customPrivacyAlertViewBlock = ^(UIViewController *vc, NSArray *appPrivacys, void (^loginAction)(void)) {
        [weakSelf showHintOnController:vc];
    };

    UIColor *privacyNavTitleColor = SDJVColorARGB(0xFFFFFFFF);
    UIFont *privacyNavTitleFont = [UIFont systemFontOfSize:16];
    config.agreementNavBackgroundColor = SDJVColorARGB(0xFF1C1C1C);
    config.agreementNavText = [[NSAttributedString alloc] initWithString:@"运营商服务条款"
                                                              attributes:@{NSForegroundColorAttributeName: privacyNavTitleColor,
                                                                           NSFontAttributeName: privacyNavTitleFont}];
    config.agreementNavTextColor = privacyNavTitleColor;
    config.agreementNavTextFont = privacyNavTitleFont;
    if (backImage != nil) {
        config.agreementNavReturnImage = backImage;
    }

    // 贴底弹窗：宽 = 屏幕宽，圆角 24，遮罩 0.7；按屏幕中心 + 偏移定位到贴底
    config.showWindow = YES;
    config.windowCornerRadius = kSDJVCornerRadius;
    config.windowBackgroundAlpha = kSDJVBackgroundAlpha;
    if (background != nil) {
        config.windowBackgroundImage = background;
    }
    JVLayoutConstraint *windowX = [JVLayoutConstraint constraintWithAttribute:NSLayoutAttributeCenterX relatedBy:NSLayoutRelationEqual toItem:JVLayoutItemSuper attribute:NSLayoutAttributeCenterX multiplier:1 constant:0];
    JVLayoutConstraint *windowY = [JVLayoutConstraint constraintWithAttribute:NSLayoutAttributeCenterY relatedBy:NSLayoutRelationEqual toItem:JVLayoutItemSuper attribute:NSLayoutAttributeCenterY multiplier:1 constant:layout.offsetCenterY];
    JVLayoutConstraint *windowW = [JVLayoutConstraint constraintWithAttribute:NSLayoutAttributeWidth relatedBy:NSLayoutRelationEqual toItem:JVLayoutItemNone attribute:NSLayoutAttributeWidth multiplier:1 constant:layout.width];
    JVLayoutConstraint *windowH = [JVLayoutConstraint constraintWithAttribute:NSLayoutAttributeHeight relatedBy:NSLayoutRelationEqual toItem:JVLayoutItemNone attribute:NSLayoutAttributeHeight multiplier:1 constant:layout.height];
    config.windowConstraints = @[windowX, windowY, windowW, windowH];
    config.windowHorizontalConstraints = config.windowConstraints;

    return config;
}

#pragma mark - 自定义控件（对照 jverify_login.dart 的 buildJverifyAuthWidgets）

/// 弹窗里 SDK 不提供的部分：欢迎语、头图、拖动条、一键登录按钮上的图标、短信登录和微信登录两个按钮、协议第一行。
/// 后加的控件画在上面，所以图标排在它所在的按钮后面。
- (void)installCustomViewsOn:(UIView *)area content:(SDJVSheetContent *)content layout:(SDJVSheetLayout)layout {
    if (area == nil) {
        return;
    }
    CGFloat width = layout.width;
    CGFloat textWidth = width - kSDJVSidePadding * 2 - kSDJVHeaderArtSize;

    if (content.title.length > 0) {
        UILabel *title = [[UILabel alloc] initWithFrame:CGRectMake(kSDJVSidePadding + 4, kSDJVTitleTop, textWidth, 30)];
        title.text = content.title;
        title.font = [UIFont systemFontOfSize:20 weight:UIFontWeightSemibold];
        title.textColor = SDJVColorARGB(0xFFFFFFFF);
        title.textAlignment = NSTextAlignmentLeft;
        title.adjustsFontSizeToFitWidth = YES;
        title.minimumScaleFactor = 0.7;
        [area addSubview:title];
    }
    if (content.subtitle.length > 0) {
        UILabel *subtitle = [[UILabel alloc] initWithFrame:CGRectMake(kSDJVSidePadding + 4, kSDJVSubtitleTop, textWidth, 18)];
        subtitle.text = content.subtitle;
        subtitle.font = [UIFont systemFontOfSize:12];
        subtitle.textColor = SDJVColorARGB(0x66FFFFFF);
        subtitle.textAlignment = NSTextAlignmentLeft;
        subtitle.adjustsFontSizeToFitWidth = YES;
        subtitle.minimumScaleFactor = 0.7;
        [area addSubview:subtitle];
    }

    // 头图、拖动条
    [self addImageNamed:@"jv_sheet_header"
                  frame:CGRectMake(width - kSDJVSidePadding - kSDJVHeaderArtSize, 16, kSDJVHeaderArtSize, kSDJVHeaderArtSize)
                     to:area];
    [self addImageNamed:@"jv_sheet_handle" frame:CGRectMake(floor((width - 40) / 2), 10, 40, 6) to:area];

    // 一键登录是 SDK 自己的按钮，只补上文字左边的图标。图标不接收点击，点在它上面等于点按钮。
    UIFont *loginFont = [UIFont boldSystemFontOfSize:kSDJVButtonTextSize];
    CGFloat iconTopInset = floor((kSDJVButtonHeight - kSDJVButtonIconSize) / 2);
    [self addImageNamed:@"jv_login_phone"
                  frame:CGRectMake(round(SDJVButtonIconLeft(layout, content.loginTitle, loginFont, kSDJVOneClickIconGap)),
                                   SDJVButtonTopAt(0) + iconTopInset,
                                   kSDJVButtonIconSize,
                                   kSDJVButtonIconSize)
                     to:area];

    // 短信登录、微信登录：和一键登录等宽等高，相邻两个隔 16
    UIFont *otherFont = [UIFont systemFontOfSize:kSDJVButtonTextSize];
    NSMutableArray<NSArray<NSString *> *> *others = [NSMutableArray array];
    if (content.smsTitle.length > 0) {
        [others addObject:@[kSDJVActionSms, content.smsTitle, @"jv_login_sms"]];
    }
    if (content.wechatTitle.length > 0) {
        [others addObject:@[kSDJVActionWechat, content.wechatTitle, @"jv_login_wechat"]];
    }
    for (NSUInteger position = 0; position < others.count; position++) {
        NSArray<NSString *> *entry = others[position];
        NSInteger index = (NSInteger)position + 1;
        UIButton *button = [UIButton buttonWithType:UIButtonTypeCustom];
        button.frame = CGRectMake(layout.buttonLeft, SDJVButtonTopAt(index), layout.buttonWidth, kSDJVButtonHeight);
        NSString *buttonTitle = [kSDJVButtonIconSpace stringByAppendingString:entry[1]];
        [button setTitle:buttonTitle forState:UIControlStateNormal];
        [button setTitle:buttonTitle forState:UIControlStateHighlighted];
        [button setTitleColor:SDJVColorARGB(0x99FFFFFF) forState:UIControlStateNormal];
        button.titleLabel.font = otherFont;
        button.contentHorizontalAlignment = UIControlContentHorizontalAlignmentCenter;
        UIImage *secondary = SDJVPillImage(@"jv_btn_secondary");
        UIImage *secondaryPressed = SDJVPillImage(@"jv_btn_secondary_pressed") ?: secondary;
        if (secondary != nil) {
            [button setBackgroundImage:secondary forState:UIControlStateNormal];
        }
        if (secondaryPressed != nil) {
            [button setBackgroundImage:secondaryPressed forState:UIControlStateHighlighted];
            [button setBackgroundImage:secondaryPressed forState:UIControlStateSelected];
        }
        [button addTarget:self action:@selector(otherWayButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
        [self.widgetActions setObject:entry[0] forKey:button];
        [area addSubview:button];

        [self addImageNamed:entry[2]
                      frame:CGRectMake(round(SDJVButtonIconLeft(layout, entry[1], otherFont, kSDJVOtherWaysIconGap)),
                                       SDJVButtonTopAt(index) + iconTopInset,
                                       kSDJVButtonIconSize,
                                       kSDJVButtonIconSize)
                         to:area];
    }

    // 协议第一行：排在勾选框右边，条款名可点。每段左对齐，从上一段文字结束的位置起排。
    UIFont *clauseFont = [UIFont systemFontOfSize:kSDJVClauseTextSize];
    CGFloat total = 0;
    for (SDJVClausePart *part in content.clauseParts) {
        total += SDJVTextWidth(part.text, clauseFont);
    }
    CGFloat x = SDJVClauseTextLeft(layout, total);
    for (SDJVClausePart *part in content.clauseParts) {
        CGFloat advance = SDJVTextWidth(part.text, clauseFont);
        // 右侧多留一点宽度防止被截断，多出的部分是空的，压在下一段上不影响显示。
        UILabel *label = [[UILabel alloc] initWithFrame:CGRectMake(round(x), layout.clauseTop, ceil(advance) + 8, kSDJVClauseLineHeight)];
        label.text = part.text;
        label.font = clauseFont;
        label.textAlignment = NSTextAlignmentLeft;
        label.textColor = part.isLink ? SDJVColorARGB(0xFF7F58F7) : SDJVColorARGB(0x66FFFFFF);
        if (part.isLink) {
            label.userInteractionEnabled = YES;
            UITapGestureRecognizer *tap = [[UITapGestureRecognizer alloc] initWithTarget:self action:@selector(clauseLinkTapped:)];
            tap.numberOfTapsRequired = 1;
            [label addGestureRecognizer:tap];
            [self.widgetActions setObject:part forKey:label];
        }
        [area addSubview:label];
        x += advance;
    }

    [self installSheetDismissOn:area];
}

/// 只当图片用的控件（头图、拖动条、压在按钮上的图标）：不接收触摸，画在同级的 SDK 控件上面。
- (void)addImageNamed:(NSString *)name frame:(CGRect)frame to:(UIView *)area {
    UIImage *image = [UIImage imageNamed:name];
    if (image == nil) {
        return;
    }
    UIImageView *imageView = [[UIImageView alloc] initWithFrame:frame];
    imageView.image = image;
    imageView.contentMode = UIViewContentModeScaleAspectFit;
    imageView.userInteractionEnabled = NO;
    imageView.layer.zPosition = 1;
    [area addSubview:imageView];
}

- (void)otherWayButtonTapped:(UIButton *)button {
    id action = [self.widgetActions objectForKey:button];
    if (![action isKindOfClass:[NSString class]] || self.authCompletion == nil) {
        return;
    }
    // 微信、短信和一键登录一样要先勾选协议：没勾选就提示，弹窗留在原地。
    if (!self.privacyChecked) {
        [self showHintOnController:self.sheetController];
        return;
    }
    [self leaveWithAction:(NSString *)action];
}

- (void)clauseLinkTapped:(UITapGestureRecognizer *)recognizer {
    if (recognizer.state != UIGestureRecognizerStateEnded) {
        return;
    }
    id part = [self.widgetActions objectForKey:recognizer.view];
    if (![part isKindOfClass:[SDJVClausePart class]]) {
        return;
    }
    SDJVClausePart *clause = (SDJVClausePart *)part;
    void (^handler)(NSString *, NSString *, UIViewController *_Nullable) = self.privacyLinkHandler;
    if (handler != nil) {
        handler(clause.name, clause.url, [self hintHost]);
    }
}

#pragma mark - 提示

/// 授权页盖在 Kuikly 页面上面，Kuikly 的 toast 会被挡住，提示由原生弹（与参考工程 iOS 端一致：提示框）。
- (void)showHintOnController:(nullable UIViewController *)controller {
    NSString *text = self.uncheckedHint;
    if (text.length == 0) {
        return;
    }
    SDJVOnMain(^{
        UIViewController *host = controller;
        if (host == nil || host.view.window == nil) {
            host = [self hintHost];
        }
        while (host.presentedViewController != nil && !host.presentedViewController.isBeingDismissed) {
            host = host.presentedViewController;
        }
        // 已经有提示框在屏幕上就不再叠一个。
        if (host == nil || [host isKindOfClass:[UIAlertController class]]) {
            return;
        }
        UIAlertController *alert = [UIAlertController alertControllerWithTitle:text message:nil preferredStyle:UIAlertControllerStyleAlert];
        [alert addAction:[UIAlertAction actionWithTitle:@"确定" style:UIAlertActionStyleDefault handler:nil]];
        [host presentViewController:alert animated:YES completion:nil];
    });
}

- (nullable UIViewController *)hintHost {
    UIViewController *host = self.sheetController;
    if (host == nil || host.view.window == nil) {
        host = [self topViewController];
    }
    return host;
}

- (nullable UIViewController *)topViewController {
    UIWindow *keyWindow = nil;
    for (UIScene *scene in [UIApplication sharedApplication].connectedScenes) {
        if (![scene isKindOfClass:[UIWindowScene class]]) {
            continue;
        }
        for (UIWindow *window in ((UIWindowScene *)scene).windows) {
            if (window.isKeyWindow) {
                keyWindow = window;
                break;
            }
            if (keyWindow == nil && !window.isHidden) {
                keyWindow = window;
            }
        }
        if (keyWindow.isKeyWindow) {
            break;
        }
    }
    UIViewController *controller = keyWindow.rootViewController;
    while (controller.presentedViewController != nil && !controller.presentedViewController.isBeingDismissed) {
        controller = controller.presentedViewController;
    }
    return controller;
}

#pragma mark - 贴底弹窗的关闭手势（对照 JverifyPlugin.m 的 installSheetDismissOn:config:）

/// 补上两种关闭手势：点弹窗外的空白处，或按住弹窗顶部向下滑。
- (void)installSheetDismissOn:(UIView *)customAreaView {
    __weak typeof(self) weakSelf = self;
    __weak UIView *weakArea = customAreaView;
    // 等视图挂进授权页控制器之后再找它的根视图。
    dispatch_async(dispatch_get_main_queue(), ^{
        __strong typeof(weakSelf) strongSelf = weakSelf;
        UIView *area = weakArea;
        if (strongSelf == nil || area == nil) {
            return;
        }
        // 手势挂在控制器的根视图上，弹窗外的遮罩区域也能收到；找不到控制器就退回弹窗本身，只剩下滑可用。
        UIResponder *responder = area;
        while (responder != nil && ![responder isKindOfClass:[UIViewController class]]) {
            responder = responder.nextResponder;
        }
        UIView *host = area;
        if ([responder isKindOfClass:[UIViewController class]]) {
            strongSelf.sheetController = (UIViewController *)responder;
            host = ((UIViewController *)responder).view ?: area;
        }

        UITapGestureRecognizer *tap = [[UITapGestureRecognizer alloc] initWithTarget:strongSelf action:@selector(sheetOutsideTapped:)];
        tap.delegate = strongSelf;
        tap.cancelsTouchesInView = NO;
        [host addGestureRecognizer:tap];

        UIPanGestureRecognizer *pan = [[UIPanGestureRecognizer alloc] initWithTarget:strongSelf action:@selector(sheetPanned:)];
        pan.delegate = strongSelf;
        pan.cancelsTouchesInView = NO;
        [host addGestureRecognizer:pan];
    });
}

- (void)sheetOutsideTapped:(UITapGestureRecognizer *)recognizer {
    if (recognizer.state == UIGestureRecognizerStateEnded) {
        [self leaveWithAction:@"cancel"];
    }
}

- (void)sheetPanned:(UIPanGestureRecognizer *)recognizer {
    if (recognizer.state != UIGestureRecognizerStateEnded) {
        return;
    }
    CGPoint moved = [recognizer translationInView:recognizer.view];
    CGPoint velocity = [recognizer velocityInView:recognizer.view];
    // 下滑超过 40，或者是一次明显的向下轻扫。
    if (moved.y >= 40 || velocity.y >= 600) {
        [self leaveWithAction:@"cancel"];
    }
}

/// 点击只认弹窗上方的空白处；下滑只认从弹窗顶部 48 高的区域起手。其余触摸照常交给授权页的控件。
- (BOOL)gestureRecognizer:(UIGestureRecognizer *)gestureRecognizer shouldReceiveTouch:(UITouch *)touch {
    CGFloat y = [touch locationInView:nil].y;
    if ([gestureRecognizer isKindOfClass:[UITapGestureRecognizer class]]) {
        return y < self.sheetTop;
    }
    return y >= self.sheetTop && y <= self.sheetTop + 48;
}

@end
