import Foundation

/// 友盟统计（契约见 shared 的 `SDBizModules.kt` → `SDAnalyticsModule`）。
///
/// SDK 调用在 ObjC 适配层 `SDUmengBridge` 里。隐私合规：`init` 之前不会触碰友盟，
/// 未初始化时的事件 / 页面 / 账号调用直接丢弃。页面统计是手动模式，由 Kuikly 侧的路由驱动。
@objc(SDAnalyticsModule)
@objcMembers
final class SDAnalyticsModule: KRBaseModule {

    /// params: {appKey, channel}
    ///
    /// Kuikly 侧的方法名是 `init`，对应的 ObjC 选择子是 `init:`。Swift 里不能把普通方法直接命名为 `init`，
    /// 这里用 `@objc(init:)` 指定选择子；它是一个普通方法（不是构造器），返回 Void。
    @objc(init:)
    func initSDK(_ args: NSDictionary) {
        let params = SD.params(args)
        let appKey = params.sdString("appKey")
        let channel = params.sdString("channel")
        SD.onMain {
            _ = SDUmengBridge.start(appKey: appKey, channel: channel)
        }
    }

    /// params: {name, props?:{string|number}}
    func onEvent(_ args: NSDictionary) {
        let params = SD.params(args)
        let name = params.sdString("name")
        if name.isEmpty {
            return
        }
        SDUmengBridge.track(event: name, attributes: params.sdDict("props"))
    }

    /// params: {name}
    func onPageStart(_ args: NSDictionary) {
        let name = SD.params(args).sdString("name")
        if name.isEmpty {
            return
        }
        SDUmengBridge.beginPage(name)
    }

    /// params: {name}
    func onPageEnd(_ args: NSDictionary) {
        let name = SD.params(args).sdString("name")
        if name.isEmpty {
            return
        }
        SDUmengBridge.endPage(name)
    }

    /// params: {userId}
    func signIn(_ args: NSDictionary) {
        let userId = SD.params(args).sdString("userId")
        if userId.isEmpty {
            return
        }
        SDUmengBridge.signIn(userId: userId)
    }

    func signOff(_ args: NSDictionary) {
        SDUmengBridge.signOff()
    }
}
