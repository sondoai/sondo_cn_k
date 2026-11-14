package com.sondo.app.ui.works

import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.DevLog
import com.sondo.app.data.models.ModelDate
import com.sondo.app.data.models.VideoPublishStatus
import com.sondo.app.service.MusicService
import com.sondo.app.service.MvActionsRequest
import com.sondo.app.service.MvService
import com.sondo.app.service.SongActionsRequest
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.SDWeight
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.atoms.SDMarqueeText
import com.sondo.app.ui.atoms.SDSwitch
import com.sondo.app.ui.dialog.SDSheetHeader
import com.sondo.app.ui.dialog.showPlainBottomSheet
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

private fun JSONArray?.containsStr(key: String): Boolean {
    if (this == null || key.isEmpty()) return false
    for (i in 0 until length()) if (optString(i, "") == key) return true
    return false
}

/**
 * 进行中操作结束后自动关闭面板（Dart：`_wasSharing && !isSharing` → Get.back()）。
 * 只有观察到「开始」之后的「结束」才关闭。
 */
private class BusyWatcher(private val handle: () -> DialogHandle?) {
    private var was = false
    fun update(now: Boolean) {
        if (was && !now) {
            val h = handle()
            if (h != null && h.isShowing) h.dismiss()
        }
        was = now
    }
}

// ============================================================================
// DialogActionsSong
// ============================================================================

/**
 * Dart `DialogActionsSong`：歌曲「更多」面板。展示数据读 PlayerService.song（WorksState.actionSong），
 * 分享 / 下载结束后自动关闭（按打开时的歌曲 id 判断）。
 */
fun BasePager.showDialogActionsSong(request: SongActionsRequest): DialogHandle? {
    val pager = this
    val ws = WorksState.of(pager)
    if (ws.actionSong == null) ws.actionSong = request.item
    val songKey = (ws.actionSong ?: request.item).id.toString()
    var handle: DialogHandle? = null
    val shareWatch = BusyWatcher { handle }
    val downWatch = BusyWatcher { handle }
    shareWatch.update(MusicService.sharingUrls.contains(songKey))
    downWatch.update(MusicService.downloadingUrls.contains(songKey))
    pager.on(Events.MUSIC_BUSY_CHANGED) { json ->
        if (handle?.isShowing != true) return@on
        shareWatch.update(json.optJSONArray("sharing").containsStr(songKey))
        downWatch.update(json.optJSONArray("downloading").containsStr(songKey))
    }
    val isMySong = request.type == "my_song"
    handle = pager.showPlainBottomSheet { h ->
        SDSheetHeader("more".tr) { h.dismiss() }
        View { attr { height(24f) } }
        // 歌曲信息
        View {
            attr {
                flexDirectionRow()
                alignItemsCenter()
            }
            SDImage {
                attr {
                    size(60f, 60f)
                    borderRadius(8f)
                    url = ws.actionSong?.musicCoverUrl ?: ""
                }
            }
            View {
                attr {
                    flex(1f)
                    marginLeft(16f)
                }
                SDMarqueeText {
                    attr {
                        size = 14f
                        fontWeight = SDWeight.W700
                        text = ws.actionSong?.musicTitle ?: ""
                    }
                }
                Text {
                    attr {
                        marginTop(8f)
                        val s = ws.actionSong
                        text(ModelDate.formatMMMdyyyy(s?.updateTime ?: (s?.createTime ?: 0L)))
                        fontSize(12f)
                        color(Color(0x8AFFFFFFL))
                    }
                }
            }
            View { attr { width(10f) } }
            vif({ isMySong && ws.actionSong?.musicPublish == 0 }) {
                Image {
                    attr {
                        size(20f, 20f)
                        src(ImageUri.commonAssets(AssetsImage.edit))
                    }
                    event { click { request.onEdit() } }
                }
            }
        }
        View { attr { height(24f) } }
        // 收藏 / 分享
        View {
            attr {
                flexDirectionRow()
                justifyContentSpaceBetween()
            }
            pillAction(
                icon = { if (ws.actionSong?.isCollect == true) AssetsImage.likeActive else AssetsImage.like },
                text = "like".tr,
                loading = { false },
            ) { request.onLike(ws.actionSong?.isCollect ?: false) }
            pillAction(
                icon = { AssetsImage.share },
                text = "share".tr,
                loading = { ws.musicSharing.contains(songKey) },
            ) { if (!ws.musicSharing.contains(songKey)) request.onShare() }
        }
        if (isMySong && AppConstants.enableWorkPublish) {
            View { attr { height(18f) } }
            vif({ ws.actionSong?.publishErrorMsg != null && ws.actionSong?.musicPublish == 3 }) {
                Text {
                    attr {
                        text(ws.actionSong?.publishErrorMsg ?: "")
                        fontSize(12f)
                        color(Color(0xFFF44336L))
                    }
                }
            }
            View {
                attr {
                    padding(6f, 0f, 6f, 0f)
                    flexDirectionRow()
                    alignItemsCenter()
                    justifyContentSpaceBetween()
                }
                View {
                    attr {
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    sheetIcon(AssetsImage.publish, 20f, 20f)
                    sheetLabel("publish".tr, Color(0xBDFFFFFFL), 8f)
                    vif({ ws.actionSong?.musicPublish == 2 }) {
                        sheetLabel("(${"review".tr})", Color(0xBDFFFFFFL), 4f)
                    }
                    vif({ ws.actionSong?.musicPublish == 3 }) {
                        sheetLabel("(${"rejected".tr})", Color(0xFFF44336L), 4f)
                    }
                }
                SDSwitch {
                    attr {
                        scale = 0.7f
                        val p = ws.actionSong?.musicPublish
                        value = p == 1 || p == 2
                    }
                    event {
                        onChanged { v ->
                            DevLog.logger("value: $v")
                            request.onPublish(v)
                        }
                    }
                }
            }
        }
        if (isMySong) {
            vif({ ws.actionSong?.musicGenerateStatus == 3 }) {
                View {
                    attr {
                        padding(10f, 0f, 10f, 0f)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    event { click { if (!ws.musicDownloading.contains(songKey)) request.onDownload() } }
                    vif({ ws.musicDownloading.contains(songKey) }) {
                        View {
                            attr {
                                size(18f, 15f)
                                allCenter()
                            }
                            SDLoading { attr { size = 16f } }
                        }
                    }
                    velse {
                        sheetIcon(AssetsImage.download, 18f, 15f)
                    }
                    sheetLabel("download".tr, Color(0xBDFFFFFFL), 8f)
                }
            }
        }
        View { attr { height(10f) } }
        if (isMySong) {
            vif({ ws.actionSong?.musicPublish != 1 && ws.actionSong?.musicPublish != 2 }) {
                View {
                    attr {
                        padding(6f, 0f, 6f, 0f)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    event { click { request.onDelete() } }
                    sheetIcon(AssetsImage.delete, 20f, 20f)
                    sheetLabel("delete".tr, Color(0xBDFFFFFFL), 8f)
                }
            }
        }
    }
    return handle
}

/** 150×40 胶囊按钮（白 20%，圆角 24）。 */
private fun ViewContainer<*, *>.pillAction(icon: () -> String, text: String, loading: () -> Boolean, onTap: () -> Unit) {
    View {
        attr {
            size(150f, 40f)
            borderRadius(24f)
            backgroundColor(SDColors.white20)
            flexDirectionRow()
            allCenter()
        }
        event { click { onTap() } }
        vif({ loading() }) {
            View {
                attr {
                    size(20f, 20f)
                    allCenter()
                }
                SDLoading { attr { size = 18f } }
            }
        }
        velse {
            Image {
                attr {
                    size(20f, 20f)
                    src(ImageUri.commonAssets(icon()))
                }
            }
        }
        Text {
            attr {
                marginLeft(8f)
                text(text)
                fontSize(14f)
                color(Color.WHITE)
            }
        }
    }
}

private fun ViewContainer<*, *>.sheetIcon(icon: String, w: Float, h: Float) {
    Image {
        attr {
            size(w, h)
            src(ImageUri.commonAssets(icon))
            resizeContain()
        }
    }
}

private fun ViewContainer<*, *>.sheetLabel(text: String, color: Color, left: Float, medium: Boolean = false) {
    Text {
        attr {
            marginLeft(left)
            text(text)
            fontSize(14f)
            color(color)
            if (medium) fontFamily("Poppins-Medium")
        }
    }
}

// ============================================================================
// DialogActionsMv
// ============================================================================

/**
 * Dart `DialogActionsMv`：MV「更多」面板。发布行受 [AppConstants.enableWorkPublish]、分享行受
 * [AppConstants.enableMvDanceShare] 控制（CN 版只剩下载 + 删除）；下载 / 分享结束后自动关闭。
 */
fun BasePager.showDialogActionsMv(request: MvActionsRequest): DialogHandle? {
    val pager = this
    val ws = WorksState.of(pager)
    if (ws.currentMv == null || ws.currentMv?.id != request.item.id) ws.currentMv = request.item
    val url = request.url
    var handle: DialogHandle? = null
    val shareWatch = BusyWatcher { handle }
    val downWatch = BusyWatcher { handle }
    downWatch.update(url.isNotEmpty() && MvService.downloadingUrls.contains(url))
    shareWatch.update(url.isNotEmpty() && MvService.sharingUrls.contains(url))
    pager.on(Events.MV_BUSY_CHANGED) { json ->
        if (handle?.isShowing != true) return@on
        downWatch.update(json.optJSONArray("downloading").containsStr(url))
        if (AppConstants.enableMvDanceShare) shareWatch.update(json.optJSONArray("sharing").containsStr(url))
    }
    handle = pager.showPlainBottomSheet { h ->
        View {
            attr {
                flexDirectionRow()
                alignItemsCenter()
                justifyContentSpaceBetween()
            }
            Text {
                attr {
                    text("more".tr)
                    fontSize(16f)
                    fontWeightBold()
                    color(Color.WHITE)
                }
            }
            View {
                attr { paddingLeft(20f) }
                event {
                    click {
                        DevLog.logger("close")
                        h.dismiss()
                    }
                }
                sheetIcon(AssetsImage.close, 26f, 26f)
            }
        }
        View { attr { height(16f) } }
        if (AppConstants.enableWorkPublish) {
            View {
                attr {
                    padding(6f, 0f, 6f, 0f)
                    flexDirectionRow()
                    alignItemsCenter()
                    justifyContentSpaceBetween()
                }
                View {
                    attr {
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    Image {
                        attr {
                            size(20f, 20f)
                            val p = ws.currentMv?.mvPublish ?: VideoPublishStatus.unpublish
                            src(
                                ImageUri.commonAssets(
                                    when (p) {
                                        VideoPublishStatus.published -> AssetsImage.aUnpulish
                                        VideoPublishStatus.auditing -> AssetsImage.aReview
                                        else -> AssetsImage.aPublish
                                    },
                                ),
                            )
                        }
                    }
                    sheetLabel("publish".tr, SDColors.white80, 8f, medium = true)
                    vif({ ws.currentMv?.mvPublish == VideoPublishStatus.auditing }) {
                        sheetLabel("(${"review".tr})", SDColors.white80, 4f, medium = true)
                    }
                    vif({ ws.currentMv?.mvPublish == VideoPublishStatus.auditFailed }) {
                        sheetLabel("(${"rejected".tr})", Color(0xFFF44336L), 4f, medium = true)
                    }
                }
                SDSwitch {
                    attr {
                        scale = 0.7f
                        val p = ws.currentMv?.mvPublish
                        value = p == VideoPublishStatus.published || p == VideoPublishStatus.auditing
                    }
                    event {
                        onChanged { v ->
                            DevLog.logger("value: $v")
                            request.onPublish(v)
                        }
                    }
                }
            }
        }
        mvActionItem(AssetsImage.aDownload, "download".tr, { url.isNotEmpty() && ws.mvDownloading.contains(url) }) {
            if (!(url.isNotEmpty() && ws.mvDownloading.contains(url))) request.onDownload()
        }
        if (AppConstants.enableMvDanceShare) {
            mvActionItem(AssetsImage.whiteShare, "share".tr, { url.isNotEmpty() && ws.mvSharing.contains(url) }) {
                if (!(url.isNotEmpty() && ws.mvSharing.contains(url))) request.onShare()
            }
        }
        mvActionItem(AssetsImage.aDelete, "delete".tr, { false }) { request.onDelete() }
    }
    return handle
}

/** MV 面板的行：上下 14，图标 20 + 8 + 14 w500 白 80%。 */
private fun ViewContainer<*, *>.mvActionItem(icon: String, text: String, loading: () -> Boolean, onTap: () -> Unit) {
    View {
        attr {
            padding(14f, 0f, 14f, 0f)
            flexDirectionRow()
            alignItemsCenter()
        }
        event { click { onTap() } }
        vif({ loading() }) {
            View {
                attr {
                    size(20f, 20f)
                    allCenter()
                }
                SDLoading { attr { size = 18f } }
            }
        }
        velse {
            sheetIcon(icon, 20f, 20f)
        }
        sheetLabel(text, SDColors.white80, 8f, medium = true)
    }
}

// ============================================================================
// ReportTagOptions
// ============================================================================

/** Dart `ReportTagOptions`（features/music/.../report_tag_options.dart）：举报原因面板，选中后回调原因文案并关闭。 */
fun BasePager.showReportTagOptions(onSelect: (String) -> Unit): DialogHandle {
    val tags = listOf(
        "report_hateful".tr,
        "report_sexual".tr,
        "report_violence".tr,
        "report_copyright".tr,
        "report_political".tr,
        "report_other".tr,
    )
    // Get.bottomSheet 默认 enableDrag: true
    return showPlainBottomSheet(enableDrag = true) { h ->
        SDSheetHeader("report_why".tr) { h.dismiss() }
        View { attr { height(24f) } }
        Scroller {
            attr { height(150f) }
            View {
                attr {
                    flexDirectionRow()
                    flexWrapWrap()
                }
                tags.forEach { tag ->
                    View {
                        attr {
                            height(42f)
                            padding(0f, 16f, 0f, 16f)
                            marginRight(10f)
                            marginBottom(10f)
                            borderRadius(10f)
                            backgroundColor(SDColors.white10)
                            allCenter()
                        }
                        event {
                            click {
                                onSelect(tag)
                                h.dismiss()
                            }
                        }
                        Text {
                            attr {
                                text(tag)
                                fontSize(12f)
                                color(SDColors.white80)
                            }
                        }
                    }
                }
            }
        }
    }
}
