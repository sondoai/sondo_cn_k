import Foundation
import UIKit

/// App 级系统回调的广播中心（NotificationCenter）。
///
/// AppDelegate / SceneDelegate 只负责把系统回调转发到这里；微信、极光、支付宝、友盟等
/// SDK 模块自行订阅，不需要改 AppDelegate。
///
/// 约定：
/// - 所有通知都在**主线程**同步 post，`object` 恒为 nil；
/// - 冷启动时带进来的 URL / Universal Link 也会走同一个通知（在根窗口建好之后 post），
///   同时保存在 `AppEvents.launchURL`，晚订阅的模块可以自己读取；
/// - `AppEvents.launchOptions` 保存 `didFinishLaunchingWithOptions` 的原始参数（极光等 SDK 初始化要用）。
///
/// | 通知名                                   | userInfo                                                            |
/// |------------------------------------------|---------------------------------------------------------------------|
/// | `.sdAppOpenURL`                          | `AppEvents.Key.url`: URL，`AppEvents.Key.options`: [UIApplication.OpenURLOptionsKey: Any]（可能为空字典），`AppEvents.Key.sourceApplication`: String（可选） |
/// | `.sdAppContinueUserActivity`             | `AppEvents.Key.userActivity`: NSUserActivity，`AppEvents.Key.url`: URL（仅 NSUserActivityTypeBrowsingWeb 且有 webpageURL 时） |
/// | `.sdAppDidRegisterForRemoteNotifications`| `AppEvents.Key.deviceToken`: Data                                   |
/// | `.sdAppDidFailToRegisterForRemoteNotifications` | `AppEvents.Key.error`: Error                                 |
/// | `.sdAppDidReceiveRemoteNotification`     | `AppEvents.Key.userInfo`: [AnyHashable: Any]                        |
/// | `.sdAppDidBecomeActive`                  | 无                                                                  |
/// | `.sdAppWillResignActive`                 | 无                                                                  |
/// | `.sdAppDidEnterBackground`               | 无                                                                  |
/// | `.sdAppWillEnterForeground`              | 无                                                                  |
///
/// 订阅示例：
/// ```swift
/// token = AppEvents.observe(.sdAppOpenURL) { note in
///     guard let url = note.userInfo?[AppEvents.Key.url] as? URL else { return }
///     WXApi.handleOpen(url, delegate: self)
/// }
/// // deinit: AppEvents.remove(token)
/// ```
extension Notification.Name {
    /// 自定义 scheme（sondo://、wx…://、alipay 回跳等）打开 App。
    static let sdAppOpenURL = Notification.Name("SDAppOpenURL")
    /// Universal Link / Handoff。
    static let sdAppContinueUserActivity = Notification.Name("SDAppContinueUserActivity")
    static let sdAppDidRegisterForRemoteNotifications = Notification.Name("SDAppDidRegisterForRemoteNotifications")
    static let sdAppDidFailToRegisterForRemoteNotifications = Notification.Name("SDAppDidFailToRegisterForRemoteNotifications")
    static let sdAppDidReceiveRemoteNotification = Notification.Name("SDAppDidReceiveRemoteNotification")
    static let sdAppDidBecomeActive = Notification.Name("SDAppDidBecomeActive")
    static let sdAppWillResignActive = Notification.Name("SDAppWillResignActive")
    static let sdAppDidEnterBackground = Notification.Name("SDAppDidEnterBackground")
    static let sdAppWillEnterForeground = Notification.Name("SDAppWillEnterForeground")
}

enum AppEvents {

    /// userInfo 的 key。
    enum Key {
        static let url = "url"
        static let options = "options"
        static let sourceApplication = "sourceApplication"
        static let userActivity = "userActivity"
        static let deviceToken = "deviceToken"
        static let error = "error"
        static let userInfo = "userInfo"
    }

    /// `application(_:didFinishLaunchingWithOptions:)` 的原始参数。
    static var launchOptions: [UIApplication.LaunchOptionsKey: Any]?

    /// 冷启动时带进来的链接（自定义 scheme 或 Universal Link）；没有则为 nil。只读，不会被清空。
    static private(set) var launchURL: URL?

    /// 最近一次拿到的 APNs device token（注册成功后才有）。
    static private(set) var deviceToken: Data?

    /// App 当前是否处于 active 状态（主线程读取）。
    static var isActive: Bool {
        return UIApplication.shared.applicationState == .active
    }

    // MARK: - 订阅

    /// 订阅（回调在主线程）。返回的 token 需要自己持有，并在 deinit 时 `AppEvents.remove(token)`。
    @discardableResult
    static func observe(_ name: Notification.Name, using block: @escaping (Notification) -> Void) -> NSObjectProtocol {
        return NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main, using: block)
    }

    static func remove(_ token: NSObjectProtocol?) {
        if let token = token {
            NotificationCenter.default.removeObserver(token)
        }
    }

    // MARK: - 发布（仅 AppDelegate / SceneDelegate 调用）

    static func postOpenURL(_ url: URL,
                            options: [UIApplication.OpenURLOptionsKey: Any] = [:],
                            isLaunch: Bool = false) {
        if isLaunch && launchURL == nil {
            launchURL = url
        }
        var info: [AnyHashable: Any] = [Key.url: url, Key.options: options]
        if let source = options[.sourceApplication] as? String {
            info[Key.sourceApplication] = source
        }
        post(.sdAppOpenURL, info)
    }

    static func postContinueUserActivity(_ activity: NSUserActivity, isLaunch: Bool = false) {
        var info: [AnyHashable: Any] = [Key.userActivity: activity]
        if activity.activityType == NSUserActivityTypeBrowsingWeb, let url = activity.webpageURL {
            info[Key.url] = url
            if isLaunch && launchURL == nil {
                launchURL = url
            }
        }
        post(.sdAppContinueUserActivity, info)
    }

    static func postDidRegisterForRemoteNotifications(deviceToken token: Data) {
        deviceToken = token
        post(.sdAppDidRegisterForRemoteNotifications, [Key.deviceToken: token])
    }

    static func postDidFailToRegisterForRemoteNotifications(error: Error) {
        post(.sdAppDidFailToRegisterForRemoteNotifications, [Key.error: error])
    }

    static func postDidReceiveRemoteNotification(userInfo: [AnyHashable: Any]) {
        post(.sdAppDidReceiveRemoteNotification, [Key.userInfo: userInfo])
    }

    static func postDidBecomeActive() { post(.sdAppDidBecomeActive, nil) }
    static func postWillResignActive() { post(.sdAppWillResignActive, nil) }
    static func postDidEnterBackground() { post(.sdAppDidEnterBackground, nil) }
    static func postWillEnterForeground() { post(.sdAppWillEnterForeground, nil) }

    private static func post(_ name: Notification.Name, _ userInfo: [AnyHashable: Any]?) {
        if Thread.isMainThread {
            NotificationCenter.default.post(name: name, object: nil, userInfo: userInfo)
        } else {
            DispatchQueue.main.async {
                NotificationCenter.default.post(name: name, object: nil, userInfo: userInfo)
            }
        }
    }
}
