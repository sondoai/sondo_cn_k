package com.sondo.app.service

import com.sondo.app.base.BasePager
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.asset
import com.sondo.app.core.constants.blackA
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.DevLog
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.service.analytics.UmengUtils
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** 「社交媒体」弹窗（参考工程 UserController.openContactUs）。 */
internal object ContactUsDialog {
    fun show(pager: BasePager) {
        pager.showDialog(maskColor = blackA(0.5f)) { _ ->
            View {
                attr {
                    width(pager.screenWidth * 0.8f)
                    maxHeight(700f)
                    padding(16f, 16f, 16f, 16f)
                    borderRadius(24f)
                    backgroundColor(UIColor.c1C1C1C)
                }
                Text {
                    attr {
                        marginTop(6f)
                        marginBottom(10f)
                        alignSelfCenter()
                        text("social_media".tr)
                        fontSize(16f)
                        fontWeightBold()
                        color(Color.WHITE)
                    }
                }
                // Telegram 用外部应用打开，其余走应用内浏览器（与参考工程一致）
                item(pager, AssetsImage.telegram, "Telegram", AppConstants.telegramUrl, "external")
                item(pager, AssetsImage.twitterIcon, "Twitter", AppConstants.xUrl, "inApp")
                item(pager, AssetsImage.discordIcon2, "Discord", AppConstants.discordUrl, "inApp")
                item(pager, AssetsImage.youtubeIcon, "YouTube", AppConstants.youTuBeUrl, "inApp")
                item(pager, AssetsImage.sondoBlog, "Sondo Blog", AppConstants.sondoBlogUrl, "inApp")
                View { attr { height(4f) } }
            }
        }
    }

    private fun ViewContainer<*, *>.item(pager: BasePager, icon: String, title: String, url: String, mode: String) {
        View {
            attr {
                paddingTop(8f)
                paddingBottom(8f)
                flexDirectionRow()
                alignItemsCenter()
            }
            event {
                click {
                    UmengUtils.logEvent("contact_us", mapOf("af_content_type" to title, "af_success" to "true"))
                    pager.launch {
                        try {
                            pager.acquireModule<SDAppModule>(SDAppModule.MODULE_NAME).openUrl(url, mode)
                        } catch (e: Throwable) {
                            DevLog.logger("open $title error $e")
                        }
                    }
                }
            }
            Image {
                attr {
                    size(36f, 36f)
                    src(icon.asset())
                }
            }
            Text {
                attr {
                    marginLeft(10f)
                    flex(1f)
                    text(title)
                    fontSize(14f)
                    fontWeight600()
                    color(Color.WHITE)
                    lines(1)
                }
            }
            Image {
                attr {
                    size(22f, 22f)
                    src(AssetsImage.arrowRight.asset())
                    tintColor(Color.WHITE)
                }
            }
        }
    }
}
