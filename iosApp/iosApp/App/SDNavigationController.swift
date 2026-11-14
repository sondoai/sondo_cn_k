import UIKit

/// Kuikly 页面栈的导航控制器：导航栏隐藏、保留系统侧滑返回、按页面的 `sdTransition` 定制转场。
///
/// - 根导航栈是 window 的 rootViewController；
/// - 透明页（`sdTranslucent = 1`）装在另一个 SDNavigationController 里以 overFullScreen 模态盖在上面
///   （见 `SDPageStack`），所以整个页面栈 = 「导航栈 + 模态导航栈 …」首尾相接。
final class SDNavigationController: UINavigationController, UINavigationControllerDelegate, UIGestureRecognizerDelegate {

    /// 是否为透明模态容器（背景透明，露出下面的页面）。必须在 view 加载前设置。
    var isTranslucentContainer = false

    /// 模态容器已经开始收起（SDPageStack 发起 dismiss 时置位），页面栈统计时不再计入。
    var isClosing = false

    /// 创建承载透明页的模态容器。
    static func makeTranslucentContainer(root: UIViewController) -> SDNavigationController {
        let nav = SDNavigationController(rootViewController: root)
        nav.isTranslucentContainer = true
        nav.modalPresentationStyle = .overFullScreen
        nav.modalPresentationCapturesStatusBarAppearance = true
        return nav
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        setNavigationBarHidden(true, animated: false)
        view.backgroundColor = isTranslucentContainer ? .clear : .black
        delegate = self
        // 导航栏隐藏后系统侧滑默认失效，接管手势代理即可恢复。
        interactivePopGestureRecognizer?.delegate = self
        interactivePopGestureRecognizer?.isEnabled = true
    }

    // MARK: - 状态栏 / 方向

    override var childForStatusBarStyle: UIViewController? {
        return topViewController
    }

    override var childForStatusBarHidden: UIViewController? {
        return topViewController
    }

    override var preferredStatusBarStyle: UIStatusBarStyle {
        return .lightContent
    }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        return .portrait
    }

    override var shouldAutorotate: Bool {
        return false
    }

    // MARK: - UINavigationControllerDelegate

    func navigationController(_ navigationController: UINavigationController,
                              animationControllerFor operation: UINavigationController.Operation,
                              from fromVC: UIViewController,
                              to toVC: UIViewController) -> UIViewControllerAnimatedTransitioning? {
        // 入场看新页面的转场，退场看被关闭页面的转场（与 Flutter GetPage.transition 的进出对称一致）。
        let isPush = operation == .push
        let subject = isPush ? toVC : fromVC
        guard let page = subject as? KuiklyViewController else { return nil }
        switch page.sdTransition {
        case "bottom":
            return SDPageTransitionAnimator(style: .bottom, isPush: isPush)
        case "fade":
            return SDPageTransitionAnimator(style: .fade, isPush: isPush)
        default:
            return nil
        }
    }

    func navigationController(_ navigationController: UINavigationController,
                              didShow viewController: UIViewController,
                              animated: Bool) {
        SDPageStack.shared.resync()
    }

    // MARK: - UIGestureRecognizerDelegate（系统侧滑返回）

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard gestureRecognizer === interactivePopGestureRecognizer else { return true }
        // 转场进行中不响应，避免连续侧滑把栈弄乱。
        if transitionCoordinator != nil {
            return false
        }
        guard let page = topViewController as? KuiklyViewController else {
            return viewControllers.count > 1
        }
        if viewControllers.count <= 1 {
            // 栈底页面：根页面不可侧滑；模态容器里的透明页交给 Kuikly 的返回逻辑决定是否关闭。
            if presentingViewController != nil {
                page.handleSystemBack()
            }
            return false
        }
        // 默认转场 + 允许侧滑：走系统的可交互返回。
        if page.swipeBackEnabled && page.sdTransition.isEmpty {
            return true
        }
        // 页面关闭了侧滑（有未保存内容 / 弹窗等）或使用自定义转场：
        // 交给 Kuikly 的 BackPressHandler，未被消费且允许侧滑时再按页面自己的转场关闭。
        page.handleSystemBack()
        return false
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldBeRequiredToFailBy otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        // 页面内的横向滚动（Banner、Tab 滑动）不抢屏幕边缘的返回手势。
        guard gestureRecognizer === interactivePopGestureRecognizer else { return false }
        return otherGestureRecognizer is UIPanGestureRecognizer
            && !(otherGestureRecognizer is UIScreenEdgePanGestureRecognizer)
    }
}

/// `sdTransition = "bottom" | "fade"` 的 push / pop 转场。
final class SDPageTransitionAnimator: NSObject, UIViewControllerAnimatedTransitioning {

    enum Style {
        /// 自下而上滑入（Flutter Transition.downToUp），退场原路滑出。
        case bottom
        /// 淡入淡出（Flutter Transition.fadeIn）。
        case fade
    }

    private let style: Style
    private let isPush: Bool

    init(style: Style, isPush: Bool) {
        self.style = style
        self.isPush = isPush
        super.init()
    }

    func transitionDuration(using transitionContext: UIViewControllerContextTransitioning?) -> TimeInterval {
        switch style {
        case .bottom:
            return 0.3
        case .fade:
            return 0.25
        }
    }

    func animateTransition(using transitionContext: UIViewControllerContextTransitioning) {
        guard let toVC = transitionContext.viewController(forKey: .to),
              let fromVC = transitionContext.viewController(forKey: .from) else {
            transitionContext.completeTransition(false)
            return
        }
        let container = transitionContext.containerView
        let toView: UIView = transitionContext.view(forKey: .to) ?? toVC.view
        let fromView: UIView = transitionContext.view(forKey: .from) ?? fromVC.view
        let duration = transitionDuration(using: transitionContext)
        let offscreen = CGAffineTransform(translationX: 0, y: container.bounds.height)
        let style = self.style

        toView.frame = transitionContext.finalFrame(for: toVC)

        if isPush {
            container.addSubview(toView)
            switch style {
            case .bottom:
                toView.transform = offscreen
            case .fade:
                toView.alpha = 0
            }
            UIView.animate(withDuration: duration, delay: 0, options: [.curveEaseOut], animations: {
                toView.transform = .identity
                toView.alpha = 1
            }, completion: { _ in
                toView.transform = .identity
                toView.alpha = 1
                transitionContext.completeTransition(!transitionContext.transitionWasCancelled)
            })
        } else {
            container.insertSubview(toView, belowSubview: fromView)
            UIView.animate(withDuration: duration, delay: 0, options: [.curveEaseIn], animations: {
                switch style {
                case .bottom:
                    fromView.transform = offscreen
                case .fade:
                    fromView.alpha = 0
                }
            }, completion: { _ in
                fromView.transform = .identity
                fromView.alpha = 1
                transitionContext.completeTransition(!transitionContext.transitionWasCancelled)
            })
        }
    }
}
