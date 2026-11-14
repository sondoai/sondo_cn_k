#import <Foundation/Foundation.h>

#if DEBUG
#import <OpenKuiklyIOSRender/KuiklyRenderViewControllerBaseDelegator.h>

NS_ASSUME_NONNULL_BEGIN
FOUNDATION_EXTERN NSNotificationName const SDHotReloadDidUpdateNotification;

@interface SDHotReloadDelegator : KuiklyRenderViewControllerBaseDelegator
@end

@interface SDHotReload : NSObject
+ (instancetype)shared;
@property (nonatomic, readonly) BOOL enabled;
- (void)fetchScript:(void (^)(NSData * _Nullable script, NSError * _Nullable error))completion;
@end
NS_ASSUME_NONNULL_END
#endif
