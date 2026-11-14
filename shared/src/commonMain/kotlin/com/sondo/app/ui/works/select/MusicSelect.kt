package com.sondo.app.ui.works.select

import com.sondo.app.base.BasePager
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.storage.Sp
import com.sondo.app.data.models.AudioUploadItem
import com.sondo.app.data.models.SongModel
import com.sondo.app.service.UserStore
import com.sondo.app.ui.dialog.showAppBottomSheet
import com.sondo.app.ui.overlay.DialogHandle
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 选曲弹窗 Tab（Dart `MusicSelectTab`，下标即持久化的值）。 */
enum class MusicSelectTab { my, upload, trending }

/**
 * 选曲弹窗结果（Dart 返回 dynamic）：
 * - [Song]：选中歌曲（我的 / 热门 / 已完成上传），或本地文件秒传成功
 * - [Upload]：本地上传已开始，调用方自己跟 `OssUploadService.tasks` / `Events.UPLOAD_TASKS_CHANGED` 的进度
 * - [TryNow]：我的 Tab 空态点 Try Now（Dart 返回 1）→ 调用方跳音乐创作
 * 取消为 null。
 */
sealed class MusicSelectResult {
    class Song(val song: SongModel) : MusicSelectResult()
    class Upload(val item: AudioUploadItem) : MusicSelectResult()
    object TryNow : MusicSelectResult()
}

/** 弹层最大高度占屏高比例。 */
private const val SHEET_HEIGHT_RATIO = 0.6f

/** 进行中的弹窗（Dart 串行化 show：同一时刻只有一个选曲弹窗）。 */
private var ongoingHandle: DialogHandle? = null

/**
 * Dart `MusicSelectBottomSheet.show(type:, initialTab:)`。
 *
 * 初始 Tab：未登录固定热门（且不显示 Tab 栏）；已登录 [initialTab] ?? 上次的 Tab（`StorageKey.musicSelectLastTab`）?? 热门。
 * 打开时暂停主播放器；关闭时停止试听。
 * @param type 业务类型（目前只有 "mv"），用于缓存 key 与上传统计
 * @param onSelected 关闭后回调；取消为 null
 * @return 已有选曲弹窗显示时返回那一个（新的调用直接回调 null，等价于 Dart 的串行等待后再开）
 */
fun BasePager.showMusicSelect(
    type: String,
    initialTab: MusicSelectTab? = null,
    onSelected: (MusicSelectResult?) -> Unit,
): DialogHandle {
    ongoingHandle?.let {
        if (it.isShowing) {
            onSelected(null)
            return it
        }
    }
    val isLogin = UserStore.isLogin
    // 已登录且无本地记录时默认热门；未登录固定热门
    val resolvedTab = if (isLogin) initialTab ?: readLastMusicSelectTab() ?: MusicSelectTab.trending else MusicSelectTab.trending
    if (isLogin) Sp.setInt(StorageKey.musicSelectLastTab, resolvedTab.ordinal)

    var result: MusicSelectResult? = null
    var state: MusicSelectState? = null
    val handle = showAppBottomSheet(
        maxHeight = screenHeight * SHEET_HEIGHT_RATIO,
        keyboardAware = true,
        onDismiss = {
            state?.dispose()
            ongoingHandle = null
            onSelected(result)
        },
    ) { h ->
        val s = MusicSelectState(this@showMusicSelect, type, resolvedTab) { r ->
            result = r
            h.dismiss()
        }
        state = s
        MusicSelectSheet {
            attr {
                this.state = s
                sheetHeight = this@showMusicSelect.screenHeight * SHEET_HEIGHT_RATIO
            }
        }
        s.start()
    }
    ongoingHandle = handle
    return handle
}

/** [showMusicSelect] 的挂起版：取消返回 null。 */
suspend fun BasePager.musicSelect(type: String, initialTab: MusicSelectTab? = null): MusicSelectResult? =
    suspendCoroutine { cont -> showMusicSelect(type, initialTab) { cont.resume(it) } }

/** 读取上次选中的 Tab；索引非法时返回 null。 */
internal fun readLastMusicSelectTab(): MusicSelectTab? {
    val index = Sp.getInt(StorageKey.musicSelectLastTab, -1)
    if (index < 0 || index >= MusicSelectTab.values().size) return null
    return MusicSelectTab.values()[index]
}
