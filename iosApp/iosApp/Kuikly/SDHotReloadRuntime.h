#import <Foundation/Foundation.h>

#if DEBUG
NS_ASSUME_NONNULL_BEGIN

typedef id _Nullable (^SDHotReloadNativeCallback)(NSInteger method, NSArray *arguments);

/// 同一份调试产物的所有页面共享 JS 执行环境，保留业务单例的跨页面语义。
@interface SDHotReloadRuntime : NSObject
+ (nullable instancetype)runtimeWithScript:(NSData *)script error:(NSError **)error;
+ (nullable instancetype)runtimeWithScript:(NSData *)script expectedVersion:(NSString *)version error:(NSError **)error;
- (BOOL)callMethod:(NSInteger)method
        arguments:(NSArray *)arguments
   nativeCallback:(SDHotReloadNativeCallback)callback
            error:(NSError **)error;
- (void)removePager:(NSString *)pagerId;
@end

NS_ASSUME_NONNULL_END
#endif
