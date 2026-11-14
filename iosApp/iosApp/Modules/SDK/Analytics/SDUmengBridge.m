#import "SDUmengBridge.h"

// 与参考工程 umeng_common_sdk 1.3.1（UmengCommonSdkPlugin.m）相同的引入方式。
#if __has_include(<UMCommon/UMConfigure.h>)
#import <UMCommon/UMConfigure.h>
#import <UMCommon/MobClick.h>
#elif __has_include(<UMCommon/UMCommon.h>)
#import <UMCommon/UMCommon.h>
#endif

/// 事件属性的字符串值上限（与参考工程 umeng_utils.dart 一致）。
static const NSUInteger kSDUmengMaxStringLength = 256;

static BOOL sSDUmengStarted = NO;

@implementation SDUmengBridge

+ (BOOL)isStarted {
    @synchronized (self) {
        return sSDUmengStarted;
    }
}

+ (BOOL)startWithAppKey:(NSString *)appKey channel:(NSString *)channel {
    NSCharacterSet *blank = [NSCharacterSet whitespaceAndNewlineCharacterSet];
    NSString *key = [appKey stringByTrimmingCharactersInSet:blank];
    if (key.length == 0) {
        return NO;
    }
    NSString *channelName = [channel stringByTrimmingCharactersInSet:blank];
    if (channelName.length == 0) {
        channelName = @"App Store";
    }
    @synchronized (self) {
        if (sSDUmengStarted) {
            return YES;
        }
        sSDUmengStarted = YES;
    }
    [UMConfigure initWithAppkey:key channel:channelName];
    // 手动页面统计：页面起止由 Kuikly 侧的路由驱动（onPageStart / onPageEnd）。
    [MobClick setAutoPageEnabled:NO];
    return YES;
}

+ (void)trackEvent:(NSString *)name attributes:(nullable NSDictionary<NSString *, id> *)attributes {
    if (!self.isStarted || name.length == 0) {
        return;
    }
    [MobClick event:name attributes:[self sanitizedAttributes:attributes]];
}

+ (void)beginPage:(NSString *)name {
    if (!self.isStarted || name.length == 0) {
        return;
    }
    [MobClick beginLogPageView:name];
}

+ (void)endPage:(NSString *)name {
    if (!self.isStarted || name.length == 0) {
        return;
    }
    [MobClick endLogPageView:name];
}

+ (void)signInWithUserId:(NSString *)userId {
    if (!self.isStarted || userId.length == 0) {
        return;
    }
    [MobClick profileSignInWithPUID:userId];
}

+ (void)signOff {
    if (!self.isStarted) {
        return;
    }
    [MobClick profileSignOff];
}

/// 友盟的事件属性只接受 NSString / NSNumber：字符串截到 256 字，布尔转 0/1，其它类型转成字符串。
+ (NSDictionary<NSString *, id> *)sanitizedAttributes:(nullable NSDictionary<NSString *, id> *)attributes {
    NSMutableDictionary<NSString *, id> *result = [NSMutableDictionary dictionary];
    if (![attributes isKindOfClass:[NSDictionary class]]) {
        return result;
    }
    [attributes enumerateKeysAndObjectsUsingBlock:^(id key, id value, BOOL *stop) {
        if (![key isKindOfClass:[NSString class]] || [(NSString *)key length] == 0) {
            return;
        }
        if (value == nil || value == [NSNull null]) {
            return;
        }
        if ([value isKindOfClass:[NSString class]]) {
            NSString *text = (NSString *)value;
            if (text.length > kSDUmengMaxStringLength) {
                NSRange range = [text rangeOfComposedCharacterSequencesForRange:NSMakeRange(0, kSDUmengMaxStringLength)];
                text = [text substringWithRange:range];
            }
            result[key] = text;
        } else if ([value isKindOfClass:[NSNumber class]]) {
            NSNumber *number = (NSNumber *)value;
            if (CFGetTypeID((__bridge CFTypeRef)number) == CFBooleanGetTypeID()) {
                result[key] = @(number.boolValue ? 1 : 0);
            } else {
                result[key] = number;
            }
        } else if ([value isKindOfClass:[NSDictionary class]] || [value isKindOfClass:[NSArray class]]) {
            NSData *data = [NSJSONSerialization isValidJSONObject:value]
                ? [NSJSONSerialization dataWithJSONObject:value options:0 error:nil]
                : nil;
            NSString *text = data != nil ? [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] : nil;
            if (text == nil) {
                text = [value description];
            }
            if (text.length > kSDUmengMaxStringLength) {
                NSRange range = [text rangeOfComposedCharacterSequencesForRange:NSMakeRange(0, kSDUmengMaxStringLength)];
                text = [text substringWithRange:range];
            }
            result[key] = text ?: @"";
        } else {
            result[key] = [value description] ?: @"";
        }
    }];
    return result;
}

@end
