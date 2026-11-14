package com.sondo.app.ui.works

import com.sondo.app.base.BasePager
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.core.utils.CommonUtils
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.LoadingUtil
import com.sondo.app.data.api.FileUploadApi
import com.sondo.app.data.api.ModerationApi
import com.sondo.app.data.models.ImageUploadResult
import com.sondo.app.data.models.ImageUploadStatus
import com.sondo.app.service.UserStore
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.atoms.SDGradientButton
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.dialog.SDSheetContainerView
import com.sondo.app.ui.input.SDInput
import com.sondo.app.ui.input.hideKeyboard
import com.sondo.app.ui.overlay.DialogEntry
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 编辑弹层（歌曲 / 用户）的页面级状态。 */
private class EditSheetState(pager: BasePager, title: String, cover: String, catId: String?, catName: String?) {
    var title by pager.observable(title)
    var imageUrl by pager.observable(cover)
    /** 未通过 / 上传失败时显示的本地图（Dart `_imageFile`） */
    var imageFile by pager.observable("")
    var isImageLoading by pager.observable(false)
    var isHasSensitive by pager.observable(false)
    var message by pager.observable("")
    var categoryId by pager.observable<String?>(catId)
    var categoryName by pager.observable<String?>(catName)
    var isSubmitting = false
    var alive = true
}

/**
 * 底部弹层外壳（与 showPlainBottomSheet 同款：顶部圆角 16、无拖拽条），可换底色并带键盘避让。
 * DialogEditUser 的底色 #2A2139 与其它弹层不同，所以不能直接用 showPlainBottomSheet。
 */
private fun BasePager.showEditSheet(
    bg: Color,
    onDismiss: () -> Unit,
    builder: ViewContainer<*, *>.(DialogHandle) -> Unit,
): DialogHandle {
    val limit = screenHeight * 0.85f
    return showDialog(
        align = DialogEntry.ALIGN_BOTTOM,
        maskColor = Color(0, 0, 0, 0.6f),
        onDismiss = onDismiss,
    ) { handle ->
        addChild(SDSheetContainerView()) {
            attr {
                this.handle = handle
                this.builder = { h ->
                    View {
                        attr { padding(16f) }
                        builder(h)
                    }
                }
                this.maxHeight = limit
                this.enableDrag = false
                this.showHandle = false
                this.keyboardAware = true
                this.topRadius = 16f
                this.bgColor = bg
            }
        }
    }
}

/**
 * 选图 → 上传 + 审核（最多重试 3 次，间隔 1s，仅「普通失败」重试）。
 * @param onSuccess 上传成功（DialogEditUser 在这里打 avatar_edit 埋点）
 */
private suspend fun pickAndUpload(pager: BasePager, st: EditSheetState, onSuccess: () -> Unit = {}) {
    // 同意弹窗和系统相册都可能在弹层关闭后返回，之后不再写状态
    val isAgree = UserStore.showAgree(StorageKey.isAgreeUpload, "upload", pager)
    if (!st.alive || !isAgree) return
    val image = CommonUtils.pickImage(
        CommonUtils.SOURCE_GALLERY,
        pager = pager,
        callback = { type -> if (type == "pick_image_success" && st.alive) st.isImageLoading = true },
    )
    if (!st.alive) return
    if (image == null) {
        st.isImageLoading = false
        return
    }
    var retryCount = 0
    var result: ImageUploadResult = FileUploadApi.uploadImageWithCheck(image).getOrNull() ?: ImageUploadResult.failure()
    if (!st.alive) return
    while (retryCount < 3 && result.status == ImageUploadStatus.failure && result.size == null) {
        retryCount++
        pager.lifecycleScope.delay(1000)
        if (!st.alive) return
        result = FileUploadApi.uploadImageWithCheck(image).getOrNull() ?: ImageUploadResult.failure()
        if (!st.alive) return
    }
    when (result.status) {
        ImageUploadStatus.success -> {
            onSuccess()
            st.imageFile = ""
            st.imageUrl = result.url ?: ""
            st.isHasSensitive = false
        }
        ImageUploadStatus.check -> {
            st.isHasSensitive = true
            st.imageUrl = ""
            st.imageFile = image
            st.message = "violation_detected".tr
        }
        ImageUploadStatus.failure -> {
            val size = result.size
            if (size != null) pager.toast("the_image_size_cannot_exceed".trParams("value" to "$size"))
            st.imageFile = image
        }
    }
    if (!st.alive) return
    st.isImageLoading = false
}

/** 字段标题行：图标 20 + 8 + 14 w500。 */
private fun ViewContainer<*, *>.fieldLabel(icon: String, label: String) {
    View {
        attr {
            flexDirectionRow()
            alignItemsCenter()
        }
        Image {
            attr {
                size(20f, 20f)
                src(ImageUri.commonAssets(icon))
            }
        }
        Text {
            attr {
                marginLeft(8f)
                text(label)
                fontSize(14f)
                fontWeight500()
                color(Color.WHITE)
            }
        }
    }
}

/** 标题行：左标题 16 bold，右 Material close 24。 */
private fun ViewContainer<*, *>.editHeader(title: String, onClose: () -> Unit) {
    View {
        attr {
            flexDirectionRow()
            alignItemsCenter()
            justifyContentSpaceBetween()
        }
        Text {
            attr {
                text(title)
                fontSize(16f)
                fontWeightBold()
                color(Color.WHITE)
            }
        }
        View {
            attr {
                size(24f, 24f)
                allCenter()
            }
            event { click { onClose() } }
            // close.png 的 × 只占画布 50%，Material close 占 58%：图放到 28（≈ Icons.close 24 的视觉大小），布局仍占 24
            Image {
                attr {
                    size(28f, 28f)
                    src(ImageUri.commonAssets(AssetsImage.close))
                }
            }
        }
    }
}

/** 封面 / 头像选择卡：132 高，中间 104×104 预览；审核不通过时底部红字。 */
private fun ViewContainer<*, *>.coverPicker(st: EditSheetState, fill: Color, radius: Float, onTap: () -> Unit) {
    View {
        attr {
            height(132f)
            padding(14f)
            backgroundColor(fill)
            borderRadius(radius)
            allCenter()
        }
        event { click { onTap() } }
        View {
            attr {
                size(104f, 104f)
                borderRadius(10f)
                backgroundColor(Color(0x61FFFFFFL))
                allCenter()
            }
            vif({ st.imageFile.isNotEmpty() || st.imageUrl.isNotEmpty() }) {
                SDImage {
                    attr {
                        absolutePositionAllZero()
                        placeholder = false
                        url = if (st.imageFile.isNotEmpty()) st.imageFile else st.imageUrl
                    }
                }
            }
            vif({ st.isImageLoading }) {
                SDLoading {
                    attr {
                        size = 36f
                        color = SDColors.brandPurple
                    }
                }
            }
            vif({ st.imageFile.isEmpty() && st.imageUrl.isEmpty() }) {
                View {
                    attr { allCenter() }
                    Image {
                        attr {
                            size(40f, 40f)
                            src(ImageUri.commonAssets(AssetsImage.picture))
                        }
                    }
                    Text {
                        attr {
                            marginTop(8f)
                            text("click_to_upload".tr)
                            fontSize(12f)
                            color(Color(0x8AFFFFFFL))
                        }
                    }
                }
            }
        }
    }
    // Dart 把红字定位在卡片下沿 -15；这里放在卡片下方占同样的位置
    vif({ st.isHasSensitive && st.message.isNotEmpty() }) {
        Text {
            attr {
                marginTop(2f)
                text(st.message)
                fontSize(12f)
                color(Color(0xFFF44336L))
                textAlignCenter()
            }
        }
    }
}

private fun ViewContainer<*, *>.editButtons(
    btnHeight: Float,
    submitDisabled: () -> Boolean,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    View {
        attr {
            flexDirectionRow()
            alignItemsCenter()
        }
        SDGradientButton {
            attr {
                flex(1f)
                padding(0f, 0f, 0f, 0f)
                height(btnHeight)
                text = "submit".tr
                disabled = submitDisabled()
            }
            event { onTap { onSubmit() } }
        }
        View { attr { width(16f) } }
        SDGradientButton {
            attr {
                flex(1f)
                padding(0f, 0f, 0f, 0f)
                height(btnHeight)
                gradientColors = listOf(Color(0x33FFFFFFL), Color(0x33FFFFFFL))
                textColor = UIColor.cFFF60
                text = "cancel".tr
            }
            event { onTap { onCancel() } }
        }
    }
}

// ============================================================================
// DialogEditSong
// ============================================================================

/**
 * Dart `DialogEditSong`：编辑歌名 / 封面 / 曲风。返回 `{title, image, categoryId, categoryName}`，取消返回 null。
 * 曲风列表来自 `UserStore.musicGenerList`（`{genreId, genre}`）。
 */
suspend fun BasePager.showDialogEditSong(
    title: String? = null,
    cover: String? = null,
    musicCategoryId: String? = null,
    musicCategory: String? = null,
): JSONObject? = suspendCoroutine { cont ->
    val pager = this
    val st = EditSheetState(pager, title ?: "", cover ?: "", musicCategoryId, musicCategory)
    var done = false
    val finish: (JSONObject?) -> Unit = { r ->
        if (!done) {
            done = true
            st.alive = false
            cont.resume(r)
        }
    }
    DevLog.logger("${st.imageFile}/$cover")
    val genres = UserStore.musicGenerList
    var handle: DialogHandle? = null
    fun close(r: JSONObject?) {
        finish(r)
        handle?.dismiss()
    }
    fun submit() {
        val titleText = st.title
        if (titleText.isEmpty()) {
            pager.toast("enter_song_name".tr)
            return
        }
        pager.launch {
            LoadingUtil.show(pager = pager)
            val textResult = ModerationApi.checkText(titleText).getOrNull() ?: ""
            LoadingUtil.close()
            if (!st.alive) return@launch
            if (textResult.isNotEmpty()) {
                pager.toast(textResult)
                return@launch
            }
            if (st.isImageLoading) {
                pager.toast("image_checking".tr)
                return@launch
            }
            if (st.isHasSensitive) {
                pager.toast("image_sensitive_copyrighted_cannot_use".tr)
                return@launch
            }
            val image = if (st.imageUrl.isNotEmpty()) st.imageUrl else cover
            close(
                JSONObject()
                    .put("title", st.title)
                    .put("image", image)
                    .put("categoryId", st.categoryId)
                    .put("categoryName", st.categoryName),
            )
        }
    }
    handle = pager.showEditSheet(SDColors.sheetBg, onDismiss = { finish(null) }) { _ ->
        View {
            event { click { pager.hideKeyboard() } }
            editHeader("edit_songs".tr) { close(null) }
            View { attr { height(20f) } }
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                    justifyContentSpaceBetween()
                }
                fieldLabel(AssetsImage.edit1, "song_name".tr)
                Text {
                    attr {
                        text("${st.title.length}/30")
                        fontSize(12f)
                        color(Color.WHITE)
                    }
                }
            }
            View { attr { height(8f) } }
            SDInput {
                attr {
                    text = st.title
                    placeholder = "enter_song_name".tr
                    fillColor = UIColor.cFFF10
                    borderRadius = 16f
                    maxLength = 30
                    hintColor = Color(0x8AFFFFFFL)
                }
                event { onChanged { st.title = it } }
            }
            View { attr { height(16f) } }
            fieldLabel(AssetsImage.picture, "song_cover".tr)
            View { attr { height(8f) } }
            coverPicker(st, UIColor.cFFF10, 16f) { pager.launch { pickAndUpload(pager, st) } }
            View { attr { height(8f) } }
            fieldLabel(AssetsImage.musicStyle, "song_style".tr)
            View { attr { height(8f) } }
            List {
                attr {
                    height(50f)
                    flexDirectionRow()
                    padding(8f, 0f, 8f, 0f)
                    showScrollerIndicator(false)
                }
                genres.forEachIndexed { index, item ->
                    val gid = item.optString("genreId", "")
                    val gname = item.optString("genre", "")
                    View {
                        attr {
                            // 横向 ListView 高 50、上下 padding 8 → 子项交叉轴被强制为 34；Container 无 alignment，文字左上
                            height(34f)
                            padding(6f, 10f, 6f, 10f)
                            if (index > 0) marginLeft(10f)
                            borderRadius(10f)
                            backgroundColor(if (st.categoryId == gid) UIColor.cFFFFFF else SDColors.white10)
                        }
                        event {
                            click {
                                st.categoryId = gid
                                st.categoryName = gname
                            }
                        }
                        Text {
                            attr {
                                text(gname)
                                fontSize(14f)
                                fontWeight500()
                                color(if (st.categoryId == gid) UIColor.c1C1C1C else UIColor.cFFFFFF)
                            }
                        }
                    }
                }
            }
            View { attr { height(20f) } }
            editButtons(38f, { st.title.isEmpty() }, { submit() }, { close(null) })
        }
    }
}

// ============================================================================
// DialogEditUser
// ============================================================================

/** Dart `DialogEditUser`：编辑昵称 / 头像（底色 #2A2139）。返回 `{title, image}`，取消返回 null。 */
suspend fun BasePager.showDialogEditUser(title: String? = null, cover: String? = null): JSONObject? = suspendCoroutine { cont ->
    val pager = this
    val st = EditSheetState(pager, title ?: "", cover ?: "", null, null)
    var done = false
    val finish: (JSONObject?) -> Unit = { r ->
        if (!done) {
            done = true
            st.alive = false
            cont.resume(r)
        }
    }
    var handle: DialogHandle? = null
    fun close(r: JSONObject?) {
        finish(r)
        handle?.dismiss()
    }
    fun submit() {
        if (st.isSubmitting) return
        if (st.isImageLoading) {
            pager.toast("image_checking".tr)
            return
        }
        if (st.isHasSensitive) {
            pager.toast("image_sensitive_copyrighted_cannot_use".tr)
            return
        }
        val text = st.title
        if (text.isEmpty()) {
            pager.toast("enter_nickname".tr)
            return
        }
        st.isSubmitting = true
        pager.launch {
            LoadingUtil.show(pager = pager)
            val textCheckResult = ModerationApi.checkText(text).getOrNull() ?: ""
            LoadingUtil.close()
            if (!st.alive) return@launch
            st.isSubmitting = false
            if (textCheckResult.isNotEmpty()) {
                pager.toast(textCheckResult)
                return@launch
            }
            close(JSONObject().put("title", text).put("image", st.imageUrl))
        }
    }
    val fill = Color(0x3394ADFFL)
    handle = pager.showEditSheet(Color(0xFF2A2139L), onDismiss = { finish(null) }) { _ ->
        View {
            event { click { pager.hideKeyboard() } }
            editHeader("edit".tr) { close(null) }
            View { attr { height(20f) } }
            View {
                attr {
                    flexDirectionRow()
                    alignItemsCenter()
                    justifyContentSpaceBetween()
                }
                fieldLabel(AssetsImage.edit1, "nickname".tr)
                Text {
                    attr {
                        text("${st.title.length}/30")
                        fontSize(12f)
                        color(Color.WHITE)
                    }
                }
            }
            View { attr { height(8f) } }
            SDInput {
                attr {
                    text = st.title
                    placeholder = "enter_nickname".tr
                    fillColor = fill
                    borderRadius = 10f
                    maxLength = 30
                    hintColor = Color(0x8AFFFFFFL)
                }
                event { onChanged { st.title = it } }
            }
            View { attr { height(16f) } }
            fieldLabel(AssetsImage.picture, "avatar".tr)
            View { attr { height(8f) } }
            coverPicker(st, fill, 10f) {
                pager.launch { pickAndUpload(pager, st) { UmengUtils.logEventButtonClick("avatar_edit") } }
            }
            View { attr { height(20f) } }
            // Dart dialog_edit_user：disabled: _titleController.text.isEmpty
            editButtons(40f, { st.title.isEmpty() }, { submit() }, { close(null) })
            View { attr { height(10f) } }
        }
    }
}
