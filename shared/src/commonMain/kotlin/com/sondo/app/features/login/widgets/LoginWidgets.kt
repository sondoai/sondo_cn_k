package com.sondo.app.features.login.widgets

import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.UIColor
import com.sondo.app.core.constants.asset
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.service.UserStore
import com.sondo.app.ui.input.SDInput
import com.sondo.app.ui.input.SDInputView
import com.sondo.app.ui.input.SDKeyboardType
import com.sondo.app.base.BasePager
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.RichText
import com.tencent.kuikly.core.views.Span
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/*
 * 登录表单页共用的小组件（Dart: features/login/presentation/widgets）。
 * 状态由页面持有，这里只做构建；读取的参数都是 lambda，保证在 attr { } 里随页面状态重算。
 */

private val white40 = Color(0x66FFFFFFL)
private val white60 = Color(0x99FFFFFFL)
private val errorColor = Color(0xFFFF3257L)
private val successColor = Color(0xFF1EC995L)

/**
 * Dart `LoginInputField`：标签 + 54 高输入框（错误红框 / 合法手机号勾 / 右侧 suffix）+ 20 高提示行。
 * 只接受数字（Dart digitsOnly），超长截断（Dart LengthLimitingTextInputFormatter）。
 */
fun ViewContainer<*, *>.LoginInputField(
    label: String,
    hintText: String,
    maxLength: Int,
    errorText: () -> String?,
    successText: () -> String? = { null },
    showEmailCheck: () -> Boolean = { false },
    onChanged: (String) -> Unit,
    suffix: (ViewContainer<*, *>.() -> Unit)? = null,
) {
    View {
        Text {
            attr {
                marginLeft(4f)
                marginRight(4f)
                text(label)
                fontSize(14f)
                fontWeight500()
                color(white60)
                letterSpacing(0.014f)
            }
        }
        View {
            attr {
                marginTop(8f)
                height(54f)
                paddingLeft(14f)
                paddingRight(14f)
                borderRadius(16f)
                flexDirectionRow()
                alignItemsCenter()
                val hasError = !errorText().isNullOrEmpty()
                // Figma：错误底 rgba(247,115,115,0.1) + 边 rgba(255,50,87,0.6)
                backgroundColor(if (hasError) Color(0x1AF77373L) else Color(0x1AFFFFFFL))
                // 始终占 1px 边，避免校验红框出现时内容区被挤压抖动
                border(Border(1f, BorderStyle.SOLID, if (hasError) Color(0x99FF3257L) else Color.TRANSPARENT))
            }
            var inputRef: ViewRef<SDInputView>? = null
            SDInput {
                ref { inputRef = it }
                attr {
                    flex(1f)
                    height(52f)
                    contentPaddingH = 0f
                    fontSize = 14f
                    color = Color.WHITE
                    hintColor = white40
                    cursorColor = Color.WHITE
                    placeholder = hintText
                    this.maxLength = maxLength
                    keyboardType = SDKeyboardType.NUMBER
                }
                event {
                    onChanged { raw ->
                        val digits = raw.filter { it in '0'..'9' }.take(maxLength)
                        if (digits != raw) inputRef?.view?.setText(digits)
                        onChanged(digits)
                    }
                }
            }
            if (suffix != null) {
                suffix()
            } else {
                vif({ showEmailCheck() }) {
                    Image {
                        attr {
                            size(24f, 24f)
                            src(AssetsImage.loginEmailCheck.asset())
                        }
                    }
                }
            }
        }
        // 始终占位（8 间距 + 一行文案），避免提示显隐时整页抖动
        View {
            attr { height(20f) }
            Text {
                attr {
                    marginTop(1f)
                    marginLeft(4f)
                    marginRight(4f)
                    val err = errorText()
                    val ok = successText()
                    text(if (!err.isNullOrEmpty()) err else ok ?: "")
                    fontSize(12f)
                    fontWeight500()
                    color(if (!err.isNullOrEmpty()) errorColor else successColor)
                    lineHeight(12f * 1.4f)
                    letterSpacing(0.012f)
                    lines(1)
                    textOverFlowTail()
                }
            }
        }
    }
}

/** Dart `LoginSendCodeAction`：输入框右侧「获取验证码」/ 倒计时。 */
fun ViewContainer<*, *>.LoginSendCodeAction(
    counting: () -> Boolean,
    seconds: () -> Int,
    enabled: () -> Boolean,
    onTap: () -> Unit,
) {
    View {
        vif({ counting() }) {
            Text {
                attr {
                    text("resend_in_seconds".trParams("seconds" to "${seconds()}"))
                    fontSize(14f)
                    fontWeight500()
                    color(white40)
                }
            }
        }
        velse {
            Text {
                attr {
                    text("get_code".tr)
                    fontSize(14f)
                    fontWeight500()
                    color(if (enabled()) Color(0xFF9271F8L) else white40)
                }
                event { click { if (enabled()) onTap() } }
            }
        }
    }
}

/** Dart `LoginPrimaryButton`：未填齐半透明，可提交白底深色字。 */
fun ViewContainer<*, *>.LoginPrimaryButton(text: String, enabled: () -> Boolean, onTap: () -> Unit) {
    View {
        attr {
            height(50f)
            borderRadius(53f)
            allCenter()
            backgroundColor(if (enabled()) UIColor.cFFFFFF else Color(0x33FFFFFFL))
        }
        event { click { if (enabled()) onTap() } }
        Text {
            attr {
                text(text)
                fontSize(16f)
                fontWeight600()
                color(if (enabled()) UIColor.c0A0A0A else white60)
            }
        }
    }
}

/** Dart `LoginTermsFooter`：底栏协议，点 Terms / Privacy 打开条款。 */
fun ViewContainer<*, *>.LoginTermsFooter(pager: BasePager) {
    RichText {
        attr {
            textAlignCenter()
            alignSelfStretch()
        }
        Span {
            text("${"by_continuing_you_agree_to".tr} ")
            fontSize(12f)
            fontWeight500()
            color(white40)
        }
        Span {
            text("terms_of_service".tr)
            fontSize(12f)
            fontWeight500()
            color(white40)
            textDecorationUnderLine()
            click { UserStore.openTerms(pager) }
        }
        Span {
            text(" ${"and".tr} ")
            fontSize(12f)
            fontWeight500()
            color(white40)
        }
        Span {
            text("privacy_policy".tr)
            fontSize(12f)
            fontWeight500()
            color(white40)
            textDecorationUnderLine()
            click { UserStore.openPrivacyPolicy(pager) }
        }
    }
}
