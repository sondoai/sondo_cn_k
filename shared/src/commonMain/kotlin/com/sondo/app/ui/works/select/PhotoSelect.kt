package com.sondo.app.ui.works.select

import com.sondo.app.base.BasePager
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.CommonUtils
import com.sondo.app.data.models.RoleModel
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.dialog.showAppBottomSheet
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 照片选择弹窗操作区布局类型（Dart `PhotoSelectSectionType`）。 */
enum class PhotoSelectSectionType {
    /** 双按钮（camera / album），缩略图标题 `recommend`。 */
    recommend,

    /** 三按钮（角色库 / camera / album），缩略图标题 `usage_record`。 */
    usageRecord,
}

/** 用户操作类型（Dart `PhotoSelectAction`）。 */
enum class PhotoSelectAction { camera, album, itemSelected, roleLibrary }

/** 列表项类型（Dart `PhotoSelectItemType`）。 */
enum class PhotoSelectItemType { usageRecord, recommend }

/** 照片选择通用列表项（Dart `PhotoSelectItem`）。 */
data class PhotoSelectItem(
    val type: PhotoSelectItemType,
    val imageUrl: String,
    /** 仅 usageRecord 有值。 */
    val role: RoleModel? = null,
) {
    companion object {
        fun fromRole(role: RoleModel): PhotoSelectItem = PhotoSelectItem(PhotoSelectItemType.usageRecord, role.roleImg, role)

        fun fromRecommend(url: String): PhotoSelectItem = PhotoSelectItem(PhotoSelectItemType.recommend, url)
    }
}

/**
 * 返回结果（Dart `PhotoSelectResult`）。
 * @param imageSource camera / album 时为 [CommonUtils.SOURCE_CAMERA] / [CommonUtils.SOURCE_GALLERY]，可直接传给 `CommonUtils.pickImage`
 */
data class PhotoSelectResult(
    val action: PhotoSelectAction,
    val imageSource: String? = null,
    val selectedItem: PhotoSelectItem? = null,
)

/**
 * Dart `PhotoSelectBottomSheet.show`：创作选图弹层。弹层本身不选图、不申请权限、不上传，只回报用户的选择，
 * 由调用方打开相机 / 相册 / 角色库（同 Dart）。关闭（遮罩 / 返回 / 关闭按钮）回调 null。
 *
 * ```
 * ctx.showPhotoSelect(PhotoSelectSectionType.usageRecord, items, title = "upload_singer_photos".tr) { r -> … }
 * ```
 */
fun BasePager.showPhotoSelect(
    sectionType: PhotoSelectSectionType,
    items: kotlin.collections.List<PhotoSelectItem>,
    tip: String? = null,
    title: String? = null,
    onSelected: (PhotoSelectResult?) -> Unit,
): DialogHandle {
    var done = false
    var picked: PhotoSelectResult? = null
    val finish: () -> Unit = {
        if (!done) {
            done = true
            onSelected(picked)
        }
    }
    return showAppBottomSheet(onDismiss = finish) { handle ->
        val close: (PhotoSelectResult?) -> Unit = { r ->
            picked = r
            handle.dismiss()
        }
        View {
            attr { padding(0f, 20f, 16f, 20f) }
            photoSelectHeader(title ?: "select_photo".tr) { close(null) }
            Text {
                attr {
                    marginTop(20f)
                    text(tip ?: "select_photo_tip".tr)
                    fontSize(12f)
                    fontFamily("Poppins-Medium")
                    lineHeight(12f * 1.4f)
                    color(SDColors.white60)
                }
            }
            photoSelectActionGrid(
                sectionType,
                onCameraTap = { close(PhotoSelectResult(PhotoSelectAction.camera, CommonUtils.SOURCE_CAMERA)) },
                onAlbumTap = { close(PhotoSelectResult(PhotoSelectAction.album, CommonUtils.SOURCE_GALLERY)) },
                onRoleLibraryTap = { close(PhotoSelectResult(PhotoSelectAction.roleLibrary)) },
            )
            if (items.isNotEmpty()) {
                photoSelectThumbnailSection(sectionType, items) { item ->
                    close(PhotoSelectResult(PhotoSelectAction.itemSelected, selectedItem = item))
                }
            }
        }
    }
}

/** [showPhotoSelect] 的挂起版：取消返回 null。 */
suspend fun BasePager.photoSelect(
    sectionType: PhotoSelectSectionType,
    items: kotlin.collections.List<PhotoSelectItem>,
    tip: String? = null,
    title: String? = null,
): PhotoSelectResult? = suspendCoroutine { cont ->
    showPhotoSelect(sectionType, items, tip, title) { cont.resume(it) }
}

// ---------------------------------------------------------------------------- 子组件

/** Dart `PhotoSelectHeader`：标题 16 w600 白 80% + 28 圆形关闭按钮。 */
private fun ViewContainer<*, *>.photoSelectHeader(title: String, onClose: () -> Unit) {
    View {
        attr {
            flexDirectionRow()
            alignItemsCenter()
        }
        Text {
            attr {
                flex(1f)
                text(title)
                fontSize(16f)
                fontFamily("Poppins-SemiBold")
                color(SDColors.white80)
            }
        }
        View {
            attr {
                size(28f, 28f)
                borderRadius(14f)
                backgroundColor(SDColors.white10)
                allCenter()
            }
            event { click { onClose() } }
            Image {
                attr {
                    size(17.5f, 17.5f)
                    src(ImageUri.commonAssets(AssetsImage.close))
                    resizeContain()
                }
            }
        }
    }
}

/** Dart `PhotoSelectActionGrid` + `PhotoSelectActionButton`。 */
private fun ViewContainer<*, *>.photoSelectActionGrid(
    sectionType: PhotoSelectSectionType,
    onCameraTap: () -> Unit,
    onAlbumTap: () -> Unit,
    onRoleLibraryTap: () -> Unit,
) {
    View {
        attr {
            marginTop(20f)
            flexDirectionRow()
        }
        if (sectionType == PhotoSelectSectionType.usageRecord) {
            photoSelectActionButton(AssetsImage.addLibraryIcon, "role_library".tr, onRoleLibraryTap)
            View { attr { width(10f) } }
        }
        photoSelectActionButton(AssetsImage.cameraIcon, "camera".tr, onCameraTap)
        View { attr { width(10f) } }
        photoSelectActionButton(AssetsImage.albumIcon1, "album".tr, onAlbumTap)
    }
}

private fun ViewContainer<*, *>.photoSelectActionButton(icon: String, label: String, onTap: () -> Unit) {
    View {
        attr {
            flex(1f)
            height(80f)
            backgroundColor(SDColors.white5)
            borderRadius(16f)
            border(Border(1f, BorderStyle.SOLID, SDColors.white10))
            allCenter()
        }
        event { click { onTap() } }
        Image {
            attr {
                size(18f, 18f)
                src(ImageUri.commonAssets(icon))
                resizeContain()
            }
        }
        Text {
            attr {
                marginTop(8f)
                marginLeft(4f)
                marginRight(4f)
                text(label)
                fontSize(10f)
                fontFamily("Poppins-Medium")
                color(SDColors.white80)
                textAlignCenter()
                lines(1)
                textOverFlowTail()
            }
        }
    }
}

/** Dart `PhotoSelectThumbnailSection` + `PhotoSelectThumbnailRow`：只有一个分组，标题由 sectionType 决定。 */
private fun ViewContainer<*, *>.photoSelectThumbnailSection(
    sectionType: PhotoSelectSectionType,
    items: kotlin.collections.List<PhotoSelectItem>,
    onItemTap: (PhotoSelectItem) -> Unit,
) {
    val title = if (sectionType == PhotoSelectSectionType.usageRecord) "usage_record".tr else "recommend".tr
    Text {
        attr {
            marginTop(20f)
            text(title)
            fontSize(14f)
            fontFamily("Poppins-SemiBold")
            color(SDColors.white80)
        }
    }
    List {
        attr {
            marginTop(12f)
            height(68f)
            flexDirectionRow()
            showScrollerIndicator(false)
        }
        items.forEachIndexed { index, item ->
            View {
                attr {
                    size(68f, 68f)
                    marginLeft(if (index == 0) 0f else 10f)
                }
                event { click { onItemTap(item) } }
                SDImage {
                    attr {
                        size(68f, 68f)
                        borderRadius(12f)
                        url = item.imageUrl
                    }
                }
            }
        }
    }
}
