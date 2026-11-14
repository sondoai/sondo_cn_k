import UIKit

/// 页面栈操作。契约见 shared/.../nativeapi/SDNavModule.kt；实际逻辑在 `SDPageStack`。
@objc(SDNavModule)
@objcMembers
final class SDNavModule: KRBaseModule {

    // MARK: - 同步方法（Kuikly 线程）

    /// 关闭 pageName 之上的所有页面；栈内没有该页面时回到根页面。→ "1" 命中 / "0" 未命中
    func popTo(_ args: NSDictionary) -> NSString {
        let pageName = SD.params(args).sdString("pageName")
        guard !pageName.isEmpty else { return SD.bool(false) }
        return SD.bool(SDPageStack.shared.popTo(pageName: pageName))
    }

    /// 当前页面栈（自底向上的 pageName 列表）→ JSON 数组字符串
    func stack(_ args: NSDictionary) -> NSString {
        return SD.jsonResult(SDPageStack.shared.pageNames())
    }

    // MARK: - 异步方法（主线程）

    /// 回到根页面。
    func popToRoot(_ args: NSDictionary) {
        SDPageStack.shared.popToRoot()
    }

    /// 关闭当前页并打开新页。params: {pageName, pageData}
    func replace(_ args: NSDictionary) {
        let params = SD.params(args)
        let pageName = params.sdString("pageName")
        let pageData = params.sdDict("pageData")
        SD.onMain { [weak self] in
            SDPageStack.shared.replace(from: self?.sd_viewController, pageName: pageName, pageData: pageData)
        }
    }

    /// 清空整个栈并以 pageName 作为新的根。params: {pageName, pageData}
    func resetRoot(_ args: NSDictionary) {
        let params = SD.params(args)
        let pageName = params.sdString("pageName")
        let pageData = params.sdDict("pageData")
        SD.onMain {
            SDPageStack.shared.resetRoot(pageName: pageName, pageData: pageData)
        }
    }

    /// 是否允许系统侧滑关闭当前页。params: {enabled}
    func setSwipeBackEnabled(_ args: NSDictionary) {
        let enabled = SD.params(args).sdBool("enabled", true)
        SD.onMain { [weak self] in
            guard let page = self?.sd_viewController as? KuiklyViewController else { return }
            page.swipeBackEnabled = enabled
        }
    }
}
