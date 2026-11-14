package com.sondo.app.ui.works.cropper

import com.sondo.app.base.BasePager
import com.sondo.app.core.i18n.tr
import com.sondo.app.ui.atoms.SDPrimaryButton
import com.sondo.app.ui.dialog.SDSheetHeader
import com.sondo.app.ui.dialog.showAppBottomSheet
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.views.View
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 裁剪结果：`start` / `end` 为 `mm:ss`，`scope` = `"start,end"`（VideoGenParams.subDurationScope 格式）。 */
data class AudioCropResult(val startMs: Long, val endMs: Long, val start: String, val end: String) {
    val scope: String get() = "$start,$end"
    val durationSeconds: Int get() = ((endMs - startMs) / 1000L).toInt()
}

/**
 * 弹层版裁剪器（参考工程只有内嵌的 `AudioCropper`；需要「选完再确认」的入口用这个）：
 * 标题 + [SDAudioCropper] + 确定按钮。确定回调当前区间；关闭 / 删除回调 null。
 */
fun BasePager.showAudioCropper(
    url: String,
    fileName: String,
    musicCoverUrl: String? = null,
    durationMs: Long? = null,
    defaultSelectionSeconds: Int = 0,
    initialSelectionScope: String = "",
    title: String = "",
    confirmText: String? = null,
    onResult: (AudioCropResult?) -> Unit,
): DialogHandle {
    var current: AudioCropResult? = null
    var confirmed: AudioCropResult? = null
    var done = false
    return showAppBottomSheet(onDismiss = {
        if (!done) {
            done = true
            onResult(confirmed)
        }
    }) { handle ->
        View {
            attr { padding(0f, 20f, 16f, 20f) }
            if (title.isNotEmpty()) {
                SDSheetHeader(title) { handle.dismiss() }
                View { attr { height(16f) } }
            }
            SDAudioCropper {
                attr {
                    this.url = url
                    this.fileName = fileName
                    this.musicCoverUrl = musicCoverUrl
                    this.durationMs = durationMs
                    this.defaultSelectionSeconds = defaultSelectionSeconds
                    this.initialSelectionScope = initialSelectionScope
                }
                event {
                    onChange { s, e, sMs, eMs -> current = AudioCropResult(sMs, eMs, s, e) }
                    onClear { handle.dismiss() }
                }
            }
            SDPrimaryButton {
                attr {
                    marginTop(20f)
                    height(44f)
                    text = confirmText ?: "confirm".tr
                }
                event {
                    onTap {
                        val r = current ?: return@onTap
                        confirmed = r
                        handle.dismiss()
                    }
                }
            }
        }
    }
}

/** [showAudioCropper] 的挂起版。 */
suspend fun BasePager.audioCrop(
    url: String,
    fileName: String,
    musicCoverUrl: String? = null,
    durationMs: Long? = null,
    defaultSelectionSeconds: Int = 0,
    initialSelectionScope: String = "",
    title: String = "",
): AudioCropResult? = suspendCoroutine { cont ->
    showAudioCropper(url, fileName, musicCoverUrl, durationMs, defaultSelectionSeconds, initialSelectionScope, title) {
        cont.resume(it)
    }
}
