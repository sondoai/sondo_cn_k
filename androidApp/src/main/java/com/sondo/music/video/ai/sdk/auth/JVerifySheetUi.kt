package com.sondo.music.video.ai.sdk.auth

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cn.jiguang.verifysdk.api.JVerifyUIClickCallback
import cn.jiguang.verifysdk.api.JVerifyUIConfig
import cn.jiguang.verifysdk.api.PrivacyBean
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.roundToInt

/** `jverifyLoginAuth` 的入参（文案和协议链接都由 Kuikly 侧传入，原生不写死任何文案）。 */
internal class JVerifySheetParams(
    val title: String,
    val subtitle: String,
    val loginButtonText: String,
    /** 短信 / 其他手机号登录入口文案；为空则不显示这个按钮。 */
    val otherLoginText: String,
    /** 微信登录入口文案；为空则不显示这个按钮。 */
    val wechatLoginText: String,
    val privacyPrefix: String,
    val privacySuffix: String,
    /** 最后一个条款前的分隔符（契约参数 privacySeparator，缺省“和”）；条款自带 separator 时以条款为准。 */
    val privacySeparator: String,
    val privacyItems: List<PrivacyItem>,
    val privacyUncheckedToast: String,
    val timeoutMs: Int,
) {
    class PrivacyItem(val name: String, val url: String, val separator: String?)

    companion object {
        fun from(json: JSONObject): JVerifySheetParams {
            val items = ArrayList<PrivacyItem>()
            val array = json.optJSONArray("privacyItems")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    // SDK 自己会给条款名加书名号，这里先去掉传入的，避免出现《《》》。
                    val name = item.optString("name", "").trim().removePrefix("《").removeSuffix("》")
                    val url = item.optString("url", "").trim()
                    if (name.isEmpty() || url.isEmpty()) continue
                    val separator = if (item.has("separator")) item.optString("separator", "") else null
                    items.add(PrivacyItem(name, url, separator))
                }
            }
            val timeout = json.optInt("timeoutMs", 10000)
            return JVerifySheetParams(
                title = json.optString("title", ""),
                subtitle = json.optString("subtitle", ""),
                loginButtonText = json.optString("loginButtonText", ""),
                otherLoginText = json.optString("otherLoginText", ""),
                wechatLoginText = json.optString("wechatLoginText", ""),
                privacyPrefix = json.optString("privacyPrefix", ""),
                privacySuffix = json.optString("privacySuffix", ""),
                privacySeparator = json.optString("privacySeparator", "").ifEmpty { "和" },
                privacyItems = items,
                privacyUncheckedToast = json.optString("privacyUncheckedToast", ""),
                // SDK 只接受 (0, 30000]，范围外它会自己改回 10000，这里直接收敛。
                timeoutMs = if (timeout in 1..30000) timeout else 10000,
            )
        }
    }
}

/**
 * 一键登录授权页：贴底弹窗，深色底，上圆角。
 *
 * 号码、运营商说明、登录按钮、勾选框和协议是 SDK 自带的控件，这里只给位置和样式；
 * 欢迎语、头图、登录按钮上的图标、短信登录和微信登录两个按钮是加进去的自定义控件。
 * 数值取自设计稿（与 Flutter 版一致），单位 dp，纵向从弹窗顶边算起。
 */
internal object JVerifySheetUi {

    private const val SIDE_PADDING = 20
    private const val HEADER_ART_SIZE = 110
    private const val TITLE_TOP = 43
    private const val SUBTITLE_TOP = 79
    private const val NUMBER_TOP = 150
    private const val SLOGAN_TOP = 194
    private const val BUTTON_TOP = 230
    private const val BUTTON_HEIGHT = 50

    /** 一键登录下面依次是短信登录、微信登录，和一键登录等宽等高，相邻两个隔 16。 */
    private const val BUTTON_GAP = 16
    private const val BUTTON_TEXT_SIZE = 14
    private const val BUTTON_ICON_SIZE = 20
    private const val ONE_CLICK_ICON_GAP = 8
    private const val OTHER_WAYS_ICON_GAP = 10
    private const val LOADING_SIZE = 16
    private const val LOADING_GAP = 8

    /** 协议排在最后一个按钮下方 30 处，至少留两行（每行 16）的高度，下面再留 30。 */
    private const val CLAUSE_GAP = 30
    private const val CLAUSE_LINE_HEIGHT = 16
    private const val CLAUSE_TEXT_SIZE = 10
    private const val CHECKBOX_SIZE = 12
    private const val BOTTOM_PADDING = 30

    private const val COLOR_SHEET = 0xFF1C1C1C.toInt()
    private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
    private const val COLOR_SECONDARY_TEXT = 0x66FFFFFF
    private const val COLOR_OTHER_BUTTON_TEXT = 0x99FFFFFF.toInt()
    private const val COLOR_LOGIN_TEXT = 0xFF0A0A0A.toInt()
    private const val COLOR_LINK = 0xFF7F58F7.toInt()

    /**
     * 一键登录按钮的文案前垫两个全角空格，给左边的图标和间距让出位置：
     * 文字仍由 SDK 的按钮自己居中，图标作为自定义控件压在这两个空格的位置上。
     */
    private const val BUTTON_ICON_SPACE = "　　"

    /** 估算协议行数时用来占位的运营商条款名（移动、联通 12 个字，电信 13 个）。 */
    private const val CARRIER_CLAUSE_PLACEHOLDER = "《中国移动认证服务条款》"

    interface Actions {
        fun onOtherLoginClick()
        fun onWechatLoginClick()
    }

    /**
     * @param hostActivity 发起登录的页面，用来读底部系统栏高度；拿不到时按 0 处理。
     */
    fun build(
        context: Context,
        hostActivity: Activity?,
        params: JVerifySheetParams,
        actions: Actions,
    ): JVerifyUIConfig {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        fun px(dp: Number): Int = (dp.toFloat() * density + 0.5f).toInt()
        fun spPx(sp: Number): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), metrics)

        val widthDp = (metrics.widthPixels / density).roundToInt().coerceAtLeast(260)
        val widthPx = metrics.widthPixels
        val buttonWidth = (widthDp - SIDE_PADDING * 2).coerceIn(220, 420)
        val buttonLeft = (widthDp - buttonWidth) / 2

        // 一键登录是第 0 个整行按钮；短信、微信按传入的文案决定要不要，依次往下排。
        val extraButtons = ArrayList<ExtraButton>()
        if (params.otherLoginText.isNotEmpty()) {
            extraButtons.add(ExtraButton(params.otherLoginText, "jv_login_sms", actions::onOtherLoginClick))
        }
        if (params.wechatLoginText.isNotEmpty()) {
            extraButtons.add(ExtraButton(params.wechatLoginText, "jv_login_wechat", actions::onWechatLoginClick))
        }
        fun buttonTopAt(index: Int) = BUTTON_TOP + index * (BUTTON_HEIGHT + BUTTON_GAP)

        val clauseTop = buttonTopAt(extraButtons.size) + BUTTON_HEIGHT + CLAUSE_GAP
        val clauseHeight = maxOf(CLAUSE_LINE_HEIGHT * 2, clauseHeightDp(params, widthDp, density, ::spPx))
        val contentHeight = clauseTop + clauseHeight + BOTTOM_PADDING
        val sheetHeight = contentHeight + bottomInsetDp(hostActivity, density)

        val builder = JVerifyUIConfig.Builder()

        // ---- 弹窗本体 ----
        builder.setDialogTheme(widthDp, sheetHeight, 0, 0, true)
        // 圆角和顶部的拖动条画在背景里。
        builder.setAuthBGImgPath("jv_sheet_bg")
        builder.setNavHidden(true)
        builder.setNavColor(COLOR_SHEET)
        builder.setNavReturnImgPath("jv_nav_back")
        builder.setVirtualButtonColor(COLOR_SHEET)
        builder.setLogoHidden(true)
        builder.setNeedStartAnim(true)
        builder.setNeedCloseAnim(true)

        // ---- 号码、运营商说明 ----
        builder.setNumberColor(COLOR_WHITE)
        builder.setNumberSize(28)
        builder.setNumberTextBold(false)
        builder.setNumFieldOffsetY(NUMBER_TOP)
        builder.setSloganTextColor(COLOR_SECONDARY_TEXT)
        builder.setSloganTextSize(10)
        builder.setSloganOffsetY(SLOGAN_TOP)

        // ---- 一键登录按钮 ----
        builder.setLogBtnWidth(buttonWidth)
        builder.setLogBtnHeight(BUTTON_HEIGHT)
        builder.setLogBtnOffsetY(BUTTON_TOP)
        builder.setLogBtnText(BUTTON_ICON_SPACE + params.loginButtonText)
        builder.setLogBtnTextColor(COLOR_LOGIN_TEXT)
        builder.setLogBtnTextSize(BUTTON_TEXT_SIZE)
        builder.setLogBtnTextBold(true)
        builder.setLogBtnImgPath("jv_btn_login")

        // 图标排在可见文字的左边：按钮文字居中，先量出整段文字的宽度，再推出两个空格之后文字的起点。
        val buttonPaint = TextPaint().apply {
            textSize = spPx(BUTTON_TEXT_SIZE)
            typeface = Typeface.DEFAULT_BOLD
        }
        val spaceWidth = buttonPaint.measureText(BUTTON_ICON_SPACE)
        val titleWidth = buttonPaint.measureText(BUTTON_ICON_SPACE + params.loginButtonText)
        val labelStart = (widthPx - titleWidth) / 2f + spaceWidth
        val iconLeftPx = (labelStart - px(ONE_CLICK_ICON_GAP) - px(BUTTON_ICON_SIZE)).roundToInt()
            .coerceAtLeast(px(buttonLeft + 12))
        val iconTopPx = px(BUTTON_TOP + (BUTTON_HEIGHT - BUTTON_ICON_SIZE) / 2)

        // 登录中的 loading：垂直居中在按钮里，排在图标左侧，不和图标叠在一起。
        val loadingLeftPx = (iconLeftPx - px(LOADING_GAP) - px(LOADING_SIZE)).coerceAtLeast(px(buttonLeft + 12))
        try {
            builder.setLoadingView(
                JVerifyLoadingView.create(
                    context,
                    loadingLeftPx,
                    px(BUTTON_TOP + (BUTTON_HEIGHT - LOADING_SIZE) / 2),
                    px(LOADING_SIZE),
                    COLOR_LOGIN_TEXT,
                ),
                JVerifyLoadingView.spin(),
            )
        } catch (_: Throwable) {
            // 换不了就用 SDK 默认的 loading。
        }

        // ---- 勾选框和协议 ----
        builder.setPrivacyState(false)
        builder.setPrivacyCheckboxHidden(false)
        builder.setPrivacyCheckboxSize(CHECKBOX_SIZE)
        // 勾选框贴着第一行，上边只让 1，和第一行文字的中线对齐。
        builder.setPrivacyCheckboxInCenter(false)
        builder.setPrivacyCheckboxMargin(3, 1, 3, 3)
        builder.setCheckedImgPath("jv_check_on")
        builder.setUncheckedImgPath("jv_check_off")
        // Android 按底边定位时参照的不是弹窗底边，和其他元素一样从顶边算。
        builder.setPrivacyTopOffsetY(clauseTop)
        builder.setPrivacyMarginL(SIDE_PADDING)
        builder.setPrivacyMarginR(SIDE_PADDING)
        builder.setPrivacyTextSize(CLAUSE_TEXT_SIZE)
        builder.setPrivacyTextCenterGravity(false)
        builder.setPrivacyWithBookTitleMark(true)
        builder.setPrivacyText(params.privacyPrefix, params.privacySuffix)
        builder.setAppPrivacyColor(COLOR_SECONDARY_TEXT, COLOR_LINK)
        if (params.privacyItems.isNotEmpty()) {
            builder.setPrivacyNameAndUrlBeanList(privacyBeans(params))
        }
        // 条款用系统浏览器打开：弹窗模式下 SDK 自带的条款页也只有弹窗那么大。
        // 下面的导航栏配置只在 SDK 回退到自带页时生效。
        builder.setOpenPrivacyInBrowser(true)
        builder.setPrivacyNavColor(COLOR_SHEET)
        builder.setPrivacyNavTitleTextColor(COLOR_WHITE)
        builder.setPrivacyNavReturnBtnPath("jv_nav_back")
        builder.setPrivacyStatusBarColorWithNav(true)
        // 没勾选就点一键登录时的提示。
        val toast = if (params.privacyUncheckedToast.isNotEmpty()) {
            Toast.makeText(context.applicationContext, params.privacyUncheckedToast, Toast.LENGTH_SHORT)
        } else {
            null
        }
        builder.enableHintToast(true, toast)

        // ---- 自定义控件（后加的画在上面，所以图标排在它所在的按钮后面）----
        val noop = JVerifyUIClickCallback { _, _ -> }
        val textWidth = widthDp - SIDE_PADDING * 2 - HEADER_ART_SIZE
        if (params.title.isNotEmpty()) {
            builder.addCustomView(
                label(context, params.title, 20f, COLOR_WHITE, bold = true).positioned(
                    px(SIDE_PADDING + 4), px(TITLE_TOP), px(textWidth), RelativeLayout.LayoutParams.WRAP_CONTENT,
                ),
                false,
                noop,
            )
        }
        if (params.subtitle.isNotEmpty()) {
            builder.addCustomView(
                label(context, params.subtitle, 12f, COLOR_SECONDARY_TEXT, bold = false).positioned(
                    px(SIDE_PADDING + 4), px(SUBTITLE_TOP), px(textWidth), RelativeLayout.LayoutParams.WRAP_CONTENT,
                ),
                false,
                noop,
            )
        }
        builder.addCustomView(
            image(context, "jv_sheet_header").positioned(
                px(widthDp - SIDE_PADDING - HEADER_ART_SIZE), px(16), px(HEADER_ART_SIZE), px(HEADER_ART_SIZE),
            ),
            false,
            noop,
        )
        builder.addCustomView(
            image(context, "jv_login_phone").positioned(
                iconLeftPx, iconTopPx, px(BUTTON_ICON_SIZE), px(BUTTON_ICON_SIZE),
            ),
            false,
            noop,
        )
        extraButtons.forEachIndexed { i, button ->
            val view = secondaryButton(context, button, ::px).positioned(
                px(buttonLeft), px(buttonTopAt(i + 1)), px(buttonWidth), px(BUTTON_HEIGHT),
            )
            // 第二个参数是“点了就关授权页”。这里不关：没勾选协议时要留在原地提示。
            builder.addCustomView(view, false) { _, _ -> button.onClick() }
        }
        return builder.build()
    }

    private class ExtraButton(val text: String, val icon: String, val onClick: () -> Unit)

    /**
     * SDK 把运营商条款固定排在最前面，自定义条款依次接在后面，每个条款名前带一个分隔符：
     * 前缀《运营商条款》、《条款 1》和《条款 2》后缀。没单独指定时，最后一个用 privacySeparator（缺省“和”），其余用“、”。
     */
    private fun privacyBeans(params: JVerifySheetParams): List<PrivacyBean> {
        val items = params.privacyItems
        // 必须是可变列表：SDK 会把运营商条款插到这个列表的最前面。
        val beans = ArrayList<PrivacyBean>(items.size + 1)
        items.forEachIndexed { index, item ->
            val separator = item.separator ?: if (index == items.lastIndex) params.privacySeparator else "、"
            beans.add(PrivacyBean(item.name, item.url, separator))
        }
        return beans
    }

    /** 按实际字体缩放量出协议要占几行，窄屏或系统大字体时弹窗跟着变高，不会把协议挤出弹窗。 */
    private fun clauseHeightDp(
        params: JVerifySheetParams,
        widthDp: Int,
        density: Float,
        spPx: (Number) -> Float,
    ): Int {
        return try {
            val text = buildString {
                append(params.privacyPrefix)
                append(CARRIER_CLAUSE_PLACEHOLDER)
                params.privacyItems.forEachIndexed { index, item ->
                    append(item.separator ?: if (index == params.privacyItems.lastIndex) params.privacySeparator else "、")
                    append('《').append(item.name).append('》')
                }
                append(params.privacySuffix)
            }
            val paint = TextPaint().apply { textSize = spPx(CLAUSE_TEXT_SIZE) }
            // 左右边距各 20，勾选框 12 加左右各 3 的外边距。
            val available = ((widthDp - SIDE_PADDING * 2 - CHECKBOX_SIZE - 6) * density).toInt().coerceAtLeast(1)
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, available)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(true)
                .build()
            ceil(layout.height / density).toInt()
        } catch (_: Throwable) {
            CLAUSE_LINE_HEIGHT * 2
        }
    }

    /** 底部系统栏（三键导航或手势条）的高度。弹窗贴着屏幕底边，内容要让到系统栏上面。 */
    private fun bottomInsetDp(activity: Activity?, density: Float): Int {
        return try {
            val decor = activity?.window?.decorView ?: return 0
            val insets = ViewCompat.getRootWindowInsets(decor) ?: return 0
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            (bottom / density).roundToInt().coerceIn(0, 80)
        } catch (_: Throwable) {
            0
        }
    }

    /** 自定义文字抬高一层：和 SDK 自带文字重叠时显示在上面。 */
    private fun label(context: Context, text: String, sizeSp: Float, color: Int, bold: Boolean): TextView {
        val view = TextView(context)
        view.text = text
        view.setTextColor(color)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) view.typeface = Typeface.DEFAULT_BOLD
        view.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        view.isSingleLine = true
        view.ellipsize = TextUtils.TruncateAt.END
        view.includeFontPadding = false
        view.elevation = context.resources.displayMetrics.density
        return view
    }

    /** 纯图片：不接收触摸，固定抬高 7dp，画在按钮上面、loading（8dp）下面。 */
    private fun image(context: Context, drawableName: String): ImageView {
        val view = PassThroughImageView(context)
        val id = drawableId(context, drawableName)
        if (id != 0) view.setImageResource(id)
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        view.elevation = 7f * context.resources.displayMetrics.density
        return view
    }

    /** 短信登录 / 微信登录：整行按钮，图标和文字作为一个整体居中，中间隔 10。 */
    private fun secondaryButton(context: Context, button: ExtraButton, px: (Number) -> Int): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        val background = drawableId(context, "jv_btn_other")
        if (background != 0) row.setBackgroundResource(background)

        val icon = ImageView(context)
        val iconId = drawableId(context, button.icon)
        if (iconId != 0) icon.setImageResource(iconId)
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        row.addView(icon, LinearLayout.LayoutParams(px(BUTTON_ICON_SIZE), px(BUTTON_ICON_SIZE)))

        val text = TextView(context)
        text.text = button.text
        text.setTextColor(COLOR_OTHER_BUTTON_TEXT)
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SIZE.toFloat())
        text.isSingleLine = true
        text.ellipsize = TextUtils.TruncateAt.END
        text.includeFontPadding = false
        val textParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        textParams.leftMargin = px(OTHER_WAYS_ICON_GAP)
        row.addView(text, textParams)
        return row
    }

    private fun drawableId(context: Context, name: String): Int =
        context.resources.getIdentifier(name, "drawable", context.packageName)

    private fun <T : View> T.positioned(leftPx: Int, topPx: Int, widthPx: Int, heightPx: Int): T {
        val params = RelativeLayout.LayoutParams(widthPx, heightPx)
        params.leftMargin = leftPx
        params.topMargin = topPx
        layoutParams = params
        return this
    }
}
