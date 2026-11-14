import Foundation
import StoreKit
import UIKit

/// StoreKit 2 内购（进程级单例，`SDPayModule` 的实现）。
///
/// 原生只做 SDK 调用；建单、验单、会话与补单状态机都在 Kuikly 侧 PayService。
/// 行为对照参考工程 `in_app_purchase_storekit 0.4.8+1` 的 StoreKit 2 实现与 `docs/payments/*.md`：
///
/// - **从不自动 finish**：交易（含消耗型积分包）只有 Kuikly 侧验单成功后调用 `iapFinish` 才完成；
///   验单失败的交易留在 `Transaction.unfinished` 里，等下次补单。
/// - 购买结果、`Transaction.updates`（续订、Ask to Buy 批准、其它设备的购买、退款）一律以 `iapUpdate` 事件送出，
///   过期 / 撤销的交易不在原生过滤，由服务端验单返回 INELIGIBLE 后再 finish。
/// - 设备本地验签不过（`.unverified`）的交易：主动购买的结果照常上报（服务端用完整 JWS 再验），
///   后台补查（updates / unfinished / 当前权益）里直接跳过 —— 与参考工程一致。
/// - `appAccountToken` 统一输出小写 UUID（服务端下发的就是小写；比较时仍应忽略大小写）。
@available(iOS 15.0, *)
final class SDIAPManager: @unchecked Sendable {

    static let shared = SDIAPManager()

    private let lock = NSLock()
    private var updatesTask: Task<Void, Never>?
    /// 已经送给 Kuikly、可能还没 finish 的交易（`iapFinish` 先在这里找）。
    private var knownTransactions: [UInt64: StoreKit.Transaction] = [:]
    private var productCache: [String: Product] = [:]
    private var purchasing = false

    private init() {}

    // MARK: - 交易监听

    /// 开始监听 `Transaction.updates`（幂等）。模块第一次被使用时调用。
    func start() {
        lock.lock()
        defer { lock.unlock() }
        if updatesTask != nil {
            return
        }
        updatesTask = Task.detached(priority: .utility) { [weak self] in
            for await result in StoreKit.Transaction.updates {
                guard let manager = self else { return }
                manager.handleUpdate(result)
            }
        }
    }

    private func handleUpdate(_ result: VerificationResult<StoreKit.Transaction>) {
        guard case .verified(let transaction) = result else {
            return
        }
        remember(transaction)
        emit(source: "updates", items: [Self.item(result, status: "purchased", pendingFinish: true)])
    }

    // MARK: - 商品

    /// → {ok, products:[…], notFound:[id]}
    func queryProducts(ids: [String], done: SDOnce) {
        var seen = Set<String>()
        var unique: [String] = []
        for raw in ids {
            let id = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if !id.isEmpty && seen.insert(id).inserted {
                unique.append(id)
            }
        }
        if unique.isEmpty {
            done.success(["products": [[String: Any]](), "notFound": [String]()])
            return
        }
        let requested = unique
        Task {
            do {
                let fetched = try await Product.products(for: requested)
                self.cache(products: fetched)
                var byId: [String: Product] = [:]
                for product in fetched {
                    byId[product.id] = product
                }
                var products: [[String: Any]] = []
                var notFound: [String] = []
                for id in requested {
                    if let product = byId[id] {
                        products.append(Self.productPayload(product))
                    } else {
                        notFound.append(id)
                    }
                }
                done.success(["products": products, "notFound": notFound])
            } catch {
                done.failure(error.localizedDescription,
                             extra: ["products": [[String: Any]](), "notFound": requested])
            }
        }
    }

    /// 是否还能享受该订阅的首购优惠。→ {ok, eligible}
    func isIntroEligible(productId: String, done: SDOnce) {
        let id = productId.trimmingCharacters(in: .whitespacesAndNewlines)
        if id.isEmpty {
            done.failure("invalid_product_id", extra: ["eligible": false])
            return
        }
        Task {
            do {
                guard let product = try await self.product(for: id) else {
                    done.failure("product_not_found", extra: ["eligible": false])
                    return
                }
                guard let subscription = product.subscription, subscription.introductoryOffer != nil else {
                    done.success(["eligible": false])
                    return
                }
                let eligible = await Product.SubscriptionInfo.isEligibleForIntroOffer(for: subscription.subscriptionGroupID)
                done.success(["eligible": eligible])
            } catch {
                done.failure(error.localizedDescription, extra: ["eligible": false])
            }
        }
    }

    private func product(for id: String) async throws -> Product? {
        if let cached = cachedProduct(id) {
            return cached
        }
        let fetched = try await Product.products(for: [id])
        cache(products: fetched)
        return fetched.first { $0.id == id }
    }

    // MARK: - 购买

    /// params: {productId, appAccountToken(UUID), consumable, promotionalOffer?:{offerId, keyId, nonce, timestamp, signature}}
    /// → {ok, started, error?}；结果经 `iapUpdate` 事件返回（每次成功发起的购买恰好对应一个事件）。
    func purchase(params: [String: Any], done: SDOnce) {
        let productId = params.sdString("productId").trimmingCharacters(in: .whitespacesAndNewlines)
        if productId.isEmpty {
            done.failure("invalid_product_id", extra: ["started": false])
            return
        }
        // 账户令牌只用服务端建单返回的值；缺失或不是标准 UUID 时不拉起商店。
        let tokenText = params.sdString("appAccountToken").trimmingCharacters(in: .whitespacesAndNewlines)
        guard let accountToken = UUID(uuidString: tokenText) else {
            done.failure("invalid_app_account_token", extra: ["started": false])
            return
        }

        var options: Set<Product.PurchaseOption> = [.appAccountToken(accountToken)]

        // 促销优惠（回归周卡）：签名由服务端下发，Base64；字段不全时不拉起商店。
        let offer = params.sdDict("promotionalOffer")
        if !offer.isEmpty {
            let offerId = offer.sdString("offerId").trimmingCharacters(in: .whitespacesAndNewlines)
            let keyId = offer.sdString("keyId").trimmingCharacters(in: .whitespacesAndNewlines)
            let nonceText = offer.sdString("nonce").trimmingCharacters(in: .whitespacesAndNewlines)
            let signatureText = offer.sdString("signature").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !offerId.isEmpty,
                  !keyId.isEmpty,
                  let nonce = UUID(uuidString: nonceText),
                  let signature = Data(base64Encoded: signatureText), !signature.isEmpty,
                  let timestamp = offer.sdOptInt64("timestamp") else {
                done.failure("invalid_promotional_offer", extra: ["started": false])
                return
            }
            options.insert(.promotionalOffer(offerID: offerId,
                                             keyID: keyId,
                                             nonce: nonce,
                                             signature: signature,
                                             timestamp: Int(timestamp)))
        }

        guard beginPurchase() else {
            done.failure("purchase_in_progress", extra: ["started": false])
            return
        }
        start()

        let purchaseOptions = options
        let tokenForEvent = accountToken.uuidString.lowercased()
        Task { @MainActor in
            let product: Product
            do {
                guard let found = try await self.product(for: productId) else {
                    self.endPurchase()
                    done.failure("product_not_found", extra: ["started": false])
                    return
                }
                product = found
            } catch {
                self.endPurchase()
                done.failure(error.localizedDescription, extra: ["started": false])
                return
            }

            // 购买已发起：之后的结果只走 iapUpdate 事件。
            done.success(["started": true])

            do {
                let result = try await product.purchase(options: purchaseOptions)
                switch result {
                case .success(let verification):
                    self.remember(verification.unsafePayloadValue)
                    self.emit(source: "purchase",
                              items: [Self.item(verification, status: "purchased", pendingFinish: true)])
                case .pending:
                    // Ask to Buy / 需要额外验证：批准后的交易会从 Transaction.updates 过来。
                    self.emit(source: "purchase",
                              items: [Self.placeholder(productId: productId, status: "pending", appAccountToken: tokenForEvent)])
                case .userCancelled:
                    self.emit(source: "purchase",
                              items: [Self.placeholder(productId: productId, status: "canceled", appAccountToken: tokenForEvent)])
                @unknown default:
                    self.emit(source: "purchase",
                              items: [Self.placeholder(productId: productId,
                                                       status: "error",
                                                       appAccountToken: tokenForEvent,
                                                       errorMessage: "unknown_purchase_result")])
                }
            } catch {
                var cancelled = false
                if let storeError = error as? StoreKitError, case .userCancelled = storeError {
                    cancelled = true
                }
                if cancelled {
                    self.emit(source: "purchase",
                              items: [Self.placeholder(productId: productId, status: "canceled", appAccountToken: tokenForEvent)])
                } else {
                    self.emit(source: "purchase",
                              items: [Self.placeholder(productId: productId,
                                                       status: "error",
                                                       appAccountToken: tokenForEvent,
                                                       errorMessage: error.localizedDescription,
                                                       errorCode: String(describing: error))])
                }
            }
            self.endPurchase()
        }
    }

    // MARK: - 完成 / 补查 / 恢复

    /// 完成交易（验单成功，或补单时服务端返回 INELIGIBLE 之后由 Kuikly 侧调用）。
    /// → {ok, finished}；交易已经不在未完成列表里时 finished=false，仍算成功。
    func finish(transactionId: String, done: SDOnce) {
        let text = transactionId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let id = UInt64(text), id != 0 else {
            done.failure("invalid_transaction_id")
            return
        }
        Task {
            if let known = self.knownTransaction(id) {
                await known.finish()
                self.forget(id)
                done.success(["finished": true])
                return
            }
            for await result in StoreKit.Transaction.unfinished {
                let transaction = result.unsafePayloadValue
                if transaction.id == id {
                    await transaction.finish()
                    done.success(["finished": true])
                    return
                }
            }
            done.success(["finished": false])
        }
    }

    /// 未完成交易。→ {ok, transactions:[{transactionId, productId, appAccountToken?, purchaseDateMs, jws, …}]}
    func unfinishedTransactions(done: SDOnce) {
        start()
        Task {
            var items: [[String: Any]] = []
            for await result in StoreKit.Transaction.unfinished {
                guard case .verified(let transaction) = result else {
                    continue
                }
                self.remember(transaction)
                items.append(Self.item(result, status: "purchased", pendingFinish: true))
            }
            done.success(["transactions": items])
        }
    }

    /// 手动恢复购买：先向 App Store 同步，再把当前权益逐笔以 `restored` 事件送出（不需要 finish，但仍要验单）。
    /// → {ok, synced, count}
    func restore(done: SDOnce) {
        start()
        Task {
            var synced = true
            do {
                try await AppStore.sync()
            } catch {
                if let storeError = error as? StoreKitError, case .userCancelled = storeError {
                    // 用户取消了 Apple ID 验证：这次恢复不算数。
                    done.failure("canceled", code: "canceled")
                    return
                }
                // 同步失败（断网等）：仍然用本机已有的权益继续。
                synced = false
            }
            var items: [[String: Any]] = []
            for await result in StoreKit.Transaction.currentEntitlements {
                guard case .verified = result else {
                    continue
                }
                items.append(Self.item(result, status: "restored", pendingFinish: false))
            }
            if !items.isEmpty {
                self.emit(source: "restore", items: items)
            }
            done.success(["synced": synced, "count": items.count])
        }
    }

    /// 购买历史（按商品合并）。→ {ok, transactions:[{productId, expirationDateMs?, active}]}
    ///
    /// 用途是参考工程的「是否订阅过」判断（任一条有到期时间即订阅过，决定回归优惠走促销签名还是首购优惠），
    /// 所以取 `Transaction.all`（包含已过期的订阅）而不是只取当前权益；`active` 标出它是否仍在当前权益里。
    func transactionHistory(done: SDOnce) {
        Task {
            var activeIds = Set<UInt64>()
            for await result in StoreKit.Transaction.currentEntitlements {
                if case .verified(let transaction) = result {
                    activeIds.insert(transaction.id)
                }
            }
            var order: [String] = []
            var expirations: [String: Date] = [:]
            var actives: [String: Bool] = [:]
            for await result in StoreKit.Transaction.all {
                guard case .verified(let transaction) = result else {
                    continue
                }
                let productId = transaction.productID
                if actives[productId] == nil {
                    order.append(productId)
                    actives[productId] = false
                }
                if activeIds.contains(transaction.id) {
                    actives[productId] = true
                }
                if let expiration = transaction.expirationDate {
                    if let current = expirations[productId] {
                        if expiration > current {
                            expirations[productId] = expiration
                        }
                    } else {
                        expirations[productId] = expiration
                    }
                }
            }
            var items: [[String: Any]] = []
            for productId in order {
                var item: [String: Any] = [
                    "productId": productId,
                    "active": actives[productId] ?? false,
                ]
                if let expiration = expirations[productId] {
                    item["expirationDateMs"] = Self.millis(expiration)
                }
                items.append(item)
            }
            done.success(["transactions": items])
        }
    }

    // MARK: - 订阅管理

    /// 打开系统的订阅管理面板；失败时走 fallback（外链）。仅主线程调用。
    func showManageSubscriptions(in scene: UIWindowScene, fallback: @escaping () -> Void) {
        Task { @MainActor in
            do {
                try await AppStore.showManageSubscriptions(in: scene)
            } catch {
                fallback()
            }
        }
    }

    // MARK: - 事件

    private func emit(source: String, items: [[String: Any]]) {
        SDPayModule.events.emit([
            "event": "iapUpdate",
            "source": source,
            "transactions": items,
        ])
    }

    /// 一笔真实交易 → 事件里的一项。
    private static func item(_ result: VerificationResult<StoreKit.Transaction>,
                             status: String,
                             pendingFinish: Bool) -> [String: Any] {
        let transaction = result.unsafePayloadValue
        var item: [String: Any] = [
            "transactionId": String(transaction.id),
            "originalTransactionId": String(transaction.originalID),
            "productId": transaction.productID,
            "status": status,
            "jws": result.jwsRepresentation,
            "pendingFinish": pendingFinish,
            "purchaseDateMs": millis(transaction.purchaseDate),
        ]
        if let token = transaction.appAccountToken {
            item["appAccountToken"] = token.uuidString.lowercased()
        }
        if let expiration = transaction.expirationDate {
            item["expirationDateMs"] = millis(expiration)
        }
        if let revocation = transaction.revocationDate {
            item["revocationDateMs"] = millis(revocation)
        }
        return item
    }

    /// 没有交易的结果（待支付 / 取消 / 失败）。
    private static func placeholder(productId: String,
                                    status: String,
                                    appAccountToken: String,
                                    errorMessage: String? = nil,
                                    errorCode: String? = nil) -> [String: Any] {
        var item: [String: Any] = [
            "transactionId": "",
            "productId": productId,
            "status": status,
            "jws": "",
            "pendingFinish": false,
            "appAccountToken": appAccountToken,
        ]
        if let errorMessage = errorMessage {
            item["errorMessage"] = errorMessage
        }
        if let errorCode = errorCode {
            item["errorCode"] = errorCode
        }
        return item
    }

    private static func millis(_ date: Date) -> Int64 {
        return Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    // MARK: - 商品 → JSON

    private static func productPayload(_ product: Product) -> [String: Any] {
        let style = product.priceFormatStyle
        var item: [String: Any] = [
            "id": product.id,
            "title": product.displayName,
            "description": product.description,
            "price": product.displayPrice,
            "rawPrice": NSDecimalNumber(decimal: product.price).doubleValue,
            "currencyCode": style.currencyCode,
            "currencySymbol": style.locale.currencySymbol ?? "",
            "type": productTypeName(product.type),
        ]
        if let subscription = product.subscription {
            item["subscription"] = subscriptionPayload(subscription)
        }
        return item
    }

    private static func subscriptionPayload(_ info: Product.SubscriptionInfo) -> [String: Any] {
        var offers: [[String: Any]] = []
        if let introductory = info.introductoryOffer {
            offers.append(offerPayload(introductory))
        }
        for offer in info.promotionalOffers {
            offers.append(offerPayload(offer))
        }
        return [
            "groupId": info.subscriptionGroupID,
            "periodUnit": unitName(info.subscriptionPeriod.unit),
            "periodCount": info.subscriptionPeriod.value,
            "offers": offers,
        ]
    }

    /// `periodUnit` / `periodCount` 是优惠的单个周期，`cycles` 是周期数（payAsYouGo 时 > 1）。
    private static func offerPayload(_ offer: Product.SubscriptionOffer) -> [String: Any] {
        var item: [String: Any] = [
            "type": offerTypeName(offer.type),
            "paymentMode": paymentModeName(offer.paymentMode),
            "price": offer.displayPrice,
            "rawPrice": NSDecimalNumber(decimal: offer.price).doubleValue,
            "periodUnit": unitName(offer.period.unit),
            "periodCount": offer.period.value,
            "cycles": offer.periodCount,
        ]
        // 首购优惠没有 id，促销优惠一定有。
        if let id = offer.id {
            item["id"] = id
        }
        return item
    }

    private static func productTypeName(_ type: Product.ProductType) -> String {
        if type == .consumable {
            return "consumable"
        }
        if type == .nonConsumable {
            return "nonConsumable"
        }
        if type == .nonRenewable {
            return "nonRenewable"
        }
        if type == .autoRenewable {
            return "autoRenewable"
        }
        return "unknown"
    }

    private static func unitName(_ unit: Product.SubscriptionPeriod.Unit) -> String {
        switch unit {
        case .day:
            return "day"
        case .week:
            return "week"
        case .month:
            return "month"
        case .year:
            return "year"
        @unknown default:
            return "day"
        }
    }

    private static func offerTypeName(_ type: Product.SubscriptionOffer.OfferType) -> String {
        if type == .introductory {
            return "introductory"
        }
        if type == .promotional {
            return "promotional"
        }
        return "other"
    }

    private static func paymentModeName(_ mode: Product.SubscriptionOffer.PaymentMode) -> String {
        if mode == .freeTrial {
            return "freeTrial"
        }
        if mode == .payAsYouGo {
            return "payAsYouGo"
        }
        if mode == .payUpFront {
            return "payUpFront"
        }
        return "unknown"
    }

    // MARK: - 加锁的小状态（同步方法，可在任意线程 / 异步上下文里调用）

    private func remember(_ transaction: StoreKit.Transaction) {
        lock.lock()
        defer { lock.unlock() }
        knownTransactions[transaction.id] = transaction
    }

    private func knownTransaction(_ id: UInt64) -> StoreKit.Transaction? {
        lock.lock()
        defer { lock.unlock() }
        return knownTransactions[id]
    }

    private func forget(_ id: UInt64) {
        lock.lock()
        defer { lock.unlock() }
        knownTransactions[id] = nil
    }

    private func cache(products: [Product]) {
        lock.lock()
        defer { lock.unlock() }
        for product in products {
            productCache[product.id] = product
        }
    }

    private func cachedProduct(_ id: String) -> Product? {
        lock.lock()
        defer { lock.unlock() }
        return productCache[id]
    }

    private func beginPurchase() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if purchasing {
            return false
        }
        purchasing = true
        return true
    }

    private func endPurchase() {
        lock.lock()
        defer { lock.unlock() }
        purchasing = false
    }
}
