package com.sondo.app.ui.works

import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.formIntText
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.MvStatus
import com.sondo.app.data.models.showsPreviewUnlockLibraryActions
import com.sondo.app.service.MvItemType
import com.sondo.app.service.MvService
import com.sondo.app.ui.SDColors
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.sondo.app.ui.atoms.SDImage
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

class SDMvItemAttr : ComposeAttr() {
    var item: MvModel by observable(MvModel())
    var isMy: Boolean by observable(true)
    /** 所在列表：未注册 onTap 时作为 MvService.onTap 的第三个参数 */
    var list: List<MvModel> = emptyList()
}

class SDMvItemEvent : ComposeEvent() {
    internal var tapHandler: ((MvItemType) -> Unit)? = null
    /** 不注册则默认 `MvService.onTap(type, item, list)` */
    fun onTap(handler: (MvItemType) -> Unit) { tapHandler = handler }
}

/** Dart `MvItemWidget`：作品库 MV 行（标题 / 统计 / 缩略图 / 操作胶囊）。 */
class SDMvItemView : ComposeView<SDMvItemAttr, SDMvItemEvent>() {
    override fun createAttr(): SDMvItemAttr = SDMvItemAttr()
    override fun createEvent(): SDMvItemEvent = SDMvItemEvent()

    internal fun fire(type: MvItemType) {
        val h = event.tapHandler
        val item = worksState.latestMv(attr.item)
        if (h != null) h(type) else MvService.onTap(type, item, attr.list)
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val ws = worksState
        return {
            vbind({ ctx.attr.item to ctx.attr.isMy }) {
                val item = ctx.attr.item
                val isMy = ctx.attr.isMy
                View {
                    View {
                        attr {
                            // Dart GestureDetector 包的是 crossAxis.start 的 Column：只有内容区域可点，右侧空白不响应
                            alignSelfFlexStart()
                            alignItemsFlexStart()
                        }
                        event { click { ctx.fire(MvItemType.detail) } }
                        Text {
                            attr {
                                alignSelfStretch()
                                text(item.title)
                                fontSize(14f)
                                color(Color.WHITE)
                                fontFamily("Poppins-SemiBold")
                                lines(1)
                                textOverFlowTail()
                            }
                        }
                        View {
                            attr {
                                marginTop(4f)
                                flexDirectionRow()
                                flexWrapWrap()
                                alignItemsCenter()
                            }
                            worksStat(AssetsImage.heandpic, { ws.mvStats(item).hitNum.formIntText() })
                            worksStatDivider()
                            worksStat(AssetsImage.like2, { ws.mvStats(item).likeNum.formIntText() })
                            worksStatDivider()
                            worksStat(AssetsImage.share2, { ws.mvStats(item).shareNum.formIntText() })
                            worksStatDivider()
                            worksStat(AssetsImage.time, item.createTimeStr)
                        }
                        View { attr { height(12f) } }
                        mvThumbnail(ctx, item)
                    }
                    View { attr { height(12f) } }
                    if (isMy) {
                        mvActionButtons(ctx, item)
                    } else {
                        View {
                            attr {
                                flexDirectionRow()
                                alignItemsCenter()
                                // 抵消胶囊的 runSpacing（Dart Wrap 末行下方没有间距）
                                marginBottom(-8f)
                            }
                            worksLikeChip({ ws.mvStats(item).liked }, AssetsImage.likeActive, AssetsImage.libLike, 10f) {
                                ctx.fire(MvItemType.like)
                            }
                            if (AppConstants.enableMvDanceShare) {
                                val url = item.resultUrl ?: ""
                                vif({ url.isNotEmpty() && ws.mvSharing.contains(url) }) {
                                    worksChipLoading(10f)
                                }
                                vif({ !(url.isNotEmpty() && ws.mvSharing.contains(url)) }) {
                                    worksChip(AssetsImage.share3, "share".tr, 10f, tint = SDColors.white80) {
                                        ctx.fire(MvItemType.share)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.mvThumbnail(ctx: SDMvItemView, item: MvModel) {
    val is916 = item.imageComp == "9:16"
    val w = if (is916) 135f else 240f
    val h = if (is916) 240f else 135f
    View {
        attr {
            size(w, h)
            borderRadius(14f)
        }
        // 四视图拼图：参考工程左对齐裁剪；Kuikly Image 没有对齐参数，这里居中裁剪
        SDImage {
            attr {
                absolutePositionAllZero()
                borderRadius(14f)
                url = item.cover
            }
        }
        if (item.status == MvStatus.success) {
            View {
                attr {
                    absolutePosition(top = 6f, left = 6f)
                    height(22f)
                    padding(0f, 8f, 0f, 8f)
                    borderRadius(11f)
                    backgroundColor(Color(0x99000000L))
                    allCenter()
                }
                Text {
                    attr {
                        text(item.durationStr)
                        fontSize(10f)
                        color(Color.WHITE)
                        fontWeight700()
                    }
                }
            }
            Image {
                attr {
                    absolutePosition(right = 6f, bottom = 8f)
                    size(20f, 20f)
                    src(ImageUri.commonAssets(AssetsImage.play2))
                }
            }
        } else {
            View {
                attr {
                    absolutePositionAllZero()
                    borderRadius(14f)
                    backgroundColor(Color(0x99000000L))
                    allCenter()
                }
                if (item.trialMvFlag) {
                    // 试用 MV：独立的状态文案，点击进生成等待页
                    event { click { ctx.fire(MvItemType.trialWaiting) } }
                    if (item.status == MvStatus.failed) {
                        worksStatusColumn(AssetsImage.videoGenFailed, "try_free_float_status_failed".tr)
                    } else {
                        worksStatusColumn(null, "generating".tr)
                    }
                } else {
                    event {
                        click {
                            val s = item.status
                            val ok = s == MvStatus.queue || s == MvStatus.generating || s == MvStatus.waitingConfirm ||
                                s == MvStatus.videoFailed || s == MvStatus.imageFailed
                            if (item.isUpdt == 0 && ok) ctx.fire(MvItemType.thinkLine)
                        }
                    }
                    if (item.status == MvStatus.queue || item.status == MvStatus.generating) {
                        worksStatusColumn(null, "generating".tr)
                    } else {
                        val isFailed = item.status == MvStatus.failed
                        val isMergeFailed = item.status == MvStatus.mergeFailed
                        worksStatusColumn(
                            if (isFailed || isMergeFailed) AssetsImage.videoGenFailed else AssetsImage.videoNeedAction,
                            when {
                                isFailed -> "failed".tr
                                isMergeFailed -> "merge_failed".tr
                                else -> "action_required".tr
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 锁定试看的小号胶囊：高 28、左右 8、圆角 8、图标 14 + 6 + 文字 10。 */
private fun ViewContainer<*, *>.previewAction(icon: String, text: String, onTap: () -> Unit) {
    View {
        attr {
            height(28f)
            padding(0f, 8f, 0f, 8f)
            marginRight(6f)
            marginBottom(6f)
            borderRadius(8f)
            backgroundColor(SDColors.white10)
            flexDirectionRow()
            alignItemsCenter()
        }
        event { click { onTap() } }
        Image {
            attr {
                size(14f, 14f)
                src(ImageUri.commonAssets(icon))
            }
        }
        Text {
            attr {
                marginLeft(6f)
                text(text)
                fontSize(10f)
                color(SDColors.white80)
                fontFamily("Poppins-SemiBold")
            }
        }
    }
}

private fun ViewContainer<*, *>.mvActionButtons(ctx: SDMvItemView, item: MvModel) {
    val s = item.status
    val previewOnly = showsPreviewUnlockLibraryActions(item.unlock, s)
    View {
        attr {
            flexDirectionRow()
            flexWrapWrap()
            // 抵消胶囊的 runSpacing（Dart Wrap 末行下方没有间距）
            marginBottom(if (previewOnly) -6f else -8f)
        }
        val tint = SDColors.white80
        when {
            // 试看已合成且仍锁定：只留解锁和删除
            previewOnly -> {
                previewAction(AssetsImage.mvLibraryUnlock, "unlock_full_video".tr) { ctx.fire(MvItemType.previewUnlock) }
                previewAction(AssetsImage.mvLibraryDelete, "delete".tr) { ctx.fire(MvItemType.deleteItem) }
            }
            item.trialMvFlag && s != MvStatus.success -> {
                val isFailed = s == MvStatus.failed
                worksChip(
                    if (isFailed) AssetsImage.generate else AssetsImage.checkIcon,
                    if (isFailed) "retry".tr else "check".tr,
                    10f,
                    tint = tint,
                ) { ctx.fire(MvItemType.trialWaiting) }
            }
            (s == MvStatus.queue || s == MvStatus.generating) && item.isUpdt == 0 -> {
                worksChip(AssetsImage.checkIcon, "check".tr, 10f, tint = tint) { ctx.fire(MvItemType.thinkLine) }
            }
            s == MvStatus.waitingConfirm || s == MvStatus.imageFailed || s == MvStatus.videoFailed -> {
                worksChip(AssetsImage.generate, "action_required".tr, 10f, tint = tint) {
                    if (item.isUpdt == 0) ctx.fire(MvItemType.thinkLine)
                }
                worksChip(AssetsImage.delete2, "delete".tr, 10f, tint = tint) { ctx.fire(MvItemType.deleteItem) }
            }
            s == MvStatus.mergeFailed -> {
                worksChip(AssetsImage.recreate, "recompose".tr, 10f) { ctx.fire(MvItemType.recompose) }
                worksChip(AssetsImage.delete2, "delete".tr, 10f, tint = tint) { ctx.fire(MvItemType.deleteItem) }
            }
            s == MvStatus.failed -> {
                worksChip(AssetsImage.feedback2, "feedback".tr, 10f) { ctx.fire(MvItemType.feedback) }
                worksChip(AssetsImage.delete2, "delete".tr, 10f, tint = tint) { ctx.fire(MvItemType.deleteItem) }
            }
            s == MvStatus.success -> {
                worksChip(AssetsImage.edit3, "edit_mv".tr, 10f) { ctx.fire(MvItemType.edit) }
                worksChip(AssetsImage.danceMore, "more".tr, 10f) { ctx.fire(MvItemType.more) }
            }
        }
    }
}

/** Dart `MvItemWidget`：作品库 MV 行。 */
fun ViewContainer<*, *>.SDMvItem(init: SDMvItemView.() -> Unit) {
    addChild(SDMvItemView(), init)
}
