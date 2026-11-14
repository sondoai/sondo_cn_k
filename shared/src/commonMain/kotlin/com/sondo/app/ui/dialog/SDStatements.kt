package com.sondo.app.ui.dialog

import com.sondo.app.base.BasePager
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.core.storage.Sp
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.nativeapi.SDPermissionModule
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.SDWeight
import com.sondo.app.ui.atoms.SDGradientButton
import com.sondo.app.ui.atoms.SDLinkSpan
import com.sondo.app.ui.atoms.SDLinkText
import com.sondo.app.ui.atoms.SDPrimaryButton
import com.sondo.app.ui.overlay.DialogEntry
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 协议 / 外链的打开方式。UI Kit 不依赖业务服务，默认直接用系统能力打开；
 * 账号模块可在启动时把 [opener] 换成自己的实现（如应用内 WebView 页）。
 */
object SDLinks {
    /** `(url, title) -> Unit`；为空时用 `SDAppModule.openUrl(url)`。只存函数，不存页面状态。 */
    var opener: ((url: String, title: String) -> Unit)? = null

    fun open(pager: BasePager, url: String, title: String = "") {
        val custom = opener
        if (custom != null) {
            custom(url, title)
            return
        }
        pager.launch {
            val ok = try {
                pager.acquireModule<SDAppModule>(SDAppModule.MODULE_NAME).openUrl(url)
            } catch (e: Throwable) {
                false
            }
            if (!ok) pager.toast("open_browser_failed".tr)
        }
    }
}

// ============================================================================
// 使用声明（上传 / 分享 / 下载）
// ============================================================================

/** Dart `UsageStatementType`。 */
enum class UsageStatementType(val key: String) {
    UPLOAD("upload"),
    SHARE("share"),
    DOWNLOAD("download"),
}

/**
 * Dart `UsageStatement.show(type:)` → 使用声明弹窗。同意返回 true；取消 / 点遮罩返回 false。
 *
 * 卡片左右各距屏幕 24、圆角 16；分享 / 下载类型顶部有一个探出卡片 70 的 130×130 图标；
 * 上传类型正文较长，可滚动（卡片最高 400，其余 450）。
 *
 * @param onOpenPrivacy / onOpenTerms 链接点击；默认用 [SDLinks] 打开隐私政策 / 用户协议
 */
suspend fun BasePager.showUsageStatement(
    type: UsageStatementType,
    barrierDismissible: Boolean = true,
    onOpenPrivacy: (() -> Unit)? = null,
    onOpenTerms: (() -> Unit)? = null,
): Boolean = suspendCoroutine { cont ->
    val pager = this
    var done = false
    val finish: (Boolean) -> Unit = { v ->
        if (!done) {
            done = true
            cont.resume(v)
        }
    }
    val iconPath = when (type) {
        UsageStatementType.UPLOAD -> ""
        UsageStatementType.SHARE -> AssetsImage.dialogShareIcon
        UsageStatementType.DOWNLOAD -> AssetsImage.dialogDownIcon
    }
    val titleKey = "usage_statement_${type.key}_title"
    val contentKey = "usage_statement_${type.key}_content"
    val cardWidth = screenWidth - 48f
    val isUpload = type == UsageStatementType.UPLOAD
    val bodyColor = Color(0xB3FFFFFFL)
    showDialog(
        align = DialogEntry.ALIGN_CENTER,
        dismissOnMask = barrierDismissible,
        dismissOnBack = barrierDismissible,
        maskColor = SDColors.mask50,
        onDismiss = { finish(false) },
    ) { handle ->
        // 外层不设圆角：圆角会裁剪子节点，探出卡片的图标要放在外层
        View {
            attr {
                width(cardWidth)
                // Dart 图标是 Positioned(top: -70) 探出、不占布局：用负外边距抵消，让卡片本身居中
                if (iconPath.isNotEmpty()) {
                    paddingTop(70f)
                    marginTop(-70f)
                }
            }
            View {
                attr {
                    maxHeight(if (isUpload) 400f else 450f)
                    padding(0f, 20f, 0f, 20f)
                    borderRadius(16f)
                    backgroundColor(UIColor.c1C1C1C)
                }
                View { attr { height(if (iconPath.isNotEmpty()) 62f else 24f) } }
                Text {
                    attr {
                        text(titleKey.tr)
                        fontSize(18f)
                        fontWeight700()
                        color(Color.WHITE)
                        textAlignCenter()
                    }
                }
                if (isUpload) {
                    // 400 − 标题区 − 协议行 − 两个按钮 ≈ 正文可用高度
                    Scroller {
                        attr {
                            marginTop(12f)
                            height(190f)
                            showScrollerIndicator(false)
                        }
                        Text {
                            attr {
                                text(contentKey.tr)
                                fontSize(14f)
                                lineHeight(14f * 1.35f)
                                color(bodyColor)
                                marginBottom(10f)
                            }
                        }
                    }
                } else {
                    Text {
                        attr {
                            marginTop(12f)
                            text(contentKey.tr)
                            fontSize(14f)
                            lineHeight(14f * 1.35f)
                            color(bodyColor)
                        }
                    }
                }
                View { attr { height(4f) } }
                SDLinkText(
                    spans = listOf(
                        SDLinkSpan("usage_statement_read_and_agree".tr + " "),
                        SDLinkSpan("privacy_policy".tr) {
                            if (onOpenPrivacy != null) onOpenPrivacy() else SDLinks.open(pager, AppConstants.privacyUrl, "privacy_policy".tr)
                        },
                        SDLinkSpan(" " + "usage_statement_and".tr + " "),
                        SDLinkSpan("terms_of_service".tr) {
                            if (onOpenTerms != null) onOpenTerms() else SDLinks.open(pager, AppConstants.termsUrl, "terms_of_service".tr)
                        },
                    ),
                    size = 14f,
                    color = bodyColor,
                    height = 1.35f,
                    linkColor = SDColors.brandPurple,
                    attrs = { textAlignCenter() },
                )
                View { attr { height(10f) } }
                SDPrimaryButton {
                    attr {
                        height(44f)
                        fontSize = 16f
                        text = "usage_statement_agree".tr
                    }
                    event {
                        onTap {
                            finish(true)
                            handle.dismiss()
                        }
                    }
                }
                SDGradientButton {
                    attr {
                        padding(0f, 0f, 10f, 0f)
                        height(44f)
                        fontSize = 16f
                        gradientColors = listOf(Color.TRANSPARENT, Color.TRANSPARENT)
                        textColor = Color(0x66FFFFFFL)
                        text = "cancel".tr
                    }
                    event { onTap { handle.dismiss() } }
                }
            }
            if (iconPath.isNotEmpty()) {
                View {
                    attr {
                        absolutePosition(top = 0f, left = 0f, right = 0f)
                        alignItemsCenter()
                        touchEnable(false)
                    }
                    Image {
                        attr {
                            size(130f, 130f)
                            src(ImageUri.commonAssets(iconPath))
                            resizeContain()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dart `UserController.showAgree(key, type)`：某类操作的首次使用声明。
 * - [displayUseExplain]（远程配置开关，由调用方传入）为 false → 直接 true；
 * - 本地已同意（[storageKey] 为 true）→ 直接 true；
 * - 否则弹声明，同意后写入本地并返回 true。
 *
 * [storageKey] 用 `StorageKey.isAgreeUpload / isAgreeShare / isAgreeDown`；不传则按类型取默认。
 */
suspend fun BasePager.showAgree(
    type: UsageStatementType,
    storageKey: String? = null,
    displayUseExplain: Boolean = true,
): Boolean {
    if (!displayUseExplain) return true
    val key = storageKey ?: when (type) {
        UsageStatementType.UPLOAD -> StorageKey.isAgreeUpload
        UsageStatementType.SHARE -> StorageKey.isAgreeShare
        UsageStatementType.DOWNLOAD -> StorageKey.isAgreeDown
    }
    if (Sp.getBool(key, false)) return true
    val agree = showUsageStatement(type)
    if (agree) Sp.setBool(key, true)
    return agree
}

// ============================================================================
// AI 水印声明（关闭下载水印前）
// ============================================================================

/**
 * Dart `AiWatermarkDialogs.showStatement()`：设置页关闭下载水印前的声明。
 * 「同意并关闭」返回 true；「保留水印」/ 点遮罩返回 false。
 */
suspend fun BasePager.showAiWatermarkStatement(): Boolean = suspendCoroutine { cont ->
    val pager = this
    var done = false
    val finish: (Boolean) -> Unit = { v ->
        if (!done) {
            done = true
            cont.resume(v)
        }
    }
    showCardDialog(
        title = "ai_statement_title".tr,
        cancelText = "keep_watermark".tr,
        confirmText = "agree_and_close".tr,
        widthFactor = 0.84f,
        onDismiss = { finish(false) },
        onConfirm = { handle ->
            finish(true)
            handle.dismiss()
        },
    ) {
        Text {
            attr {
                text("ai_statement_keep_implicit".tr)
                fontSize(13f)
                fontWeight500()
                lineHeight(13f * 1.45f)
                color(Color.WHITE)
            }
        }
        Text {
            attr {
                marginTop(8f)
                text("ai_statement_publish_duty".tr)
                fontSize(13f)
                fontWeight500()
                lineHeight(13f * 1.45f)
                color(Color.WHITE)
            }
        }
        SDLinkText(
            spans = listOf(
                SDLinkSpan("ai_statement_agree_prefix".tr),
                SDLinkSpan("service_terms".tr) {
                    SDLinks.open(pager, "${AppConstants.termsUrl}#ai-content-label", "service_terms".tr)
                },
                SDLinkSpan("ai_statement_agree_suffix".tr),
            ),
            size = 13f,
            color = Color.WHITE,
            height = 1.45f,
            weight = SDWeight.W500,
            linkColor = SDColors.linkPurple,
            attrs = { marginTop(8f) },
        )
    }
}

// ============================================================================
// 权限说明
// ============================================================================

/** 权限名（`SDPermissionModule` 的 permission 字符串 → 展示名，Dart `PermissionUtil.getPermissionName`）。 */
fun permissionDisplayName(permission: String): String = when (permission) {
    "camera" -> "permission_camera".tr
    "photos", "photosAddOnly" -> "permission_photos".tr
    "storage" -> "permission_storage".tr
    "microphone" -> "permission_microphone".tr
    "location" -> "permission_location".tr
    "notification" -> "permission_notification".tr
    else -> "permission_unknown".tr
}

private fun permissionRationaleContent(permission: String, name: String, purpose: String?): String {
    val trimmed = purpose?.trim()
    if (!trimmed.isNullOrEmpty()) {
        // 模板在 @purpose 后已有句号，去掉用途末尾的句号避免「。。」
        val purposeText = trimmed.trimEnd('。')
        return "permission_rationale_template".trParams("name" to name, "purpose" to purposeText)
    }
    val key = when (permission) {
        "camera" -> "permission_rationale_camera"
        "photos" -> "permission_rationale_photos"
        "photosAddOnly" -> "permission_rationale_photos_add_only"
        "storage" -> "permission_rationale_storage"
        "microphone" -> "permission_rationale_microphone"
        "location" -> "permission_rationale_location"
        "notification" -> "permission_rationale_notification"
        else -> "permission_rationale"
    }
    return key.trParams("name" to name)
}

/**
 * Dart `PermissionUtil.showPermissionRationale(permission, …)`：系统授权框之前的用途说明。
 * 按钮固定「拒绝 / 同意」；只有返回 true 调用方才应继续触发系统授权。
 *
 * @param permission `SDPermissionModule` 的权限名："camera" | "photos" | "photosAddOnly" | "storage" | "microphone" | "notification"
 * @param purpose 自定义用途（套用通用模板）；不传用该权限的固定说明文案
 */
suspend fun BasePager.showPermissionRationale(
    permission: String,
    title: String? = null,
    content: String? = null,
    purpose: String? = null,
): Boolean {
    val name = permissionDisplayName(permission)
    return confirm(
        content = content ?: permissionRationaleContent(permission, name, purpose),
        title = title ?: "permission_required_title".trParams("name" to name),
        cancelText = "permission_deny".tr,
        confirmText = "permission_agree".tr,
    )
}

/**
 * 权限被永久拒绝时的引导弹窗（Dart `PermissionUtil` 里 `isPermanentlyDenied` 分支）：
 * 「权限提示 / @name权限已关闭，请在系统设置中开启后再继续。」+ 拒绝 / 前往设置。
 */
fun BasePager.showPermissionSettingsDialog(permission: String) {
    val pager = this
    showConfirmDialog(
        title = "permission_tips".tr,
        content = "permission_settings_required".trParams("name" to permissionDisplayName(permission)),
        cancelText = "permission_deny".tr,
        confirmText = "go_to_settings".tr,
    ) {
        try {
            pager.acquireModule<SDAppModule>(SDAppModule.MODULE_NAME).openAppSettings()
        } catch (e: Throwable) {
        }
    }
}

/**
 * 「说明 → 系统授权」的完整流程（Dart `PermissionUtil.requestWithRationale` 的通用形态）：
 * 已授权直接 true；永久拒绝弹设置引导并返回 false；否则先弹用途说明，同意后再发起系统申请。
 */
suspend fun BasePager.ensurePermission(permission: String, purpose: String? = null): Boolean {
    val module = try {
        acquireModule<SDPermissionModule>(SDPermissionModule.MODULE_NAME)
    } catch (e: Throwable) {
        return false
    }
    val status = module.status(permission)
    if (SDPermissionModule.isGranted(status)) return true
    if (status == SDPermissionModule.PERMANENTLY_DENIED) {
        showPermissionSettingsDialog(permission)
        return false
    }
    if (status == SDPermissionModule.RESTRICTED) return false
    if (!showPermissionRationale(permission, purpose = purpose)) return false
    return SDPermissionModule.isGranted(module.request(permission))
}
