#import "SDKuiklyAdapters.h"
#import <objc/runtime.h>
#import <OpenKuiklyIOSRender/KuiklyRenderBridge.h>
#import <OpenKuiklyIOSRender/KRRouterModule.h>
#import <OpenKuiklyIOSRender/KRLogModule.h>
#import <OpenKuiklyIOSRender/KRConvertUtil.h>
#import <SDWebImage/SDWebImage.h>

static NSString *const kSDAssetsScheme = @"assets://";
static NSString *const kSDFileScheme = @"file://";
static NSString *const kSDImageErrorDomain = @"com.sondo.kuikly.image";

static id<SDPageRouting> gSDRouter = nil;
static __weak UIWindow *gSDHostWindow = nil;

#pragma mark - Font table

/// 业务字体族（Flutter pubspec.yaml 的 family）→ [[CSS 字重, PostScript 名], ...]。
/// PostScript 名取自 iosApp/Fonts 下字体文件的 name 表（nameID 6）。
static NSDictionary<NSString *, NSArray<NSArray *> *> *SDFontFamilyTable(void) {
    static NSDictionary<NSString *, NSArray<NSArray *> *> *table = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        table = @{
            @"poppins": @[
                @[@100, @"Poppins-Thin"],
                @[@300, @"Poppins-Light"],
                @[@400, @"Poppins-Regular"],
                @[@500, @"Poppins-Medium"],
                @[@600, @"Poppins-SemiBold"],
                @[@700, @"Poppins-Bold"]
            ],
            @"montserrat": @[
                @[@500, @"Montserrat-Medium"],
                @[@600, @"Montserrat-SemiBold"],
                @[@700, @"Montserrat-Bold"]
            ],
            @"anton": @[ @[@400, @"AntonSC-Regular"] ],
            @"bangers": @[ @[@400, @"Bangers"] ],
            @"lemonmilk": @[ @[@400, @"LemonMilk"] ],
            @"momcakebold": @[ @[@700, @"MOMCAKE-Bold"] ],
            @"momcakethin": @[ @[@100, @"MOMCAKE-Thin"] ],
            @"monotonregular": @[ @[@400, @"Monoton-Regular"] ],
            @"montserratblack": @[ @[@900, @"Montserrat-Black"] ],
            @"montserratblackitalic": @[ @[@900, @"MontserratAlternates-BlackItalic"] ],
            @"roboto": @[ @[@500, @"Roboto-Medium"] ],
            @"carter": @[ @[@400, @"CarterOne"] ],
            @"paytoneone": @[ @[@400, @"PaytoneOne"] ]
        };
    });
    return table;
}

/// UIFontWeight → CSS 字重（100…900）。
static NSInteger SDCSSWeightFromUIFontWeight(UIFontWeight weight) {
    if (weight <= (UIFontWeightUltraLight + UIFontWeightThin) / 2) { return 100; }
    if (weight <= (UIFontWeightThin + UIFontWeightLight) / 2) { return 200; }
    if (weight <= (UIFontWeightLight + UIFontWeightRegular) / 2) { return 300; }
    if (weight <= (UIFontWeightRegular + UIFontWeightMedium) / 2) { return 400; }
    if (weight <= (UIFontWeightMedium + UIFontWeightSemibold) / 2) { return 500; }
    if (weight <= (UIFontWeightSemibold + UIFontWeightBold) / 2) { return 600; }
    if (weight <= (UIFontWeightBold + UIFontWeightHeavy) / 2) { return 700; }
    if (weight <= (UIFontWeightHeavy + UIFontWeightBlack) / 2) { return 800; }
    return 900;
}

/// Kuikly 文本样式里的 fontWeight（"400" / "bold" / "normal" …）→ CSS 字重。
static NSInteger SDCSSWeightFromStyleValue(id value) {
    if (value == nil || value == [NSNull null]) {
        return 400;
    }
    NSString *text = [[value description] lowercaseString];
    if ([text isEqualToString:@"bold"]) { return 700; }
    if ([text isEqualToString:@"normal"] || text.length == 0) { return 400; }
    NSInteger number = [text integerValue];
    if (number >= 100 && number <= 900) {
        return number;
    }
    return 400;
}

#pragma mark - Log

@interface SDKuiklyLogHandler : NSObject <KuiklyLogProtocol>
@end

@implementation SDKuiklyLogHandler

- (BOOL)asyncLogEnable {
    return YES;
}

- (void)logInfo:(NSString *)message {
#if DEBUG
    NSLog(@"%@", message);
#endif
}

- (void)logDebug:(NSString *)message {
#if DEBUG
    NSLog(@"%@", message);
#endif
}

- (void)logError:(NSString *)message {
    NSLog(@"%@", message);
}

@end

#pragma mark - Router

@interface SDKuiklyRouterHandler : NSObject <KRRouterProtocol>
@end

@implementation SDKuiklyRouterHandler

- (void)openPageWithName:(NSString *)pageName
                pageData:(NSDictionary *)pageData
              controller:(UIViewController *)controller {
    if (![pageName isKindOfClass:[NSString class]] || pageName.length == 0) {
        return;
    }
    NSDictionary *data = [pageData isKindOfClass:[NSDictionary class]] ? pageData : nil;
    [gSDRouter openPageWithName:pageName pageData:data fromController:controller];
}

- (void)closePage:(UIViewController *)controller {
    [gSDRouter closePageOfController:controller];
}

@end

#pragma mark - Component expand (image / color / font)

@interface SDKuiklyComponentHandler : NSObject <KuiklyRenderComponentExpandProtocol>
@end

@implementation SDKuiklyComponentHandler {
    dispatch_queue_t _decodeQueue;
}

- (instancetype)init {
    if (self = [super init]) {
        _decodeQueue = dispatch_queue_create("com.sondo.kuikly.image.decode", DISPATCH_QUEUE_CONCURRENT);
    }
    return self;
}

#pragma mark image helpers

/// 字符串 → NSURL；含中文 / 空格等非法字符时先做百分号转义。
+ (nullable NSURL *)sd_URLFromString:(NSString *)string {
    if (string.length == 0) {
        return nil;
    }
    NSURL *url = [NSURL URLWithString:string];
    if (url != nil) {
        return url;
    }
    NSMutableCharacterSet *allowed = [[NSCharacterSet URLQueryAllowedCharacterSet] mutableCopy];
    [allowed addCharactersInString:@"#%[]"];
    NSString *escaped = [string stringByAddingPercentEncodingWithAllowedCharacters:allowed];
    return escaped.length > 0 ? [NSURL URLWithString:escaped] : nil;
}

/// 本地图片路径：`file://…`、绝对路径、`assets://…`（默认位置没找到时渲染器会把原始 src 传进来）。
+ (nullable NSString *)sd_localPathFromSource:(NSString *)source {
    if ([source hasPrefix:kSDAssetsScheme]) {
        NSString *relative = [source substringFromIndex:kSDAssetsScheme.length];
        return [SDKuiklyAdapters assetURLForRelativePath:relative].path;
    }
    if ([source hasPrefix:kSDFileScheme]) {
        NSURL *url = [self sd_URLFromString:source];
        if (url.isFileURL && url.path.length > 0) {
            return url.path;
        }
        NSString *raw = [source substringFromIndex:kSDFileScheme.length];
        return [raw stringByRemovingPercentEncoding] ?: raw;
    }
    if ([source hasPrefix:@"/"]) {
        return source;
    }
    return nil;
}

+ (NSError *)sd_errorWithCode:(NSInteger)code message:(NSString *)message {
    return [NSError errorWithDomain:kSDImageErrorDomain
                               code:code
                           userInfo:@{NSLocalizedDescriptionKey: message ?: @""}];
}

- (void)sd_loadLocalImageAtPath:(NSString *)path
                    callbackURL:(nullable NSURL *)callbackURL
                       complete:(ImageCompletionBlock)completeBlock {
    NSString *cacheKey = [@"sdlocal://" stringByAppendingString:path];
    UIImage *cached = [[SDImageCache sharedImageCache] imageFromMemoryCacheForKey:cacheKey];
    if (cached != nil) {
        if (completeBlock) {
            completeBlock(cached, nil, callbackURL);
        }
        return;
    }
    dispatch_async(_decodeQueue, ^{
        UIImage *image = nil;
        NSData *data = [NSData dataWithContentsOfFile:path options:NSDataReadingMappedIfSafe error:nil];
        if (data.length > 0) {
            image = [UIImage sd_imageWithData:data];
            if (image != nil && image.images == nil) {
                // 提前解码，避免首次上屏时在主线程解码造成掉帧
                image = [SDImageCoderHelper decodedImageWithImage:image] ?: image;
            }
        }
        if (image != nil) {
            [[SDImageCache sharedImageCache] storeImageToMemory:image forKey:cacheKey];
        }
        NSError *error = image != nil ? nil : [SDKuiklyComponentHandler sd_errorWithCode:-2 message:@"local image not found or undecodable"];
        dispatch_async(dispatch_get_main_queue(), ^{
            if (completeBlock) {
                completeBlock(image, error, callbackURL);
            }
        });
    });
}

#pragma mark KuiklyRenderComponentExpandProtocol - image

/// 旧接口（协议 @required）。渲染器优先调用下面带 complete 的版本，这里只做兜底。
- (BOOL)hr_setImageWithUrl:(nullable NSString *)url forImageView:(UIImageView *)imageView {
    if (url.length == 0) {
        [imageView sd_cancelCurrentImageLoad];
        imageView.image = nil;
        return YES;
    }
    NSString *localPath = [SDKuiklyComponentHandler sd_localPathFromSource:url];
    if (localPath != nil) {
        [imageView sd_cancelCurrentImageLoad];
        imageView.image = [UIImage imageWithContentsOfFile:localPath];
        return YES;
    }
    [imageView sd_setImageWithURL:[SDKuiklyComponentHandler sd_URLFromString:url]];
    return YES;
}

/// 推荐接口：只负责加载，图片由渲染器在校验 src 一致后设置（complete 必须回调）。
- (BOOL)hr_setImageWithUrl:(nonnull NSString *)loadURL
               imageParams:(NSDictionary *_Nullable)imageParams
                  complete:(ImageCompletionBlock)completeBlock {
    // url 为空 = 取消上一次加载（渲染器在切换到 base64 图片前会这样调用）。
    if (![loadURL isKindOfClass:[NSString class]] || loadURL.length == 0) {
        if (completeBlock) {
            completeBlock(nil, [SDKuiklyComponentHandler sd_errorWithCode:-1 message:@"empty image url"], nil);
        }
        return YES;
    }

    // 注意：回调的 imageURL 必须对应入参 loadURL（渲染器用它做 src 一致性校验），不能用 SDWebImage 回传的 URL。
    NSURL *callbackURL = [SDKuiklyComponentHandler sd_URLFromString:loadURL];

    NSString *localPath = [SDKuiklyComponentHandler sd_localPathFromSource:loadURL];
    if (localPath != nil) {
        if (callbackURL == nil || [loadURL hasPrefix:@"/"]) {
            callbackURL = [NSURL fileURLWithPath:localPath];
        }
        [self sd_loadLocalImageAtPath:localPath callbackURL:callbackURL complete:completeBlock];
        return YES;
    }

    if ([loadURL hasPrefix:kSDAssetsScheme]) {
        // assets 资源不存在
        if (completeBlock) {
            completeBlock(nil, [SDKuiklyComponentHandler sd_errorWithCode:-2 message:@"asset not found"], callbackURL);
        }
        return YES;
    }

    if (callbackURL == nil) {
        if (completeBlock) {
            completeBlock(nil, [SDKuiklyComponentHandler sd_errorWithCode:-3 message:@"invalid image url"], nil);
        }
        return YES;
    }

    [[SDWebImageManager sharedManager] loadImageWithURL:callbackURL
                                                options:SDWebImageAvoidAutoSetImage | SDWebImageRetryFailed
                                               progress:nil
                                              completed:^(UIImage *_Nullable image,
                                                          NSData *_Nullable data,
                                                          NSError *_Nullable error,
                                                          SDImageCacheType cacheType,
                                                          BOOL finished,
                                                          NSURL *_Nullable imageURL) {
        if (!finished) {
            return;
        }
        if (completeBlock) {
            completeBlock(image, error, callbackURL);
        }
    }];
    return YES;
}

/// assets:// 资源定位（图片、PAG 等都会走这里）。返回 nil 时渲染器继续按 mainBundle 默认规则查找。
- (NSURL *)hr_customBundleUrlForFileName:(NSString *)fileName extension:(NSString *)fileExtension {
    if (fileName.length == 0) {
        return nil;
    }
    NSString *relative = fileExtension.length > 0 ? [fileName stringByAppendingPathExtension:fileExtension] : fileName;
    return [SDKuiklyAdapters assetURLForRelativePath:relative ?: fileName];
}

#pragma mark KuiklyRenderComponentExpandProtocol - color / font

/// 颜色走渲染器默认解析。
- (UIColor *)hr_colorWithValue:(NSString *)value {
    return nil;
}

- (UIFont *)hr_fontWithFontFamily:(NSString *)fontfamily
                         fontSize:(CGFloat)fontSize
                       fontWeight:(UIFontWeight)fontWeight
                         isItalic:(BOOL)isItalic {
    if (fontfamily.length == 0) {
        return nil;
    }
    return [SDKuiklyAdapters fontWithFamily:fontfamily
                                       size:fontSize
                                  cssWeight:SDCSSWeightFromUIFontWeight(fontWeight)];
}

@end

#pragma mark - KRConvertUtil font hook

/// 渲染器解析字体的顺序是「UIFont fontWithName:fontFamily → 动态注册 → 适配器」。
/// `fontWithName:@"Poppins"` 会直接命中字体族并返回 Regular 字面，导致 fontWeight 被忽略，
/// 所以这里在渲染器之前先按「业务族名 + 字重」解析一次；不认识的族名原样交回渲染器。
@interface KRConvertUtil (SDFontHook)
+ (UIFont *)sd_UIFont:(id)json;
@end

@implementation KRConvertUtil (SDFontHook)

+ (UIFont *)sd_UIFont:(id)json {
    if ([json isKindOfClass:[NSDictionary class]]) {
        NSDictionary *style = (NSDictionary *)json;
        id family = style[@"fontFamily"];
        if ([family isKindOfClass:[NSString class]] && [(NSString *)family length] > 0) {
            CGFloat fontSize = [KRConvertUtil CGFloat:style[@"fontSize"]];
            if (fontSize <= 0) {
                fontSize = 15;
            }
            UIFont *font = [SDKuiklyAdapters fontWithFamily:(NSString *)family
                                                       size:fontSize
                                                  cssWeight:SDCSSWeightFromStyleValue(style[@"fontWeight"])];
            if (font != nil) {
                return font;
            }
        }
    }
    // 方法实现已交换：这里调用的是渲染器原本的 +UIFont:。
    return [self sd_UIFont:json];
}

@end

#pragma mark - SDKuiklyAdapters

@implementation SDKuiklyAdapters

+ (void)installWithRouter:(id<SDPageRouting>)router {
    gSDRouter = router;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        [KuiklyRenderBridge registerComponentExpandHandler:[[SDKuiklyComponentHandler alloc] init]];
        [KuiklyRenderBridge registerLogHandler:[[SDKuiklyLogHandler alloc] init]];
        [KRRouterModule registerRouterHandler:[[SDKuiklyRouterHandler alloc] init]];
        [self sd_installFontHook];
    });
}

+ (void)sd_installFontHook {
    Class cls = [KRConvertUtil class];
    Method original = class_getClassMethod(cls, @selector(UIFont:));
    Method replacement = class_getClassMethod(cls, @selector(sd_UIFont:));
    if (original != NULL && replacement != NULL) {
        method_exchangeImplementations(original, replacement);
    }
}

+ (id<SDPageRouting>)router {
    return gSDRouter;
}

+ (UIWindow *)hostWindow {
    return gSDHostWindow;
}

+ (void)setHostWindow:(UIWindow *)hostWindow {
    gSDHostWindow = hostWindow;
}

+ (nullable NSURL *)assetURLForRelativePath:(NSString *)relativePath {
    if (relativePath.length == 0) {
        return nil;
    }
    static NSCache<NSString *, NSURL *> *cache = nil;
    static NSArray<NSURL *> *roots = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        cache = [[NSCache alloc] init];
        NSBundle *mainBundle = [NSBundle mainBundle];
        NSMutableArray<NSURL *> *candidates = [NSMutableArray array];
        // 1. App 包根目录：shared pod 的 resources（src/commonMain/assets/**）由 CocoaPods 的
        //    「[CP] Copy Pods Resources」原样拷到这里（iosApp.app/common/...）。
        if (mainBundle.resourceURL != nil) {
            [candidates addObject:mainBundle.resourceURL];
        }
        // 2. 兜底：shared 以动态 framework 集成时资源在 framework 包内。
        NSURL *frameworkURL = [mainBundle.privateFrameworksURL URLByAppendingPathComponent:@"shared.framework"];
        if (frameworkURL != nil) {
            [candidates addObject:frameworkURL];
        }
        // 3. 兜底：以 resource bundle 方式打包。
        NSURL *bundleURL = [mainBundle.resourceURL URLByAppendingPathComponent:@"shared.bundle"];
        if (bundleURL != nil) {
            [candidates addObject:bundleURL];
        }
        roots = [candidates copy];
    });

    NSString *decoded = [relativePath stringByRemovingPercentEncoding] ?: relativePath;
    while ([decoded hasPrefix:@"/"]) {
        decoded = [decoded substringFromIndex:1];
    }
    if (decoded.length == 0 || [decoded containsString:@".."]) {
        return nil;
    }

    NSURL *cached = [cache objectForKey:decoded];
    if (cached != nil) {
        return cached;
    }
    NSFileManager *fileManager = [NSFileManager defaultManager];
    for (NSURL *root in roots) {
        NSURL *candidate = [root URLByAppendingPathComponent:decoded];
        if (candidate.path.length > 0 && [fileManager fileExistsAtPath:candidate.path]) {
            [cache setObject:candidate forKey:decoded];
            return candidate;
        }
    }
    return nil;
}

+ (nullable UIFont *)fontWithFamily:(NSString *)family size:(CGFloat)size cssWeight:(NSInteger)cssWeight {
    if (family.length == 0) {
        return nil;
    }
    NSArray<NSArray *> *faces = SDFontFamilyTable()[family.lowercaseString];
    if (faces.count == 0) {
        return nil;
    }
    // 取字重最接近的字面；等距时字重 ≥ 500 取更粗的，否则取更细的（与 CSS / Flutter 的匹配方向一致）。
    NSString *bestName = nil;
    NSInteger bestDistance = NSIntegerMax;
    NSInteger bestWeight = 0;
    for (NSArray *face in faces) {
        NSInteger faceWeight = [face[0] integerValue];
        NSInteger distance = labs((long)(faceWeight - cssWeight));
        BOOL better = distance < bestDistance;
        if (!better && distance == bestDistance) {
            better = cssWeight >= 500 ? faceWeight > bestWeight : faceWeight < bestWeight;
        }
        if (better) {
            bestDistance = distance;
            bestWeight = faceWeight;
            bestName = face[1];
        }
    }
    if (bestName == nil) {
        return nil;
    }
    return [UIFont fontWithName:bestName size:size];
}

+ (void)clearLocalImageMemoryCache {
    [[SDImageCache sharedImageCache] clearMemory];
}

@end
