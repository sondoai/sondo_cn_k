import UIKit
import WebKit

/// 内置网页视图（隐私政策 / 用户协议 / 活动页）。契约见 shared 的 `SDNativeViews.kt`。
///
/// 属性：url
/// 事件：sdTitle {title}、sdLoad {state: "start" | "finish" | "error", progress: 0..1}
/// 方法：reload / goBack（无法后退时不做任何事）
///
/// 非 http(s) 的链接（tel: / mailto: / weixin: / itms-apps: …）交给系统打开，不在网页里加载。
@objc(SDWebView)
final class SDWebView: UIView, KuiklyRenderViewExportProtocol, WKNavigationDelegate, WKUIDelegate {

    private let webView: WKWebView
    private var observations: [NSKeyValueObservation] = []

    private var url = ""
    private var onTitle: KuiklyRenderCallback?
    private var onLoad: KuiklyRenderCallback?

    /// 当前加载阶段，随 estimatedProgress 一起上报。
    private var loadState = ""
    private var loadTicket = 0

    override init(frame: CGRect) {
        webView = SDWebView.makeWebView()
        super.init(frame: frame)
        commonInit()
    }

    required init?(coder: NSCoder) {
        webView = SDWebView.makeWebView()
        super.init(coder: coder)
        commonInit()
    }

    private static func makeWebView() -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.allowsBackForwardNavigationGestures = true
        view.isOpaque = false
        view.backgroundColor = .clear
        view.scrollView.backgroundColor = .clear
        view.scrollView.contentInsetAdjustmentBehavior = .never
        return view
    }

    private func commonInit() {
        webView.frame = bounds
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        webView.navigationDelegate = self
        webView.uiDelegate = self
        addSubview(webView)

        observations.append(webView.observe(\.title, options: [.new]) { [weak self] _, _ in
            SD.onMain { self?.emitTitle() }
        })
        observations.append(webView.observe(\.estimatedProgress, options: [.new]) { [weak self] _, _ in
            SD.onMain { self?.progressChanged() }
        })
    }

    deinit {
        for observation in observations {
            observation.invalidate()
        }
        webView.navigationDelegate = nil
        webView.uiDelegate = nil
        webView.stopLoading()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        webView.frame = bounds
    }

    // MARK: - KuiklyRenderViewExportProtocol

    @objc(hrv_setPropWithKey:propValue:)
    func hrv_setProp(withKey propKey: String, propValue: Any) {
        if css_setProp(withKey: propKey, value: propValue) {
            return
        }
        switch propKey {
        case "url":
            let value = SDMedia.string(propValue)
            if value != url {
                url = value
                scheduleLoad()
            }
        case "sdTitle":
            onTitle = SDMedia.eventCallback(propValue)
            if onTitle != nil, let title = webView.title, !title.isEmpty {
                emitTitle()
            }
        case "sdLoad":
            onLoad = SDMedia.eventCallback(propValue)
        default:
            break
        }
    }

    @objc(hrv_callWithMethod:params:callback:)
    func hrv_call(withMethod method: String, params: String?, callback: KuiklyRenderCallback?) {
        switch method {
        case "reload":
            if webView.url != nil {
                webView.reload()
            } else {
                loadCurrentURL()
            }
        case "goBack":
            if webView.canGoBack {
                webView.goBack()
            }
        default:
            break
        }
    }

    // MARK: - 加载

    /// 事件回调和 url 在同一批属性里到达；延后一个主线程循环再加载，保证 start 事件不丢。
    private func scheduleLoad() {
        loadTicket += 1
        let ticket = loadTicket
        DispatchQueue.main.async { [weak self] in
            guard let self = self, ticket == self.loadTicket else { return }
            self.loadCurrentURL()
        }
    }

    private func loadCurrentURL() {
        let text = url.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty {
            webView.stopLoading()
            return
        }
        if let file = SDMedia.fileURL(text) {
            webView.loadFileURL(file, allowingReadAccessTo: file.deletingLastPathComponent())
            return
        }
        guard let target = SDMedia.remoteURL(text) else {
            emitLoad("error", progress: 0)
            return
        }
        webView.load(URLRequest(url: target))
    }

    // MARK: - 事件

    private func emitTitle() {
        onTitle?(["title": webView.title ?? ""])
    }

    private func emitLoad(_ state: String, progress: Double) {
        loadState = state
        onLoad?(["state": state, "progress": min(1, max(0, progress))])
    }

    private func progressChanged() {
        // 只在加载过程中上报进度；结束 / 失败由导航回调上报。
        guard loadState == "start" else {
            return
        }
        emitLoad("start", progress: webView.estimatedProgress)
    }

    // MARK: - WKNavigationDelegate

    func webView(_ webView: WKWebView,
                 decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let target = navigationAction.request.url else {
            decisionHandler(.allow)
            return
        }
        let scheme = (target.scheme ?? "").lowercased()
        let inPage: Set<String> = ["http", "https", "about", "file", "data", "blob", "javascript"]
        if inPage.contains(scheme) {
            // target=_blank 的链接没有目标 frame：在当前网页里打开。
            if navigationAction.targetFrame == nil && (scheme == "http" || scheme == "https") {
                decisionHandler(.cancel)
                webView.load(navigationAction.request)
                return
            }
            decisionHandler(.allow)
            return
        }
        decisionHandler(.cancel)
        UIApplication.shared.open(target, options: [:], completionHandler: nil)
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        emitLoad("start", progress: webView.estimatedProgress)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        emitLoad("finish", progress: 1)
        emitTitle()
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        reportFailure(error)
    }

    func webView(_ webView: WKWebView,
                 didFailProvisionalNavigation navigation: WKNavigation!,
                 withError error: Error) {
        reportFailure(error)
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        // 网页进程被系统回收（内存压力）：重新加载，避免白屏。
        webView.reload()
    }

    private func reportFailure(_ error: Error) {
        let nsError = error as NSError
        // 被新的导航打断（-999）、交给系统打开的链接（WebKit 102）都不算失败。
        if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled {
            return
        }
        if nsError.domain == "WebKitErrorDomain" && nsError.code == 102 {
            return
        }
        emitLoad("error", progress: webView.estimatedProgress)
    }

    // MARK: - WKUIDelegate

    func webView(_ webView: WKWebView,
                 createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction,
                 windowFeatures: WKWindowFeatures) -> WKWebView? {
        // window.open：不开新窗口，在当前网页里加载。
        if navigationAction.request.url != nil {
            webView.load(navigationAction.request)
        }
        return nil
    }

    func webView(_ webView: WKWebView,
                 runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping () -> Void) {
        guard let presenter = presentingController() else {
            completionHandler()
            return
        }
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "确定", style: .default) { _ in
            completionHandler()
        })
        presenter.present(alert, animated: true, completion: nil)
    }

    func webView(_ webView: WKWebView,
                 runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping (Bool) -> Void) {
        guard let presenter = presentingController() else {
            completionHandler(false)
            return
        }
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "取消", style: .cancel) { _ in
            completionHandler(false)
        })
        alert.addAction(UIAlertAction(title: "确定", style: .default) { _ in
            completionHandler(true)
        })
        presenter.present(alert, animated: true, completion: nil)
    }

    /// 弹 JS 对话框用的控制器；视图不在屏幕上时返回 nil（此时直接回调，避免 WebKit 因回调未执行而崩溃）。
    private func presentingController() -> UIViewController? {
        guard window != nil, let top = SD.topViewController() else {
            return nil
        }
        if top.isBeingDismissed || top is UIAlertController {
            return nil
        }
        return top
    }
}
