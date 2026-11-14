package com.sondo.app.service

import com.sondo.app.base.AppEnv
import com.sondo.app.base.AppHooks
import com.sondo.app.base.AppHost
import com.sondo.app.base.Events
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.http.AuthSession
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.HostBinding
import com.sondo.app.core.utils.onHost
import com.sondo.app.core.utils.delayMs
import com.sondo.app.core.utils.isRecallOfferActive
import com.sondo.app.core.utils.nowMs
import com.sondo.app.core.utils.recallExpireWhenSeen
import com.sondo.app.data.api.AppConfigApi
import com.sondo.app.data.models.ConfigModel
import com.sondo.app.data.models.CountryPricingModel
import com.sondo.app.data.models.MvDiscountModel
import com.sondo.app.data.models.SplashConfig
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.timer.setTimeout

/**
 * 远端开关与积分单价（对应 Flutter 侧 ConfigService + AppConfigRemoteDataSource 的配置部分）。
 *
 * 字段都是普通属性；任何一组值刷新后广播 [Events.CONFIG_CHANGED]（data.source 标明来源：
 * guide / mvConfigs / pricing / discount），页面在事件里把需要的值同步到自己的 observable。
 */
object ConfigService {
    var reIndex = 0
    var reConfigIndex = 0

    /** 当前版本（x.y.z） */
    var currentVersion = ""
    /** 当前版本 Number（各段直接拼接，如 1.2.3 → 123；解析失败 10000） */
    var versionNumber = 10000
    /** 自定义模式0 固定10积分 */
    var customOnePoint = 10

    /** 新用户登录积分 */
    var loginPoint = 0
    /** 是否检查更新 */
    var isCheckUpdate = 0
    /** 是否强制更新 */
    var isMandatoryUpdate = 0
    /** 是否开启评分 */
    var isCommentOpen = false
    /** 是否开启首页评分 */
    var isHomeCommentOpen = false
    /** 首次打开是否跳转到订阅页面 */
    var isPushSub = false
    /** 自定义mv收费模式 0 开始收10积分 1 开始全部收 */
    var mvPointModel = 0
    /** mv(手动、自动)创建开关 0 关闭 1 开启 */
    var mvModeSwitch = 0
    /** 重新生成封面所需积分 v1 */
    var againMergePicPointV1 = 10
    /** （编辑后）重新合成视频所需积分 */
    var againMergePoint = 10
    /** （线上版本）本地版本小于等于线上版本，则评分展示 */
    var scoreBuildNo = 0
    /** 是否显示使用说明 */
    var displayUseExplain = false
    /** 自定义mv描述是否必填 */
    var mvDescIsNeed = false
    /** 生成四视图图片所需积分 */
    var genFourViewImgPoint = 20
    /** 优化描述功能是否打开 */
    var featureEnabled = false
    /** 折扣页是否开启 */
    var discountOpen = false
        set(value) {
            if (field == value) return
            field = value
            notifyChanged("discount")
        }
    /** 是否开启随机积分 */
    var isMvRandomJsPoint = false
    /** mv故事线智能选曲多少秒 */
    var mvFlowV2MusicChoseSecond = 0
    /** mv故事线智能选曲多少秒(Ultra) */
    var mvFlowV2AiDanceMusicChoseSecond = 0
    /** 是否开启旗舰版(MV) */
    var mvFlagshipOpen = false
    /** 是否显示特效开关：1开启，0关闭 */
    var mvSpecialEffectsOpen = false

    /** 抽卡高清视频 N积分/秒 */
    var gachaHdVideoPoint = 0
    /** 抽卡图片 N积分/张 */
    var gachaImagePoint = 0
    /** 抽卡标清视频 N积分/秒 */
    var gachaSdVideoPoint = 0
    /** 高清视频 N积分/秒 */
    var hdVideoPoint = 0
    /** 图片 N积分/张 */
    var imagePoint = 0
    /** 音乐创作 N积分/次 */
    var musicCreatePoint = 10
    /** 标清视频 N积分/秒 */
    var sdVideoPoint = 0

    // -------- seedance mv --------
    /** 创建 MV */
    var mvSeedanceCreatePoint = 10
    /** 续跑全片 标清（积分/秒） */
    var mvSeedanceSdVideoPoint = 0
    /** 分镜重生 标清（积分/秒） */
    var mvSeedanceGachaSdVideoPoint = 0
    /** 续跑全片 高清（积分/秒） */
    var mvSeedanceHdVideoPoint = 0
    /** 分镜重生 高清（积分/秒） */
    var mvSeedanceGachaHdVideoPoint = 0
    /** 分镜重生图片（积分/张） */
    var mvSeedanceGachaImagePoint = 0
    /** 封面10积分 */
    var mvSeedanceCoverRegenPoint = 10

    var isCountryLoading = false
    /** 每次拉取国家定价后更新（毫秒），页面据此重算展示价格。 */
    var updatePointTime = 0L

    private var discountModel: MvDiscountModel? = null

    /** 折扣漏斗的本地状态（首次访问时从存储恢复）；修改后调用 [updateMvDiscountModel]。 */
    val mvDiscountModel: MvDiscountModel
        get() = discountModel ?: MvDiscountModel.getFromStorage().also { discountModel = it }

    /** 幸运周卡和回归周卡的 10 分钟窗口。 */
    val countdown = OfferCountdown()

    private val hostBinding = HostBinding()

    /**
     * 对应 `ConfigService.init()`：读版本号后并行拉取各组配置（不等待结果）。
     * 同一个根页面上重复调用是空操作；根页面重建后会重新拉取并重启倒计时。
     */
    fun init() {
        AppHooks.priceMismatchHandler = { AppHost.launch { getCountryPricing() } }
        currentVersion = AppEnv.appVersionName
        // 报错不展示
        versionNumber = if (currentVersion.isEmpty()) 10000 else currentVersion.split('.').joinToString("").toIntOrNull() ?: 10000
        DevLog.logger("versionNumber: $versionNumber")
        if (!hostBinding.needsBind()) return
        // 上一个根页面上没跑完的定价请求不会再有结果
        isCountryLoading = false
        discountModel = MvDiscountModel.getFromStorage()
        AppHost.launch { displayGuidePage() }
        AppHost.launch { getMvConfigs() }
        AppHost.launch { getDiscountConfig() }
        countdown.startAll(mvDiscountModel)
        AppHost.launch { getCountryPricing() }
    }

    // ------------------------------------------------------------------ 折扣漏斗

    /** 页面改写漏斗状态后回写内存（落盘用 model.saveToStorage()，由调用方决定）。 */
    fun updateMvDiscountModel(model: MvDiscountModel) {
        discountModel = model
        notifyChanged("discount")
    }

    /** 查询「周留存折扣」资格；失败按未命中。退出或换号后的旧响应丢弃。 */
    suspend fun getDiscountConfig() = onHost { loadDiscountConfig() }

    private suspend fun loadDiscountConfig() {
        val revision = AuthSession.revision
        val result = AppConfigApi.getDiscountConfig().getOrNull() ?: false
        if (!AuthSession.isCurrent(revision)) return
        discountOpen = if (!AppEnv.isIOS && result) {
            // 进入过折扣页且已超时，则不显示折扣页
            if (mvDiscountModel.isEnteredDiscountPage) !mvDiscountModel.isTimeOut else true
        } else {
            false
        }
        DevLog.logger("discountOpen====$discountOpen")
    }

    // ------------------------------------------------------------------ displayGuidePage

    /** ConfigModel 按数字宽松解析（Dart 侧 secondPoint 回整数会整份解析失败，这里不复现这个缺陷）。 */
    private suspend fun fetchDisplayGuide(): ConfigModel? = AppConfigApi.getDisplayGuide().getOrNull()

    private suspend fun displayGuidePage() {
        var retryCount = 0
        var fetched = fetchDisplayGuide()
        while (fetched == null && retryCount < 3) {
            delayMs(1000)
            fetched = fetchDisplayGuide()
            retryCount++
        }
        val config = fetched ?: ConfigModel()
        if (AppEnv.isIOS) {
            isCheckUpdate = config.isCheckUpdateIos
            isMandatoryUpdate = config.isMandatoryUpdateIos
            isCommentOpen = config.commentOpenIos == 1
            isHomeCommentOpen = config.homeCommentOpenIos == 1
            isPushSub = config.sondoSubDisplayIos == 1
            scoreBuildNo = config.guideScoreControlBuildNoIos
        } else {
            isCheckUpdate = config.isCheckUpdateAndroid
            isMandatoryUpdate = config.isMandatoryUpdateAndroid
            isCommentOpen = config.commentOpenAndroid == 1
            isHomeCommentOpen = config.homeCommentOpenAndroid == 1
            isPushSub = config.sondoSubDisplay == 1
            scoreBuildNo = config.guideScoreControlBuildNoAndroid
        }
        loginPoint = config.newUserPoint
        mvPointModel = config.customMvChargingModel
        mvModeSwitch = config.customMvZdSdOpen
        againMergePicPointV1 = config.againMergePicPointV1
        againMergePoint = config.againMergePoint
        displayUseExplain = config.displayUseExplain == 1
        mvDescIsNeed = config.mvDescIsNeed == 1
        genFourViewImgPoint = config.genFourViewImgPoint
        featureEnabled = config.dailyLimit > 0
        isMvRandomJsPoint = config.mvRandomJsPoint == 1
        mvFlowV2MusicChoseSecond = config.mvFlowV2MusicChoseSecond
        mvFlowV2AiDanceMusicChoseSecond = config.mvFlowV2AiDanceMusicChoseSecond
        mvFlagshipOpen = config.mvFlagshipOpen == 1
        mvSpecialEffectsOpen = config.mvSpecialEffectsOpen == 1
        // 本地版本小于等于线上版本并且评分开关打开，才显示评分弹窗
        isCommentOpen = versionNumber <= scoreBuildNo && isCommentOpen
        if (mvModeSwitch == 0) {
            // 配置开关为 0，本地存储同步为 0
            Sp.setString(StorageKey.mvMode, "0")
        }
        notifyChanged("guide")
    }

    // ------------------------------------------------------------------ 国家定价

    /**
     * 严格解析（CountryPricingModel.fromJsonOrNull）：任何一个价格键缺失 / 类型不对都算失败，
     * 保留旧值并重试，与参考工程一致。
     */
    private suspend fun fetchCountryPricing(): CountryPricingModel? = AppConfigApi.getCountryPricing().getOrNull()

    /** 拉取按国家差异化的积分单价。需要已登录（token 与 refreshToken 都在）；同一时刻只跑一轮。 */
    suspend fun getCountryPricing() = onHost { loadCountryPricing() }

    private suspend fun loadCountryPricing() {
        if (AuthSession.accessToken.isEmpty() || AuthSession.refreshToken.isEmpty()) return
        if (isCountryLoading) return
        isCountryLoading = true
        var result = fetchCountryPricing()
        var retryCount = 0
        while (result == null && retryCount < 6) {
            delayMs(2000)
            result = fetchCountryPricing()
            retryCount++
        }
        if (result != null) {
            gachaHdVideoPoint = result.gachaHdVideoPoint
            gachaImagePoint = result.gachaImagePoint
            gachaSdVideoPoint = result.gachaSdVideoPoint
            hdVideoPoint = result.hdVideoPoint
            imagePoint = result.imagePoint
            musicCreatePoint = result.musicCreatePoint
            sdVideoPoint = result.sdVideoPoint
            // -------- seedance mv --------
            mvSeedanceCreatePoint = result.mvSeedanceCreatePoint
            mvSeedanceSdVideoPoint = result.mvSeedanceSdVideoPoint
            mvSeedanceGachaSdVideoPoint = result.mvSeedanceGachaSdVideoPoint
            mvSeedanceHdVideoPoint = result.mvSeedanceHdVideoPoint
            mvSeedanceGachaHdVideoPoint = result.mvSeedanceGachaHdVideoPoint
            mvSeedanceGachaImagePoint = result.mvSeedanceGachaImagePoint
            mvSeedanceCoverRegenPoint = result.mvSeedanceCoverRegenPoint
        }
        updatePointTime = nowMs()
        isCountryLoading = false
        notifyChanged("pricing")
    }

    // ------------------------------------------------------------------ MV 配置

    /** 自定义 MV 计费配置；失败重试到 reIndex 达到 3。 */
    suspend fun getMvConfigs() = onHost { loadMvConfigs() }

    private suspend fun loadMvConfigs() {
        while (true) {
            val res = AppConfigApi.getMvConfigs()
            if (res is ApiResult.Success) {
                customOnePoint = (res.data.opt("customOnePoint") as? Number)?.toInt() ?: 10
                DevLog.logger("result: customOnePoint: $customOnePoint")
                notifyChanged("mvConfigs")
                return
            }
            reIndex++
            if (reIndex >= 3) return
        }
    }

    // ------------------------------------------------------------------ 启动期请求

    /** 启动页配置（2s 短超时、不带登录态、失败静默）；失败返回空配置。 */
    suspend fun fetchSplashConfig(): SplashConfig = AppConfigApi.fetchSplashConfig().getOrNull() ?: SplashConfig()

    /** 是否需弹出「应用火爆」弹窗（走 apiOtherUrl，失败按 false）。 */
    suspend fun isAppHotOpen(): Boolean = AppConfigApi.isAppHotOpen().getOrNull() ?: false

    private fun notifyChanged(source: String) {
        EventBus.post(Events.CONFIG_CHANGED, JSONObject().put("source", source))
    }
}

/**
 * 幸运周卡和回归周卡的 10 分钟窗口。两段到期时间分开，只共用按到期时间重算剩余秒。
 * 每秒变化时广播 [Events.OFFER_COUNTDOWN]。
 */
class OfferCountdown {
    var remainingSeconds = 0
        private set
    var recallRemainingSeconds = 0
        private set

    private val discount = ExpireClock { remainingSeconds = it; notifyTick() }
    private val recall = ExpireClock { recallRemainingSeconds = it; notifyTick() }

    val timerText: String get() = clockText(remainingSeconds)

    /** 回归周卡剩余时间。和幸运周卡分开，首页入口优先显示这段。 */
    val recallTimerText: String get() = clockText(recallRemainingSeconds)

    private fun clockText(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    }

    val recallExpireMs: Long get() = Sp.getLong(StorageKey.recallProductExpireTime, 0L)

    val recallOfferActive: Boolean get() = isRecallOfferActive(expireMs = recallExpireMs, nowMs = nowMs())

    /** 启动时带上当前折扣资格，本类不自己拉配置。 */
    fun startAll(discount: MvDiscountModel) {
        startDiscount(discount)
        startRecall()
    }

    /** 资格不满足只停表，不改已经写下的剩余秒，避免和原来的折扣入口不一致。 */
    fun startDiscount(discount: MvDiscountModel) {
        val endTime = discount.enteredDiscountPageExpireTime
        if (!discount.isNewUser || !discount.isEnteredDiscountPage || !discount.isEnteredSubscriptionPage ||
            endTime == 0L || discount.isTimeOut
        ) {
            this.discount.stop()
            return
        }
        this.discount.sync(endTime)
    }

    /** 资格接口判定回归时调用：过期记录清掉，进行中的 10 分钟不动。是否新一轮以接口为准，本地不判断。 */
    fun prepareNewRecallCycle() {
        if (recallOfferActive) return
        Sp.setLong(StorageKey.recallProductExpireTime, 0L)
        recallRemainingSeconds = 0
        recall.stop()
        notifyTick()
    }

    /** 购买成功后停掉回归倒计时。记成已过期，之后再看到商品也不会开 10 分钟。 */
    fun endRecallOffer() {
        val ended = nowMs() - 1
        Sp.setLong(StorageKey.recallProductExpireTime, if (ended < 1) 1 else ended)
        recallRemainingSeconds = 0
        recall.stop()
        notifyTick()
    }

    /** 落地页展示出商品后立刻开始计时。已有到期记录不会被重置。 */
    fun startRecallCountdownOnce() {
        val next = recallExpireWhenSeen(existingExpireMs = recallExpireMs, sawProduct = true, nowMs = nowMs())
        if (next != recallExpireMs) Sp.setLong(StorageKey.recallProductExpireTime, next)
        startRecall()
    }

    fun stop() {
        discount.stop()
        recall.stop()
    }

    private fun startRecall() {
        val endTime = recallExpireMs
        if (endTime == 0L || endTime <= nowMs()) {
            recallRemainingSeconds = 0
            recall.stop()
            notifyTick()
            return
        }
        recall.sync(endTime)
    }

    private fun notifyTick() {
        EventBus.post(
            Events.OFFER_COUNTDOWN,
            JSONObject().put("remainingSeconds", remainingSeconds).put("recallRemainingSeconds", recallRemainingSeconds),
        )
    }
}

/** 按到期时间重算剩余秒，到 0 停掉。定时器挂在宿主页面上，跨页面存活。 */
private class ExpireClock(private val onValue: (Int) -> Unit) {
    private var generation = 0

    fun sync(endTimeMs: Long) {
        val gen = ++generation
        schedule(gen, endTimeMs)
        onValue(leftSeconds(endTimeMs))
    }

    fun stop() {
        generation++
    }

    private fun schedule(gen: Int, endTimeMs: Long) {
        setTimeout(AppHost.pager.pagerId, 1000) {
            if (gen != generation) return@setTimeout
            val left = leftSeconds(endTimeMs)
            if (left <= 0) generation++ else schedule(gen, endTimeMs)
            onValue(left)
        }
    }

    private fun leftSeconds(endTimeMs: Long): Int {
        val diff = endTimeMs - nowMs()
        if (diff <= 0) return 0
        return (diff / 1000).toInt()
    }
}
