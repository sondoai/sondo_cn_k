import Foundation
import SafariServices
import UIKit

/// 登录相关三方 SDK（契约见 shared 的 `SDBizModules.kt` → `SDAuthModule`）：微信授权 + 极光一键登录。
///
/// SDK 调用在 ObjC 适配层里：`SDWeChatBridge`（WechatOpenSDK）、`SDJVerifyBridge`（JVerification）。
/// 隐私合规：`wechatRegister` / `jverifySetup` 之前不会触碰对应 SDK。
///
/// 事件（`addEventListener`）：`{event:"wechatAuthResp", errCode, code?, state?}`
@objc(SDAuthModule)
@objcMembers
final class SDAuthModule: KRBaseModule {

    /// 向所有存活页面广播。
    static let events = SDSDKEventHub(maxPending: 4)

    deinit {
        SDAuthModule.events.remove(ownerId: ObjectIdentifier(self))
    }

    // MARK: - 微信

    /// params: {appId, universalLink} → {ok}
    func wechatRegister(_ args: NSDictionary) {
        let params = SD.params(args)
        let done = SD.once(args)
        let appId = params.sdString("appId").trimmingCharacters(in: .whitespacesAndNewlines)
        let universalLink = params.sdString("universalLink").trimmingCharacters(in: .whitespacesAndNewlines)
        if appId.isEmpty {
            done.failure("invalid_app_id")
            return
        }
        if universalLink.isEmpty {
            // iOS 的微信 SDK 必须带 Universal Link 注册。
            done.failure("invalid_universal_link")
            return
        }
        SD.onMain {
            if SDAuthCenter.shared.registerWeChat(appId: appId, universalLink: universalLink) {
                done.success()
            } else {
                done.failure("register_failed")
            }
        }
    }

    /// sync → "1" / "0"
    func wechatIsInstalled(_ args: NSDictionary) -> NSString {
        return SD.bool(SDAuthCenter.shared.isWeChatInstalled())
    }

    /// params: {scope, state} → {ok, launched}；结果经 `wechatAuthResp` 事件返回。
    func wechatAuth(_ args: NSDictionary) {
        let params = SD.params(args)
        let done = SD.once(args)
        let scope = params.sdString("scope", "snsapi_userinfo")
        let state = params.sdString("state")
        if !SDWeChatBridge.shared.isRegistered {
            done.failure("not_registered", extra: ["launched": false])
            return
        }
        SD.onMain {
            SDWeChatBridge.shared.sendAuth(scope: scope, state: state) { launched in
                if launched {
                    done.success(["launched": true])
                } else {
                    done.failure("launch_failed", extra: ["launched": false])
                }
            }
        }
        SDAuthModule.resolve(done, after: 15, with: SD.failure("timeout", extra: ["launched": false]))
    }

    // MARK: - 极光一键登录

    /// params: {appKey, channel, debug, timeoutMs} → {ok, code}（8000 = 成功）
    func jverifySetup(_ args: NSDictionary) {
        let params = SD.params(args)
        let done = SD.once(args)
        SDAuthCenter.shared.prepareJVerify()
        SDJVerifyBridge.shared.setup(appKey: params.sdString("appKey"),
                                     channel: params.sdString("channel"),
                                     debug: params.sdBool("debug"),
                                     timeoutMs: params.sdInt("timeoutMs", 10000)) { result in
            done.resolve(result)
        }
    }

    /// → {ok, enable}
    func jverifyCheckEnable(_ args: NSDictionary) {
        let done = SD.once(args)
        SDJVerifyBridge.shared.checkVerifyEnable { result in
            done.resolve(result)
        }
        SDAuthModule.resolve(done, after: 10, with: SD.failure("timeout", extra: ["enable": false]))
    }

    /// params: {timeoutMs} → {ok, code}
    func jverifyPreLogin(_ args: NSDictionary) {
        let timeoutMs = SD.params(args).sdInt("timeoutMs", 5000)
        let done = SD.once(args)
        SDJVerifyBridge.shared.preLogin(timeoutMs: timeoutMs) { result in
            done.resolve(result)
        }
        SDAuthModule.resolve(done, after: 15, with: SD.failure("timeout", code: -2))
    }

    /// 拉起底部弹层样式的授权页。
    /// params: {title, subtitle?, loginButtonText, otherLoginText?, wechatLoginText?, privacyPrefix, privacySuffix,
    ///          privacySeparator?, privacyItems:[{name, url}], privacyUncheckedToast, timeoutMs}
    /// → {ok, action:"token"|"wechat"|"sms"|"cancel"|"unsupported"|"error", token?, operator?, code?, error?, shown}
    func jverifyLoginAuth(_ args: NSDictionary) {
        let params = SD.params(args)
        let done = SD.once(args)
        SDAuthCenter.shared.prepareJVerify()
        SD.onMain {
            SDJVerifyBridge.shared.loginAuth(params: params, presenter: SD.topViewController()) { result in
                done.resolve(result)
            }
        }
    }

    func jverifyDismiss(_ args: NSDictionary) {
        SDJVerifyBridge.shared.dismiss()
    }

    // MARK: - 事件

    func addEventListener(_ args: NSDictionary) {
        guard let callback = SD.callback(args) else { return }
        SDAuthModule.events.add(owner: self, callback: callback)
    }

    // MARK: - private

    /// 兜底：SDK 的回调万一丢了，到点用 fallback 结束这次调用（已经回调过则什么都不做），Kuikly 侧的挂起不会一直等下去。
    private static func resolve(_ done: SDOnce, after seconds: TimeInterval, with fallback: [String: Any]) {
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
            done.resolve(fallback)
        }
    }
}

/// 登录 SDK 的进程级状态：微信回跳的订阅、「是否安装微信」的缓存、极光条款链接的打开方式。
final class SDAuthCenter {

    static let shared = SDAuthCenter()

    private let lock = NSLock()
    /// 最近一次在主线程查到的「是否安装微信」。同步方法跑在 Kuikly 线程，优先读它。
    private var installedCache: Bool?
    private var observers: [NSObjectProtocol] = []
    private var subscribed = false
    private var launchURLHandled = false
    private var jverifyPrepared = false

    private init() {}

    // MARK: - 微信

    /// 注册微信并开始接收回跳（仅主线程）。
    func registerWeChat(appId: String, universalLink: String) -> Bool {
        SDWeChatBridge.shared.authResponseHandler = { errCode, code, state in
            var event: [String: Any] = ["event": "wechatAuthResp", "errCode": errCode]
            if let code = code, !code.isEmpty {
                event["code"] = code
            }
            if let state = state {
                event["state"] = state
            }
            SDAuthModule.events.emit(event)
        }
        subscribeIfNeeded()
        guard SDWeChatBridge.shared.register(appId: appId, universalLink: universalLink) else {
            return false
        }
        handleLaunchURLIfNeeded()
        refreshInstalled()
        return true
    }

    /// 是否安装了微信（任意线程；未注册时恒为 false）。
    func isWeChatInstalled() -> Bool {
        if !SDWeChatBridge.shared.isRegistered {
            return false
        }
        if Thread.isMainThread {
            return refreshInstalled()
        }
        lock.lock()
        let cached = installedCache
        lock.unlock()
        if let cached = cached {
            // 用缓存立即返回，同时在主线程刷新，下一次调用就是最新值。
            DispatchQueue.main.async {
                SDAuthCenter.shared.refreshInstalled()
            }
            return cached
        }
        return SDWeChatBridge.shared.isWXAppInstalled()
    }

    @discardableResult
    private func refreshInstalled() -> Bool {
        let installed = SDWeChatBridge.shared.isWXAppInstalled()
        lock.lock()
        installedCache = installed
        lock.unlock()
        return installed
    }

    /// 订阅 AppDelegate / SceneDelegate 转发过来的回跳（见 `AppEvents`）。
    private func subscribeIfNeeded() {
        if subscribed {
            return
        }
        subscribed = true
        observers.append(AppEvents.observe(.sdAppOpenURL) { note in
            guard let url = note.userInfo?[AppEvents.Key.url] as? URL else { return }
            _ = SDWeChatBridge.shared.handleOpen(url: url)
        })
        observers.append(AppEvents.observe(.sdAppContinueUserActivity) { note in
            guard let activity = note.userInfo?[AppEvents.Key.userActivity] as? NSUserActivity else { return }
            _ = SDWeChatBridge.shared.handleUniversalLink(activity)
        })
        observers.append(AppEvents.observe(.sdAppDidBecomeActive) { _ in
            if SDWeChatBridge.shared.isRegistered {
                SDAuthCenter.shared.refreshInstalled()
            }
        })
    }

    /// 冷启动时带进来的链接在订阅之前就已经广播过了，这里补处理一次（不是微信的链接会被适配层忽略）。
    private func handleLaunchURLIfNeeded() {
        if launchURLHandled {
            return
        }
        launchURLHandled = true
        guard let url = AppEvents.launchURL else { return }
        let scheme = (url.scheme ?? "").lowercased()
        if scheme == "http" || scheme == "https" {
            let activity = NSUserActivity(activityType: NSUserActivityTypeBrowsingWeb)
            activity.webpageURL = url
            _ = SDWeChatBridge.shared.handleUniversalLink(activity)
        } else {
            _ = SDWeChatBridge.shared.handleOpen(url: url)
        }
    }

    // MARK: - 极光

    /// 条款名的点击：授权页留在原地，条款在它上面用应用内浏览器打开，回来后勾选状态不变。
    func prepareJVerify() {
        lock.lock()
        let alreadyPrepared = jverifyPrepared
        jverifyPrepared = true
        lock.unlock()
        if alreadyPrepared {
            return
        }
        SDJVerifyBridge.shared.privacyLinkHandler = { _, url, presenter in
            SDAuthCenter.open(link: url, from: presenter)
        }
    }

    /// http(s) 链接用 SFSafariViewController 盖在当前页面上打开，其它 scheme 交给系统。仅主线程。
    static func open(link: String, from presenter: UIViewController?) {
        let text = link.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty {
            return
        }
        var resolved = URL(string: text)
        if resolved == nil, let encoded = text.addingPercentEncoding(withAllowedCharacters: .urlFragmentAllowed) {
            resolved = URL(string: encoded)
        }
        guard let url = resolved else { return }

        let scheme = (url.scheme ?? "").lowercased()
        var host = presenter
        if host == nil || host?.view.window == nil {
            host = SD.topViewController()
        }
        while let presented = host?.presentedViewController, !presented.isBeingDismissed {
            host = presented
        }
        if (scheme == "http" || scheme == "https"), let host = host {
            let safari = SFSafariViewController(url: url)
            host.present(safari, animated: true, completion: nil)
        } else {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
    }
}
