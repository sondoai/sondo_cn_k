package com.sondo.app.service.pay

import com.sondo.app.base.AppEnv
import com.sondo.app.base.AppHost
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.base.currentPager
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.AuthSession
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.currentPagerId
import com.sondo.app.core.utils.isPagerAlive
import com.sondo.app.data.models.AlipayOrderResult
import com.sondo.app.data.models.AppleReturningPromotion
import com.sondo.app.data.models.GoodsModel
import com.sondo.app.data.models.IosOrder
import com.sondo.app.data.models.ProductInfo
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.nativeapi.SDPayModule
import com.sondo.app.nativeapi.nativeModule
import com.sondo.app.service.ConfigService
import com.sondo.app.service.UserStore
import com.sondo.app.service.analytics.AdEvent
import com.sondo.app.service.analytics.UmengUtils
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 应用级支付服务，管理商品购买、恢复购买与平台交易监听（Dart: lib/shared/services/pay_service.dart）。
 *
 * iOS 走 StoreKit 2：建单 → 拉起商店 → 验单 → 成功后完成交易。
 * Android / 鸿蒙 会员走支付宝自动续费：建单 → 打开签约链接 → 按后端订单状态发权益。
 * Android / 鸿蒙 积分包走支付宝 APP 支付：建单 → payV2 → 同一套查单。
 * 权益只认后端确认：StoreKit 回调和支付宝 9000 都不算到账。
 *
 * StoreKit 会话隔离规则；支付宝另用独立的账号与订单上下文：
 * 1. 用户主动购买只使用 buySession。
 * 2. 自动补单 / 手动补单只使用 repairSession。
 * 3. 补单验单成功后只刷新用户信息和完成交易，不执行主动购买 UI、打点、页面返回。
 *
 * 运行方式：对外方法可以在任意页面上下文调用，状态机统一切到宿主页面（根页面）上跑，
 * 页面关闭不影响建单 / 验单 / 轮询；toast、loading、关页由 [PayUi] 找到可见页面执行。
 * 结果：每次用户操作恰好回执一次（回调 + [Events.PAY_RESULT] 广播，见 [PayOutcome]）；
 * 回执之后才到账（待批准、轮询超时后补查到）以及静默补单另外广播一次，`late` / `userInitiated` 标明。
 */
object PayService {

    // --- 会话隔离 ---

    /** 仅主动购买使用；补单禁止读写，避免串打点和页面返回。 */
    private var buySession = BuySession(currentPageName = "")

    /** 自动/手动补单上下文；未在补单时为 null。 */
    private var repairSession: RepairSession? = null

    // --- 互斥标志 ---

    /** 主动购买进行中，阻止连续 submit 共用同一套 IAP 状态。 */
    private var userPurchaseInFlight = false

    /** 补单 session 已激活，供 resumePurchase 互斥和事件分流使用。 */
    private var repairInFlight = false

    /**
     * 补单处理函数执行中，拦截事件重入。
     * 与 [repairInFlight] 的区别：resumePurchase 在调用 restore 之前就设置了 repairInFlight，
     * 商店事件此时尚未到来；用此标志拦截处理函数重入，而不会误拦来自 resumePurchase 的第一次合法事件。
     */
    private var repairProcessing = false

    /** resumePurchase 持有本轮补单收尾权时，事件处理只更新计数，不自行 toast/清 session。 */
    private var resumeOwnsRepair = false

    /**
     * 商店购买已发起、它的结果事件（`iapUpdate` source = purchase）还没到，避免此时再次拉起支付。
     * 原生在付款面板弹出前就返回，所以这个标志从调用起一直保持到结果事件处理完。
     */
    private var nativePurchaseInFlight = false

    /** 发起这笔商店购买的会话；结果事件处理完后据此判断本单是否还没收尾。 */
    private var nativePurchaseSession: BuySession? = null

    /** 积分包正在查支付宝是否安装。此时会话还没建，靠它挡住连点。 */
    private var alipayTradeStarting = false

    /** 支付宝渠道被后端关闭后，订阅入口不再发起新单。 */
    var alipayChannelClosed = false

    /** 支付宝自动续费的账号、订单和轮询。积分包另用 [alipayTradeSession]。 */
    private var alipaySession: AlipaySession? = null

    /** 支付宝积分包订单。和自动续费分开存，避免互相覆盖。 */
    private var alipayTradeSession: AlipaySession? = null

    /** 支付宝查单间隔（毫秒）与每轮最多次数（Dart: debugAlipayPollInterval / debugAlipayPollMaxTicks）。 */
    var alipayPollIntervalMs = 3000
    var alipayPollMaxTicks = 20

    /** 已验过的 Apple 交易，避免 restored 后再用同一笔 purchased 重复 PURCHASE。 */
    private val verifiedPurchaseIds = HashSet<String>()

    /** 商店事件串行队列（每项：事件来源 + 交易列表），避免补单处理中丢掉随后到达的交易。 */
    private val pendingStreamBatches = ArrayList<Pair<String, List<PurchaseDetails>>>()
    private var handlingPurchaseStream = false

    /** 购买 loading 是否已弹出，防止重复 show。 */
    var isOpenDialog = false
        private set

    /** 积分商品集合。Apple 交易统一由 StoreKit 完成。 */
    val creditProductIDs: Set<String> = setOf(
        "add_credit_200",
        "add_credit_800",
        "add_credit_3200",
        "add_credit_8000",
        "ios_add_credit_200",
        "ios_add_credit_800",
        "ios_add_credit_3200",
        "ios_add_credit_8000",
    )

    /** 监听绑定的宿主页面；宿主重建后要重新绑定。 */
    private var boundHostId = ""

    /** iOS 交易事件监听已注册（Dart: `_subscription != null`）。 */
    private var iapListening = false

    /** 已按登录触发过补单的账号代次，防止到账后刷新资料时递归恢复。 */
    private var loginRepairRevision = -1

    /** 正在走「取消自动续费」的页面，防连点。 */
    private var cancelingAlipayPagerId = ""


    /** 购买成功后自动关闭的页面。 */
    private val closableRoutes = setOf(
        Routes.subscribe, Routes.discount, Routes.recall, Routes.recallProduct, Routes.creditsPurchase,
    )

    /** 平台适配入口；iOS 走 StoreKit 2，其余（Android / 鸿蒙）走支付宝。 */
    private val isIos: Boolean get() = AppEnv.isIOS

    /** 未登录不得验单或完成交易，避免把权益记到错误账户。 */
    private val isLoggedIn: Boolean get() = UserStore.isLogin

    private fun log(msg: String) = DevLog.logger("[Pay] $msg")

    private fun payModule(): SDPayModule = nativeModule(SDPayModule.MODULE_NAME)

    private fun appModule(): SDAppModule = nativeModule(SDAppModule.MODULE_NAME)

    // =============================================================================================
    // 初始化 / 生命周期
    // =============================================================================================

    /** 注册平台交易监听。应用启动时在根页面就绪后调用一次；宿主页面重建后再次调用会重新绑定。 */
    fun init(): PayService {
        initializeInAppPurchase()
        return this
    }

    /**
     * 初始化应用内购买服务。
     *
     * 商店事件是全局流，这里按当前上下文分流：
     * - 用户主动购买：走 handleUserPurchaseUpdates。
     * - restore / restored / 补单：走 handleRepairPurchaseUpdates。
     */
    fun initializeInAppPurchase() {
        val hostId = PayHost.pagerId
        if (boundHostId == hostId && isPagerAlive(hostId)) {
            log("IAP already initialized, skip")
            return
        }
        if (boundHostId.isNotEmpty()) {
            // 宿主页面重建：旧页面上的定时器、回调都已丢失，内存态清掉重来。
            onClose()
        }
        boundHostId = hostId
        bindHostEvents()
        if (!isIos) {
            // 冷启动时把上次未结束的支付宝订单再查一次，不重新下单。
            log("国内 Android / 鸿蒙 版本不初始化商店支付")
            PayHost.launch { resumeAlipayOrderInternal() }
            return
        }
        try {
            AppHost.module<SDPayModule>(SDPayModule.MODULE_NAME).addEventListener { onIapEvent(it) }
            iapListening = true
        } catch (e: Throwable) {
            log("iOS 交易监听注册失败: $e")
            return
        }
        // StoreKit 启动后立即补查未完成交易。
        // 原生会把冷启动时暂存的 Transaction.updates 在监听注册后补发：在宿主上下文里同步起跑，
        // 让补单标志先于这些事件就位，它们才会走静默补单而不是主动购买分支。
        if (currentPagerId() == hostId) {
            PayHost.spawn { resumePurchaseInternal(showRestoreTips = false) }
        } else {
            PayHost.launch { resumePurchaseInternal(showRestoreTips = false) }
        }
    }

    private fun ensureInit() {
        val hostId = PayHost.pagerId
        if (boundHostId != hostId || !isPagerAlive(hostId)) initializeInAppPurchase()
    }

    /** 在宿主页面上订阅前后台与账号变化（与 App 同寿命）。 */
    private fun bindHostEvents() {
        try {
            val notify = AppHost.pager.acquireModule<NotifyModule>(NotifyModule.MODULE_NAME)
            notify.addNotify(Events.APP_LIFECYCLE) { data ->
                if (data?.optBoolean("foreground", false) == true) onAppResumed()
            }
            notify.addNotify(Events.USER_CHANGED) { data -> onUserChanged(data) }
        } catch (e: Throwable) {
            log("bindHostEvents failed: $e")
        }
    }

    /** 回到前台：iOS 补查未完成交易；支付宝从收银台 / 签约页回来后查原订单，不重新下单。 */
    private fun onAppResumed() {
        if (isIos) {
            PayHost.spawn { resumePurchaseInternal(showRestoreTips = false) }
        } else {
            PayHost.spawn { resumeAlipayOrderInternal() }
        }
    }

    /** 登录就绪后静默补单；每个账号代次只触发一次。登出兜底清内存态。 */
    private fun onUserChanged(data: JSONObject?) {
        val login = if (data != null && data.has("login")) data.optBoolean("login", false) else isLoggedIn
        if (!login) {
            if (hasMemoryState()) resetOnLogout()
            return
        }
        sweepStaleSessions()
        val revision = AuthSession.revision
        if (loginRepairRevision == revision || !isLoggedIn) return
        loginRepairRevision = revision
        if (isIos) {
            PayHost.spawn { resumePurchaseInternal(showRestoreTips = false) }
        } else {
            PayHost.spawn { resumeAlipayOrderInternal() }
        }
    }

    private fun hasMemoryState(): Boolean =
        userPurchaseInFlight || repairInFlight || nativePurchaseInFlight || buySession.attempt != null ||
            alipaySession != null || alipayTradeSession != null || pendingStreamBatches.isNotEmpty()

    /** 换号后旧账号留下的会话：停轮询、回执作废。 */
    private fun sweepStaleSessions() {
        val attempt = buySession.attempt
        if (attempt != null && !attempt.resolved && !AuthSession.isCurrent(buySession.revision)) {
            disposeDialog(27)
            clearBuySession("all", PayStatus.SUPERSEDED, PayReason.ACCOUNT_SWITCHED)
        }
        val member = alipaySession
        if (member != null && !isAlipaySessionActive(member)) {
            dropAlipaySlot(member, PayReason.ACCOUNT_SWITCHED)
            alipaySession = null
        }
        val trade = alipayTradeSession
        if (trade != null && !isAlipaySessionActive(trade)) {
            dropAlipaySlot(trade, PayReason.ACCOUNT_SWITCHED)
            alipayTradeSession = null
        }
    }

    /** 登出后清支付内存态。PayService 是应用级单例，不会随登出销毁。 */
    fun resetOnLogout() {
        pendingStreamBatches.clear()
        handlingPurchaseStream = false
        clearBuySession("all", PayStatus.SUPERSEDED, PayReason.LOGOUT)
        clearRepairSession()
        nativePurchaseInFlight = false
        nativePurchaseSession = null
        verifiedPurchaseIds.clear()
        dropAlipaySlot(alipaySession, PayReason.LOGOUT)
        dropAlipaySlot(alipayTradeSession, PayReason.LOGOUT)
        alipaySession = null
        alipayTradeSession = null
        disposeDialog(26)
    }

    /** 宿主页面销毁 / 重建时清内存态（Dart: onClose）。本地保存的支付宝待单保留。 */
    fun onClose() {
        pendingStreamBatches.clear()
        handlingPurchaseStream = false
        clearBuySession("all", PayStatus.SUPERSEDED, PayReason.INTERNAL)
        clearRepairSession()
        nativePurchaseInFlight = false
        nativePurchaseSession = null
        dropAlipaySlot(alipaySession, PayReason.INTERNAL)
        dropAlipaySlot(alipayTradeSession, PayReason.INTERNAL)
        alipaySession = null
        alipayTradeSession = null
        iapListening = false
        boundHostId = ""
        disposeDialog(28)
    }

    /**
     * 登记一个页面，供支付流程在它上面弹 toast / loading、关页。
     * 调用过 submit / resumePurchase 等入口的页面会自动登记；BasePager 也可以在出现时统一调用，
     * 这样任何页面在栈顶时都能收到支付提示。
     */
    fun attachPage(pager: BasePager) = PayUi.register(pager)

    /** 是否为积分消耗型商品。 */
    fun isCreditProduct(productID: String): Boolean = creditProductIDs.contains(productID)

    // =============================================================================================
    // 主动购买会话
    // =============================================================================================

    private fun buyOutcome(
        session: BuySession,
        status: String,
        reason: String,
        message: String,
        isNewOrder: Boolean,
        pageClosed: Boolean,
        late: Boolean,
    ): PayOutcome = PayOutcome(
        type = PayOutcome.TYPE_PURCHASE,
        channel = PayOutcome.CHANNEL_APPLE,
        status = status,
        reason = reason,
        pageName = session.currentPageName,
        productId = session.productId,
        orderId = session.getOrder(session.productId) ?: session.iosOrder?.orderNum ?: "",
        subscription = session.subscription,
        isNewOrder = isNewOrder,
        userInitiated = true,
        late = late,
        pageClosed = pageClosed,
        message = message,
    )

    /**
     * 清理主动购买上下文并释放 in-flight，同时给本次购买回执（只回执一次）。
     *
     * [productId] 为 `all` 时清空整次会话；否则只移除该商品，但仍清 iosOrder，
     * 避免 appAccountToken 残留到下一笔购买。
     */
    private fun clearBuySession(
        productId: String,
        status: String,
        reason: String = "",
        message: String = "",
        isNewOrder: Boolean = false,
        pageClosed: Boolean = false,
    ): Boolean {
        val session = buySession
        if (isForeignProduct(productId)) {
            // 别的商品的交易（如前台到达的续订）收尾，不能把正在进行的这笔购买一起释放。
            session.removeProduct(productId)
            session.removeOrder(productId)
            return false
        }
        val attempt = session.attempt
        val outcome = if (attempt == null) null else buyOutcome(
            session, status, reason, message, isNewOrder, pageClosed, late = attempt.resolved,
        )
        if (productId == "all") {
            session.clearAll()
        } else if (productId.isNotEmpty()) {
            session.removeProduct(productId)
            session.removeOrder(productId)
        }
        session.iosOrder = null
        userPurchaseInFlight = false
        if (attempt != null && outcome != null) {
            if (!attempt.resolved) {
                attempt.resolve(outcome)
            } else if (status == PayStatus.SUCCESS) {
                // 已回执过（待批准），到账后补发一次广播
                PayHost.emit(Events.PAY_RESULT, outcome.toJson())
            }
        }
        return true
    }

    /** 主动购买进行中，而 [productId] 不是这笔购买的商品。 */
    private fun isForeignProduct(productId: String): Boolean {
        if (productId == "all" || productId.isEmpty() || !userPurchaseInFlight) return false
        val current = buySession.productId
        return current.isNotEmpty() && current != productId
    }

    /** 清理补单上下文，并复位所有补单互斥标志。 */
    private fun clearRepairSession() {
        repairSession = null
        repairInFlight = false
        repairProcessing = false
        resumeOwnsRepair = false
    }

    /** 异步返回后校验：session 已被下一笔购买替换或已登出则丢弃结果。 */
    private fun isBuySessionActive(session: BuySession): Boolean = buySession === session && isLoggedIn

    // =============================================================================================
    // 商店事件
    // =============================================================================================

    private fun onIapEvent(event: JSONObject) {
        if (event.optString("event", "") != "iapUpdate") return
        val source = event.optString("source", "").ifEmpty { PurchaseDetails.SOURCE_UPDATES }
        val list = PurchaseDetails.listFromUpdate(event.optJSONArray("transactions"), source)
        // 监听注册在宿主页面上，这里已经是宿主上下文；直接起协程保证按到达顺序入队。
        PayHost.spawn { onPurchaseStream(source, list) }
    }

    /** 商店回调入口：按到达顺序入队，串行处理。 */
    private suspend fun onPurchaseStream(source: String, purchaseList: List<PurchaseDetails>) {
        pendingStreamBatches.add(source to purchaseList)
        if (handlingPurchaseStream) return
        handlingPurchaseStream = true
        try {
            while (pendingStreamBatches.isNotEmpty()) {
                val (batchSource, batch) = pendingStreamBatches.removeAt(0)
                try {
                    dispatchPurchaseStream(batch)
                } finally {
                    if (batchSource == PurchaseDetails.SOURCE_PURCHASE) onNativePurchaseSettled()
                }
            }
        } catch (e: Throwable) {
            // 商店会重投未完成交易，保留会话等待下次补查。
            log("iOS 交易监听异常，等待下次未完成交易补查: $e")
        } finally {
            handlingPurchaseStream = false
        }
    }

    /**
     * 本次商店购买的结果事件已经处理完（每笔发起的购买恰好一个）。
     *
     * 此时本单仍未收尾，说明商店回的是旧交易（已订阅再买、旧续订转补单、已验过的交易），
     * 不会再有匹配交易，必须释放 loading 和购买互斥，否则用户无法再操作。
     * 对应参考工程的 `_watchUnresolvedIosPurchase`：那边的商店调用在付款面板关闭后才返回，
     * 所以从调用返回起计时 3 秒；这里的原生在面板弹出前就返回，改为由结果事件触发。
     */
    private fun onNativePurchaseSettled() {
        val session = nativePurchaseSession
        nativePurchaseInFlight = false
        nativePurchaseSession = null
        if (session == null || buySession !== session || !userPurchaseInFlight || session.storePending) return
        log("iOS 购买结果里没有属于本单的交易，释放购买会话")
        disposeDialog(32)
        clearBuySession("all", PayStatus.TIMEOUT, PayReason.NO_TRANSACTION)
    }

    /** 串行消化一批事件：未登录直接丢弃；空列表交给 resume 收尾；其余按分流规则处理。 */
    private suspend fun dispatchPurchaseStream(purchaseList: List<PurchaseDetails>) {
        if (!isLoggedIn) {
            log("未登录，不处理订单")
            clearBuySession("all", PayStatus.SUPERSEDED, PayReason.LOGOUT)
            clearRepairSession()
            disposeDialog(4)
            return
        }
        log(
            "检测到需要处理的订单数：${purchaseList.size}, " +
                "userPurchaseInFlight:$userPurchaseInFlight, repairInFlight:$repairInFlight",
        )
        if (purchaseList.isEmpty()) {
            handleEmptyPurchaseList()
            return
        }

        val userList = ArrayList<PurchaseDetails>()
        val repairList = ArrayList<PurchaseDetails>()
        for (item in purchaseList) {
            val ignorable = item.status == PurchaseStatus.error || item.status == PurchaseStatus.canceled ||
                item.status == PurchaseStatus.pending
            if (item.productID.trim().isEmpty() && !ignorable) continue
            if (shouldRouteToRepair(item)) repairList.add(item) else userList.add(item)
        }
        if (userList.isEmpty() && repairList.isEmpty()) {
            handleEmptyPurchase()
        }

        if (repairList.isNotEmpty() && !repairProcessing) {
            ensureRepairSession()
            repairProcessing = true
            handleRepairPurchaseUpdates(repairList, repairSession?.flowType ?: PayFlowType.autoRepair)
        }

        if (userList.isNotEmpty()) {
            handleUserPurchaseUpdates(userList)
        }
    }

    /**
     * 判断回调应走补单还是主动购买。
     *
     * 优先级：
     * 1. restored 状态一定走补单。
     * 2. 本单已有合法 appAccountToken 时，token 不一致的旧续订走补单，禁止绑到新订单。
     * 3. 如果 buySession 中已有该 productID 的订单，则走主动购买。
     * 4. 补单进行中且无主动购买订单时走补单；其余默认主动购买。
     */
    private fun shouldRouteToRepair(item: PurchaseDetails): Boolean {
        if (item.status == PurchaseStatus.restored) return true
        if (item.status != PurchaseStatus.purchased) return false
        if (isIosAccountTokenMismatch(item)) return true
        val activeBuyOrderNum = buySession.getOrder(item.productID) ?: ""
        if (activeBuyOrderNum.isNotEmpty()) return false
        // 后台到达的交易（续订、其它设备的购买、冷启动补发的未完成交易）没有本次建的订单，只能按补单验。
        if (item.source == PurchaseDetails.SOURCE_UPDATES) return true
        return repairInFlight
    }

    /** 本单已有合法 token 时，其他 UUID（含空 token）视为旧账户交易。 */
    private fun isIosAccountTokenMismatch(item: PurchaseDetails): Boolean {
        val expected = buySession.iosOrder?.appAccountToken
        if (expected == null || !IosOrder.validToken(expected)) return false
        return !IosOrder.sameToken(expected, item.appAccountToken)
    }

    /** 空 restore 列表处理。resumePurchase 持有收尾权时不拆 session，避免提前结束本轮。 */
    private fun handleEmptyPurchaseList() {
        // resumePurchase 统一收尾，避免空 restore 事件提前拆掉 session。
        if (resumeOwnsRepair) return
        val showTips = repairSession?.showTips ?: false
        if (showTips) {
            PayUi.toast("no_content_to_restore".tr)
        }
        if (!userPurchaseInFlight && showTips) {
            disposeDialog(5)
        }
        // 待批准的购买保留订单映射，等批准后的交易回来对号。
        if (!userPurchaseInFlight && !buySession.storePending) {
            clearBuySession("all", PayStatus.SUPERSEDED, PayReason.REPLACED)
        }
        if (repairInFlight) {
            clearRepairSession()
        }
    }

    /** 无有效商品的占位事件：手动恢复时结束本轮，避免 loading 卡住。 */
    private fun handleEmptyPurchase() {
        if ((repairSession?.showTips ?: false) && !resumeOwnsRepair) {
            disposeDialog(5)
            clearRepairSession()
        }
    }

    /** 账外 purchased 进入补单时补建 autoRepair session，静默处理、不弹 toast。 */
    private fun ensureRepairSession() {
        if (repairSession != null) return
        repairSession = RepairSession(flowType = PayFlowType.autoRepair, showTips = false)
        repairInFlight = true
    }

    /** 用户主动购买事件处理，不处理补单 UI。 */
    private suspend fun handleUserPurchaseUpdates(purchaseList: List<PurchaseDetails>) {
        try {
            val seenPurchaseIds = HashSet<String>()
            for (purchase in purchaseList) {
                val purchaseId = purchase.purchaseID ?: ""
                if (purchaseId.isNotEmpty() && !seenPurchaseIds.add(purchaseId)) continue
                log("process user order: ${purchase.productID}, status: ${purchase.status}")
                when (purchase.status) {
                    PurchaseStatus.pending -> handleUserPurchasePending(purchase)
                    PurchaseStatus.error -> handleUserPurchaseError(purchase)
                    PurchaseStatus.canceled -> handleUserPurchaseCanceled(purchase)
                    PurchaseStatus.purchased -> handleUserPurchased(purchase)
                    PurchaseStatus.restored -> handleRepairPurchaseUpdates(
                        listOf(purchase),
                        repairSession?.flowType ?: PayFlowType.autoRepair,
                    )
                }
            }
        } catch (e: Throwable) {
            log("user purchase handler error: $e")
            disposeDialog(24)
            clearBuySession("all", PayStatus.FAILED, PayReason.INTERNAL)
        }
    }

    /**
     * 商店回了待批准（家长审批 / SCA），结果可能很久才到。
     *
     * 参考工程只记 storePending，loading 一直挂到交易回来；这里回执 pending 并放开界面，
     * 订单映射保留：批准后的交易带着本单 appAccountToken 回来时仍按 PURCHASE + 本单订单号验单。
     */
    private fun handleUserPurchasePending(purchase: PurchaseDetails) {
        log("order status: waiting for payment")
        val session = buySession
        val known = session.getProduct(purchase.productID) != null ||
            (purchase.productID.isEmpty() && userPurchaseInFlight)
        if (!known) return
        session.storePending = true
        userPurchaseInFlight = false
        disposeDialog(33)
        session.attempt?.resolve(
            buyOutcome(
                session, PayStatus.PENDING, PayReason.STORE_PENDING, "",
                isNewOrder = false, pageClosed = false, late = false,
            ),
        )
    }

    /** 主动购买失败：释放 session、关 loading，并按商店错误码给出可读提示。 */
    private fun handleUserPurchaseError(purchase: PurchaseDetails) {
        val errorMessage = purchaseErrorMessage(purchase)
        if (clearBuySession(purchase.productID, PayStatus.FAILED, PayReason.STORE_ERROR, errorMessage)) {
            disposeDialog(7)
        }
        PayUi.toast(errorMessage, 5000)
    }

    /** 将商店错误转为文案。 */
    private fun purchaseErrorMessage(purchase: PurchaseDetails): String {
        var errorMessage = "pay_error".tr
        if (purchase.errorMessage == "BillingResponse.itemAlreadyOwned") {
            errorMessage = "this_item_has_already_been_purchased".tr
        }
        return errorMessage
    }

    /** 用户取消支付：释放 session，并打取消购买点，不验单、不 finish。 */
    private fun handleUserPurchaseCanceled(purchase: PurchaseDetails) {
        val message = "payment_has_been_canceled".tr
        if (clearBuySession(purchase.productID, PayStatus.CANCELED, PayReason.USER_CANCELED, message)) {
            disposeDialog(8)
        }
        PayUi.toast(message)
        val product = ProductInfo.productInfoMap[purchase.productID]
        if (product != null) {
            AdEvent.logCancelPaymentEvent(productId = product.productId ?: "", productType = product.productType ?: 0)
        }
    }

    /** 主动购买 purchased：解析订单 → 验单 → 成功才 finish 和打点。 */
    private suspend fun handleUserPurchased(purchase: PurchaseDetails) {
        val session = buySession
        val verifyOrderNum = resolveVerifyOrderNum(purchase, PayFlowType.userPurchase)
        // 旧续订对不上本次建单时改 RESTORE，不清 buy session，等匹配交易。
        if (isIosAccountTokenMismatch(purchase) || (session.iosOrder != null && verifyOrderNum.isEmpty())) {
            repairOnePurchase(purchase, PayFlowType.autoRepair, null)
            return
        }
        val purchaseId = purchase.purchaseID ?: ""
        if (purchaseId.isNotEmpty() && verifiedPurchaseIds.contains(purchaseId)) {
            PayStore.finish(purchase, consumable = isCreditProduct(purchase.productID))
            return
        }
        val isNewOrder = verifyOrderNum.isNotEmpty()
        log("监控到用户购买需要验证的订单，订单号：$verifyOrderNum")
        if (isNewOrder) {
            openDialog()
        }

        val payResult = verifyAndFinish(
            purchase,
            flowType = PayFlowType.userPurchase,
            isNewOrder = isNewOrder,
            orderNum = verifyOrderNum,
            repair = null,
        )
        if (!isBuySessionActive(session)) {
            // 验单期间用户登出或发起了下一笔购买，丢弃本笔成功 UI，避免串页。
            if (buySession === session) {
                disposeDialog(29)
                clearBuySession("all", PayStatus.SUPERSEDED, PayReason.LOGOUT)
            } else {
                session.attempt?.resolve(
                    buyOutcome(
                        session, PayStatus.SUPERSEDED, PayReason.REPLACED, "",
                        isNewOrder = false, pageClosed = false, late = false,
                    ),
                )
            }
            return
        }
        if (!payResult.status) {
            val message = payResult.message ?: ""
            if (message.isNotEmpty()) {
                PayUi.toast(message)
            }
            if (clearBuySession(purchase.productID, PayStatus.FAILED, PayReason.VERIFY, message)) {
                disposeDialog(9)
            }
            return
        }

        PayAnalytics.sendPurchaseEvent(
            ProductInfo.productInfoMap[purchase.productID] ?: session.getProduct(purchase.productID),
            verifyOrderNum,
            purchase.purchaseID ?: "",
            productId = purchase.productID,
        )
        log("user purchase successful")
        onUserPurchaseSuccess(isNewOrder)
    }

    /**
     * 自动补单和手动补单都走这里。
     * 不使用 buySession，不触发主动购买页面返回、折扣状态清理、购买打点。
     */
    private suspend fun handleRepairPurchaseUpdates(purchaseList: List<PurchaseDetails>, flowType: PayFlowType) {
        val session = repairSession
        try {
            // iOS 恢复交易即使无需 finish，也必须验单。
            val purchasesToVerify = purchaseList.filter { item ->
                log(
                    "订单:${item.productID}, status:${item.status}, " +
                        "pendingCompletePurchase:${item.pendingCompletePurchase}",
                )
                item.status == PurchaseStatus.restored || item.status == PurchaseStatus.purchased
            }
            log("处理${flowType.apiValue}订单:${purchasesToVerify.size}")

            if (purchasesToVerify.isEmpty()) {
                if (resumeOwnsRepair) return
                if (flowType.isManualRepair) {
                    disposeDialog(23)
                    PayUi.toast("no_content_to_restore".tr)
                }
                clearRepairSession()
                return
            }

            if (!isLoggedIn) {
                if (resumeOwnsRepair) return
                disposeDialog(22)
                PayUi.toast("please_log_in_to_restore_purchases".tr)
                clearRepairSession()
                return
            }

            for (item in purchasesToVerify) {
                val repaired = repairOnePurchase(item, flowType, session)
                if (session != null) {
                    if (repaired) {
                        session.successCount++
                        if (!isCreditProduct(item.productID)) {
                            session.hasSubscribe = true
                        }
                    } else {
                        session.failureCount++
                    }
                }
            }

            if (resumeOwnsRepair) {
                // 收尾交给 resumePurchase 汇总，避免事件处理提前 toast/清 session。
                return
            }
            finalizeRepair(session, ownedByResume = false)
        } catch (e: Throwable) {
            if (session != null) {
                session.failureCount++
            }
            if (resumeOwnsRepair) {
                log("补单处理失败，保留交易")
                return
            }
            log("${flowType.apiValue} repair error: $e")
            if (flowType.isManualRepair) {
                disposeDialog(10)
                PayUi.toast("no_content_to_restore".tr)
            }
            clearRepairSession()
        } finally {
            repairProcessing = false
        }
    }

    /** 单笔补单：验单成功或 INELIGIBLE 才 finish，其余保留商店交易。 */
    private suspend fun repairOnePurchase(
        purchase: PurchaseDetails,
        flowType: PayFlowType,
        repair: RepairSession?,
    ): Boolean {
        val orderNum = resolveVerifyOrderNum(purchase, flowType)
        val purchaseId = purchase.purchaseID ?: ""
        if (purchaseId.isNotEmpty() && verifiedPurchaseIds.contains(purchaseId)) {
            PayStore.finish(purchase, consumable = isCreditProduct(purchase.productID))
            return true
        }
        log("补单商品ID: ${purchase.productID}, 订单号: $orderNum")
        val payResult = verifyAndFinish(purchase, flowType, isNewOrder = false, orderNum = orderNum, repair = repair)
        if (!payResult.status) {
            log("${flowType.apiValue}---验单失败:${payResult.message}")
            return false
        }
        return true
    }

    /** 解析验单订单号：iOS 补单留空由后端凭 JWS 关联。 */
    private fun resolveVerifyOrderNum(purchase: PurchaseDetails, flowType: PayFlowType): String {
        if (flowType.isRepair) return ""
        return buySession.getOrder(purchase.productID) ?: ""
    }

    /** Apple 验单成功或补单 INELIGIBLE 才完成商店交易，其余保留供补查。 */
    private suspend fun verifyAndFinish(
        purchase: PurchaseDetails,
        flowType: PayFlowType,
        isNewOrder: Boolean,
        orderNum: String,
        repair: RepairSession?,
    ): PayResult {
        val userPurchase = flowType == PayFlowType.userPurchase
        // 令牌对不上本单时拒绝 PURCHASE，避免旧续订绑到刚建的订单。
        val rejectUserPurchase = userPurchase &&
            (isIosAccountTokenMismatch(purchase) || (orderNum.trim().isEmpty() && buySession.iosOrder != null))
        // 验单带发起账号自己的凭证：主动购买取下单时的，补单取本轮开始时的。
        val authToken = when {
            userPurchase -> buySession.authToken
            repair != null -> repair.authToken
            else -> AuthSession.accessToken
        }
        val revision = when {
            userPurchase -> buySession.revision
            repair != null -> repair.revision
            else -> AuthSession.revision
        }
        val payResult = PayStore.verify(
            purchase,
            flowType = flowType,
            isNewOrder = isNewOrder,
            orderNum = orderNum,
            rejectUserPurchase = rejectUserPurchase,
            authToken = authToken,
            revision = revision,
            isLoggedIn = { isLoggedIn },
        )
        if (payResult.status || payResult.finishTransaction) {
            val id = purchase.purchaseID
            if (!id.isNullOrEmpty()) {
                verifiedPurchaseIds.add(id)
            }
        }
        if (payResult.finishTransaction) {
            PayStore.finish(purchase, consumable = isCreditProduct(purchase.productID))
        }
        return payResult
    }

    /**
     * 用户主动购买成功处理。
     *
     * 只有主动购买才允许：关闭购买页 / 折扣页、清理折扣页状态、弹购买成功 toast、清理 buySession。
     */
    private suspend fun onUserPurchaseSuccess(isNewOrder: Boolean) {
        refreshUser()
        disposeDialog(3)
        val closed = showPurchaseSuccess(pageName = buySession.currentPageName, isNewOrder = isNewOrder)
        clearBuySession(
            "all",
            PayStatus.SUCCESS,
            message = if (isNewOrder) "purchase_successful".tr else "",
            isNewOrder = isNewOrder,
            pageClosed = closed,
        )
    }

    /**
     * 已确认到账后的页面行为；调用方负责账号校验与各自会话收尾。
     * @return 是否关掉了购买页
     */
    private fun showPurchaseSuccess(pageName: String, isNewOrder: Boolean): Boolean {
        val route = PayUi.currentRouteName()
        log("user purchase success, pageName:$pageName, currentRoute:$route, isNewOrder:$isNewOrder")

        if (pageName == Routes.subscribe || pageName == Routes.discount ||
            pageName == Routes.recall || pageName == Routes.recallProduct
        ) {
            // 订阅页买成功都停回归倒计时。记成已过期，之后再看到商品也不会开 10 分钟。
            ConfigService.countdown.endRecallOffer()
        }

        val successToast = if (isNewOrder) "purchase_successful".tr else null
        var closed = false
        var toasted = false
        if (closableRoutes.contains(route) && isNewOrder) {
            // 关页和提示一起做：提示要落在关页后露出的页面上。
            closed = PayUi.back(successToast)
            toasted = true
        }

        if (pageName == Routes.discount) {
            DiscountOffer.endAfterPurchase()
        }

        if (successToast != null && !toasted) {
            PayUi.toast(successToast)
        }
        return closed
    }

    /**
     * 补单收尾：刷新权益、按计数 toast；[ownedByResume] 为 true 时留给 resumePurchase 清 session。
     * 有成功项或手动恢复时广播一次 restore 结果。
     */
    private suspend fun finalizeRepair(session: RepairSession?, ownedByResume: Boolean): PayOutcome? {
        if (session == null || !isLoggedIn) {
            if (!ownedByResume) clearRepairSession()
            return null
        }

        if (session.successCount > 0) {
            refreshUser()
            // 恢复到有效订阅后停掉回归倒计时。已过期的记录之后再看到商品也不会开 10 分钟。
            if (session.hasSubscribe && UserStore.isSubscribed) {
                ConfigService.countdown.endRecallOffer()
            }
        }

        var message = ""
        if (session.showTips && isLoggedIn) {
            var key = "no_content_to_restore"
            if (session.failureCount > 0) {
                key = "check_pay_info_error"
            } else if (session.successCount > 0) {
                key = "purchase_successful"
            }
            message = if (key.isEmpty()) "" else key.tr
            disposeDialog(2)
        }

        // 订阅恢复成功后关掉订阅页和回归商品页。
        var closed = false
        var toasted = false
        if (session.hasSubscribe && UserStore.isSubscribed) {
            val route = PayUi.currentRouteName()
            if (route == Routes.subscribe || route == Routes.recallProduct) {
                closed = PayUi.back(message.ifEmpty { null })
                toasted = true
            }
        }
        if (message.isNotEmpty() && !toasted) {
            PayUi.toast(message)
        }

        val outcome = repairOutcome(session, message, closed)
        if (session.successCount > 0 || session.showTips) {
            PayHost.emit(Events.PAY_RESULT, outcome.toJson())
        }

        if (!ownedByResume) clearRepairSession()
        return outcome
    }

    private fun repairOutcome(session: RepairSession, message: String, pageClosed: Boolean): PayOutcome {
        val status: String
        val reason: String
        when {
            session.restoreCanceled && session.successCount == 0 -> {
                status = PayStatus.CANCELED
                reason = PayReason.USER_CANCELED
            }
            session.failureCount > 0 && session.successCount == 0 -> {
                status = PayStatus.FAILED
                reason = PayReason.VERIFY
            }
            session.successCount > 0 -> {
                status = PayStatus.SUCCESS
                reason = if (session.failureCount > 0) PayReason.VERIFY else ""
            }
            else -> {
                status = PayStatus.FAILED
                reason = PayReason.NOTHING_TO_RESTORE
            }
        }
        return PayOutcome(
            type = PayOutcome.TYPE_RESTORE,
            channel = PayOutcome.CHANNEL_APPLE,
            status = status,
            reason = reason,
            subscription = session.hasSubscribe,
            userInitiated = session.showTips,
            pageClosed = pageClosed,
            message = message,
            successCount = session.successCount,
            failureCount = session.failureCount,
            hasSubscribe = session.hasSubscribe,
        )
    }

    // =============================================================================================
    // 提交购买
    // =============================================================================================

    /**
     * 提交购买。只创建 buySession，不影响补单。可在任意页面上下文调用。
     *
     * @param buyNonConsumable true 为订阅，false 为积分。
     * @param pageName 发起页面（Routes.xxx），成功后用于关闭订阅页或折扣页。
     * @param onResult 本次购买的结果，恰好回调一次（在调用页面的上下文里；页面已销毁则不回调）。
     *   同一结果也经 [Events.PAY_RESULT] 广播。
     */
    fun submit(
        currentItem: GoodsModel,
        buyNonConsumable: Boolean,
        pageName: String,
        onResult: ((PayOutcome) -> Unit)? = null,
    ) {
        PayUi.registerCurrent()
        val attempt = PayAttempt(onResult)
        PayHost.launch {
            ensureInit()
            try {
                submitInternal(currentItem, buyNonConsumable, pageName, attempt)
            } catch (e: Throwable) {
                log("submit error: $e")
                if (buySession.attempt === attempt) {
                    disposeDialog(30)
                    clearBuySession("all", PayStatus.FAILED, PayReason.INTERNAL)
                }
                attempt.resolve(failedOutcome(currentItem, buyNonConsumable, pageName, PayReason.INTERNAL))
            }
        }
    }

    /** [submit] 的挂起版本：等到本次购买有结果（成功 / 取消 / 待批准 / 失败 / 超时 / 作废）。 */
    suspend fun submitAwait(currentItem: GoodsModel, buyNonConsumable: Boolean, pageName: String): PayOutcome =
        suspendCoroutine { cont ->
            submit(currentItem, buyNonConsumable, pageName) { cont.resume(it) }
        }

    /** 还没建会话就失败时的回执。 */
    private fun failedOutcome(
        currentItem: GoodsModel,
        buyNonConsumable: Boolean,
        pageName: String,
        reason: String,
        message: String = "",
        status: String = PayStatus.FAILED,
    ): PayOutcome = PayOutcome(
        type = PayOutcome.TYPE_PURCHASE,
        channel = if (isIos) PayOutcome.CHANNEL_APPLE else PayOutcome.CHANNEL_ALIPAY,
        status = status,
        reason = reason,
        pageName = pageName,
        productId = (if (isIos) currentItem.productDetails?.id else currentItem.productId)?.trim() ?: "",
        subscription = buyNonConsumable,
        userInitiated = true,
        message = message,
    )

    private suspend fun submitInternal(
        currentItem: GoodsModel,
        buyNonConsumable: Boolean,
        pageName: String,
        attempt: PayAttempt,
    ) {
        if (!isIos) {
            // 积分包走一次性 APP 支付；会员订阅才走自动续费。
            if (!buyNonConsumable) {
                submitAlipayTrade(currentItem, pageName, attempt)
            } else {
                submitAlipay(currentItem, pageName, attempt)
            }
            return
        }
        fun reject(reason: String, messageKey: String) {
            val message = messageKey.tr
            PayUi.toast(message)
            attempt.resolve(failedOutcome(currentItem, buyNonConsumable, pageName, reason, message))
        }
        if (!Api.hasNetwork) {
            reject(PayReason.NO_NETWORK, "no_network_connection")
            return
        }

        val details = currentItem.productDetails
        val productId = details?.id
        if (details == null || productId.isNullOrBlank()) {
            reject(PayReason.PRODUCT_MISSING, "product_information_is_missing")
            return
        }

        if (userPurchaseInFlight || nativePurchaseInFlight || (repairSession?.showTips ?: false)) {
            reject(PayReason.IN_PROGRESS, "task_in_progress_try_later")
            return
        }

        userPurchaseInFlight = true
        openDialog()
        val session = BuySession(currentPageName = pageName)
        session.attempt = attempt
        session.productId = productId
        session.subscription = buyNonConsumable
        session.setProduct(productId, currentItem.copy())
        buySession = session

        val isAvailable = PayStore.isAvailable()
        if (buySession !== session) return
        if (!isAvailable) {
            val message = "failed_to_initiate_payment".tr
            clearBuySession(productId, PayStatus.FAILED, PayReason.STORE_UNAVAILABLE, message)
            disposeDialog(11)
            PayUi.toast(message, 5000)
            return
        }

        val createOrderNum = createUserOrder(session, productId, currentItem) ?: return

        AdEvent.logAddToCartEvent(
            productId = productId,
            productType = "${currentItem.productType ?: 1}",
            price = currentItem.productPrice ?: 0.0,
            orderId = createOrderNum,
        )

        val applicationUserName = buildApplicationUserName(session) ?: return

        // 先排空对本账号无效的未完成交易，再拉起商店。
        prepareIosNativePurchase()
        if (!isBuySessionActive(session)) {
            if (buySession === session) {
                disposeDialog(15)
                clearBuySession("all", PayStatus.SUPERSEDED, PayReason.LOGOUT)
            }
            return
        }

        startNativePurchase(session, currentItem, details, productId, applicationUserName, buyNonConsumable)
    }

    /** 打开系统订阅说明页（仅 iOS）。不向服务端查询订阅来源。 */
    fun openSubscriptionManagement() {
        if (!isIos) return
        PayUi.registerCurrent()
        PayHost.launch { launchExternal(AppConstants.unSubscribeIosUrl) }
    }

    /**
     * 主动购买建单。失败不拉起商店。
     * 返回 null 表示已提示失败或 session 已失效。
     */
    private suspend fun createUserOrder(session: BuySession, productId: String, currentItem: GoodsModel): String? {
        var failed = !isLoggedIn
        var createOrderNum = ""
        if (!failed) {
            createOrderNum = try {
                PayStore.createOrder(
                    productId,
                    flowType = PayFlowType.userPurchase,
                    logPrefix = "user purchase",
                    onIosOrder = { order -> session.iosOrder = order },
                )
            } catch (e: Throwable) {
                log("创建订单失败:$e")
                ""
            }
            if (!isBuySessionActive(session)) {
                if (buySession === session) {
                    disposeDialog(12)
                    clearBuySession(productId, PayStatus.SUPERSEDED, PayReason.LOGOUT)
                }
                return null
            }
            buySession.setOrder(productId, createOrderNum)
            failed = createOrderNum.isEmpty()
        }
        if (failed) {
            if (!isBuySessionActive(session)) {
                if (buySession === session) {
                    disposeDialog(12)
                    clearBuySession(productId, PayStatus.SUPERSEDED, PayReason.LOGOUT)
                }
                return null
            }
            val message = "failed_to_create_order".tr
            PayUi.toast(message, 3000)
            disposeDialog(12)
            clearBuySession(productId, PayStatus.FAILED, PayReason.CREATE_ORDER, message)
            return null
        }

        // 没订阅过走推介优惠，不传促销签名。订阅过才签名；查历史失败仍签名，避免老用户按原价成交。
        if (!currentItem.appleOfferId.isNullOrEmpty()) {
            val subscribedBefore = appleAccountSubscribedBefore()
            if (!isBuySessionActive(session)) {
                if (buySession === session) {
                    disposeDialog(12)
                    clearBuySession(productId, PayStatus.SUPERSEDED, PayReason.LOGOUT)
                }
                return null
            }
            if (!subscribedBefore) {
                log("回归周卡无订阅历史，走推介促销优惠")
                currentItem.appleOfferId = null
                session.getProduct(productId)?.appleOfferId = null
            } else {
                val signed = signRecallPromotion(session, productId, createOrderNum) ?: return null
                currentItem.appleOfferId = signed.offerId
                session.getProduct(productId)?.appleOfferId = signed.offerId
            }
        }
        UmengUtils.logEvent(
            "create_order",
            mapOf(
                "productId" to productId,
                "productType" to (currentItem.productType ?: -1),
                "productName" to (currentItem.productName ?: ""),
            ),
        )
        return createOrderNum
    }

    /** 查这个 Apple 账号的订阅历史。失败时当作订阅过，继续走促销签名。 */
    private suspend fun appleAccountSubscribedBefore(): Boolean {
        val result = try {
            PayStore.appleAccountHasSubscription()
        } catch (e: Throwable) {
            null
        }
        if (result == null) {
            log("回归周卡订阅历史查询失败，仍走促销签名")
            return true
        }
        return result
    }

    /** 用本单订单号换促销签名。资格、已购、未完成购买按业务码提示；其余按加载失败，不按无资格处理。 */
    private suspend fun signRecallPromotion(
        session: BuySession,
        productId: String,
        orderNum: String,
    ): AppleReturningPromotion? {
        val result = PayBackend.signReturningApplePromotion(orderNum)
        if (!isBuySessionActive(session)) {
            if (buySession === session) {
                disposeDialog(12)
                clearBuySession(productId, PayStatus.SUPERSEDED, PayReason.LOGOUT)
            }
            return null
        }
        val order = session.iosOrder
        val promotion = result.promotion
        if (order == null || promotion == null || !promotion.matchesOrder(order = order, productId = productId)) {
            log("回归优惠签名无效 code=${result.code} product=$productId")
            val message = recallSignMessage(result.code)
            PayUi.toast(message, 3000)
            disposeDialog(12)
            clearBuySession(productId, PayStatus.FAILED, PayReason.OFFER_SIGNATURE, message)
            return null
        }
        // 签名覆盖的账户令牌必须原样传给 StoreKit，不能再改大小写。
        session.iosOrder = IosOrder(
            orderNum = order.orderNum,
            appAccountToken = promotion.appAccountToken,
            promotionalSignature = promotion.promotionalSignature,
        )
        return promotion
    }

    private fun recallSignMessage(code: Int?): String = when (code) {
        1009496001 -> "recall_offer_unavailable".tr
        1009496002 -> "recall_offer_already_used".tr
        1009496003 -> "recall_offer_pending".tr
        else -> "failed_to_initiate_payment".tr
    }

    /** StoreKit 的 appAccountToken：只用建单（或促销签名）返回的值。 */
    private fun buildApplicationUserName(session: BuySession): String? {
        val order = session.iosOrder
        if (!isLoggedIn || order == null) {
            disposeDialog(13)
            clearBuySession("all", PayStatus.FAILED, if (isLoggedIn) PayReason.CREATE_ORDER else PayReason.LOGOUT)
            return null
        }
        return order.appAccountToken
    }

    /** 等待静默补单结束，避免 resume 补单与本次 Apple 购买抢未完成交易。 */
    private suspend fun waitForRepairIdle() {
        var i = 0
        while (i < 250 && (repairInFlight || repairProcessing)) {
            PayHost.sleep(20)
            i++
        }
    }

    /** 拉起商店前 finish 对本账号无效的未完成交易，避免旧续订吞掉这次购买。 */
    private suspend fun prepareIosNativePurchase() {
        waitForRepairIdle()
        if (!isLoggedIn || resumeOwnsRepair || repairProcessing) return
        ensureRepairSession()
        val session = repairSession ?: return
        try {
            scanUnfinishedIosTransactions(session)
        } catch (e: Throwable) {
            log("排空 iOS 未完成交易失败:$e")
        } finally {
            if (!resumeOwnsRepair && repairSession === session) {
                clearRepairSession()
            }
        }
    }

    /** 拉起 Apple StoreKit 支付。 */
    private suspend fun startNativePurchase(
        session: BuySession,
        currentItem: GoodsModel,
        details: com.sondo.app.data.models.ProductDetails,
        productId: String,
        applicationUserName: String,
        buyNonConsumable: Boolean,
    ) {
        if (!isLoggedIn) {
            disposeDialog(14)
            clearBuySession(productId, PayStatus.SUPERSEDED, PayReason.LOGOUT)
            return
        }
        val signature = buySession.iosOrder?.promotionalSignature
        val purchaseParam = purchaseParamWithStoreOffer(
            details = details,
            applicationUserName = applicationUserName,
            consumable = !buyNonConsumable,
            appleOfferId = currentItem.appleOfferId,
            appleSignature = signature,
        )
        if (!currentItem.appleOfferId.isNullOrEmpty() && signature == null) {
            log("回归优惠缺少 Apple 签名，不拉起商店: ${currentItem.appleOfferId}")
            disposeDialog(14)
            clearBuySession(productId, PayStatus.FAILED, PayReason.OFFER_SIGNATURE)
            return
        }
        nativePurchaseInFlight = true
        nativePurchaseSession = session
        log(if (buyNonConsumable) "订阅商品----------" else "一次性商品购买----------")
        // 原生查到商品后、付款面板弹出前就返回；之后本次购买的结果只走 iapUpdate（source = purchase），
        // 由 onNativePurchaseSettled 在结果事件处理完后收尾。这里的超时只防商品查询卡住不回调。
        val started: Boolean? = try {
            PayHost.withTimeout(PayStore.STORE_TIMEOUT_MS) { PayStore.buy(purchaseParam) }
        } catch (e: Throwable) {
            false
        }
        if (started == true) return

        if (nativePurchaseSession === session) {
            nativePurchaseInFlight = false
            nativePurchaseSession = null
        }
        // 商店事件（取消 / 失败）已经给本单收过尾时不再重复提示。
        if (buySession === session && (!userPurchaseInFlight || session.attempt?.resolved == true)) return
        if (!isBuySessionActive(session)) {
            if (buySession === session) {
                disposeDialog(14)
                clearBuySession(productId, PayStatus.SUPERSEDED, PayReason.LOGOUT)
            }
            return
        }
        log(if (started == null) "Apple 购买调用超时未返回" else "Apple 发起购买失败")
        val message = "failed_to_initiate_payment_with_error".trParams("error" to "pay_error".tr)
        PayUi.toast(message, 3000)
        disposeDialog(14)
        clearBuySession(productId, PayStatus.FAILED, PayReason.LAUNCH, message)
    }

    // =============================================================================================
    // 恢复购买 / 补单
    // =============================================================================================

    /**
     * 恢复购买 / 补单（仅 iOS，其余平台直接回执 not_supported）。登录、回前台、进入购买页、
     * 手动点「恢复购买」时调用。可在任意页面上下文调用。
     *
     * @param showRestoreTips false 为自动补单（无 toast、不关购买遮罩）；true 为手动恢复，需要 loading 和结果提示。
     * @param onResult 本轮结果（type = restore），恰好回调一次。
     */
    fun resumePurchase(showRestoreTips: Boolean = true, onResult: ((PayOutcome) -> Unit)? = null) {
        PayUi.registerCurrent()
        val callerId = currentPagerId()
        PayHost.launch {
            ensureInit()
            val outcome = try {
                resumePurchaseInternal(showRestoreTips)
            } catch (e: Throwable) {
                log("resumePurchase error: $e")
                restoreRejected(showRestoreTips, PayReason.INTERNAL, "")
            }
            if (onResult != null && isPagerAlive(callerId)) {
                com.tencent.kuikly.core.timer.setTimeout(callerId, 0) { onResult(outcome) }
            }
        }
    }

    /** [resumePurchase] 的挂起版本。 */
    suspend fun resumePurchaseAwait(showRestoreTips: Boolean = true): PayOutcome = suspendCoroutine { cont ->
        resumePurchase(showRestoreTips) { cont.resume(it) }
    }

    private fun restoreRejected(showRestoreTips: Boolean, reason: String, message: String): PayOutcome = PayOutcome(
        type = PayOutcome.TYPE_RESTORE,
        channel = PayOutcome.CHANNEL_APPLE,
        status = if (reason == PayReason.LOGOUT) PayStatus.SUPERSEDED else PayStatus.FAILED,
        reason = reason,
        userInitiated = showRestoreTips,
        message = message,
    )

    private suspend fun resumePurchaseInternal(showRestoreTips: Boolean): PayOutcome {
        fun reject(reason: String, messageKey: String): PayOutcome {
            val message = if (showRestoreTips) messageKey.tr else ""
            if (showRestoreTips) PayUi.toast(message)
            return restoreRejected(showRestoreTips, reason, message)
        }
        if (!iapListening) {
            return restoreRejected(showRestoreTips, PayReason.NOT_SUPPORTED, "")
        }
        if (!isLoggedIn) {
            return reject(PayReason.NOT_LOGGED_IN, "please_log_in_to_restore_purchases")
        }
        if (!Api.hasNetwork) {
            return reject(PayReason.NO_NETWORK, "no_network_connection")
        }
        if (repairInFlight || (showRestoreTips && userPurchaseInFlight)) {
            return reject(PayReason.IN_PROGRESS, "task_in_progress_try_later")
        }

        repairInFlight = true
        resumeOwnsRepair = true
        val session = RepairSession(
            flowType = if (showRestoreTips) PayFlowType.manualRepair else PayFlowType.autoRepair,
            showTips = showRestoreTips,
        )
        repairSession = session
        openDialog(canOpen = showRestoreTips)
        var outcome: PayOutcome? = null
        try {
            bootstrapRepair(session)
            if (isLoggedIn) {
                outcome = finalizeRepair(session, ownedByResume = true)
            }
        } catch (e: Throwable) {
            log("resumePurchase failed: $e")
        } finally {
            if (repairSession === session) {
                clearRepairSession()
            }
            if (showRestoreTips) {
                disposeDialog(31)
            }
        }
        return outcome ?: restoreRejected(showRestoreTips, PayReason.LOGOUT, "")
    }

    /** 向 Apple 商店拉取待恢复交易，先扫 StoreKit 2 未完成交易，再按需执行 restore。 */
    private suspend fun bootstrapRepair(session: RepairSession) {
        scanUnfinishedIosTransactions(session)
        if (session.showTips && isLoggedIn) {
            when (PayStore.restorePurchases()) {
                PayStore.RESTORE_OK -> {}
                PayStore.RESTORE_CANCELED -> {
                    // 与参考工程一致：restorePurchases 抛错（含取消 Apple ID 验证）计一次失败，提示 check_pay_info_error；
                    // 回执状态仍标为取消。
                    session.restoreCanceled = true
                    session.failureCount++
                    return
                }
                else -> {
                    session.failureCount++
                    return
                }
            }
            // 原生把当前权益作为一批 restored 事件送出（先于调用返回），空权益不发事件；等这批处理完。
            PayHost.sleep(200)
            var waited = 0
            while ((repairProcessing || handlingPurchaseStream || pendingStreamBatches.isNotEmpty()) && waited < 3000) {
                PayHost.sleep(20)
                waited++
            }
        }
    }

    /** 补查 StoreKit 2 未完成交易，走同一套补单验单。 */
    private suspend fun scanUnfinishedIosTransactions(session: RepairSession) {
        val unfinishedPurchases = try {
            PayStore.unfinishedIosPurchases()
        } catch (e: Throwable) {
            null
        }
        if (unfinishedPurchases == null) {
            session.failureCount++
            return
        }
        if (!isLoggedIn) return
        repairProcessing = true
        handleRepairPurchaseUpdates(unfinishedPurchases, session.flowType)
    }

    // =============================================================================================
    // 支付宝
    // =============================================================================================

    /**
     * 回到前台或冷启动时查未结束订单。未到 CLOSED 不重新下单。续费和积分包都查。
     * 可在任意页面上下文调用（iOS 上是空操作）。
     */
    fun resumeAlipayOrder() {
        if (isIos) return
        PayHost.launch {
            ensureInit()
            resumeAlipayOrderInternal()
        }
    }

    private suspend fun resumeAlipayOrderInternal() {
        if (isIos || !isLoggedIn || !Api.hasNetwork) return
        resumeAlipay(trade = false)
        if (isIos || !isLoggedIn || !Api.hasNetwork) return
        resumeAlipay(trade = true)
    }

    private suspend fun resumeAlipay(trade: Boolean) {
        var session = if (trade) alipayTradeSession else alipaySession
        if (session == null || !isAlipaySessionActive(session)) {
            val userId = payUserId() ?: return
            val rebuilt = AlipaySession(userId = userId, trade = trade)
            if (!readPendingAlipayOrder(rebuilt)) return
            dropAlipaySlot(if (trade) alipayTradeSession else alipaySession, PayReason.REPLACED)
            assignAlipaySession(rebuilt)
            session = rebuilt
        }
        if (session.submitting || session.completing || session.orderId.isEmpty()) return
        if (session.pollTimer != null) {
            pollAlipayOnce(session)
            return
        }
        startAlipayPoll(session)
    }

    private fun alipayOutcome(
        session: AlipaySession,
        status: String,
        reason: String,
        message: String = "",
        code: Int? = null,
        productId: String = "",
        isNewOrder: Boolean = false,
        pageClosed: Boolean = false,
        late: Boolean = false,
    ): PayOutcome = PayOutcome(
        type = PayOutcome.TYPE_PURCHASE,
        channel = PayOutcome.CHANNEL_ALIPAY,
        status = status,
        reason = reason,
        pageName = session.pageName,
        productId = productId.ifEmpty { session.productId.ifEmpty { session.goods?.productId?.trim() ?: "" } },
        orderId = session.orderId,
        subscription = !session.trade,
        isNewOrder = isNewOrder,
        userInitiated = session.userInitiated,
        late = late,
        pageClosed = pageClosed,
        message = message,
        code = code,
    )

    /** 给本次点击回执（只一次）。已经回执过或静默补查的终态另外广播一次。 */
    private fun settleAlipay(session: AlipaySession, outcome: PayOutcome, terminal: Boolean) {
        val attempt = session.attempt
        if (attempt != null && !attempt.resolved) {
            attempt.resolve(outcome)
            return
        }
        if (terminal) {
            val late = attempt != null
            val data = if (late == outcome.late) outcome else PayOutcome(
                type = outcome.type, channel = outcome.channel, status = outcome.status, reason = outcome.reason,
                pageName = outcome.pageName, productId = outcome.productId, orderId = outcome.orderId,
                subscription = outcome.subscription, isNewOrder = outcome.isNewOrder,
                userInitiated = outcome.userInitiated, late = late, pageClosed = outcome.pageClosed,
                message = outcome.message, code = outcome.code,
            )
            PayHost.emit(Events.PAY_RESULT, data.toJson())
        }
    }

    /** Android / 鸿蒙 积分包下单。未安装支付宝不建单；SDK 结果不能当到账。 */
    private suspend fun submitAlipayTrade(currentItem: GoodsModel, pageName: String, attempt: PayAttempt) {
        fun reject(reason: String, messageKey: String?) {
            val message = messageKey?.tr ?: ""
            if (message.isNotEmpty()) PayUi.toast(message)
            attempt.resolve(failedOutcome(currentItem, false, pageName, reason, message))
        }
        if (!isLoggedIn) {
            reject(PayReason.NOT_LOGGED_IN, null)
            return
        }
        if (alipayChannelClosed) {
            reject(PayReason.CHANNEL_CLOSED, "alipay_not_available")
            return
        }
        if (!Api.hasNetwork) {
            reject(PayReason.NO_NETWORK, "no_network_connection")
            return
        }
        val productId = currentItem.productId?.trim() ?: ""
        if (productId.isEmpty()) {
            reject(PayReason.PRODUCT_MISSING, "product_information_is_missing")
            return
        }
        val current = alipayTradeSession
        if (alipayTradeStarting ||
            (current != null && isAlipaySessionActive(current) && (current.submitting || current.completing))
        ) {
            reject(PayReason.IN_PROGRESS, "task_in_progress_try_later")
            return
        }
        val mobile = UserStore.userInfo?.mobile?.trim() ?: ""
        if (mobile.isEmpty()) {
            reject(PayReason.BIND_MOBILE, "alipay_bind_mobile")
            return
        }
        alipayTradeStarting = true
        val installed = try {
            isAlipayInstalled()
        } finally {
            alipayTradeStarting = false
        }
        if (!installed) {
            reject(PayReason.NOT_INSTALLED, "alipay_install_required")
            return
        }

        val userId = payUserId()
        if (userId == null) {
            reject(PayReason.NOT_LOGGED_IN, null)
            return
        }
        val session = AlipaySession(userId = userId, pageName = pageName, trade = true, goods = currentItem)
        session.submitting = true
        session.attempt = attempt
        dropAlipaySlot(alipayTradeSession, PayReason.REPLACED)
        alipayTradeSession = session
        openDialog()
        try {
            if (readPendingAlipayOrder(session)) {
                val blocked = reusePendingAlipayOrder(session, productId)
                if (!isAlipaySessionActive(session) || blocked) return
            }

            val created = PayBackend.createAlipayTrade(productId)
            if (!isAlipaySessionActive(session)) return
            if (!created.ready) {
                failAlipayTrade(session, created.code)
                return
            }
            session.orderId = created.orderId
            session.orderStr = created.orderStr
            session.productId = productId
            session.payAmount = created.payAmount
            savePendingAlipayOrder(session)
            AdEvent.logAddToCartEvent(
                productId = productId,
                productType = "${currentItem.productType ?: 1}",
                price = created.payAmount ?: currentItem.productPrice ?: 0.0,
                orderId = created.orderId,
            )
            launchAlipayOrder(session)
        } catch (e: Throwable) {
            log("支付宝积分支付未完成: $e")
            if (isAlipaySessionActive(session)) PayUi.toast("alipay_busy".tr)
        } finally {
            if (isAlipaySessionActive(session)) {
                session.submitting = false
                disposeDialog(42)
            }
            settleAfterSubmit(session)
        }
    }

    /** Android / 鸿蒙 会员下单。积分包走 [submitAlipayTrade]。 */
    private suspend fun submitAlipay(currentItem: GoodsModel, pageName: String, attempt: PayAttempt) {
        fun reject(reason: String, messageKey: String?) {
            val message = messageKey?.tr ?: ""
            if (message.isNotEmpty()) PayUi.toast(message)
            attempt.resolve(failedOutcome(currentItem, true, pageName, reason, message))
        }
        if (!isLoggedIn) {
            reject(PayReason.NOT_LOGGED_IN, null)
            return
        }
        if (alipayChannelClosed) {
            reject(PayReason.CHANNEL_CLOSED, "alipay_not_available")
            return
        }
        if (!Api.hasNetwork) {
            reject(PayReason.NO_NETWORK, "no_network_connection")
            return
        }
        val productId = currentItem.productId?.trim() ?: ""
        if (productId.isEmpty()) {
            reject(PayReason.PRODUCT_MISSING, "product_information_is_missing")
            return
        }
        val current = alipaySession
        if (current != null && isAlipaySessionActive(current) && (current.submitting || current.completing)) {
            reject(PayReason.IN_PROGRESS, "task_in_progress_try_later")
            return
        }
        val mobile = UserStore.userInfo?.mobile?.trim() ?: ""
        if (mobile.isEmpty()) {
            reject(PayReason.BIND_MOBILE, "alipay_bind_mobile")
            return
        }

        val userId = payUserId()
        if (userId == null) {
            reject(PayReason.NOT_LOGGED_IN, null)
            return
        }
        val session = AlipaySession(userId = userId, pageName = pageName, goods = currentItem)
        session.submitting = true
        session.attempt = attempt
        dropAlipaySlot(alipaySession, PayReason.REPLACED)
        alipaySession = session
        openDialog()
        try {
            if (readPendingAlipayOrder(session)) {
                val blocked = reusePendingAlipayOrder(session, productId)
                if (!isAlipaySessionActive(session) || blocked) return
            }

            val created = PayBackend.createAlipaySubscription(productId)
            if (!isAlipaySessionActive(session)) return
            if (!created.ready) {
                failAlipayCreate(session, created.code)
                return
            }
            session.orderId = created.orderId
            session.jumpSchema = created.jumpSchema
            session.productId = productId
            session.payAmount = created.payAmount
            savePendingAlipayOrder(session)
            AdEvent.logAddToCartEvent(
                productId = productId,
                productType = "${currentItem.productType ?: 2}",
                price = created.payAmount ?: currentItem.productPrice ?: 0.0,
                orderId = created.orderId,
            )
            launchAlipayOrder(session)
        } catch (e: Throwable) {
            log("支付宝支付未完成: $e")
            if (isAlipaySessionActive(session)) PayUi.toast("alipay_busy".tr)
        } finally {
            // 外部支付与后台确认不占用阻塞遮罩，重复点击仍受当前提交/到账收尾保护。
            if (isAlipaySessionActive(session)) {
                session.submitting = false
                disposeDialog(41)
            }
            settleAfterSubmit(session)
        }
    }

    /**
     * submit 流程返回时的回执兜底：会话已失效 → 作废；
     * 既没在轮询也没在查单 / 到账收尾 → 不会再有结果，按「暂时无法确认」回执。
     */
    private fun settleAfterSubmit(session: AlipaySession) {
        val attempt = session.attempt ?: return
        if (attempt.resolved) return
        if (!isAlipaySessionActive(session)) {
            val reason = when {
                !isLoggedIn -> PayReason.LOGOUT
                !AuthSession.isCurrent(session.revision) || payUserId() != session.userId -> PayReason.ACCOUNT_SWITCHED
                else -> PayReason.REPLACED
            }
            attempt.resolve(alipayOutcome(session, PayStatus.SUPERSEDED, reason))
            return
        }
        if (session.pollTimer == null && !session.querying && !session.completing) {
            attempt.resolve(alipayOutcome(session, PayStatus.FAILED, PayReason.BUSY))
            return
        }
        // Dart finally { submitting = false }：支付宝已拉起、只剩后台查单时立即放开页面，
        // 再点同一商品走旧单复用重开；到账 / 关单等终态之后以 late 广播。
        if (session.pollTimer != null && !session.completing) {
            attempt.resolve(alipayOutcome(session, PayStatus.PENDING, PayReason.AWAITING_CONFIRM))
        }
    }

    /** 旧单先查后用：等待付款可重开原链接，已关闭才允许重新建单。返回 true 表示不再建新单。 */
    private suspend fun reusePendingAlipayOrder(session: AlipaySession, selectedProductId: String): Boolean {
        val result = PayBackend.queryAlipayOrder(session.orderId)
        if (!isAlipaySessionActive(session)) return true
        if (result.orderId.isNotEmpty() && result.orderId != session.orderId) {
            val message = "alipay_busy".tr
            PayUi.toast(message)
            settleAlipay(session, alipayOutcome(session, PayStatus.FAILED, PayReason.BUSY, message), terminal = false)
            return true
        }
        if (result.code == 1046000005 || (result.code == 0 && result.status == "CLOSED")) {
            clearPendingAlipayOrder(session)
            session.orderId = ""
            session.jumpSchema = ""
            return false
        }
        if (result.code != 0) {
            val message = alipayCreateMessage(result.code) ?: "alipay_busy".tr
            endAlipayAttempt(
                session,
                alipayOutcome(session, PayStatus.FAILED, PayReason.BUSY, message, code = result.code),
            )
            PayUi.toast(message)
            return true
        }
        if (result.waiting) {
            val pendingProductId = result.productId.ifEmpty { session.productId }
            val sameProduct = result.status == "WAITING" && pendingProductId == selectedProductId
            if (sameProduct) {
                launchAlipayOrder(session)
            } else {
                // 换商品时旧的付款凭证还在，只提示未完成，不重新下单、也不打开旧收银台。
                if (result.status == "WAITING" && session.canReopenPayment) {
                    session.lastNoticeStatus = result.status
                    val message = "alipay_order_pending".tr
                    PayUi.toast(message)
                    settleAlipay(
                        session,
                        alipayOutcome(
                            session, PayStatus.FAILED, PayReason.ORDER_PENDING, message, productId = selectedProductId,
                        ),
                        terminal = false,
                    )
                } else {
                    applyAlipayQuery(session, result)
                    settleAlipay(
                        session,
                        alipayOutcome(session, PayStatus.FAILED, PayReason.ORDER_PENDING, productId = selectedProductId),
                        terminal = false,
                    )
                }
                startAlipayPoll(session)
            }
        } else {
            applyAlipayQuery(session, result)
        }
        return true
    }

    private fun failAlipayTrade(session: AlipaySession, code: Int?) {
        if (code == 1046000001) alipayChannelClosed = true
        val message = alipayTradeMessage(code) ?: "failed_to_create_order".tr
        endAlipayAttempt(
            session,
            alipayOutcome(
                session, PayStatus.FAILED, PayReason.CREATE_ORDER, message, code = code,
                productId = session.goods?.productId?.trim() ?: "",
            ),
        )
        PayUi.toast(message)
        // 非会员或已到期：积分包不能买，转到订阅页开通或续费。
        if ((code == 1009000004 || code == 1009000005) && PayUi.currentRouteName() != Routes.subscribe) {
            PayUi.toNamed(Routes.subscribe)
        }
    }

    private fun failAlipayCreate(session: AlipaySession, code: Int?) {
        if (code == 1046000001) alipayChannelClosed = true
        val message = alipayCreateMessage(code) ?: "failed_to_create_order".tr
        var closed = false
        var toasted = false
        if (code == 1046000004 || code == 1009280003) {
            val route = PayUi.currentRouteName()
            if (route == Routes.subscribe || route == Routes.discount || route == Routes.recallProduct) {
                disposeDialog(41)
                closed = PayUi.back(message, result = false)
                toasted = true
            }
        }
        endAlipayAttempt(
            session,
            alipayOutcome(
                session, PayStatus.FAILED, PayReason.CREATE_ORDER, message, code = code,
                productId = session.goods?.productId?.trim() ?: "", pageClosed = closed,
            ),
        )
        if (!toasted) PayUi.toast(message)
    }

    private suspend fun launchAlipayOrder(session: AlipaySession) {
        if (session.trade) {
            if (session.orderStr.isNotEmpty()) {
                val status = payWithAlipaySdk(session.orderStr)
                if (!isAlipaySessionActive(session)) return
                if (status == null) {
                    // 走到这里已经确认装了支付宝，收银台没跑完按暂时不可用提示。
                    PayUi.toast("alipay_busy".tr)
                } else if (status == "6001") {
                    // 用户在收银台取消：只提示未完成并查一次，不轮询一分钟。原订单留着，再点同一商品可重开。
                    session.lastNoticeStatus = "WAITING"
                    val message = "alipay_payment_incomplete".tr
                    PayUi.toast(message)
                    stopAlipayPoll(session)
                    settleAlipay(
                        session,
                        alipayOutcome(session, PayStatus.CANCELED, PayReason.USER_CANCELED, message),
                        terminal = false,
                    )
                    PayHost.spawn { pollAlipayOnce(session) }
                    return
                }
            }
        } else if (session.jumpSchema.isNotEmpty()) {
            val opened = openAlipay(session.jumpSchema)
            if (!isAlipaySessionActive(session)) return
            if (!opened) {
                val message = "alipay_install_required".tr
                PayUi.toast(message)
                settleAlipay(
                    session,
                    alipayOutcome(session, PayStatus.FAILED, PayReason.NOT_INSTALLED, message),
                    terminal = false,
                )
            }
        }
        // SDK 或签约页返回后都查单。缺少凭证时只查，不重新建单。
        startAlipayPoll(session)
    }

    /** [immediate] 为 false 时只等下一拍，用于刷新权益失败后的重试，避免立刻重入。 */
    private fun startAlipayPoll(session: AlipaySession, immediate: Boolean = true) {
        if (!isAlipaySessionActive(session) || session.completing) return
        stopAlipayPoll(session)
        session.pollTicks = 0
        if (immediate) PayHost.spawn { pollAlipayOnce(session) }
        session.pollTimer = PayHost.periodic(alipayPollIntervalMs) {
            if (!isAlipaySessionActive(session)) {
                stopAlipayPoll(session)
                settleAfterSubmit(session)
                return@periodic
            }
            session.pollTicks++
            if (session.pollTicks >= alipayPollMaxTicks) {
                stopAlipayPoll(session)
                var message = ""
                if (session.userInitiated && session.lastNoticeStatus == null) {
                    message = "alipay_payment_confirming".tr
                    PayUi.toast(message)
                }
                // 订单保留在本地，回前台 / 重启后继续查。
                settleAlipay(
                    session,
                    alipayOutcome(session, PayStatus.TIMEOUT, PayReason.CONFIRMING, message),
                    terminal = false,
                )
                return@periodic
            }
            PayHost.spawn { pollAlipayOnce(session) }
        }
    }

    private suspend fun pollAlipayOnce(session: AlipaySession) {
        if (!isAlipaySessionActive(session) || session.querying || session.completing) return
        session.querying = true
        try {
            val result = PayBackend.queryAlipayOrder(session.orderId)
            if (!isAlipaySessionActive(session)) return
            applyAlipayQuery(session, result)
        } catch (e: Throwable) {
            log("支付宝订单暂未确认: $e")
        } finally {
            session.querying = false
        }
    }

    /** 只认后端 status。支付宝 App 的成功回调不能发权益。 */
    private suspend fun applyAlipayQuery(session: AlipaySession, result: AlipayOrderResult) {
        if (!isAlipaySessionActive(session) || session.completing) return
        if (result.orderId.isNotEmpty() && result.orderId != session.orderId) return
        if (result.succeeded) {
            session.completing = true
            var retry = false
            try {
                val refreshed = refreshUser()
                if (!isAlipaySessionActive(session)) return
                // 权益刷新失败时保留待单，后续只重试查单和刷新，不再次付款。
                if (!refreshed) {
                    if (session.userInitiated && session.lastNoticeStatus != "PROCESSING") {
                        session.lastNoticeStatus = "PROCESSING"
                        PayUi.toast("alipay_benefits_pending".tr)
                    }
                    retry = true
                    return
                }
                if (session.userInitiated) reportAlipayPurchase(session, result)
                clearPendingAlipayOrder(session)
                var closed = false
                if (session.userInitiated) {
                    disposeDialog(41)
                    closed = showPurchaseSuccess(pageName = session.pageName, isNewOrder = true)
                }
                endAlipayAttempt(
                    session,
                    alipayOutcome(
                        session, PayStatus.SUCCESS, "",
                        message = if (session.userInitiated) "purchase_successful".tr else "",
                        productId = result.productId,
                        isNewOrder = session.userInitiated,
                        pageClosed = closed,
                    ),
                )
            } finally {
                session.completing = false
                // 轮询还在就等下一拍；已经停了（只查一次的场景）才补一个，仍受次数上限约束。
                if (retry && session.pollTimer == null) startAlipayPoll(session, immediate = false)
            }
            return
        }
        if (result.code != 0 && result.code != 1046000005) return
        if (result.waiting) {
            if (session.userInitiated && session.lastNoticeStatus != result.status) {
                session.lastNoticeStatus = result.status
                PayUi.toast(
                    (if (result.status == "PROCESSING") "alipay_benefits_pending" else "alipay_payment_confirming").tr,
                )
            }
            return
        }
        val messageKey: String
        val reason: String
        when {
            result.status == "CLOSED" -> {
                messageKey = "alipay_payment_incomplete"
                reason = PayReason.ORDER_CLOSED
            }
            result.status == "REFUNDED" -> {
                messageKey = "alipay_refunded"
                reason = PayReason.ORDER_REFUNDED
            }
            result.code == 1046000005 -> {
                messageKey = "alipay_order_not_found"
                reason = PayReason.ORDER_NOT_FOUND
            }
            else -> return
        }
        val message = if (session.userInitiated) messageKey.tr else ""
        clearPendingAlipayOrder(session)
        endAlipayAttempt(
            session,
            alipayOutcome(
                session, PayStatus.FAILED, reason, message,
                code = if (result.code == 0) null else result.code, productId = result.productId,
            ),
        )
        if (session.userInitiated) PayUi.toast(message)
    }

    /** 到账后的购买上报，只给用户本次发起的订单用；静默补查不报。 */
    private fun reportAlipayPurchase(session: AlipaySession, result: AlipayOrderResult) {
        val goods = session.goods
        val productId = goods?.productId?.trim() ?: ""
        val paidProductId = result.productId.ifEmpty { session.productId }
        // 换商品时查到的是旧订单，手里的商品不是付款的那个，价格对不上就不报。
        if (goods == null || productId.isEmpty() || productId != paidProductId) return
        // 首期优惠时实付和标价不同，优先用建单返回的实付金额；复用旧单拿不到时退回标价。
        val price = session.payAmount ?: goods.productPrice ?: 0.0
        // 按下单通道分，不看商品上的 productType：后端没返回类型时也要报。
        if (session.trade) {
            AdEvent.logPurchaseEvent(
                price = price,
                productId = productId,
                productName = goods.productName ?: "",
                orderId = session.orderId,
                purchaseId = session.orderId,
            )
        } else {
            AdEvent.logSubscribeEvent(price = price, productId = productId, orderId = session.orderId)
        }
    }

    /** 结束本次支付宝操作：停轮询、关 loading、让出槽位，并回执 / 广播终态。 */
    private fun endAlipayAttempt(session: AlipaySession, outcome: PayOutcome) {
        if (!isAlipaySessionActive(session)) return
        stopAlipayPoll(session)
        disposeDialog(41)
        if (session.trade) {
            alipayTradeSession = null
        } else {
            alipaySession = null
        }
        settleAlipay(session, outcome, terminal = true)
    }

    private fun assignAlipaySession(session: AlipaySession) {
        if (session.trade) {
            alipayTradeSession = session
        } else {
            alipaySession = session
        }
    }

    /** 槽位被新会话顶替、登出或换号：停掉旧会话的轮询，回执作废。 */
    private fun dropAlipaySlot(session: AlipaySession?, reason: String) {
        if (session == null) return
        stopAlipayPoll(session)
        val attempt = session.attempt
        if (attempt != null && !attempt.resolved) {
            attempt.resolve(alipayOutcome(session, PayStatus.SUPERSEDED, reason))
        }
    }

    private fun stopAlipayPoll(session: AlipaySession?) {
        if (session == null) return
        session.pollTimer?.cancel()
        session.pollTimer = null
    }

    private fun isAlipaySessionActive(session: AlipaySession): Boolean {
        val current = if (session.trade) alipayTradeSession else alipaySession
        return isLoggedIn &&
            current === session &&
            AuthSession.isCurrent(session.revision) &&
            payUserId() == session.userId
    }

    /** 未安装时积分包不建单。 */
    private fun isAlipayInstalled(): Boolean = try {
        payModule().alipayIsInstalled()
    } catch (e: Throwable) {
        log("检查支付宝安装失败: $e")
        false
    }

    /** 拉起收银台，返回 SDK 的 resultStatus；没跑完返回 null。9000 也不能当到账。 */
    private suspend fun payWithAlipaySdk(orderStr: String): String? = try {
        val raw = payModule().alipayPay(orderStr)
        val status = raw.optString("resultStatus", "").trim()
        if (status.isEmpty() || status == "null") null else status
    } catch (e: Throwable) {
        log("拉起支付宝收银台失败: $e")
        null
    }

    private suspend fun openAlipay(schema: String): Boolean {
        val url = schema.trim()
        if (url.isEmpty() || !url.contains(":")) return false
        return try {
            appModule().openUrl(url, "external")
        } catch (e: Throwable) {
            log("拉起支付宝失败: $e")
            false
        }
    }

    private fun payUserId(): String? {
        val user = UserStore.userInfo ?: return null
        return "${user.id}"
    }

    private fun pendingAlipayKey(session: AlipaySession): String {
        val prefix = if (session.trade) StorageKey.alipayPendingTrade else StorageKey.alipayPendingOrder
        return "${prefix}_${session.userId}"
    }

    /** 新订单按账号保存付款凭证。续费兼容旧版仅有 userId|orderId 的记录；积分包不读那条。 */
    private fun readPendingAlipayOrder(session: AlipaySession): Boolean {
        val stored = Sp.getString(pendingAlipayKey(session))
        if (stored.isNotEmpty()) {
            try {
                val data = JSONObject(stored)
                val orderId = data.opt("orderId")
                if (orderId is String) {
                    session.orderId = orderId.trim()
                    if (session.trade) {
                        session.orderStr = data.opt("orderStr") as? String ?: ""
                    } else {
                        session.jumpSchema = data.opt("jumpSchema") as? String ?: ""
                    }
                    session.productId = data.opt("productId") as? String ?: ""
                    if (session.orderId.isNotEmpty()) return true
                }
            } catch (e: Throwable) {
                log("支付宝本地订单记录无效")
            }
        }
        if (session.trade) return false
        val raw = Sp.getString(StorageKey.alipayPendingOrder)
        if (raw.isEmpty() || !raw.startsWith("${session.userId}|")) return false
        session.orderId = raw.substring(raw.indexOf('|') + 1).trim()
        return session.orderId.isNotEmpty()
    }

    private fun savePendingAlipayOrder(session: AlipaySession) {
        val payload = JSONObject().put("orderId", session.orderId)
        if (session.trade) {
            payload.put("orderStr", session.orderStr)
        } else {
            payload.put("jumpSchema", session.jumpSchema)
        }
        payload.put("productId", session.productId)
        Sp.setString(pendingAlipayKey(session), payload.toString())
    }

    private fun clearPendingAlipayOrder(session: AlipaySession) {
        if (!isAlipaySessionActive(session)) return
        val saved = AlipaySession(userId = session.userId, trade = session.trade)
        if (readPendingAlipayOrder(saved) && saved.orderId == session.orderId) {
            Sp.remove(pendingAlipayKey(session))
        }
        if (!session.trade && Sp.getString(StorageKey.alipayPendingOrder) == "${session.userId}|${session.orderId}") {
            Sp.remove(StorageKey.alipayPendingOrder)
        }
    }

    private fun alipayTradeMessage(code: Int?): String? = when (code) {
        1009000004 -> "alipay_credits_members_only".tr
        1009000005 -> "alipay_credits_renew".tr
        else -> alipayCreateMessage(code)
    }

    private fun alipayCreateMessage(code: Int?): String? = when (code) {
        1004040004 -> "alipay_bind_mobile".tr
        1046000001 -> "alipay_not_available".tr
        1046000002 -> "alipay_product_unavailable".tr
        1046000003 -> "alipay_busy".tr
        1046000004 -> "alipay_already_auto_renew".tr
        1046000008 -> "alipay_lucky_first_only".tr
        1046000009 -> "alipay_intro_unavailable".tr
        1009496001 -> "alipay_winback_ineligible".tr
        1009496002 -> "alipay_winback_used".tr
        1009496003 -> "alipay_winback_pending".tr
        1009496005 -> "alipay_winback_ended".tr
        1009280003 -> "alipay_member_still_active".tr
        1009280005 -> "alipay_winback_closed".tr
        else -> null
    }

    // =============================================================================================
    // 订阅管理 / 取消自动续费
    // =============================================================================================

    /**
     * 设置页「取消订阅」：iOS 打开系统订阅说明页；Android / 鸿蒙 查询并确认取消支付宝自动续费。
     * 在页面自己的协程里调用。
     * @param confirm 弹确认框（文案 `alipay_cancel_confirm`），返回用户是否确认。
     */
    suspend fun openUnSubscribe(confirm: suspend () -> Boolean) {
        if (isIos) {
            openSubscriptionManagement()
            return
        }
        confirmCancelAlipay(confirm)
    }

    /**
     * 取消支付宝自动续费：点击后再查是否可取消，满足才弹窗，确定后才请求取消。
     * 查询与确认都归属发起账号和发起页面，换号或离开页面后不继续操作。成功只表示不再续扣，当前周期权益仍在。
     * @return 是否已取消
     */
    suspend fun confirmCancelAlipay(confirm: suspend () -> Boolean): Boolean {
        if (cancelingAlipayPagerId.isNotEmpty() && isPagerAlive(cancelingAlipayPagerId)) return false
        val pager = currentPager()
        cancelingAlipayPagerId = currentPagerId()
        val revision = AuthSession.revision
        try {
            val state = PayBackend.fetchAlipayRenewState()
            if (!AuthSession.isCurrent(revision)) return false
            if (state.code != 0) {
                pager?.toast("alipay_busy".tr)
                return false
            }
            if (!state.canCancelAlipay) {
                pager?.toast("alipay_no_auto_renew".tr)
                return false
            }
            val confirmed = confirm()
            if (!confirmed || !AuthSession.isCurrent(revision)) return false
            val result = PayBackend.cancelAlipaySubscription()
            if (!AuthSession.isCurrent(revision)) return false
            if (result.code == 0) {
                val end = result.currentPeriodEnd
                pager?.toast(
                    if (end == null) "alipay_cancel_success".tr else "alipay_cancel_success_until".trParams("time" to end),
                )
                return true
            }
            pager?.toast(if (result.code == 1046000006) "alipay_no_auto_renew".tr else "alipay_busy".tr)
            return false
        } finally {
            cancelingAlipayPagerId = ""
        }
    }

    // =============================================================================================
    // 商品 / 弹窗 / 外链
    // =============================================================================================

    /**
     * 查询 Apple 商店商品详情。在调用页面自己的协程里执行。
     * 非 iOS 返回空详情（全部记入 notFoundIDs）；平台不可用时提示并带上 error。
     */
    suspend fun getProduct(kIds: Set<String>): ProductDetailsResponse {
        if (!isIos) {
            return ProductDetailsResponse(productDetails = emptyList(), notFoundIDs = kIds.toList())
        }
        val response = if (PayStore.isAvailable()) PayStore.queryProductDetails(kIds) else null
        if (response == null || response.error != null) {
            val message = "payment_platform_is_not_available".tr
            currentPager()?.toast(message)
            return ProductDetailsResponse(emptyList(), kIds.toList(), error = response?.error ?: message)
        }
        return response
    }

    /** 弹出购买 loading。[canOpen] 为 false 时静默补单不挡操作。 */
    private fun openDialog(canOpen: Boolean = true) {
        if (!isOpenDialog && canOpen) {
            isOpenDialog = true
            PayUi.showLoading()
        }
    }

    /** 关闭购买 loading。[index] 仅用于日志定位调用来源。可在任意页面上下文调用。 */
    fun disposeDialog(index: Int) {
        if (!isOpenDialog) return
        log("disposeDialog: $index")
        isOpenDialog = false
        PayUi.hideLoading()
    }

    private suspend fun launchExternal(url: String) {
        val opened = try {
            appModule().openUrl(url, "external")
        } catch (e: Throwable) {
            log("打开外链失败: $e")
            false
        }
        if (!opened) PayUi.toast("open_browser_failed".tr)
    }

    /** 刷新用户信息（权益 / 积分）。返回是否刷新成功。 */
    private suspend fun refreshUser(): Boolean = try {
        UserStore.getUserInfo()
    } catch (e: Throwable) {
        log("刷新用户信息失败: $e")
        false
    }
}
