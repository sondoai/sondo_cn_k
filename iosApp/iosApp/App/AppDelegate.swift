import UIKit

/// App 入口。只做三件事：
/// 1. 注册 Kuikly 适配器（图片 / 字体 / 日志 / 路由）；
/// 2. 启动 App 级事件采集（深链、前后台、键盘）；
/// 3. 把系统回调转发到 `AppEvents`，SDK 模块（微信 / 极光 / 友盟…）自行订阅，不要在这里加 SDK 代码。
///
/// 窗口与根页面在 `SceneDelegate` 里创建（与参考工程一样使用 UIScene 生命周期）。
@main
final class AppDelegate: UIResponder, UIApplicationDelegate {

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        AppEvents.launchOptions = launchOptions
        SDKuiklyAdapters.install(router: SDPageStack.shared)
        SDAppCenter.shared.bootstrap()
        return true
    }

    // MARK: - 方向

    func application(_ application: UIApplication,
                     supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        return .portrait
    }

    // MARK: - URL / Universal Link（未走 Scene 时的兜底；Scene 生命周期下由 SceneDelegate 转发）

    func application(_ app: UIApplication,
                     open url: URL,
                     options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        AppEvents.postOpenURL(url, options: options)
        return true
    }

    func application(_ application: UIApplication,
                     continue userActivity: NSUserActivity,
                     restorationHandler: @escaping ([UIUserActivityRestoring]?) -> Void) -> Bool {
        AppEvents.postContinueUserActivity(userActivity)
        return true
    }

    // MARK: - 远程推送

    func application(_ application: UIApplication,
                     didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        AppEvents.postDidRegisterForRemoteNotifications(deviceToken: deviceToken)
    }

    func application(_ application: UIApplication,
                     didFailToRegisterForRemoteNotificationsWithError error: Error) {
        AppEvents.postDidFailToRegisterForRemoteNotifications(error: error)
    }

    func application(_ application: UIApplication,
                     didReceiveRemoteNotification userInfo: [AnyHashable: Any],
                     fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        AppEvents.postDidReceiveRemoteNotification(userInfo: userInfo)
        completionHandler(.newData)
    }
}
