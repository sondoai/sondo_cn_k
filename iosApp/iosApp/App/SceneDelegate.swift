import UIKit

/// 窗口场景代理（Info.plist: UISceneDelegateClassName = SDSceneDelegate）。
///
/// - 创建窗口与根导航栈（根页面 `launch`）；
/// - 把 URL / Universal Link / 前后台回调转发到 `AppEvents`；冷启动带进来的链接同样转发，
///   并记在 `AppEvents.launchURL`。
@objc(SDSceneDelegate)
final class SceneDelegate: UIResponder, UIWindowSceneDelegate {

    var window: UIWindow?

    func scene(_ scene: UIScene,
               willConnectTo session: UISceneSession,
               options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }

        let window = UIWindow(windowScene: windowScene)
        window.backgroundColor = .black
        self.window = window
        SDKuiklyAdapters.hostWindow = window

        // 先用启动图占位把窗口显示出来，下一个 runloop 再创建 Kuikly 根页面：
        // Kuikly 在页面创建时一次性读取状态栏高度 / 安全区，窗口可见之后这些值才可靠。
        window.rootViewController = SceneDelegate.makeLaunchPlaceholder()
        window.makeKeyAndVisible()

        // 冷启动深链：先记录（SDAppCenter 已在 AppDelegate 里开始监听），页面注册监听后补发。
        for context in connectionOptions.urlContexts {
            AppEvents.postOpenURL(context.url, options: SceneDelegate.openURLOptions(from: context), isLaunch: true)
        }
        for activity in connectionOptions.userActivities {
            AppEvents.postContinueUserActivity(activity, isLaunch: true)
        }

        DispatchQueue.main.async { [weak window] in
            guard let window = window else { return }
            window.rootViewController = SDPageStack.shared.makeRootNavigationController()
        }
    }

    // MARK: - URL / Universal Link（热启动）

    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        for context in URLContexts {
            AppEvents.postOpenURL(context.url, options: SceneDelegate.openURLOptions(from: context))
        }
    }

    func scene(_ scene: UIScene, continue userActivity: NSUserActivity) {
        AppEvents.postContinueUserActivity(userActivity)
    }

    // MARK: - 前后台

    func sceneDidBecomeActive(_ scene: UIScene) {
        AppEvents.postDidBecomeActive()
    }

    func sceneWillResignActive(_ scene: UIScene) {
        AppEvents.postWillResignActive()
    }

    func sceneDidEnterBackground(_ scene: UIScene) {
        AppEvents.postDidEnterBackground()
    }

    func sceneWillEnterForeground(_ scene: UIScene) {
        AppEvents.postWillEnterForeground()
    }

    // MARK: - private

    /// 与 LaunchScreen.storyboard 一致的占位页，避免启动图与首帧之间闪黑。
    private static func makeLaunchPlaceholder() -> UIViewController {
        let storyboard = UIStoryboard(name: "LaunchScreen", bundle: nil)
        if let controller = storyboard.instantiateInitialViewController() {
            return controller
        }
        let fallback = UIViewController()
        fallback.view.backgroundColor = .black
        return fallback
    }

    /// UIScene 的打开参数 → UIApplication 风格的 options（微信 / 支付宝等 SDK 的 handleOpenURL 需要）。
    private static func openURLOptions(from context: UIOpenURLContext) -> [UIApplication.OpenURLOptionsKey: Any] {
        var options: [UIApplication.OpenURLOptionsKey: Any] = [:]
        if let source = context.options.sourceApplication {
            options[.sourceApplication] = source
        }
        if let annotation = context.options.annotation {
            options[.annotation] = annotation
        }
        options[.openInPlace] = context.options.openInPlace
        return options
    }
}
