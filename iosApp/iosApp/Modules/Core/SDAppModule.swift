import UIKit
import Network
import StoreKit
import SafariServices

// =====================================================================================
// SDAppCenter：进程级的 App 状态采集（深链 / 前后台 / 键盘 / 联网类型 / 设备信息缓存）。
// AppDelegate 启动时 bootstrap 一次；SDAppModule 的各页面实例共享它。
// =====================================================================================

final class SDAppCenter {

    static let shared = SDAppCenter()

    /// `SDAppModule.addEventListener` 注册的常驻回调（每个页面一份）。
    let events = EventBroadcaster()

    private let lock = NSLock()
    private var bootstrapped = false
    private var observers: [NSObjectProtocol] = []

    // 深链（投递规则见 shared/.../nativeapi/SDAppModule.kt 类注释）
    private var pendingLink: String?
    /// 深链消费者：最近一次调用 getInitialLink 的模块实例（页面销毁后自动变 nil）。
    private weak var linkConsumer: AnyObject?
    /// 消费者在 getInitialLink 之后注册的监听 id，deepLink 只投递给它们。
    private var linkListenerIds: Set<String> = []
    /// 曾经有过消费者：消费者页面销毁后（linkConsumer 变 nil）退回为向所有监听广播，与改动前一致。
    private var hadLinkConsumer = false

    // 设备 / 窗口信息缓存（同步方法运行在 Kuikly 线程，不能碰 UIKit，只读缓存）
    private var deviceInfo: [String: Any] = ["platform": "ios"]
    private var bottomInset: Double = 0

    // 联网
    private var pathMonitor: NWPathMonitor?
    private var connectivityTypes: [String]?
    private let monitorQueue = DispatchQueue(label: "com.sondo.app.connectivity", qos: .utility)

    // 键盘
    private var keyboardHeight: Double = 0

    // canOpenURL 结果缓存（scheme → 是否可打开）
    private var schemeCache: [String: Bool] = [:]

    private init() {}

    // MARK: - 启动

    /// 在主线程调用一次（AppDelegate.didFinishLaunching）。不联网、不读取任何用户数据。
    func bootstrap() {
        lock.lock()
        let already = bootstrapped
        bootstrapped = true
        lock.unlock()
        guard !already else { return }

        refreshDeviceInfo()

        let center = NotificationCenter.default

        // 深链：自定义 scheme / Universal Link（冷启动的链接同样从这里进来）
        observers.append(AppEvents.observe(.sdAppOpenURL) { [weak self] note in
            if let url = note.userInfo?[AppEvents.Key.url] as? URL {
                self?.handleIncomingLink(url)
            }
        })
        observers.append(AppEvents.observe(.sdAppContinueUserActivity) { [weak self] note in
            if let url = note.userInfo?[AppEvents.Key.url] as? URL {
                self?.handleIncomingLink(url)
            }
        })

        // 前后台
        observers.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self = self else { return }
            self.refreshWindowInfo()
            self.refreshSchemeCache()
            self.events.emit(["event": "lifecycle", "state": "resumed"])
        })
        observers.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.events.emit(["event": "lifecycle", "state": "paused"])
        })

        // 页面栈变化时刷新安全区缓存
        observers.append(center.addObserver(forName: .SDKuiklyPageStackDidChange, object: nil, queue: .main) { [weak self] _ in
            self?.refreshWindowInfo()
        })

        // 键盘
        observers.append(center.addObserver(forName: UIResponder.keyboardWillChangeFrameNotification, object: nil, queue: .main) { [weak self] note in
            self?.handleKeyboard(note, hiding: false)
        })
        observers.append(center.addObserver(forName: UIResponder.keyboardWillHideNotification, object: nil, queue: .main) { [weak self] note in
            self?.handleKeyboard(note, hiding: true)
        })
    }

    // MARK: - 深链

    /// 只转发业务深链：`sondo://…` 与 http(s) 的 Universal Link；微信 / 支付等回跳 scheme 不转发。
    private func handleIncomingLink(_ url: URL) {
        guard let scheme = url.scheme?.lowercased(),
              scheme == "sondo" || scheme == "https" || scheme == "http" else {
            return
        }
        // 微信 SDK 自己的 Universal Link（https://go.sondo.vip/wechat/…）由 SDK 模块经 AppEvents 处理，不是业务深链。
        if scheme != "sondo" && url.path.hasPrefix("/wechat/") {
            return
        }
        deliverOrHold(url.absoluteString)
    }

    /// 有消费者监听就投递，否则暂存（新链接覆盖旧的）。
    private func deliverOrHold(_ text: String) {
        lock.lock()
        let consumer = linkConsumer
        let ids = linkListenerIds
        let fallbackBroadcast = consumer == nil && hadLinkConsumer && !events.isEmpty
        if !fallbackBroadcast && (consumer == nil || ids.isEmpty) {
            pendingLink = text
            lock.unlock()
            return
        }
        lock.unlock()
        if fallbackBroadcast {
            events.emit(["event": "deepLink", "url": text])
            return
        }
        let delivered = events.emit(["event": "deepLink", "url": text], toOwnerId: ObjectIdentifier(consumer!), listenerIds: ids)
        if delivered == 0 {
            lock.lock()
            pendingLink = text
            lock.unlock()
        }
    }

    /// getInitialLink：登记 consumer 为深链消费者，取走暂存的深链（取后清空）。
    func takeInitialLink(consumer: AnyObject) -> String {
        lock.lock()
        defer { lock.unlock() }
        hadLinkConsumer = true
        if linkConsumer !== consumer {
            linkConsumer = consumer
            linkListenerIds = []
        }
        let link = pendingLink
        pendingLink = nil
        return link ?? ""
    }

    // MARK: - 监听注册

    func addListener(owner: AnyObject, listenerId: String, callback: @escaping SDCallback) {
        startConnectivityMonitorIfNeeded()
        events.add(owner: owner, listenerId: listenerId, callback: callback)
        // 消费者在 getInitialLink 之后注册的监听：接收 deepLink，并补发期间暂存的链接。
        lock.lock()
        var flush: String?
        if let consumer = linkConsumer, consumer === owner {
            linkListenerIds.insert(listenerId)
            flush = pendingLink
            pendingLink = nil
        }
        lock.unlock()
        if let link = flush {
            DispatchQueue.main.async { [weak self] in
                self?.deliverOrHold(link)
            }
        }
    }

    func removeListener(owner: AnyObject, listenerId: String) {
        events.remove(ownerId: ObjectIdentifier(owner), listenerId: listenerId)
        lock.lock()
        if let consumer = linkConsumer, consumer === owner {
            linkListenerIds.remove(listenerId)
        }
        lock.unlock()
    }

    // MARK: - 设备 / 窗口

    private func refreshDeviceInfo() {
        let device = UIDevice.current
        let version = ProcessInfo.processInfo.operatingSystemVersion
        let machine = SDAppCenter.machineIdentifier()
        let info: [String: Any] = [
            "platform": "ios",
            "osVersion": device.systemVersion,
            "sdkInt": version.majorVersion,
            "manufacturer": "Apple",
            "brand": "Apple",
            "model": machine,
            "hardware": machine,
            "deviceName": device.name,
        ]
        lock.lock()
        deviceInfo = info
        lock.unlock()
    }

    private func refreshWindowInfo() {
        let window = SDKuiklyAdapters.hostWindow ?? SD.keyWindow()
        let inset = Double(window?.safeAreaInsets.bottom ?? 0)
        lock.lock()
        bottomInset = inset
        lock.unlock()
    }

    func currentDeviceInfo() -> [String: Any] {
        lock.lock()
        defer { lock.unlock() }
        return deviceInfo
    }

    func currentBottomInset() -> Double {
        lock.lock()
        defer { lock.unlock() }
        return bottomInset
    }

    /// 机型标识，如 `iPhone15,2`（模拟器为 `arm64` / `x86_64`）。
    private static func machineIdentifier() -> String {
        var systemInfo = utsname()
        uname(&systemInfo)
        let machine = withUnsafePointer(to: &systemInfo.machine) { pointer in
            pointer.withMemoryRebound(to: CChar.self, capacity: Int(_SYS_NAMELEN)) { cString in
                String(cString: cString)
            }
        }
        return machine
    }

    // MARK: - 键盘

    private func handleKeyboard(_ note: Notification, hiding: Bool) {
        var height: Double = 0
        if !hiding, let value = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue {
            let frame = value.cgRectValue
            let screenHeight = UIScreen.main.bounds.height
            if frame.origin.y < screenHeight {
                height = Double(max(0, min(frame.height, screenHeight - frame.origin.y)))
            }
        }
        lock.lock()
        let changed = abs(keyboardHeight - height) > 0.5
        keyboardHeight = height
        lock.unlock()
        if changed {
            // 与 Android 一致：只发给最顶层的 Kuikly 页面（被盖住的页面不跟着键盘重新布局 / 滚动）
            if let top = SDPageStack.shared.topPage() {
                events.emit(["event": "keyboard", "height": height]) { owner in
                    (owner as? KRBaseModule)?.sd_viewController === top
                }
            } else {
                events.emit(["event": "keyboard", "height": height])
            }
        }
    }

    // MARK: - 联网类型

    /// 首次有页面用到联网状态时才启动（NWPathMonitor 只读取本机网络接口状态，不发起请求）。
    func startConnectivityMonitorIfNeeded() {
        lock.lock()
        if pathMonitor != nil {
            lock.unlock()
            return
        }
        let monitor = NWPathMonitor()
        pathMonitor = monitor
        lock.unlock()

        monitor.pathUpdateHandler = { [weak self] path in
            self?.handlePathUpdate(path)
        }
        monitor.start(queue: monitorQueue)
    }

    private func handlePathUpdate(_ path: NWPath) {
        var types: [String] = []
        if path.status == .satisfied {
            if path.usesInterfaceType(.wifi) { types.append("wifi") }
            if path.usesInterfaceType(.cellular) { types.append("mobile") }
            if path.usesInterfaceType(.wiredEthernet) { types.append("ethernet") }
            if types.isEmpty { types.append("other") }
        } else {
            types.append("none")
        }
        lock.lock()
        let changed = connectivityTypes != types
        connectivityTypes = types
        lock.unlock()
        if changed {
            events.emit(["event": "connectivity", "types": types])
        }
    }

    /// 当前联网类型；监视器还没给出第一次结果时返回空数组（Kuikly 侧按「已联网」处理）。
    func currentConnectivity() -> [String] {
        startConnectivityMonitorIfNeeded()
        lock.lock()
        defer { lock.unlock() }
        return connectivityTypes ?? []
    }

    // MARK: - canOpenURL

    /// 主线程：回到前台时重新探测「业务已经查询过」的 scheme（期间用户可能安装 / 卸载了对应 App）。
    /// 不主动探测没被查询过的 scheme：是否安装了某个 App 只在业务需要时才读取（隐私合规）。
    private func refreshSchemeCache() {
        lock.lock()
        let schemes = Array(schemeCache.keys)
        lock.unlock()
        guard !schemes.isEmpty else { return }
        var fresh: [String: Bool] = [:]
        for scheme in schemes {
            if let url = URL(string: scheme + "://") {
                fresh[scheme] = UIApplication.shared.canOpenURL(url)
            }
        }
        lock.lock()
        for (scheme, value) in fresh {
            schemeCache[scheme] = value
        }
        lock.unlock()
    }

    /// 是否可以打开该 URL / scheme。任意线程。
    func canOpen(_ text: String) -> Bool {
        guard let url = SDURLUtil.make(text), let scheme = url.scheme?.lowercased() else { return false }
        if Thread.isMainThread {
            let result = UIApplication.shared.canOpenURL(url)
            storeScheme(scheme, result)
            return result
        }
        lock.lock()
        let cached = schemeCache[scheme]
        lock.unlock()
        if let cached = cached {
            return cached
        }
        // 缓存未命中：到主线程查一次，最多等 200ms。
        // （首帧同步渲染 / 同步事件期间主线程在等 Kuikly 线程，此时会超时，退回当前线程直接查询。）
        let box = SDResultBox()
        let semaphore = DispatchSemaphore(value: 0)
        DispatchQueue.main.async { [weak self] in
            let result = UIApplication.shared.canOpenURL(url)
            self?.storeScheme(scheme, result)
            box.set(result)
            semaphore.signal()
        }
        if semaphore.wait(timeout: .now() + .milliseconds(200)) == .success {
            return box.get()
        }
        let result = UIApplication.shared.canOpenURL(url)
        storeScheme(scheme, result)
        return result
    }

    private func storeScheme(_ scheme: String, _ value: Bool) {
        lock.lock()
        schemeCache[scheme] = value
        lock.unlock()
    }
}

/// 跨线程传递一个 Bool 结果。
private final class SDResultBox {
    private let lock = NSLock()
    private var value = false

    func set(_ newValue: Bool) {
        lock.lock()
        value = newValue
        lock.unlock()
    }

    func get() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}

/// 系统分享面板里的文本条目（同时提供邮件主题）。
private final class SDShareTextItem: NSObject, UIActivityItemSource {
    private let text: String
    private let subject: String?

    init(text: String, subject: String?) {
        self.text = text
        self.subject = subject
        super.init()
    }

    func activityViewControllerPlaceholderItem(_ activityViewController: UIActivityViewController) -> Any {
        return text
    }

    func activityViewController(_ activityViewController: UIActivityViewController,
                                itemForActivityType activityType: UIActivity.ActivityType?) -> Any? {
        return text
    }

    func activityViewController(_ activityViewController: UIActivityViewController,
                                subjectForActivityType activityType: UIActivity.ActivityType?) -> String {
        return subject ?? ""
    }
}

// =====================================================================================
// SDAppModule：应用 / 设备 / 系统 UI。契约见 shared/.../nativeapi/SDAppModule.kt。
// =====================================================================================

@objc(SDAppModule)
@objcMembers
final class SDAppModule: KRBaseModule {

    /// App Store 应用 id（Info.plist `SDAppStoreId`）。
    private static var appStoreId: String {
        return (Bundle.main.object(forInfoDictionaryKey: "SDAppStoreId") as? String) ?? "6751086438"
    }

    deinit {
        SDAppCenter.shared.events.remove(ownerId: ObjectIdentifier(self))
    }

    // MARK: - 同步方法（Kuikly 线程；只读缓存，不碰 UIKit）

    /// → {appName, version, buildNumber, packageName}
    func getAppInfo(_ args: NSDictionary) -> NSString {
        let bundle = Bundle.main
        let appName = (bundle.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String)
            ?? (bundle.object(forInfoDictionaryKey: "CFBundleName") as? String)
            ?? ""
        let info: [String: Any] = [
            "appName": appName,
            "version": (bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "",
            "buildNumber": (bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String) ?? "",
            "packageName": bundle.bundleIdentifier ?? "",
        ]
        return SD.jsonResult(info)
    }

    /// → {platform, osVersion, sdkInt, manufacturer, brand, model, hardware, deviceName}
    func getDeviceInfo(_ args: NSDictionary) -> NSString {
        return SD.jsonResult(SDAppCenter.shared.currentDeviceInfo())
    }

    /// 渠道号：iOS 恒为空串。
    func getInstallChannel(_ args: NSDictionary) -> NSString {
        return ""
    }

    /// → {threeButton: false, bottomInset}
    func getSystemNavMode(_ args: NSDictionary) -> NSString {
        let info: [String: Any] = [
            "threeButton": false,
            "bottomInset": SDAppCenter.shared.currentBottomInset(),
        ]
        return SD.jsonResult(info)
    }

    /// → {types: ["wifi"|"mobile"|"ethernet"|"other"|"none"]}
    func getConnectivity(_ args: NSDictionary) -> NSString {
        return SD.jsonResult(["types": SDAppCenter.shared.currentConnectivity()])
    }

    /// → {tempDir, documentsDir, cacheDir}
    /// tempDir 与参考工程的 path_provider `getTemporaryDirectory()` 一致，在 iOS 上是 `Library/Caches`。
    func getPaths(_ args: NSDictionary) -> NSString {
        let caches = SDPath.cachesDirectory
        let info: [String: Any] = [
            "tempDir": caches,
            "documentsDir": SDPath.documentsDirectory,
            "cacheDir": caches,
        ]
        return SD.jsonResult(info)
    }

    /// → 冷启动深链 URL（没有则空串）；读取后清空。
    func getInitialLink(_ args: NSDictionary) -> NSString {
        return SDAppCenter.shared.takeInitialLink(consumer: self) as NSString
    }

    /// → 毫秒时间戳
    func now(_ args: NSDictionary) -> NSString {
        let millis = Int64((Date().timeIntervalSince1970 * 1000).rounded())
        return String(millis) as NSString
    }

    /// → "1" / "0"
    func canOpenUrl(_ args: NSDictionary) -> NSString {
        let url = SD.params(args).sdString("url")
        return SD.bool(SDAppCenter.shared.canOpen(url))
    }

    // MARK: - 异步方法（主线程）

    /// style: "light"（浅色图标）| "dark"
    func setStatusBarStyle(_ args: NSDictionary) {
        let style = SD.params(args).sdString("style", "light")
        SD.onMain { [weak self] in
            guard let page = self?.sd_viewController as? KuiklyViewController else { return }
            page.statusBarStyle = (style == "dark") ? .darkContent : .lightContent
        }
    }

    func setKeepScreenOn(_ args: NSDictionary) {
        let on = SD.params(args).sdBool("on", false)
        SD.onMain {
            UIApplication.shared.isIdleTimerDisabled = on
        }
    }

    /// Android 专用，iOS 无需处理。
    func setGestureExclusionRects(_ args: NSDictionary) {
    }

    func copyToClipboard(_ args: NSDictionary) {
        let text = SD.params(args).sdString("text")
        SD.onMain {
            UIPasteboard.general.string = text
        }
    }

    func hideKeyboard(_ args: NSDictionary) {
        SD.onMain {
            UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        }
    }

    /// type: "light" | "medium" | "heavy" | "selection"
    func haptic(_ args: NSDictionary) {
        let type = SD.params(args).sdString("type", "light")
        SD.onMain {
            switch type {
            case "selection":
                let generator = UISelectionFeedbackGenerator()
                generator.selectionChanged()
            case "medium":
                let generator = UIImpactFeedbackGenerator(style: .medium)
                generator.impactOccurred()
            case "heavy":
                let generator = UIImpactFeedbackGenerator(style: .heavy)
                generator.impactOccurred()
            default:
                let generator = UIImpactFeedbackGenerator(style: .light)
                generator.impactOccurred()
            }
        }
    }

    /// → {ok, opened}
    func openUrl(_ args: NSDictionary) {
        let params = SD.params(args)
        let done = SD.once(args)
        let mode = params.sdString("mode", "external")
        guard let url = SDURLUtil.make(params.sdString("url")) else {
            done.resolve(SD.success(["opened": false]))
            return
        }
        SD.onMain {
            let scheme = url.scheme?.lowercased() ?? ""
            if mode == "inApp", scheme == "http" || scheme == "https", let presenter = SD.topViewController() {
                let safari = SFSafariViewController(url: url)
                safari.modalPresentationStyle = .pageSheet
                presenter.present(safari, animated: true) {
                    done.resolve(SD.success(["opened": true]))
                }
                return
            }
            UIApplication.shared.open(url, options: [:]) { opened in
                done.resolve(SD.success(["opened": opened]))
            }
        }
    }

    func openAppSettings(_ args: NSDictionary) {
        SD.onMain {
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url, options: [:], completionHandler: nil)
            }
        }
    }

    func openNotificationSettings(_ args: NSDictionary) {
        SD.onMain {
            var target = UIApplication.openSettingsURLString
            if #available(iOS 16.0, *) {
                target = UIApplication.openNotificationSettingsURLString
            }
            if let url = URL(string: target) {
                UIApplication.shared.open(url, options: [:], completionHandler: nil)
            }
        }
    }

    /// 退出 App（用户拒绝隐私协议时）。先退到后台再结束进程，避免看起来像闪退。
    func exitApp(_ args: NSDictionary) {
        SD.onMain {
            UIApplication.shared.perform(#selector(URLSessionTask.suspend))
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                exit(0)
            }
        }
    }

    /// 应用内评分。→ {ok, shown}（系统不保证一定弹出）
    func requestReview(_ args: NSDictionary) {
        let done = SD.once(args)
        SD.onMain {
            let scene = SD.keyWindow()?.windowScene
                ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
            guard let windowScene = scene else {
                done.resolve(SD.success(["shown": false]))
                return
            }
            SKStoreReviewController.requestReview(in: windowScene)
            done.resolve(SD.success(["shown": true]))
        }
    }

    /// 打开 App Store 详情页。
    func openStoreListing(_ args: NSDictionary) {
        let appId = SDAppModule.appStoreId
        SD.onMain {
            let candidates = ["itms-apps://apps.apple.com/app/id\(appId)", "https://apps.apple.com/app/id\(appId)"]
            for text in candidates {
                if let url = URL(string: text), UIApplication.shared.canOpenURL(url) {
                    UIApplication.shared.open(url, options: [:], completionHandler: nil)
                    return
                }
            }
        }
    }

    /// 系统分享面板。params: {text?, subject?, files?: [path], mimeType?}
    /// → {ok, status: "success" | "dismissed" | "unavailable"}
    func share(_ args: NSDictionary) {
        let params = SD.params(args)
        let done = SD.once(args)
        let text = params.sdOptString("text")
        let subject = params.sdOptString("subject")
        let files = params.sdStringArray("files").map { SDPath.normalize($0) }

        SD.onMain {
            var items: [Any] = []
            if let text = text, !text.isEmpty {
                items.append(SDShareTextItem(text: text, subject: subject))
            }
            for path in files where FileManager.default.fileExists(atPath: path) {
                items.append(URL(fileURLWithPath: path))
            }
            guard !items.isEmpty, let presenter = SD.topViewController() else {
                done.resolve(SD.success(["status": "unavailable"]))
                return
            }
            let sheet = UIActivityViewController(activityItems: items, applicationActivities: nil)
            sheet.completionWithItemsHandler = { _, completed, _, _ in
                done.resolve(SD.success(["status": completed ? "success" : "dismissed"]))
            }
            if let popover = sheet.popoverPresentationController {
                popover.sourceView = presenter.view
                popover.sourceRect = CGRect(x: presenter.view.bounds.midX, y: presenter.view.bounds.maxY - 1, width: 1, height: 1)
                popover.permittedArrowDirections = []
            }
            presenter.present(sheet, animated: true, completion: nil)
        }
    }

    /// 注册事件监听（常驻回调）：connectivity / lifecycle / deepLink / keyboard。
    func addEventListener(_ args: NSDictionary) {
        guard let callback = SD.callback(args) else { return }
        let listenerId = SD.params(args).sdOptString("listenerId") ?? UUID().uuidString
        SDAppCenter.shared.addListener(owner: self, listenerId: listenerId, callback: callback)
    }

    /// 注销单个监听。params: {listenerId}
    func removeEventListener(_ args: NSDictionary) {
        guard let listenerId = SD.params(args).sdOptString("listenerId") else { return }
        SDAppCenter.shared.removeListener(owner: self, listenerId: listenerId)
    }
}
