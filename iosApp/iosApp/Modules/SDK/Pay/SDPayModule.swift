import Foundation
import StoreKit
import UIKit

/// 支付模块（契约见 shared 的 `SDBizModules.kt` → `SDPayModule`）。
///
/// iOS 只有 StoreKit 2 内购，没有支付宝：`alipayIsInstalled` 恒为 "0"，`alipayPay` 恒回 `unsupported`。
/// StoreKit 2 需要 iOS 15；更低的系统上所有 iap 方法回 `{ok:false, error:"unsupported"}`。
/// 具体实现见 `SDIAPManager`（进程级单例；`Transaction.updates` 的监听在模块第一次被使用时启动）。
///
/// 事件（`addEventListener`）：
/// `{event:"iapUpdate", source:"purchase"|"updates"|"restore",
///   transactions:[{transactionId, productId, status:"pending"|"purchased"|"restored"|"canceled"|"error",
///                  errorMessage?, errorCode?, appAccountToken?, jws, pendingFinish,
///                  originalTransactionId?, purchaseDateMs?, expirationDateMs?, revocationDateMs?}]}`
@objc(SDPayModule)
@objcMembers
final class SDPayModule: KRBaseModule {

    /// 向所有存活页面广播；还没有页面监听时先暂存（冷启动时 `Transaction.updates` 吐出的未完成交易）。
    static let events = SDSDKEventHub(maxPending: 64)

    /// App Store 的订阅管理页（系统面板打不开时的回退）。
    private static let manageSubscriptionsURL = "https://apps.apple.com/account/subscriptions"

    deinit {
        SDPayModule.events.remove(ownerId: ObjectIdentifier(self))
    }

    // MARK: - 支付宝（iOS 不支持）

    /// sync → "0"
    func alipayIsInstalled(_ args: NSDictionary) -> NSString {
        return SD.bool(false)
    }

    /// → {ok:false, error:"unsupported"}
    func alipayPay(_ args: NSDictionary) {
        SD.once(args).failure("unsupported")
    }

    // MARK: - 华为支付（仅鸿蒙）

    /// → {ok:false, error:"unsupported"}
    func huaweiPay(_ args: NSDictionary) {
        SD.once(args).failure("unsupported")
    }

    /// → {ok:false, error:"unsupported"}
    func huaweiContract(_ args: NSDictionary) {
        SD.once(args).failure("unsupported")
    }

    // MARK: - 内购

    /// → {ok, available}
    func iapIsAvailable(_ args: NSDictionary) {
        let done = SD.once(args)
        if #available(iOS 15.0, *) {
            SDIAPManager.shared.start()
            done.success(["available": AppStore.canMakePayments])
        } else {
            done.failure("unsupported", extra: ["available": false])
        }
    }

    /// params: {ids:[…]} → {ok, products:[…], notFound:[id]}
    func iapQueryProducts(_ args: NSDictionary) {
        let ids = SD.params(args).sdStringArray("ids")
        let done = SD.once(args)
        if #available(iOS 15.0, *) {
            SDIAPManager.shared.start()
            SDIAPManager.shared.queryProducts(ids: ids, done: done)
        } else {
            done.failure("unsupported", extra: ["products": [[String: Any]](), "notFound": ids])
        }
    }

    /// params: {productId} → {ok, eligible}
    func iapIsIntroEligible(_ args: NSDictionary) {
        let productId = SD.params(args).sdString("productId")
        let done = SD.once(args)
        if #available(iOS 15.0, *) {
            SDIAPManager.shared.isIntroEligible(productId: productId, done: done)
        } else {
            done.failure("unsupported", extra: ["eligible": false])
        }
    }

    /// params: {productId, appAccountToken, consumable, promotionalOffer?} → {ok, started, error?}
    /// 结果经 `iapUpdate` 事件返回。
    func iapPurchase(_ args: NSDictionary) {
        let params = SD.params(args)
        let done = SD.once(args)
        if #available(iOS 15.0, *) {
            SDIAPManager.shared.purchase(params: params, done: done)
        } else {
            done.failure("unsupported", extra: ["started": false])
        }
    }

    /// params: {transactionId} → {ok}
    func iapFinish(_ args: NSDictionary) {
        let transactionId = SD.params(args).sdString("transactionId")
        let done = SD.once(args)
        if #available(iOS 15.0, *) {
            SDIAPManager.shared.finish(transactionId: transactionId, done: done)
        } else {
            done.failure("unsupported")
        }
    }

    /// → {ok}；恢复到的权益经 `iapUpdate`（status = restored）事件返回，事件先于本回调送出。
    func iapRestore(_ args: NSDictionary) {
        let done = SD.once(args)
        if #available(iOS 15.0, *) {
            SDIAPManager.shared.restore(done: done)
        } else {
            done.failure("unsupported")
        }
    }

    /// → {ok, transactions:[{transactionId, productId, appAccountToken?, purchaseDateMs, jws}]}
    func iapUnfinishedTransactions(_ args: NSDictionary) {
        let done = SD.once(args)
        if #available(iOS 15.0, *) {
            SDIAPManager.shared.unfinishedTransactions(done: done)
        } else {
            done.failure("unsupported", extra: ["transactions": [[String: Any]]()])
        }
    }

    /// → {ok, transactions:[{productId, expirationDateMs?, active}]}
    func iapTransactionHistory(_ args: NSDictionary) {
        let done = SD.once(args)
        if #available(iOS 15.0, *) {
            SDIAPManager.shared.transactionHistory(done: done)
        } else {
            done.failure("unsupported", extra: ["transactions": [[String: Any]]()])
        }
    }

    /// 打开系统订阅管理页（iOS 15+ 用应用内面板，失败或更低系统跳 App Store 的订阅页）。
    func openSubscriptionManagement(_ args: NSDictionary) {
        SD.onMain {
            if #available(iOS 15.0, *), let scene = SD.keyWindow()?.windowScene {
                SDIAPManager.shared.showManageSubscriptions(in: scene) {
                    SDPayModule.openManageSubscriptionsURL()
                }
            } else {
                SDPayModule.openManageSubscriptionsURL()
            }
        }
    }

    private static func openManageSubscriptionsURL() {
        SD.onMain {
            guard let url = URL(string: SDPayModule.manageSubscriptionsURL) else { return }
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
    }

    // MARK: - 事件

    func addEventListener(_ args: NSDictionary) {
        guard let callback = SD.callback(args) else { return }
        SDPayModule.events.add(owner: self, callback: callback)
        if #available(iOS 15.0, *) {
            SDIAPManager.shared.start()
        }
    }
}
