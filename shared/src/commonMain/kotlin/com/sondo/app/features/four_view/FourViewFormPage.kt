package com.sondo.app.features.four_view

import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.whiteA
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.core.utils.CommonUtils
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.delayMs
import com.sondo.app.data.models.ImageUploadResult
import com.sondo.app.data.models.ImageUploadStatus
import com.sondo.app.features.four_view.data.FourViewApi
import com.sondo.app.features.four_view.data.FourViewEntity
import com.sondo.app.service.ConfigService
import com.sondo.app.service.UserStore
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.ui.atoms.SDAssetImage
import com.sondo.app.ui.atoms.SDButton
import com.sondo.app.ui.atoms.SDIconButton
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDUploadImage
import com.sondo.app.ui.atoms.SDUploadImageStatus
import com.sondo.app.ui.atoms.SDUploadImageView
import com.sondo.app.ui.dialog.SDPopupMenuItem
import com.sondo.app.ui.dialog.showConfirmDialog
import com.sondo.app.ui.dialog.showPopupMenu
import com.sondo.app.ui.input.SDKeyboardAwareScroll
import com.sondo.app.ui.input.SDTextArea
import com.sondo.app.ui.input.SDTextAreaView
import com.sondo.app.ui.input.hideKeyboard
import com.sondo.app.ui.nav.SDNavBar
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 四视图角色添加 / 编辑表单页（Dart: four_view/presentation/pages/form/form_page.dart + four_view_form_controller.dart）。
 *
 * 参数（Dart `FourViewFormArgs`）：`{role?: RoleModel json}` —— 带 role 为编辑模式（重新生成 / 删除），否则新增。
 * 结果：创建或删除成功后 `nav.pop({result: true})`；直接返回为 null。
 */
@Page(Routes.fourViewForm)
internal class FourViewFormPage : BasePager() {

    /** 编辑模式下的角色，null 表示新增。 */
    var role: FourViewEntity? = null
    val isEditMode: Boolean get() = role != null

    var description by observable("")

    /** 本地待上传图片路径。 */
    var avatarFile by observable<String?>(null)

    /** 图片上传/检测中。 */
    var imageLoading by observable(false)

    /** 图片涉黄/违规。 */
    var isHasSensitive by observable(false)

    /** 违规原因展示文案。 */
    var errorMessage by observable("")

    /** 违规 toast 提示。 */
    private var errMsgToast = ""

    /** 上传失败标记。 */
    var isFailed by observable(false)

    /** 已上传通过的参考图 URL。 */
    var avatarUrl by observable("")

    /** 提交中防重入。 */
    var isSubmitting by observable(false)

    var genPoint by observable(ConfigService.genFourViewImgPoint)

    private var uploadRef: ViewRef<SDUploadImageView>? = null
    private var areaRef: ViewRef<SDTextAreaView>? = null

    override fun created() {
        super.created()
        val roleJson = pagerData.params.optJSONObject("role")
        if (roleJson != null) {
            val r = FourViewEntity.fromJson(roleJson)
            role = r
            description = r.roleImgDesc
            avatarUrl = r.coverImage
        }
        on(Events.CONFIG_CHANGED) { genPoint = ConfigService.genFourViewImgPoint }
    }

    // ------------------------------------------------------------------ 参考图

    /** HandleOptionWidget：相机 / 相册菜单，锚在参考图下方。 */
    fun showImageOptions() {
        val view = uploadRef?.view
        var x = 20f
        var y = statusBarHeight + 200f
        if (view != null) {
            val f = view.convertFrame(view.frame, null)
            x = f.x
            y = f.y + f.height + 4f
        }
        showPopupMenu(
            x,
            y,
            listOf(
                SDPopupMenuItem("camera".tr, "camera", AssetsImage.cameraIcon),
                SDPopupMenuItem("album".tr, "album", AssetsImage.albumIcon),
            ),
        ) { item ->
            if (item.id == "camera") {
                onUploadImage(CommonUtils.SOURCE_CAMERA)
                DevLog.logger("camera action")
            } else {
                onUploadImage(CommonUtils.SOURCE_GALLERY)
                DevLog.logger("album action")
            }
        }
    }

    /** 从相机/相册选取参考图并上传。 */
    fun onUploadImage(source: String) {
        val page = this
        launch {
            val isAgree = UserStore.showAgree(StorageKey.isAgreeUpload, "upload", page)
            if (!isAgree) return@launch
            UmengUtils.logEventButtonClick("role_img_replace")
            // 模型参考图宽高比须在 0.4–2.5，超限直接拦截不上传
            val image = CommonUtils.pickImage(
                source,
                pager = page,
                callback = { type -> if (type == "pick_image_success") imageLoading = true },
                isCheckImageAspectRatio = true,
                isCheckImageHW = true,
            )
            if (image == null) {
                imageLoading = false
                return@launch
            }
            uploadImage(image)
        }
    }

    /** 上传参考图并做敏感检测；仅普通 failure 最多重试 3 次。 */
    fun uploadImage(image: String) {
        launch {
            avatarFile = image
            isHasSensitive = false
            imageLoading = true
            var retryCount = 0
            var result = FourViewApi.uploadImageWithCheck(image)
            while (retryCount < 3) {
                val upload = result.getOrNull()
                val shouldRetry = upload == null || (upload.status == ImageUploadStatus.failure && upload.size == null)
                if (!shouldRetry) break
                retryCount++
                delayMs(1000)
                result = FourViewApi.uploadImageWithCheck(image)
            }
            val upload: ImageUploadResult? = result.getOrNull()
            if (upload == null) {
                isFailed = true
            } else {
                when (upload.status) {
                    ImageUploadStatus.success -> {
                        errorMessage = ""
                        avatarFile = null
                        isHasSensitive = false
                        isFailed = false
                        avatarUrl = upload.url ?: ""
                    }
                    ImageUploadStatus.check -> {
                        errorMessage = "violation_detected".tr
                        errMsgToast = "image_sensitive_copyrighted_cannot_use".tr
                        isHasSensitive = true
                        avatarUrl = ""
                        avatarFile = image
                    }
                    ImageUploadStatus.failure -> {
                        if (upload.size != null) {
                            toast("the_image_size_cannot_exceed".trParams("value" to "${upload.size}"))
                        }
                        isFailed = true
                    }
                }
            }
            imageLoading = false
        }
    }

    /** 清除已选参考图。 */
    fun removeImage() {
        isFailed = false
        avatarUrl = ""
        avatarFile = null
        isHasSensitive = false
        imageLoading = false
    }

    // ------------------------------------------------------------------ 提交 / 删除

    /** 提交创建/重新生成角色。 */
    fun submit() {
        if (isHasSensitive) {
            toast(errMsgToast)
            return
        }
        if (imageLoading) {
            toast("image_checking".tr)
            return
        }
        if (isFailed) {
            toast("role_form_upload_failed".tr)
            return
        }
        if (isSubmitting) return

        val desc = description.trim()
        if (desc.isEmpty()) {
            toast("role_form_desc_required".tr)
            return
        }

        isSubmitting = true
        showLoading()
        launch {
            val violation = FourViewApi.checkText(desc).getOrNull() ?: ""
            if (violation.isNotEmpty()) {
                isSubmitting = false
                hideLoading()
                toast(violation)
                return@launch
            }
            val uploadedUrl = avatarUrl
            val success = FourViewApi.create(desc, if (uploadedUrl.isNotEmpty()) uploadedUrl else null).getOrNull() ?: false
            // 积分已扣，刷新余额
            launch { UserStore.getUserInfo() }
            isSubmitting = false
            hideLoading()
            if (success) {
                toast("role_form_add_success".tr)
                nav.pop(JSONObject().put("result", true))
            } else {
                toast("role_form_submit_failed".tr)
            }
        }
    }

    /** 删除当前编辑的角色。 */
    fun deleteRole() {
        val r = role ?: return
        showConfirmDialog(
            content = "role_form_delete_confirm".tr,
            title = "role_form_delete_title".tr,
            confirmText = "delete".tr,
        ) {
            showLoading()
            launch {
                val success = FourViewApi.delete(r.id).getOrNull() ?: false
                hideLoading()
                if (success) {
                    toast("role_form_delete_success".tr)
                    nav.pop(JSONObject().put("result", true))
                } else {
                    toast("role_form_delete_failed".tr)
                }
            }
        }
    }

    // ------------------------------------------------------------------ UI

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            attr { flex(1f) }
            event { click { ctx.hideKeyboard() } }
            SDNavBar {
                attr {
                    title = if (ctx.isEditMode) "role_form_edit_title".tr else "role_form_add_title".tr
                    titleSize = 18f
                    if (ctx.isEditMode) {
                        actions = {
                            SDIconButton {
                                attr {
                                    icon = AssetsImage.delete
                                    iconSize = 22f
                                }
                                event { onTap { ctx.deleteRole() } }
                            }
                        }
                    }
                }
            }
            SDKeyboardAwareScroll {
                attr {
                    flex(1f)
                    // Recreate 底栏：上下 padding 8 + 按钮 44
                    marginBottom = 60f
                }
                content {
                    View {
                        attr { padding(0f, 20f, 0f, 20f) }
                        val portrait = ctx.role?.roleImg ?: ""
                        if (ctx.isEditMode && portrait.isNotEmpty()) {
                            SDImage {
                                attr {
                                    height((ctx.screenWidth - 40f) * 9f / 16f)
                                    borderRadius(16f)
                                    url = portrait
                                }
                            }
                        }
                        if (ctx.isEditMode) View { attr { height(20f) } }
                        // 参考图
                        Text {
                            attr {
                                text("role_form_reference_picture".tr)
                                fontSize(14f)
                                fontFamily("Poppins-SemiBold")
                                color(whiteA(0.8f))
                            }
                        }
                        View {
                            attr {
                                marginTop(8f)
                                flexDirectionRow()
                                alignItemsCenter()
                            }
                            SDUploadImage {
                                ref { ctx.uploadRef = it }
                                attr {
                                    size(60f, 60f)
                                    radius = 12f
                                    url = ctx.avatarFile ?: ctx.avatarUrl
                                    status = when {
                                        ctx.imageLoading -> SDUploadImageStatus.UPLOADING
                                        ctx.isFailed -> SDUploadImageStatus.FAILED
                                        else -> SDUploadImageStatus.NONE
                                    }
                                }
                                event {
                                    onEmptyTap {
                                        if (ctx.avatarUrl.isEmpty()) {
                                            ctx.hideKeyboard()
                                            ctx.showImageOptions()
                                        }
                                    }
                                    onImageTap {
                                        if (ctx.avatarUrl.isNotEmpty()) {
                                            ctx.hideKeyboard()
                                            ctx.showImageOptions()
                                        }
                                    }
                                    onClear { ctx.removeImage() }
                                    onRetry {
                                        DevLog.logger("onRetry")
                                        val image = ctx.avatarFile
                                        if (image != null) ctx.uploadImage(image)
                                    }
                                }
                            }
                            vif({ ctx.isHasSensitive }) {
                                Text {
                                    attr {
                                        marginLeft(10f)
                                        text(ctx.errorMessage)
                                        fontSize(12f)
                                        fontFamily("Poppins-Medium")
                                        color(Color.RED)
                                    }
                                }
                            }
                        }
                        View { attr { height(20f) } }
                        // 描述
                        Text {
                            attr {
                                text("role_form_description".tr)
                                fontSize(14f)
                                fontFamily("Poppins-SemiBold")
                                color(whiteA(0.8f))
                            }
                        }
                        View {
                            attr {
                                marginTop(12f)
                                backgroundColor(UIColor.cFFF10)
                                borderRadius(12f)
                            }
                            SDTextArea {
                                ref { ctx.areaRef = it }
                                attr {
                                    // 9 行 × 18 行高
                                    areaHeight = 162f
                                    radius = 12f
                                    fillColor = Color.TRANSPARENT
                                    fontSize = 12f
                                    lineHeightDp = 18f
                                    hintColor = whiteA(0.3f)
                                    maxLength = MAX_DESC_LENGTH
                                    showCounter = false
                                    // Dart EdgeInsets.fromLTRB(16, 14, 16, 0)
                                    contentPaddingLeft = 16f
                                    contentPaddingTop = 14f
                                    contentPaddingRight = 16f
                                    contentPaddingBottom = 0f
                                    placeholder = "role_form_desc_hint".tr
                                    text = ctx.description
                                }
                                event { onChanged { ctx.description = it } }
                            }
                            View {
                                attr {
                                    height(30f)
                                    paddingRight(16f)
                                    flexDirectionRow()
                                    alignItemsCenter()
                                    justifyContentFlexEnd()
                                }
                                vif({ ctx.description.isNotEmpty() }) {
                                    View {
                                        attr { padding(0f, 6f, 0f, 6f) }
                                        event {
                                            click {
                                                ctx.description = ""
                                                ctx.areaRef?.view?.setText("")
                                            }
                                        }
                                        SDAssetImage(AssetsImage.inputClear, 14f, tint = whiteA(0.4f))
                                    }
                                }
                                Text {
                                    attr {
                                        text("${ctx.description.length}/$MAX_DESC_LENGTH")
                                        fontSize(10f)
                                        fontFamily("Poppins")
                                        color(whiteA(0.4f))
                                    }
                                }
                            }
                        }
                        View { attr { height(20f) } }
                    }
                }
            }
            // 底部按钮：新增显示「生成」，编辑已有角色才是「重新生成」
            SDButton {
                attr {
                    padding(8f, 20f, 8f + ctx.safeBottom, 20f)
                    gradientColors = listOf(UIColor.cF3EFF7, UIColor.cF3EFF7)
                    loadingDark = true
                    isLoading = ctx.isSubmitting
                    text = if (ctx.isEditMode) "role_form_recreate".tr else "generate".tr
                    child = {
                        View {
                            attr {
                                flexDirectionRow()
                                alignItemsCenter()
                            }
                            Text {
                                attr {
                                    text(if (ctx.isEditMode) "role_form_recreate".tr else "generate".tr)
                                    fontSize(16f)
                                    fontWeight600()
                                    color(UIColor.c0A0A0A)
                                }
                            }
                            View { attr { width(8f) } }
                            SDAssetImage(AssetsImage.mvPreviewSpark, 16f, tint = UIColor.c0A0A0A)
                            View { attr { width(4f) } }
                            Text {
                                attr {
                                    text("${ctx.genPoint}")
                                    fontSize(16f)
                                    fontWeight600()
                                    color(UIColor.c0A0A0A)
                                }
                            }
                        }
                    }
                }
                event { onTap { ctx.submit() } }
            }
        }
    }

    companion object {
        /** 描述最大长度。 */
        const val MAX_DESC_LENGTH = 1000
    }
}
