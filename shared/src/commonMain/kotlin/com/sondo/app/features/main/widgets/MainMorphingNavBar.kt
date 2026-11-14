package com.sondo.app.features.main.widgets

import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.asset
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.nowMs
import com.sondo.app.data.models.jBool
import com.sondo.app.data.models.jDbl
import com.sondo.app.data.models.jInt
import com.sondo.app.data.models.jLong
import com.sondo.app.data.models.jStr
import com.sondo.app.features.main.MainNavTab
import com.sondo.app.service.AppNav
import com.sondo.app.service.PlayerService
import com.sondo.app.ui.SDFit
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDMarqueeText
import com.sondo.app.ui.atoms.SDRingProgress
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.Rotate
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Blur
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.timer.clearTimeout

/** Kuikly 的 Blur 半径上限（Dart sigma 15 取上限）。 */
private const val GLASS_BLUR = 12.5f

class MainMorphingNavBarAttr : ComposeAttr() {
    /** 当前选中的导航 Tab。 */
    var selected: MainNavTab by observable(MainNavTab.home)

    /** 导航栏左右内边距之内的总宽度（屏宽 - 40）。 */
    var barWidth: Float = 0f

    /** 所在根页面是否可见：不可见（被创作页等盖住）时胶囊不做出现 / 消失切换。 */
    var pageVisible: Boolean by observable(true)
}

class MainMorphingNavBarEvent : ComposeEvent() {
    internal var home: (() -> Unit)? = null
    internal var mv: (() -> Unit)? = null
    internal var music: (() -> Unit)? = null
    internal var library: (() -> Unit)? = null

    fun onHomeTap(h: () -> Unit) { home = h }
    fun onMvTap(h: () -> Unit) { mv = h }
    fun onMusicTap(h: () -> Unit) { music = h }
    fun onLibraryTap(h: () -> Unit) { library = h }
}

/**
 * 底部浮动导航栏（Dart `MainFloatingNavBar` + `MainMorphingNavBar` + `MainNavOrb` + `MainNavIconRow`
 * + `MainPlayerOrb` + `MainPlayerControls` + `MainGlassCard`）。
 *
 * 播放器状态由组件自己镜像 [PlayerService]（[Events.PLAYER_STATE] / [Events.PLAYER_POSITION]）。
 * 有可播放歌曲（生成完成且主轨道活跃）时右侧出现封面圆球，点封面在「纯导航」与「播放器展开」之间做挤压动画。
 * 根节点自带 Dart 的上下 10 / 左右 20 内边距。
 */
class MainMorphingNavBarView : ComposeView<MainMorphingNavBarAttr, MainMorphingNavBarEvent>() {
    // —— 播放器镜像 —— //
    internal var songId by observable(0L)
    internal var songTitle by observable("")
    internal var songCover by observable("")
    private var songStatus by observable(0)
    private var hasSong by observable(false)
    private var mainTrackActive by observable(false)
    /** 主音轨被试听临时占用（创作页选曲 / 裁剪试听），试听结束会恢复：期间胶囊保留。 */
    private var mainTrackSuspended = false
    internal var isPlaying by observable(false)
    internal var progress by observable(0f)

    // —— 挤压动画 —— //
    /** AnimationController.value（线性 0..1） */
    private var squeezeRaw = 0f
    /** CurvedAnimation(easeInOutCubic).value */
    internal var squeeze by observable(0f)
    private var squeezeTarget = 0f
    private var squeezeFrom = 0f
    private var squeezeStartMs = 0L
    private var squeezeRunning = false

    // —— 横向拖动切歌 —— //
    internal var dragOffset by observable(0f)
    private var totalDragDelta = 0f
    private var lastPanX = 0f
    private var lastPanMs = 0L
    private var velocityX = 0f

    private var lastSongId: Long? = null
    private var alive = true
    private val notifyRefs = ArrayList<Pair<String, CallbackRef>>()

    internal val page: BasePager get() = getPager() as BasePager

    internal val selectedTab: MainNavTab get() = attr.selected

    val hasPlayer: Boolean get() = hasSong && songStatus == 3 && (mainTrackActive || mainTrackSuspended)

    /**
     * 实际是否展示播放器胶囊（布局用它，不直接用 [hasPlayer]）。
     * 播放器状态在页面切换时可能短暂报「没有主音轨」（真机上从 MV 创作页返回时出现过），直接跟随会切到纯导航布局、
     * 封面球被销毁，状态恢复后再重建、封面重新加载 —— 看起来就是胶囊先消失、封面再出现。
     * 所以：出现立即生效；消失要等根页面可见、且「无播放器」持续 [HIDE_GRACE_MS] 才切换。
     */
    internal var showPlayer by observable(false)
    private var hideTimer: String? = null

    override fun createAttr(): MainMorphingNavBarAttr = MainMorphingNavBarAttr()
    override fun createEvent(): MainMorphingNavBarEvent = MainMorphingNavBarEvent()

    override fun created() {
        super.created()
        applyState(PlayerService.snapshot())
        showPlayer = hasPlayer
        bindValueChange({ attr.pageVisible }) { syncShowPlayer() }
        val notify = getPager().acquireModule<NotifyModule>(NotifyModule.MODULE_NAME)
        notifyRefs.add(Events.PLAYER_STATE to notify.addNotify(Events.PLAYER_STATE) { if (alive && it != null) applyState(it) })
        notifyRefs.add(
            Events.PLAYER_POSITION to notify.addNotify(Events.PLAYER_POSITION) {
                if (!alive || it == null) return@addNotify
                if (it.optBoolean("isMainTrack", true)) progress = it.jDbl("progress", 0.0).toFloat()
            },
        )
    }

    override fun viewDestroyed() {
        alive = false
        squeezeRunning = false
        cancelHide()
        unbindAllValueChange()
        val notify = getPager().acquireModule<NotifyModule>(NotifyModule.MODULE_NAME)
        notifyRefs.forEach { (name, ref) -> notify.removeNotify(name, ref) }
        notifyRefs.clear()
        super.viewDestroyed()
    }

    private fun applyState(json: JSONObject) {
        val song = json.optJSONObject("currentSong")
        hasSong = json.jBool("hasCurrentSong", false) && song != null
        if (song != null) {
            songId = song.jLong("id", 0L)
            songTitle = song.jStr("musicTitle")
            songCover = song.jStr("musicCoverUrl")
            songStatus = song.jInt("musicGenerateStatus", 0)
        } else {
            songStatus = 0
        }
        mainTrackActive = json.jBool("isMainTrackActive", false)
        mainTrackSuspended = json.jBool("isMainTrackSuspended", false)
        // 试听占用播放器时 isPlaying 是试听的状态，胶囊只反映主音轨
        isPlaying = mainTrackActive && json.jBool("isPlaying", false)
        progress = json.jDbl("progress", 0.0).toFloat()
        // didUpdateWidget：换歌时复位拖动；没有播放器时的复位放在胶囊真正隐藏时（见 syncShowPlayer）
        val id: Long? = if (hasPlayer) songId else null
        val songChanged = lastSongId != null && id != null && id != lastSongId
        if (songChanged) {
            dragOffset = 0f
            totalDragDelta = 0f
        }
        if (id != null) lastSongId = id
        syncShowPlayer()
    }

    private fun syncShowPlayer() {
        if (!alive) return
        if (hasPlayer) {
            cancelHide()
            if (!showPlayer) showPlayer = true
            return
        }
        if (!showPlayer) return
        // 根页面不可见时不切换，回到前台后再按宽限期判断
        if (!attr.pageVisible) {
            cancelHide()
            return
        }
        if (hideTimer != null) return
        hideTimer = setTimeout(pagerId, HIDE_GRACE_MS) {
            hideTimer = null
            if (!alive || hasPlayer || !attr.pageVisible) return@setTimeout
            resetSqueeze()
            dragOffset = 0f
            totalDragDelta = 0f
            lastSongId = null
            showPlayer = false
        }
    }

    private fun cancelHide() {
        hideTimer?.let { clearTimeout(it) }
        hideTimer = null
    }

    private fun resetSqueeze() {
        squeezeRunning = false
        squeezeRaw = 0f
        squeezeTarget = 0f
        if (squeeze != 0f) squeeze = 0f
    }

    private fun animateSqueeze(target: Float) {
        squeezeFrom = squeezeRaw
        squeezeTarget = target
        squeezeStartMs = nowMs()
        if (!squeezeRunning) {
            squeezeRunning = true
            tickSqueeze()
        }
    }

    private fun tickSqueeze() {
        if (!alive || !squeezeRunning) return
        // 时长按剩余距离等比（AnimationController.forward/reverse 从当前值继续）
        val distance = kotlin.math.abs(squeezeTarget - squeezeFrom)
        val duration = (MainNavColors.orbSlideDurationMs * distance).coerceAtLeast(1f)
        val t = ((nowMs() - squeezeStartMs).toFloat() / duration).coerceIn(0f, 1f)
        squeezeRaw = lerpF(squeezeFrom, squeezeTarget, t)
        squeeze = easeInOutCubic(squeezeRaw)
        if (t >= 1f) {
            squeezeRunning = false
            if (squeezeRaw == 0f) {
                // AnimationStatus.dismissed
                dragOffset = 0f
                totalDragDelta = 0f
            }
            return
        }
        setTimeout(pagerId, 16) { tickSqueeze() }
    }

    internal fun collapsePlayerExpanded() {
        if (squeezeRaw == 0f && !squeezeRunning) return
        animateSqueeze(0f)
    }

    internal fun togglePlayerExpanded() {
        if (!showPlayer) return
        if (squeezeRaw >= 0.5f) collapsePlayerExpanded() else animateSqueeze(1f)
    }

    private val isExpandedState: Boolean get() = showPlayer && squeeze >= MainNavColors.squeezeContentFadeThreshold

    internal fun onPan(state: String, x: Float) {
        when (state) {
            "start" -> {
                lastPanX = x
                lastPanMs = nowMs()
                velocityX = 0f
            }
            "move" -> {
                if (!isExpandedState) return
                val now = nowMs()
                val dx = x - lastPanX
                val dt = (now - lastPanMs).coerceAtLeast(1L)
                velocityX = dx * 1000f / dt.toFloat()
                lastPanX = x
                lastPanMs = now
                totalDragDelta += dx
                dragOffset = totalDragDelta.coerceIn(-MainNavColors.maxDragOffset, MainNavColors.maxDragOffset)
            }
            "end" -> {
                if (!isExpandedState) return
                // 抬手前停顿太久时速度归零（Flutter 只取最后一段速度）
                val velocity = if (nowMs() - lastPanMs > 100L) 0f else velocityX
                val shouldGoNext = velocity < -MainNavColors.swipeVelocityThreshold || totalDragDelta < -MainNavColors.swipeThreshold
                val shouldGoPrevious = velocity > MainNavColors.swipeVelocityThreshold || totalDragDelta > MainNavColors.swipeThreshold
                if (shouldGoNext) {
                    PlayerService.launch { PlayerService.playNext() }
                } else if (shouldGoPrevious) {
                    PlayerService.launch { PlayerService.playPrevious() }
                }
                dragOffset = 0f
                totalDragDelta = 0f
            }
        }
    }

    internal fun navigateToSongDetail() {
        val current = PlayerService.currentSong ?: return
        if (current.musicGenerateStatus != 3) return
        DevLog.logger("songDetail===========${AppNav.currentRoute()}")
        page.nav.push(
            Routes.musicDetail,
            JSONObject().put("id", current.id).put("type", "nav").put("original", AppNav.currentRoute().ifEmpty { Routes.root }),
        )
    }

    internal fun tap(tab: MainNavTab) {
        when (tab) {
            MainNavTab.home -> event.home?.invoke()
            MainNavTab.mv -> event.mv?.invoke()
            MainNavTab.music -> event.music?.invoke()
            MainNavTab.library -> event.library?.invoke()
        }
    }

    // —— 几何（随挤压值逐帧计算） —— //
    private val total: Float get() = attr.barWidth
    internal val navWidth: Float
        get() = lerpF(total - MainNavColors.playerCardCollapsedWidth - MainNavColors.cardGap, MainNavColors.playerCardCollapsedWidth, squeeze)
    internal val playerWidth: Float get() = total - MainNavColors.cardGap - navWidth
    internal val playerPadding: Float
        get() = lerpF(0f, MainNavColors.innerPadding, (squeeze / MainNavColors.squeezeContentFadeThreshold).coerceIn(0f, 1f))
    internal val expandedOpacity: Float
        get() = ((squeeze - MainNavColors.squeezeContentFadeThreshold) / (1f - MainNavColors.squeezeContentFadeThreshold)).coerceIn(0f, 1f)
    internal val showExpandedContent: Boolean
        get() {
            val inner = playerWidth - playerPadding * 2f
            val slot = inner - MainNavColors.orbSizeExpanded - MainNavColors.itemGap
            return squeeze >= MainNavColors.squeezeContentFadeThreshold && slot >= MainNavColors.playerExpandedMinContentWidth
        }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                padding(MainNavColors.verticalPadding, MainNavColors.horizontalPadding, MainNavColors.verticalPadding, MainNavColors.horizontalPadding)
            }
            vif({ !ctx.showPlayer }) {
                // 纯导航
                glassCard(ctx, width = { ctx.total }, radius = { 100f }, padding = { MainNavColors.innerPadding }) {
                    navIconRow(ctx) { ctx.total - MainNavColors.innerPadding * 2f }
                }
            }
            velse {
                View {
                    attr {
                        flexDirectionRow()
                        height(MainNavColors.barHeight)
                    }
                    squeezeNavCard(ctx)
                    View { attr { width(MainNavColors.cardGap) } }
                    squeezePlayerCard(ctx)
                }
            }
        }
    }

    private companion object {
        /** 「无播放器」持续多久才收起胶囊（毫秒）。 */
        const val HIDE_GRACE_MS = 600
    }
}

/** Dart `MainGlassCard`：毛玻璃 + 半透明底 + 1px 白 10% 描边。尺寸参数可响应。 */
internal fun ViewContainer<*, *>.glassCard(
    ctx: MainMorphingNavBarView,
    width: () -> Float,
    radius: () -> Float,
    padding: () -> Float,
    content: ViewContainer<*, *>.() -> Unit,
) {
    View {
        attr {
            height(MainNavColors.barHeight)
            width(width())
            borderRadius(radius())
            border(Border(1f, BorderStyle.SOLID, MainNavColors.containerBorder))
        }
        Blur {
            attr {
                absolutePositionAllZero()
                blurRadius(GLASS_BLUR)
            }
        }
        View {
            attr {
                absolutePositionAllZero()
                backgroundColor(MainNavColors.containerFill)
            }
        }
        View {
            attr {
                absolutePositionAllZero()
                val p = padding()
                padding(p, p, p, p)
                justifyContentCenter()
            }
            content()
        }
    }
}

/** Dart `MainNavIconRow`：四图标 + 随选中 Tab 滑动的胶囊底。 */
internal fun ViewContainer<*, *>.navIconRow(ctx: MainMorphingNavBarView, rowWidth: () -> Float) {
    val tabs = listOf(
        Triple(MainNavTab.home, AssetsImage.rootNavHome, AssetsImage.rootNavHomeActive),
        Triple(MainNavTab.mv, AssetsImage.mv, AssetsImage.mvActive),
        Triple(MainNavTab.music, AssetsImage.create, AssetsImage.createActive),
        Triple(MainNavTab.library, AssetsImage.rootNavLibrary, AssetsImage.rootNavLibraryActive),
    )
    View {
        attr {
            height(MainNavColors.sideItemHeight)
            width(rowWidth())
        }
        // 选中底：首页是第 0 槽，资源库是最后一槽，切换时从当前槽滑过中间图标
        View {
            attr {
                val w = rowWidth()
                val slot = (w - MainNavColors.itemGap * 3f) / 4f
                val index = ctx.selectedTab.ordinal
                absolutePosition(top = 0f, left = index * (slot + MainNavColors.itemGap))
                width(slot)
                height(MainNavColors.sideItemHeight)
                borderRadius(MainNavColors.sideItemHeight / 2f)
                backgroundColor(MainNavColors.selectedFill)
                animate(Animation.easeInOut(0.28f), ctx.selectedTab)
            }
        }
        View {
            attr {
                absolutePositionAllZero()
                flexDirectionRow()
            }
            tabs.forEachIndexed { i, (tab, asset, activeAsset) ->
                if (i > 0) View { attr { width(MainNavColors.itemGap) } }
                View {
                    attr {
                        flex(1f)
                        height(MainNavColors.sideItemHeight)
                        allCenter()
                    }
                    event { click { ctx.tap(tab) } }
                    Image {
                        attr {
                            size(MainNavColors.iconSize, MainNavColors.iconSize)
                            resizeContain()
                            src((if (ctx.selectedTab == tab) activeAsset else asset).asset())
                        }
                    }
                }
            }
        }
    }
}

/** 挤压态左侧导航卡：t < 0.5 显示淡出中的四图标行，之后换成只显示当前 Tab 的圆球（点它收起播放器）。 */
private fun ViewContainer<*, *>.squeezeNavCard(ctx: MainMorphingNavBarView) {
    val threshold = MainNavColors.squeezeContentFadeThreshold
    glassCard(
        ctx,
        width = { ctx.navWidth },
        radius = { lerpF(MainNavColors.squeezePillRadius, MainNavColors.squeezeCircleRadius, ctx.squeeze) },
        padding = { MainNavColors.innerPadding },
    ) {
        View {
            attr {
                height(MainNavColors.sideItemHeight)
                alignItemsCenter()
                justifyContentCenter()
            }
            vif({ ctx.squeeze < threshold }) {
                View {
                    attr {
                        absolutePosition(top = 0f, left = 0f)
                        opacity((1f - ctx.squeeze / threshold).coerceIn(0f, 1f))
                        touchEnable(ctx.squeeze <= threshold)
                    }
                    navIconRow(ctx) { ctx.navWidth - MainNavColors.innerPadding * 2f }
                }
            }
            vif({ ctx.squeeze >= threshold }) {
                // MainNavOrb 收起态（onCollapsedTap = 收起播放器，展开分支在此用法下不可达）
                View {
                    attr {
                        size(MainNavColors.navOrbSize, MainNavColors.navOrbSize)
                        allCenter()
                        opacity(((ctx.squeeze - threshold) / (1f - threshold)).coerceIn(0f, 1f))
                    }
                    event { click { ctx.collapsePlayerExpanded() } }
                    Image {
                        attr {
                            size(MainNavColors.iconSize, MainNavColors.iconSize)
                            resizeContain()
                            val asset = when (ctx.selectedTab) {
                                MainNavTab.home -> AssetsImage.rootNavHomeActive
                                MainNavTab.mv -> AssetsImage.mvActive
                                MainNavTab.music -> AssetsImage.createActive
                                MainNavTab.library -> AssetsImage.rootNavLibraryActive
                            }
                            src(asset.asset())
                        }
                    }
                }
            }
        }
    }
}

/** 挤压态右侧播放器卡。 */
private fun ViewContainer<*, *>.squeezePlayerCard(ctx: MainMorphingNavBarView) {
    val threshold = MainNavColors.squeezeContentFadeThreshold
    glassCard(
        ctx,
        width = { ctx.playerWidth },
        radius = { lerpF(MainNavColors.squeezePillRadius, MainNavColors.squeezeCircleRadius, 1f - ctx.squeeze) },
        padding = { ctx.playerPadding },
    ) {
        View {
            attr { flex(1f) }
            // 收起 / 展开共用同一个封面球：用 vif 分成两个实例时，每次展开、收起都会销毁重建，
            // 封面图重新加载（占位底色闪一下）、旋转角度也归零。
            View {
                attr {
                    absolutePositionAllZero()
                    justifyContentCenter()
                    // 收起态封面撑满卡片居中；展开态靠左，右边留给标题和控制按钮
                    if (ctx.squeeze < threshold) alignItemsCenter() else alignItemsFlexStart()
                }
                playerOrb(
                    ctx,
                    collapsed = { ctx.squeeze < threshold },
                    size = {
                        if (ctx.squeeze < threshold) {
                            val inner = MainNavColors.barHeight - ctx.playerPadding * 2f
                            minOf(ctx.playerWidth - ctx.playerPadding * 2f, inner)
                        } else {
                            MainNavColors.orbSizeExpanded
                        }
                    },
                    // 收起态点封面展开播放器；展开态点封面进入音乐详情
                    onTap = { if (ctx.squeeze < threshold) ctx.togglePlayerExpanded() else ctx.navigateToSongDetail() },
                )
            }
            vif({ ctx.showExpandedContent }) {
                View {
                    attr {
                        absolutePosition(top = 0f, bottom = 0f, left = MainNavColors.orbSizeExpanded + MainNavColors.itemGap, right = 0f)
                        flexDirectionRow()
                        alignItemsCenter()
                        opacity(ctx.expandedOpacity)
                    }
                    // 标题区：横向拖动切歌、点进入详情
                    View {
                        attr {
                            flex(1f)
                            height(MainNavColors.barHeight)
                            justifyContentCenter()
                            overflow(true)
                        }
                        event {
                            click { ctx.navigateToSongDetail() }
                            pan { ctx.onPan(it.state, it.pageX) }
                        }
                        View {
                            attr {
                                marginLeft(ctx.dragOffset)
                                marginRight(-ctx.dragOffset)
                            }
                            SDMarqueeText {
                                attr {
                                    text = ctx.songTitle
                                    size = 14f
                                }
                            }
                        }
                    }
                    View { attr { width(MainNavColors.itemGap) } }
                    playerControls(ctx)
                }
            }
        }
    }
}

/** Dart `MainPlayerOrb`：圆形封面 + 环形进度；播放时封面 10 秒一圈旋转，暂停保留角度。 */
private fun ViewContainer<*, *>.playerOrb(
    ctx: MainMorphingNavBarView,
    collapsed: () -> Boolean,
    size: () -> Float,
    onTap: () -> Unit,
) {
    // 收起态封面贴边（edge-to-edge），展开态细环
    val stroke = { if (collapsed()) MainNavColors.progressStrokeWidth else MainNavColors.progressStrokeWidthExpanded }
    View {
        attr {
            val s = size()
            size(s, s)
            allCenter()
        }
        event { click { onTap() } }
        MainOrbCover {
            attr {
                // 封面在环内缘内侧：环居中描边，内缘在 size/2 - stroke
                coverSize = { val s = size(); if (collapsed()) s - stroke() * 2f + 1f else s - stroke() * 2f - 1f }
                cover = ctx.songCover
                playing = ctx.isPlaying
            }
        }
        SDRingProgress {
            attr {
                absolutePositionAllZero()
                strokeWidth = stroke()
                progressColor = MainNavColors.progressColor
                trackColor = MainNavColors.progressTrackColor
                progress = ctx.progress.coerceIn(0f, 1f)
            }
        }
    }
}

/**
 * Dart `MainPlayerControls`：上一首 / 播放暂停 / 下一首。
 * 参考是 compact 密度的 IconButton：图标 20，但每个按钮布局占 40×40（M3 最小点击区），三个共 120 宽。
 */
private fun ViewContainer<*, *>.playerControls(ctx: MainMorphingNavBarView) {
    View {
        attr {
            flexDirectionRow()
            alignItemsCenter()
        }
        controlButton({ AssetsImage.navPrev1 }) { PlayerService.launch { PlayerService.playPrevious() } }
        controlButton({ if (ctx.isPlaying) AssetsImage.navPause1 else AssetsImage.navPlay1 }) {
            PlayerService.launch { PlayerService.playOrPause() }
        }
        controlButton({ AssetsImage.navNext1 }) { PlayerService.launch { PlayerService.playNext() } }
    }
}

private fun ViewContainer<*, *>.controlButton(asset: () -> String, onTap: () -> Unit) {
    View {
        attr {
            size(40f, 40f)
            allCenter()
        }
        event { click { onTap() } }
        Image {
            attr {
                size(20f, 20f)
                resizeContain()
                src(asset().asset())
            }
        }
    }
}

// ------------------------------------------------------------------ 旋转封面

class MainOrbCoverAttr : ComposeAttr() {
    var coverSize: () -> Float = { 0f }
    var cover: String by observable("")
    var playing: Boolean by observable(false)
}

/** 播放时匀速旋转（10 秒一圈，与详情页黑胶同速），暂停时停在当前角度、续播不跳变。 */
class MainOrbCoverView : ComposeView<MainOrbCoverAttr, ComposeEvent>() {
    private var angle by observable(0f)
    private var ticking = false
    private var alive = true

    override fun createAttr(): MainOrbCoverAttr = MainOrbCoverAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun viewDidLoad() {
        super.viewDidLoad()
        bindValueChange({ attr.playing }) { if (attr.playing) tick() }
        if (attr.playing) tick()
    }

    override fun viewDestroyed() {
        alive = false
        unbindAllValueChange()
        super.viewDestroyed()
    }

    private fun tick() {
        if (!alive || ticking || !attr.playing) return
        ticking = true
        setTimeout(pagerId, TICK_MS) {
            ticking = false
            if (!alive || !attr.playing) return@setTimeout
            angle = (angle + 360f * TICK_MS / 10000f) % 360f
            tick()
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                val s = ctx.attr.coverSize()
                size(s, s)
                borderRadius(s / 2f)
                transform(Rotate(ctx.angle))
            }
            SDImage {
                attr {
                    absolutePositionAllZero()
                    fit = SDFit.COVER
                    url = ctx.attr.cover
                }
            }
        }
    }

    private companion object {
        const val TICK_MS = 50
    }
}

internal fun ViewContainer<*, *>.MainOrbCover(init: MainOrbCoverView.() -> Unit) {
    addChild(MainOrbCoverView(), init)
}

fun ViewContainer<*, *>.MainMorphingNavBar(init: MainMorphingNavBarView.() -> Unit) {
    addChild(MainMorphingNavBarView(), init)
}
