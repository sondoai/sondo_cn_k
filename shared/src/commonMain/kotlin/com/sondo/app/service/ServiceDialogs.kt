package com.sondo.app.service

import com.sondo.app.base.AppEnv
import com.sondo.app.base.BasePager
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.asset
import com.sondo.app.core.constants.blackA
import com.sondo.app.core.i18n.tr
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * service 层自带的三个弹窗（参考工程里它们的 UI 就写在对应 Service 里）：
 * 通知授权说明、版本更新、应用火爆提示。尺寸 / 颜色 / 文案键照抄参考工程。
 */
internal object ServiceDialogs {

    /**
     * 通知授权业务弹窗。返回键与遮罩都不关闭。
     * @param onAgree 点「同意」；由调用方在拿到系统授权结果后 dismiss
     * @param onDeny 点「拒绝」（弹窗已关闭）
     */
    fun showNotified(pager: BasePager, image: String, message: String, onAgree: (DialogHandle) -> Unit, onDeny: () -> Unit): DialogHandle =
        pager.showDialog(dismissOnMask = false, dismissOnBack = false, maskColor = blackA(0.5f)) { handle ->
            View {
                attr {
                    width(minOf(pager.screenWidth * 0.8f, 600f))
                    minHeight(180f)
                    maxHeight(600f)
                    padding(16f, 16f, 16f, 16f)
                    borderRadius(24f)
                    backgroundColor(UIColor.c1C1C1C)
                    alignItemsCenter()
                }
                Text {
                    attr {
                        marginTop(12f)
                        marginBottom(12f)
                        text("get_notified".tr)
                        fontSize(18f)
                        fontWeight600()
                        fontFamily("Poppins")
                        color(Color.WHITE)
                        lines(1)
                    }
                }
                Image {
                    attr {
                        size(176f, 176f)
                        src(image.asset())
                    }
                }
                Text {
                    attr {
                        marginTop(16f)
                        text(message)
                        fontSize(14f)
                        fontWeight600()
                        color(Color(0x99FFFFFFL))
                        lines(10)
                        textAlignCenter()
                    }
                }
                View {
                    attr {
                        marginTop(16f)
                        alignSelfStretch()
                        height(50f)
                        borderRadius(48f)
                        backgroundColor(UIColor.cF3EFF7)
                        allCenter()
                    }
                    event { click { onAgree(handle) } }
                    Text {
                        attr {
                            text("permission_agree".tr)
                            fontSize(16f)
                            fontWeight600()
                            color(UIColor.c0A0A0A)
                        }
                    }
                }
                View {
                    attr {
                        marginTop(10f)
                        alignSelfStretch()
                        height(40f)
                        allCenter()
                    }
                    event {
                        click {
                            handle.dismiss()
                            onDeny()
                        }
                    }
                    Text {
                        attr {
                            text("permission_deny".tr)
                            fontSize(16f)
                            fontWeight600()
                            color(Color(0x66FFFFFFL))
                        }
                    }
                }
            }
        }

    /**
     * 版本更新弹窗。返回键与遮罩都不关闭；强更时没有取消行，点更新后弹窗保留。
     * 标题与版本文案在参考工程里就是写死的英文。
     */
    fun showUpdate(pager: BasePager, storeVersion: String, force: Boolean, onUpdate: () -> Unit): DialogHandle =
        pager.showDialog(dismissOnMask = false, dismissOnBack = false, maskColor = blackA(0.5f)) { handle ->
            View {
                attr {
                    width(minOf(pager.screenWidth * 0.8f, 600f))
                    minHeight(180f)
                    maxHeight(600f)
                    padding(16f, 16f, 16f, 16f)
                    borderRadius(24f)
                    backgroundColor(UIColor.c1C1C1C)
                    alignItemsCenter()
                }
                Text {
                    attr {
                        marginTop(12f)
                        marginBottom(12f)
                        text("New Updates Available")
                        fontSize(18f)
                        fontWeight600()
                        fontFamily("Poppins")
                        color(Color.WHITE)
                        lines(1)
                    }
                }
                Text {
                    attr {
                        text("Version $storeVersion")
                        fontSize(14f)
                        fontWeight600()
                        color(Color(0x99FFFFFFL))
                        lines(1)
                    }
                }
                Image {
                    attr {
                        marginTop(16f)
                        size(230f, 230f)
                        src("images/common/update-app-icon.png".asset())
                    }
                }
                View {
                    attr {
                        marginTop(16f)
                        alignSelfStretch()
                        height(50f)
                        borderRadius(48f)
                        backgroundColor(Color(0xFF7E5AFFL))
                        flexDirectionRow()
                        allCenter()
                    }
                    event { click { onUpdate() } }
                    Image {
                        attr {
                            size(24f, 24f)
                            marginRight(12f)
                            src((if (AppEnv.isIOS) AssetsImage.appleIcon else AssetsImage.googleIcon).asset())
                        }
                    }
                    Text {
                        attr {
                            text("confirm_update".tr)
                            fontSize(16f)
                            fontWeight600()
                            color(Color.WHITE)
                        }
                    }
                }
                if (!force) {
                    View {
                        attr {
                            marginTop(10f)
                            alignSelfStretch()
                            height(50f)
                            allCenter()
                        }
                        event { click { handle.dismiss() } }
                        Text {
                            attr {
                                text("cancel".tr)
                                fontSize(16f)
                                fontWeight600()
                                color(Color(0x66FFFFFFL))
                            }
                        }
                    }
                }
            }
        }

    /** 「应用火爆 / 升级中」提示。遮罩不可关闭。 */
    fun showAppHot(pager: BasePager, onGet: (() -> Unit)? = null): DialogHandle =
        pager.showDialog(dismissOnMask = false, maskColor = blackA(0.6f)) { handle ->
            View {
                attr {
                    width(pager.screenWidth - 80f)
                    borderRadius(20f)
                    backgroundColor(UIColor.c1C1C1C)
                    alignItemsCenter()
                }
                Text {
                    attr {
                        marginTop(28f)
                        text("app_hot_upgrading".tr)
                        fontSize(20f)
                        fontWeight700()
                        color(Color.WHITE)
                    }
                }
                Image {
                    attr {
                        marginTop(20f)
                        size(180f, 160f)
                        resizeContain()
                        src(AssetsImage.appHot.asset())
                    }
                }
                Text {
                    attr {
                        marginTop(20f)
                        marginLeft(20f)
                        marginRight(20f)
                        alignSelfStretch()
                        text("app_hot_description".tr)
                        fontSize(14f)
                        lineHeight(14f * 1.5f)
                        color(Color(0xCCFFFFFFL))
                        textAlignLeft()
                    }
                }
                View {
                    attr {
                        marginTop(24f)
                        marginLeft(20f)
                        marginRight(20f)
                        marginBottom(18f)
                        alignSelfStretch()
                        height(48f)
                        borderRadius(24f)
                        backgroundColor(UIColor.cF3EFF7)
                        allCenter()
                    }
                    event {
                        click {
                            handle.dismiss()
                            onGet?.invoke()
                        }
                    }
                    Text {
                        attr {
                            text("i_know".tr)
                            fontSize(16f)
                            fontWeight600()
                            fontFamily("Poppins")
                            color(UIColor.c0A0A0A)
                        }
                    }
                }
            }
        }
}
