package com.sondo.app.ui.works

import com.sondo.app.base.currentPager
import com.sondo.app.core.utils.DevLog
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.service.AccountHooks
import com.sondo.app.service.AppNav
import com.sondo.app.service.WorksUi
import com.sondo.app.ui.dialog.SDLinks
import com.sondo.app.ui.dialog.UsageStatementType
import com.sondo.app.ui.dialog.showUsageStatement

/**
 * 把本包的弹层实现挂到 service 层的 UI 入口上（幂等；MAIN 在启动时调用）：
 * - `WorksUi.songActionsSheet / editSongSheet / mvActionsSheet / reportOptionsSheet / previewUnlock`
 * - `AccountHooks.showUsageStatement`（UserStore.showAgree 的弹窗）
 * - `SDLinks.opener`：协议链接与 UserStore.openTerms / openPrivacyPolicy 相同：应用内浏览器（url_launcher platformDefault）
 */
object WorksUiInstaller {
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        WorksUi.songActionsSheet = { pager, request -> pager.showDialogActionsSong(request) }
        WorksUi.editSongSheet = { pager, song ->
            pager.showDialogEditSong(
                title = song.musicTitle,
                cover = song.musicCoverUrl,
                musicCategoryId = song.musicCategoryId?.toString(),
                musicCategory = song.musicCategory,
            )
        }
        WorksUi.mvActionsSheet = { pager, request -> pager.showDialogActionsMv(request) }
        WorksUi.reportOptionsSheet = { pager, onSelect -> pager.showReportTagOptions(onSelect) }
        WorksUi.previewUnlock = { mvId, needPoint, onPaid ->
            val pager = currentPager() ?: AppNav.host
            if (pager == null) {
                DevLog.logger("previewUnlock: no pager")
            } else {
                WorksPreviewUnlock.show(pager, mvId, needPoint) { onPaid() }
            }
        }
        AccountHooks.showUsageStatement = { pager, type ->
            val t = when (type) {
                "share" -> UsageStatementType.SHARE
                "download" -> UsageStatementType.DOWNLOAD
                else -> UsageStatementType.UPLOAD
            }
            pager.showUsageStatement(t)
        }
        SDLinks.opener = { url, _ ->
            val pager = currentPager() ?: AppNav.host
            if (pager != null) com.sondo.app.service.UserStore.openInAppUrl(pager, url)
        }
    }
}
