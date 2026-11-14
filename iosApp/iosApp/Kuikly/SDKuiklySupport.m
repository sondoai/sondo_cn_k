#import "SDKuiklySupport.h"
#import <OpenKuiklyIOSRender/NSObject+KR.h>
#import <OpenKuiklyIOSRender/KuiklyContextParam.h>

@implementation SDKuiklyArgs

+ (nullable NSString *)paramStringFromArgs:(nullable id)args {
    if (![args isKindOfClass:[NSDictionary class]]) {
        return nil;
    }
    id param = ((NSDictionary *)args)[KR_PARAM_KEY];
    if ([param isKindOfClass:[NSString class]]) {
        NSString *string = (NSString *)param;
        return string.length > 0 ? string : nil;
    }
    return nil;
}

+ (nullable KuiklyRenderCallback)callbackFromArgs:(nullable id)args {
    if (![args isKindOfClass:[NSDictionary class]]) {
        return nil;
    }
    id callback = ((NSDictionary *)args)[KR_CALLBACK_KEY];
    if (callback == nil || callback == [NSNull null]) {
        return nil;
    }
    return (KuiklyRenderCallback)callback;
}

@end

@implementation KRBaseModule (SDSupport)

- (nullable KuiklyRenderView *)sd_rootView {
    UIView *rootView = self.hr_rootView;
    if ([rootView isKindOfClass:[KuiklyRenderView class]]) {
        return (KuiklyRenderView *)rootView;
    }
    return nil;
}

- (nullable UIViewController *)sd_viewController {
    UIView *rootView = self.hr_rootView;
    if (rootView == nil) {
        return nil;
    }
    return [rootView kr_viewController];
}

- (NSString *)sd_pageName {
    NSString *pageName = self.hr_contextParam.pageName;
    return pageName ?: @"";
}

@end
