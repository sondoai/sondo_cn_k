package com.sondo.app.service

import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.base.currentPager
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.LoadingUtil
import com.sondo.app.core.utils.currentPagerId
import com.sondo.app.core.utils.isPagerAlive
import com.sondo.app.core.utils.onPage
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.SongModel
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.timer.setTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 歌曲操作面板（DialogActionsSong）的入参与回调。 */
class SongActionsRequest(
    val item: SongModel,
    /** 面板类型：'my_song' */
    val type: String,
    val onEdit: () -> Unit,
    /** 入参为面板当前展示的收藏态 */
    val onLike: (isCollect: Boolean) -> Unit,
    val onShare: () -> Unit,
    val onPublish: (value: Boolean) -> Unit,
    val onDelete: () -> Unit,
    val onDownload: () -> Unit,
)

/** MV 操作面板（DialogActionsMv）的入参与回调。 */
class MvActionsRequest(
    val item: MvModel,
    /** 面板类型：'mv' */
    val type: String,
    val url: String,
    val onShare: () -> Unit,
    val onPublish: (value: Boolean) -> Unit,
    val onDelete: () -> Unit,
    val onDownload: () -> Unit,
)

/**
 * 作品服务需要的弹层入口。
 *
 * 参考工程里 MusicService / MvService 直接弹 DialogActionsSong、DialogEditSong、DialogActionsMv、
 * ReportTagOptions、MvPreviewUnlockFlow；这些组件属于 UI / 支付层，服务不反向依赖它们，
 * 由对应层在启动时把实现挂到这里。未挂载时对应入口不响应（只记日志）。
 * 面板内的展示数据：歌曲读 [PlayerService.song]（随 Events.PLAYER_SONG_CHANGED 刷新），
 * MV 读 [MvService.currentMv]（随 Events.MV_CURRENT_CHANGED 刷新）。
 */
object WorksUi {
    /** 歌曲「更多」面板。返回弹层句柄，服务用它在需要时关闭面板。 */
    var songActionsSheet: ((pager: BasePager, request: SongActionsRequest) -> DialogHandle?)? = null

    /** 歌曲编辑面板。返回 {title, image?, categoryId?, categoryName?}；取消返回 null。 */
    var editSongSheet: (suspend (pager: BasePager, song: SongModel) -> JSONObject?)? = null

    /** MV「更多」面板。 */
    var mvActionsSheet: ((pager: BasePager, request: MvActionsRequest) -> DialogHandle?)? = null

    /** 举报原因面板，选中后回调原因文案。 */
    var reportOptionsSheet: ((pager: BasePager, onSelect: (String) -> Unit) -> Unit)? = null

    /** 试看解锁流程（MvPreviewUnlockFlow.show）。支付成功后回调 onPaid。 */
    var previewUnlock: ((mvId: Long, needPoint: Int, onPaid: () -> Unit) -> Unit)? = null

    /**
     * 打开创作页（MainController.openCreate）。未挂载时走 [defaultOpenCreate]。
     * page 为 Routes.musicVideoGen / Routes.musicCreate。
     */
    var openCreate: ((page: String, args: JSONObject?) -> Unit)? = null

    internal fun openCreatePage(page: String, args: JSONObject?) {
        val hook = openCreate
        if (hook != null) hook(page, args) else defaultOpenCreate(page, args)
    }

    /**
     * 默认实现：目标创作页已在栈内则回到它并广播 Events.CREATE_ARGUMENTS 灌参数，否则从下至上打开。
     */
    fun defaultOpenCreate(page: String, args: JSONObject?) {
        if (page != Routes.musicVideoGen && page != Routes.musicCreate) return
        val pager = currentPager() ?: AppNav.host ?: return
        if (pager.nav.contains(page)) {
            if (AppNav.currentRoute() != page) pager.nav.popTo(page)
            if (args != null) Svc.emit(Events.CREATE_ARGUMENTS, JSONObject().put("page", page).put("args", args))
            return
        }
        pager.nav.push(page, args)
    }
}

/**
 * UI 流程上下文：记住发起操作的页面。
 *
 * 流程里等过宿主模块（下载、播放器）之后，「当前页面」会变成根页面；
 * 提示、Loading、跳转必须落回发起页，所以一律经这里而不是全局的 currentPager()。
 */
internal class UiCtx(private val origin: BasePager?) {
    /** 发起页仍存活时用它，否则退回当前页面。 */
    val pager: BasePager?
        get() {
            val o = origin
            if (o != null && isPagerAlive(o.pagerId)) return o
            return currentPager()
        }

    /** 发起页的页面名（Get.currentRoute）。 */
    val pageName: String get() = pager?.pageName ?: ""

    /**
     * 在发起页的上下文里执行 UI 操作：响应式字段只通知「当前页面」的观察者，
     * 流程跑在宿主上时直接改发起页的浮层不会刷新，所以切过去再做（已在发起页上则同步执行）。
     */
    private fun onUi(block: (BasePager) -> Unit) {
        val p = pager ?: return
        if (currentPagerId() == p.pagerId) {
            block(p)
        } else {
            setTimeout(p.pagerId, 0) { if (isPagerAlive(p.pagerId)) block(p) }
        }
    }

    fun toast(msg: String) = onUi { it.toast(msg) }

    fun showLoading() = onUi { LoadingUtil.show(pager = it) }

    fun closeLoading() = onUi { LoadingUtil.close() }

    fun push(page: String, args: JSONObject?) = onUi { it.nav.push(page, args) }

    /** Get.back()：发起页不是主框架时关闭它。 */
    fun back() = onUi { p -> if (p.pageName != Routes.root) p.nav.pop() }

    /**
     * 弹系统界面的原生调用（选择器、另存为、分享、写相册）放到发起页上执行，完成后回到调用方上下文。
     * 发起页中途销毁时返回 [onGone]。见 [onPage]。
     */
    suspend fun <T> native(onGone: () -> T, block: suspend () -> T): T = onPage(pager, onGone, block)

    /**
     * 切回发起页的上下文。等过宿主模块之后、调用依赖「当前页面」的代码（权限说明、相册保存等）之前调用。
     */
    suspend fun hop() {
        val p = pager ?: return
        suspendCoroutine<Unit> { cont -> setTimeout(p.pagerId, 0) { cont.resume(Unit) } }
    }

    /** 在发起页上跑一段挂起流程；异常只记日志。 */
    fun launch(block: suspend () -> Unit) {
        val p = pager ?: return
        p.launch {
            try {
                block()
            } catch (e: Throwable) {
                DevLog.logger("ui flow error: ${e.message}")
            }
        }
    }

    companion object {
        fun capture(): UiCtx = UiCtx(currentPager())
    }
}

/** 账号层入口（UserStore）在作品服务里的薄封装。 */
internal object Account {
    const val USAGE_UPLOAD = "upload"
    const val USAGE_SHARE = "share"
    const val USAGE_DOWNLOAD = "download"

    val isLogin: Boolean get() = UserStore.isLogin
    val isSubscribed: Boolean get() = UserStore.isSubscribed
    val userId: Long? get() = UserStore.userInfo?.id

    /** 未登录则拉起登录；返回最终是否已登录。 */
    suspend fun ensureLogin(pager: BasePager? = null): Boolean {
        if (UserStore.isLogin) return true
        return UserStore.showLoginDialog(pager)
    }

    /** 使用声明（上传 / 分享 / 下载），每个 key 只弹一次。 */
    suspend fun showAgree(key: String, type: String, pager: BasePager? = null): Boolean =
        UserStore.showAgree(key, type, pager)

    /** 刷新用户资料（积分退回、解锁后等），不等待结果。 */
    fun refreshUserInfo() {
        Svc.launch { UserStore.getUserInfo() }
    }
}

/**
 * 进行中的操作占位（防重入 + 条目级转圈）。
 *
 * 占位会记住发起页面：流程里有页面级弹窗（使用声明、登录），页面在弹窗打开时被销毁的话
 * 后续代码不会再执行、占位也就没人清，所以发起页已销毁的占位视为失效。
 */
internal class BusyKeys(
    /** 失效占位被清掉时回调（广播最新占位），保证转圈 / 防重入状态不会残留。 */
    private val onStale: (() -> Unit)? = null,
) {
    private val owners = LinkedHashMap<String, String>()
    private var sweeping = false

    fun contains(key: String): Boolean {
        val owner = owners[key] ?: return false
        if (owner.isEmpty() || isPagerAlive(owner)) return true
        owners.remove(key)
        // 不在这里同步回调：调用方可能正处于广播途中
        if (onStale != null) Svc.setTimeout(0) { onStale?.invoke() }
        return false
    }

    fun add(key: String, ui: UiCtx) {
        owners[key] = ui.pager?.pagerId ?: ""
        scheduleSweep()
    }

    /** 流程已转到宿主上跑（一定会走到 finally 清占位），占位不再随发起页失效。 */
    fun claim(key: String) {
        if (owners.containsKey(key)) owners[key] = ""
    }

    fun remove(key: String) {
        owners.remove(key)
    }

    /** 宿主定时巡检：发起页已销毁的占位主动清掉并回调，不等下一次查询。 */
    private fun scheduleSweep() {
        if (sweeping || onStale == null) return
        if (owners.values.none { it.isNotEmpty() }) return
        sweeping = true
        Svc.setTimeout(1000) {
            sweeping = false
            var purged = false
            owners.entries.toList().forEach { (k, owner) ->
                if (owner.isNotEmpty() && !isPagerAlive(owner)) {
                    owners.remove(k)
                    purged = true
                }
            }
            if (purged) onStale?.invoke()
            scheduleSweep()
        }
    }

    /** 当前仍有效的占位。 */
    val keys: Set<String>
        get() {
            owners.keys.toList().forEach { contains(it) }
            return LinkedHashSet(owners.keys)
        }
}
