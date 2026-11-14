import Foundation
import UIKit

/// 极光推送（契约见 shared 的 `SDBizModules.kt` → `SDPushModule`）。
///
/// SDK 调用在 ObjC 适配层 `SDJPushBridge` 里。隐私合规：`setup` 之前不会触碰极光
/// （`setup` 内部先开启采集授权，再注册 APNs、初始化极光）；前台收到推送不弹系统横幅。
///
/// 事件（`addEventListener`）：
/// - `{event:"connected"}`
/// - `{event:"notificationReceived", title, alert, extras:{}}`   前台收到
/// - `{event:"notificationOpened", title, alert, extras:{}}`     用户点开
/// extras 是 APNs payload 去掉 `aps` 和极光自己的字段后的业务字段（jumpAddress / jumpParams …）。
@objc(SDPushModule)
@objcMembers
final class SDPushModule: KRBaseModule {

    /// 向所有存活页面广播；还没有页面监听时先暂存。
    static let events = SDSDKEventHub(maxPending: 16)

    deinit {
        SDPushModule.events.remove(ownerId: ObjectIdentifier(self))
    }

    /// params: {appKey, channel, production, debug} → {ok}
    func setup(_ args: NSDictionary) {
        let params = SD.params(args)
        let done = SD.once(args)
        let appKey = params.sdString("appKey")
        let channel = params.sdString("channel")
        let production = params.sdBool("production")
        let debug = params.sdBool("debug")
        SD.onMain {
            if SDPushCenter.shared.setup(appKey: appKey, channel: channel, production: production, debug: debug) {
                done.success()
            } else {
                done.failure("empty_app_key")
            }
        }
    }

    /// → {ok, rid}（未连上时为空串）
    func getRegistrationId(_ args: NSDictionary) {
        SD.once(args).success(["rid": SDJPushBridge.shared.registrationID()])
    }

    /// 冷启动时点击的通知。→ {ok, title?, alert?, extras?}；读取后清空。
    func getLaunchNotification(_ args: NSDictionary) {
        let done = SD.once(args)
        SD.onMain {
            done.success(SDPushCenter.shared.takeLaunchNotification() ?? [:])
        }
    }

    /// params: {count}
    func setBadge(_ args: NSDictionary) {
        let count = SD.params(args).sdInt("count")
        SDJPushBridge.shared.setBadge(count)
    }

    func addEventListener(_ args: NSDictionary) {
        guard let callback = SD.callback(args) else { return }
        SDPushModule.events.add(owner: self, callback: callback)
    }
}

/// 极光推送的进程级状态：事件转发、APNs token 与后台推送的订阅（见 `AppEvents`）。仅主线程使用。
final class SDPushCenter {

    static let shared = SDPushCenter()

    private var observers: [NSObjectProtocol] = []
    private var subscribed = false

    private init() {}

    /// 初始化极光（重复调用只生效一次）。appKey 为空返回 false。
    func setup(appKey: String, channel: String, production: Bool, debug: Bool) -> Bool {
        SDJPushBridge.shared.eventHandler = { event, payload in
            var message: [String: Any] = payload
            message["event"] = event
            SDPushModule.events.emit(message)
        }
        noteLaunchOptions()

        var launchOptions: [AnyHashable: Any] = [:]
        if let options = AppEvents.launchOptions {
            for (key, value) in options {
                launchOptions[key.rawValue] = value
            }
        }
        let ok = SDJPushBridge.shared.setup(appKey: appKey,
                                            channel: channel,
                                            production: production,
                                            debug: debug,
                                            launchOptions: launchOptions.isEmpty ? nil : launchOptions,
                                            deviceToken: AppEvents.deviceToken)
        if ok {
            subscribeIfNeeded()
        }
        return ok
    }

    /// 冷启动 / 极光初始化之前被点开的通知；读取后清空。
    func takeLaunchNotification() -> [String: Any]? {
        noteLaunchOptions()
        return SDJPushBridge.shared.takeLaunchNotification()
    }

    /// 非 scene 工程里，被点开的通知直接放在 launchOptions 里（scene 工程由适配层的系统 delegate 接住）。
    private var launchOptionsNoted = false

    private func noteLaunchOptions() {
        if launchOptionsNoted {
            return
        }
        launchOptionsNoted = true
        if let remote = AppEvents.launchOptions?[.remoteNotification] as? [AnyHashable: Any] {
            SDJPushBridge.shared.noteLaunchRemoteNotification(remote)
        }
    }

    private func subscribeIfNeeded() {
        if subscribed {
            return
        }
        subscribed = true
        // APNs device token → 极光
        observers.append(AppEvents.observe(.sdAppDidRegisterForRemoteNotifications) { note in
            guard let token = note.userInfo?[AppEvents.Key.deviceToken] as? Data else { return }
            SDJPushBridge.shared.registerDeviceToken(token)
        })
        // 后台 / 静默推送（application:didReceiveRemoteNotification:fetchCompletionHandler:）：只做送达统计，
        // 前台展示与点击走极光的通知中心回调。
        observers.append(AppEvents.observe(.sdAppDidReceiveRemoteNotification) { note in
            guard let userInfo = note.userInfo?[AppEvents.Key.userInfo] as? [AnyHashable: Any] else { return }
            SDJPushBridge.shared.handleRemoteNotification(userInfo)
        })
    }
}
