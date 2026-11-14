#import <Foundation/Foundation.h>
#import "../iosApp/Kuikly/SDHotReloadRuntime.h"

static BOOL Run(SDHotReloadRuntime *runtime, NSString *pager, NSMutableArray *texts, NSUInteger *calls) {
    __block NSUInteger count = 0;
    NSError *error;
    NSDictionary *params = @{ @"platform":@"iOS", @"deviceWidth":@390, @"deviceHeight":@844, @"statusBarHeight":@44 };
    BOOL ok = [runtime callMethod:1 arguments:@[pager, @"ui_kit_demo", params]
                   nativeCallback:^id(NSInteger method, NSArray *args) {
        count++;
        if ((method==4 || method==11) && args.count>2 && [args[1] isEqual:@"text"]) [texts addObject:args[2]];
        if (method==15) NSLog(@"FATAL: %@",args);
        return method==6 ? @"100|30" : @"";
    } error:&error];
    if (calls) *calls=count;
    if (!ok) NSLog(@"ERROR: %@",error);
    return ok && count>100;
}

int main(int argc,const char *argv[]) {
    @autoreleasepool {
        if (argc<3) return 2;
        NSData *script=[NSData dataWithContentsOfFile:@(argv[1])];
        NSError *error;
        SDHotReloadRuntime *r1=[SDHotReloadRuntime runtimeWithScript:script expectedVersion:@(argv[2]) error:&error];
        NSCAssert(r1,@"bundle: %@",error);
        NSError *hashError;
        NSCAssert(![SDHotReloadRuntime runtimeWithScript:script expectedVersion:@"incorrect-version" error:&hashError] && hashError,@"下载产物与发布hash不一致必须拒绝");
        NSCAssert(r1==[SDHotReloadRuntime runtimeWithScript:script error:&error],@"同版本必须共享runtime");
        NSMutableArray *a=[NSMutableArray new],*b=[NSMutableArray new],*c=[NSMutableArray new];
        NSUInteger aCount=0,bCount=0,cCount=0;
        NSCAssert(Run(r1,@"pagerA",a,&aCount),@"pagerA渲染失败");
        NSCAssert(Run(r1,@"pagerB",b,&bCount),@"pagerB渲染失败");
        NSPredicate *originalTitle=[NSPredicate predicateWithFormat:@"SELF BEGINSWITH %@",@"UI Kit"];
        NSCAssert([a filteredArrayUsingPredicate:originalTitle].count && [b filteredArrayUsingPredicate:originalTitle].count,@"Native桥没有收到真实页面文本");
        [r1 callMethod:3 arguments:@[@"pagerA"] nativeCallback:^id(NSInteger m,NSArray *args){return @"";} error:&error];
        [r1 removePager:@"pagerA"];
        NSString *changed=[[[NSString alloc] initWithData:script encoding:NSUTF8StringEncoding] stringByReplacingOccurrencesOfString:@"UI Kit" withString:@"UI Kit Reload Check"];
        SDHotReloadRuntime *r2=[SDHotReloadRuntime runtimeWithScript:[changed dataUsingEncoding:NSUTF8StringEncoding] error:&error];
        NSCAssert(r2 && r2!=r1,@"代码变化必须创建新的runtime");
        NSCAssert(Run(r2,@"pagerC",c,&cCount),@"新版本渲染失败");
        NSPredicate *updatedTitle=[NSPredicate predicateWithFormat:@"SELF BEGINSWITH %@",@"UI Kit Reload Check"];
        NSCAssert([c filteredArrayUsingPredicate:updatedTitle].count,@"运行中没有切到新代码");
        error=nil;
        SDHotReloadRuntime *bad=[SDHotReloadRuntime runtimeWithScript:[@"var SondoHotReload={};" dataUsingEncoding:NSUTF8StringEncoding] error:&error];
        NSCAssert(!bad && error,@"不合法bundle必须拒绝");
        NSLog(@"PASS: 实际bundle，双pager共享与路由，销毁，运行中代码变化，拒绝坏bundle和hash不一致。Native指令 %@/%@/%@",@(aCount),@(bCount),@(cCount));
        return 0;
    }
}
