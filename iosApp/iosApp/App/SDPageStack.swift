import UIKit

/// Kuikly 页面栈（一个页面 = 一个 KuiklyViewController）。
///
/// - `RouterModule.openPage / closePage`（经 `SDKuiklyAdapters` 的路由适配器）和 `SDNavModule` 都落到这里；
/// - 栈结构：根导航栈（window.rootViewController）之上，可以叠若干个承载透明页的模态导航栈；
/// - `mirror` 是页面名列表的线程安全镜像：SDNavModule 的同步方法运行在 Kuikly 线程，
///   不能同步切主线程读 UIKit（首帧同步渲染 / 同步事件时主线程正在等 Kuikly 线程，会死锁），只读镜像。
final class SDPageStack: NSObject, SDPageRouting {

    static let shared = SDPageStack()

    /// 根页面（主框架）。栈底永远是它。
    static let rootPageName = "launch"

    private typealias Entry = (nav: SDNavigationController, page: KuiklyViewController)

    private let lock = NSLock()
    private var mirror: [String] = []
    private var observer: NSObjectProtocol?

    /// 根导航栈（SceneDelegate 创建后注册）。
    private(set) weak var rootNavigationController: SDNavigationController?

    private override init() {
        super.init()
        observer = NotificationCenter.default.addObserver(
            forName: .SDKuiklyPageStackDidChange,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.resync()
        }
    }

    deinit {
        if let observer = observer {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    // MARK: - 根页面

    /// 创建根导航栈（根页面 `launch`）。主线程调用。
    func makeRootNavigationController() -> SDNavigationController {
        let root = KuiklyViewController(pageName: SDPageStack.rootPageName, pageData: nil)
        let nav = SDNavigationController(rootViewController: root)
        rootNavigationController = nav
        setMirror([SDPageStack.rootPageName])
        return nav
    }

    // MARK: - 查询（任意线程）

    /// 当前页面栈（自底向上的 pageName）。
    func pageNames() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return mirror
    }

    private func setMirror(_ names: [String]) {
        lock.lock()
        mirror = names
        lock.unlock()
    }

    // MARK: - SDPageRouting（KRRouterModule 调用）

    func openPage(name pageName: String, pageData: [AnyHashable: Any]?, from controller: UIViewController?) {
        SD.onMain {
            self.open(pageName: pageName, pageData: pageData ?? [:])
        }
    }

    func closePage(of controller: UIViewController?) {
        SD.onMain {
            self.close(controller, animated: true)
        }
    }

    // MARK: - 打开

    /// 打开页面（主线程）。新页面永远压在整个页面栈的最顶端。
    func open(pageName: String, pageData: [AnyHashable: Any]) {
        guard !pageName.isEmpty else { return }
        let page = KuiklyViewController(pageName: pageName, pageData: pageData)
        push(page)
    }

    private func push(_ page: KuiklyViewController) {
        guard let hostNav = topNavigationController() else { return }
        let transition = page.sdTransition
        if page.sdTranslucent {
            let container = SDNavigationController.makeTranslucentContainer(root: page)
            let animated = transition != "none"
            container.modalTransitionStyle = (transition == "bottom") ? .coverVertical : .crossDissolve
            // 正常情况由最顶端的导航栈来 present；它上面已经盖着系统弹层（分享面板、网页等）时，从最上层接着 present。
            let presenter: UIViewController = hostNav.presentedViewController == nil
                ? hostNav
                : (SD.topViewController(hostNav) ?? hostNav)
            presenter.present(container, animated: animated) { [weak self] in
                self?.resync()
            }
        } else {
            hostNav.pushViewController(page, animated: transition != "none")
        }
        resync()
    }

    // MARK: - 关闭

    /// 关闭页面（主线程）。
    func close(_ controller: UIViewController?, animated: Bool) {
        guard let page = controller else { return }
        guard let nav = page.navigationController else {
            if page.presentingViewController != nil {
                page.dismiss(animated: animated) { [weak self] in
                    self?.resync()
                }
            }
            return
        }
        let stack = nav.viewControllers
        guard let index = stack.firstIndex(where: { $0 === page }) else { return }
        let transition = (page as? KuiklyViewController)?.sdTransition ?? ""
        let allowAnimation = animated && transition != "none"
        let isRootNav = nav === rootNavigationController

        if stack.count == 1 {
            // 导航栈里只剩它：根页面不可关闭；模态容器整个收起。
            // 由 presenting 方发起 dismiss：连同它上面可能还盖着的弹层一起收起。
            if !isRootNav, let presenting = nav.presentingViewController {
                (nav as? SDNavigationController)?.isClosing = true
                presenting.dismiss(animated: allowAnimation) { [weak self] in
                    self?.resync()
                }
            }
        } else if index == stack.count - 1 {
            // 栈顶页面：正常出栈（上面还盖着模态时不做动画）。
            nav.popViewController(animated: allowAnimation && nav.presentedViewController == nil)
        } else if index == 0 && isRootNav {
            // 根页面上面还有页面：忽略。
        } else {
            // 不在栈顶：静默移除。
            var rest = stack
            rest.remove(at: index)
            nav.setViewControllers(rest, animated: false)
        }
        resync()
    }

    // MARK: - SDNavModule

    /// 回到栈内最近的 `pageName`；没有则回到根页面。任意线程；返回是否命中，实际出栈在主线程执行。
    func popTo(pageName: String) -> Bool {
        lock.lock()
        let index = mirror.lastIndex(of: pageName)
        let keep = (index ?? 0) + 1
        if mirror.count > keep {
            mirror.removeSubrange(keep...)
        }
        lock.unlock()
        let hit = index != nil
        SD.onMain {
            self.performPop(toPageName: hit ? pageName : nil)
        }
        return hit
    }

    /// 回到根页面。任意线程。
    func popToRoot() {
        lock.lock()
        if mirror.count > 1 {
            mirror.removeSubrange(1...)
        }
        lock.unlock()
        SD.onMain {
            self.performPop(toPageName: nil)
        }
    }

    /// 关闭 `controller` 对应的页面并打开新页（Get.offNamed）。主线程。
    func replace(from controller: UIViewController?, pageName: String, pageData: [AnyHashable: Any]) {
        guard !pageName.isEmpty else { return }
        guard let current = controller as? KuiklyViewController,
              let nav = current.navigationController as? SDNavigationController,
              let index = nav.viewControllers.firstIndex(where: { $0 === current }) else {
            open(pageName: pageName, pageData: pageData)
            return
        }
        let page = KuiklyViewController(pageName: pageName, pageData: pageData)
        let animated = page.sdTransition != "none"
        let isRootNav = nav === rootNavigationController

        if page.sdTranslucent {
            // 新页面是透明模态页：先盖上去，再静默移除当前页。
            push(page)
            close(current, animated: false)
            return
        }

        if !isRootNav && nav.viewControllers.count == 1, let below = navigationController(below: nav) {
            // 当前页是模态容器里唯一的页面（透明页）：新页面压进下面的导航栈，再收起模态容器。
            below.pushViewController(page, animated: animated)
            nav.isClosing = true
            (nav.presentingViewController ?? nav).dismiss(animated: false) { [weak self] in
                self?.resync()
            }
            resync()
            return
        }

        var stack = nav.viewControllers
        stack[index] = page
        let isTop = index == stack.count - 1
        let isOnlyRoot = isRootNav && stack.count == 1
        nav.setViewControllers(stack, animated: animated && isTop && !isOnlyRoot && nav.presentedViewController == nil)
        resync()
    }

    /// 清空整个栈，以 `pageName` 作为新的根（Get.offAllNamed）。主线程。
    func resetRoot(pageName: String, pageData: [AnyHashable: Any]) {
        guard !pageName.isEmpty, let rootNav = rootNavigationController else { return }
        let page = KuiklyViewController(pageName: pageName, pageData: pageData)
        if let presented = rootNav.presentedViewController {
            (presented as? SDNavigationController)?.isClosing = true
            rootNav.dismiss(animated: false, completion: nil)
        }
        rootNav.setViewControllers([page], animated: false)
        resync()
    }

    // MARK: - 内部

    /// 整个页面栈最顶端的导航栈。
    private func topNavigationController() -> SDNavigationController? {
        var result = rootNavigationController
        var current: UIViewController? = rootNavigationController?.presentedViewController
        while let controller = current {
            if controller.isBeingDismissed {
                break
            }
            if let nav = controller as? SDNavigationController {
                if nav.isClosing {
                    break
                }
                result = nav
            }
            current = controller.presentedViewController
        }
        return result
    }

    /// `nav` 下面一层的导航栈（`nav` 为根导航栈时返回 nil）。
    private func navigationController(below nav: SDNavigationController) -> SDNavigationController? {
        var previous: SDNavigationController? = nil
        for candidate in navigationControllers() {
            if candidate === nav {
                return previous
            }
            previous = candidate
        }
        return nil
    }

    /// 自底向上的全部导航栈（不含正在收起的模态容器）。
    private func navigationControllers() -> [SDNavigationController] {
        var result: [SDNavigationController] = []
        var current: UIViewController? = rootNavigationController
        while let controller = current {
            if controller !== rootNavigationController && controller.isBeingDismissed {
                break
            }
            if let nav = controller as? SDNavigationController {
                if nav.isClosing {
                    break
                }
                result.append(nav)
            }
            current = controller.presentedViewController
        }
        return result
    }

    /// 最顶端的 Kuikly 页面（主线程）。
    func topPage() -> KuiklyViewController? {
        return entries().last?.page
    }

    /// 自底向上的全部 Kuikly 页面。
    private func entries() -> [Entry] {
        var result: [Entry] = []
        for nav in navigationControllers() {
            for child in nav.viewControllers {
                if let page = child as? KuiklyViewController {
                    result.append((nav: nav, page: page))
                }
            }
        }
        return result
    }

    private func performPop(toPageName pageName: String?) {
        let all = entries()
        guard !all.isEmpty else { return }
        var targetIndex = 0
        if let name = pageName, let found = all.lastIndex(where: { $0.page.pageName == name }) {
            targetIndex = found
        }
        let target = all[targetIndex]
        let hasModalAbove = target.nav.presentedViewController != nil
        let needsPop = target.nav.topViewController !== target.page
        if hasModalAbove {
            // 先在模态下面把导航栈退到目标页，再把上面的模态整体收起。
            if needsPop {
                target.nav.popToViewController(target.page, animated: false)
            }
            (target.nav.presentedViewController as? SDNavigationController)?.isClosing = true
            target.nav.dismiss(animated: true) { [weak self] in
                self?.resync()
            }
        } else if needsPop {
            target.nav.popToViewController(target.page, animated: true)
        }
        resync()
    }

    /// 用实际的 UIKit 层级重建镜像，并刷新「被透明页盖住」状态。任意线程调用，主线程执行。
    func resync() {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in
                self?.resync()
            }
            return
        }
        guard rootNavigationController != nil else { return }
        let navs = navigationControllers()
        var names: [String] = []
        for (position, nav) in navs.enumerated() {
            // 上面还有 Kuikly 模态容器 → 这个导航栈的页面被透明页盖住。
            let covered = position < navs.count - 1
            for child in nav.viewControllers {
                guard let page = child as? KuiklyViewController else { continue }
                names.append(page.pageName)
                page.coveredByTranslucentPage = covered
            }
        }
        setMirror(names)
    }
}
