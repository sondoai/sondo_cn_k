package com.sondo.app.features.music.create

import com.sondo.app.base.AppEnv
import com.sondo.app.base.AppHost
import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.delayMs
import com.sondo.app.data.models.MusicStyleEntity
import com.sondo.app.data.models.UserActiveModel
import com.sondo.app.data.models.jBoolOrNull
import com.sondo.app.data.models.jIntOrNull
import com.sondo.app.data.models.jStrOrNull
import com.sondo.app.features.main.MainController
import com.sondo.app.features.music.create.widgets.MusicCreateBottomBar
import com.sondo.app.features.music.create.widgets.MusicCustomSections
import com.sondo.app.features.music.create.widgets.MusicGenColors
import com.sondo.app.features.music.create.widgets.MusicLabeledInputCard
import com.sondo.app.features.music.create.widgets.PoppinsFace
import com.sondo.app.features.music.create.widgets.showLyricsInputSheet
import com.sondo.app.features.music.create.widgets.showRefinementPromptDialog
import com.sondo.app.features.music.data.MusicGenApi
import com.sondo.app.features.music.data.MusicGenMode
import com.sondo.app.features.music.data.MusicGenParams
import com.sondo.app.service.AppNav
import com.sondo.app.service.ConfigService
import com.sondo.app.service.LibraryTabRefreshService
import com.sondo.app.service.MemoryCacheKeys
import com.sondo.app.service.MemoryCacheService
import com.sondo.app.service.PushService
import com.sondo.app.service.UserStore
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.ui.dialog.showTagOptions
import com.sondo.app.ui.input.SDKeyboardAwareScroll
import com.sondo.app.ui.input.SDKeyboardAwareScrollView
import com.sondo.app.ui.input.SDUnfocus
import com.sondo.app.ui.input.hideKeyboard
import com.sondo.app.ui.nav.SDSegmented
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.PageList
import com.tencent.kuikly.core.views.PageListView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** 音乐创建表单校验字段。 */
internal enum class MusicFormField { description, lyrics, style, title }

/**
 * AI 音乐创作页（Dart: features/music/presentation/pages/create/music_gen_page.dart + MusicGenController），从下至上弹出。
 *
 * 参数（`pagerData.params`，均可选，仅「再创作 / 创作同款」带）：
 * `{musicCreativeMode: 1 简单 | 2 自定义, prompt, title, lyrics, categoryName, categoryId, isInstrumental: Boolean}`。
 * 页面已在栈内时，新参数经 [Events.CREATE_ARGUMENTS] `{page: "music_create", args}` 送达。
 * 无参数时从内存草稿（[MemoryCacheKeys.musicGenForm]）恢复。
 */
@Page(Routes.musicCreate)
internal class MusicCreatePage : BasePager() {

    // --- Tab 与表单 ---
    /** 当前 Tab：0=简单，1=自定义 */
    var tabIndex by observable(0)
    var description by observable("")
    var lyrics by observable("")
    var style by observable("")
    var title by observable("")

    /** 校验失败的字段（红框） */
    var invalidField by observable<MusicFormField?>(null)

    // --- 风格列表 ---
    var isStyleListLoading by observable(false)
    val styleList by observableList<MusicStyleEntity>()

    // --- 创作选项 ---
    /** 人声性别：r=随机，m=男，f=女 */
    var vocal by observable("r")
    val selectedStyle by observableList<String>()
    var isInstrumental by observable(false)

    // --- 加载态 ---
    var isCreateLoading by observable(false)
    var isSimpleShuffleLoading by observable(false)
    var isLyricsShuffleLoading by observable(false)
    var isStyleShuffleLoading by observable(false)

    // --- 底栏 ---
    var createPoints by observable(ConfigService.musicCreatePoint)
    var createLabelKey by observable(UserStore.createButtonLabelKey)
    var createShowPoints by observable(UserStore.createButtonShowPoints)

    var pageListRef: ViewRef<PageListView<*, *>>? = null
    var customScrollRef: ViewRef<SDKeyboardAwareScrollView>? = null

    /** 自定义模式各区块在滚动内容里的 y（校验失败后滚动定位用）。 */
    val sectionY = HashMap<MusicFormField, Float>()

    /** 打开中的全屏歌词编辑弹层：歌词被程序改写（随机 / 清空 / 插标签）时同步给它。 */
    var lyricsSheetSync: ((String) -> Unit)? = null

    private var pageAlive = true

    companion object {
        const val titleMaxLength = 30
        const val styleMaxLength = 200
        private const val PAGE_NAME = "/music_create"
    }

    // ================================================================== 生命周期

    override fun pageBackgroundColor(): Color = MusicGenColors.bgPrimary

    override fun created() {
        super.created()
        // 恢复用户上次停留的 Tab
        val userActive = Sp.getJson(StorageKey.userActive)?.let { UserActiveModel.fromJson(it) } ?: UserActiveModel()
        tabIndex = userActive.musicTabIndex.coerceIn(0, 1)
        // 再创作预填优先于草稿，避免被旧草稿覆盖
        if (!applyRecreateArguments(pagerData.params)) {
            restoreFormDraft()
        }
        on(Events.CREATE_ARGUMENTS) { data ->
            if (data.optString("page", "") != Routes.musicCreate) return@on
            applyRecreateArguments(data.optJSONObject("args"))
        }
        on(Events.USER_CHANGED) { syncCreateButton() }
        on(Events.POINTS_CHANGED) { syncCreateButton() }
        on(Events.CONFIG_CHANGED) { syncCreateButton() }
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        syncCreateButton()
        if (styleList.isEmpty()) getMusicStyleList()
    }

    override fun pageWillDestroy() {
        // 离开页前写入草稿，创建成功后再进页可恢复全部参数
        if (pageAlive) saveFormDraft()
        pageAlive = false
        lyricsSheetSync = null
        super.pageWillDestroy()
    }

    private fun syncCreateButton() {
        createPoints = ConfigService.musicCreatePoint
        createLabelKey = UserStore.createButtonLabelKey
        createShowPoints = UserStore.createButtonShowPoints
    }

    // ================================================================== 参数 / 草稿

    /** 从再创作入口回填表单。有预填返回 true。 */
    fun applyRecreateArguments(args: JSONObject?): Boolean {
        if (args == null) return false
        // 仅再创作会带这些字段，避免残留参数误跳过草稿恢复
        if (!args.has("musicCreativeMode") && !args.has("prompt") && !args.has("lyrics") && !args.has("categoryName")) {
            return false
        }
        val mode = args.jIntOrNull("musicCreativeMode") ?: 1
        if (mode == 1) {
            setDescription(args.jStrOrNull("prompt") ?: "")
            switchTab(0)
        } else {
            setTitle(args.jStrOrNull("title") ?: "")
            setLyrics(args.jStrOrNull("lyrics") ?: "")
            setStyle(args.jStrOrNull("categoryName") ?: "")
            selectedStyle.clear()
            selectedStyle.addAll(style.split(",").filter { it.isNotEmpty() })
            switchTab(1)
        }
        isInstrumental = args.jBoolOrNull("isInstrumental") == true
        return true
    }

    private val hasFormDraftContent: Boolean
        get() = description.isNotEmpty() || lyrics.isNotEmpty() || style.isNotEmpty() || title.isNotEmpty() ||
            selectedStyle.isNotEmpty() || isInstrumental || vocal != "r"

    /** 离开页时保存快照；空表单则清除旧草稿。 */
    private fun saveFormDraft() {
        // 登出 / 删号后禁止写回，避免 clearAll 被页面关闭抵消
        if (!UserStore.isLogin) return
        if (!hasFormDraftContent) {
            MemoryCacheService.removeForm(MemoryCacheKeys.musicGenForm)
            return
        }
        val styles = JSONArray()
        selectedStyle.forEach { styles.put(it) }
        MemoryCacheService.putForm(
            MemoryCacheKeys.musicGenForm,
            JSONObject()
                .put("description", description)
                .put("lyrics", lyrics)
                .put("style", style)
                .put("title", title)
                .put("vocal", vocal)
                .put("selectedStyle", styles)
                .put("isInstrumental", isInstrumental)
                .put("tabIndex", tabIndex),
        )
    }

    /** 无再创作预填时从内存恢复草稿。 */
    private fun restoreFormDraft() {
        val draft = MemoryCacheService.getForm<JSONObject>(MemoryCacheKeys.musicGenForm) ?: return
        description = draft.optString("description", "")
        lyrics = draft.optString("lyrics", "")
        style = draft.optString("style", "")
        title = draft.optString("title", "")
        vocal = draft.optString("vocal", "r")
        isInstrumental = draft.optBoolean("isInstrumental", false)
        val styles = draft.optJSONArray("selectedStyle")
        if (styles != null) {
            selectedStyle.clear()
            for (i in 0 until styles.length()) selectedStyle.add(styles.optString(i, "") ?: "")
        }
        val savedTab = draft.jIntOrNull("tabIndex")
        if (savedTab != null) tabIndex = savedTab.coerceIn(0, 1)
    }

    // ================================================================== Tab

    /** 程序切换 Tab（再创作回填）。与 Dart 设 tabController.index 一致，变化时同样持久化 + 埋点。 */
    private fun switchTab(index: Int) {
        if (tabIndex == index) return
        tabIndex = index
        pageListRef?.view?.scrollToPageIndex(index, false)
        tabChange(index)
    }

    /** 点击分段控件。 */
    fun onSegmentTap(index: Int) {
        if (index == tabIndex) return
        pageListRef?.view?.scrollToPageIndex(index, true)
        onPageChanged(index)
    }

    /** 左右滑动 / 点击分段后页码变化。 */
    fun onPageChanged(index: Int) {
        if (index == tabIndex) return
        tabIndex = index
        tabChange(index)
    }

    /** Tab 切换时持久化用户偏好并上报埋点。 */
    private fun tabChange(index: Int) {
        val current = Sp.getJson(StorageKey.userActive)?.let { UserActiveModel.fromJson(it) }
        val next = current?.copy(musicTabIndex = index) ?: UserActiveModel(musicTabIndex = index)
        Sp.setJson(StorageKey.userActive, next.toJson())
        UmengUtils.logEventButtonClick(if (index == 0) "music_simple_tag" else "music_custom_tag", pageName = PAGE_NAME)
    }

    // ================================================================== 字段变更（对应 Dart 的 TextEditingController 监听）

    @kotlin.jvm.JvmName("setDescriptionFn")
    fun setDescription(v: String) {
        description = v
        val d = v.trim()
        if (invalidField == MusicFormField.description && d.isNotEmpty() && d.length <= 200) invalidField = null
    }

    fun setLyrics(v: String, fromSheet: Boolean = false) {
        lyrics = v
        if (!fromSheet) lyricsSheetSync?.invoke(v)
        val l = v.trim()
        if (invalidField == MusicFormField.lyrics &&
            (l.isNotEmpty() || isInstrumental) && (isInstrumental || l.length <= 3000)
        ) invalidField = null
    }

    @kotlin.jvm.JvmName("setStyleFn")
    fun setStyle(v: String) {
        style = v
        if (invalidField == MusicFormField.style && v.trim().isNotEmpty()) invalidField = null
    }

    @kotlin.jvm.JvmName("setTitleFn")
    fun setTitle(v: String) {
        title = v
        if (invalidField == MusicFormField.title && v.trim().length <= titleMaxLength) invalidField = null
    }

    /** 纯音乐开关。 */
    fun onInstrumental(value: Boolean) {
        isInstrumental = value
        if (value && invalidField == MusicFormField.lyrics) invalidField = null
    }

    // ================================================================== 校验定位

    /** 标记校验失败字段：红框 + 滚动 + Toast。 */
    private fun markInvalid(field: MusicFormField, toastMessage: String) {
        invalidField = field
        toast(toastMessage)
        scrollToSection(field)
    }

    /** 把区块滚到视口 20% 处（Dart scrollToSection alignment 0.2）。简单模式只有一张卡，无需滚动。 */
    private fun scrollToSection(field: MusicFormField) {
        if (field == MusicFormField.description) return
        val y = sectionY[field] ?: return
        postDelayed(16) {
            val scroller = customScrollRef?.view?.scroller ?: return@postDelayed
            val viewport = scroller.frame.height
            val contentH = scroller.contentView?.frame?.height ?: 0f
            val maxOffset = (contentH - viewport).coerceAtLeast(0f)
            val target = (y - viewport * 0.2f).coerceIn(0f, maxOffset)
            scroller.setContentOffset(0f, target, true)
        }
    }

    // ================================================================== 随机 / 风格

    /** 随机生成歌词，服务端用 `qinhongyang` 分隔歌词与标题。 */
    fun createLyrics() {
        // 纯音乐无人声，禁止随机填歌词
        if (isInstrumental) {
            toast("pure_music_mode_no_lyrics_needed".tr)
            return
        }
        if (isLyricsShuffleLoading) return
        isLyricsShuffleLoading = true
        launch {
            val result = MusicGenApi.getRandomLyric()
            isLyricsShuffleLoading = false
            result.onSuccess { res ->
                if (res.isNotEmpty()) {
                    if (res.contains("qinhongyang")) {
                        val parts = res.split("qinhongyang")
                        setLyrics(parts[0])
                        setTitle(if (parts.size > 1) parts[1] else "")
                    } else {
                        setLyrics(res)
                    }
                }
            }
        }
    }

    /** 随机生成风格 prompt 并同步选中 chip。 */
    fun getMusicStylePrompt() {
        if (isStyleShuffleLoading) return
        isStyleShuffleLoading = true
        launch {
            val result = MusicGenApi.getStylePrompt()
            isStyleShuffleLoading = false
            result.onSuccess { res ->
                if (res.isNotEmpty()) {
                    setStyle(res)
                    selectedStyle.clear()
                    styleList.forEach { if (res.contains(it.value)) selectedStyle.add(it.value) }
                }
            }
        }
    }

    /** 拉取音乐风格标签列表。 */
    fun getMusicStyleList() {
        if (styleList.isNotEmpty() || isStyleListLoading) return
        isStyleListLoading = true
        launch {
            val result = MusicGenApi.getStyleList()
            isStyleListLoading = false
            result.onSuccess { res ->
                styleList.clear()
                styleList.addAll(res)
            }
        }
    }

    /** 切换风格 chip 选中态并同步风格文本。 */
    private fun selectStyle(value: String) {
        var newText = style
        if (newText.isNotEmpty()) {
            if (newText.contains(value)) {
                newText = if (newText.contains(",$value")) newText.replace(",$value", "") else newText.replace(value, "")
            } else {
                newText += ",$value"
            }
        } else {
            newText = value
        }
        if (newText.isNotEmpty() && newText[0] == ',') newText = newText.substring(1)
        setStyle(newText)
    }

    /** 点击风格 chip 切换选中。 */
    fun toggleStyleChip(value: String) {
        if (selectedStyle.contains(value)) selectedStyle.remove(value) else selectedStyle.add(value)
        selectStyle(value)
    }

    /** 清空风格文本与已选 chip。 */
    fun clearStyle() {
        setStyle("")
        selectedStyle.clear()
    }

    /** 简单模式随机生成描述 prompt。 */
    fun getMusicSimplePromptList() {
        if (isSimpleShuffleLoading) return
        isSimpleShuffleLoading = true
        launch {
            val result = MusicGenApi.getSimplePrompt()
            isSimpleShuffleLoading = false
            result.onSuccess { res -> if (res.isNotEmpty()) setDescription(res) }
        }
    }

    fun onVocalSelected(value: String) {
        vocal = value
    }

    // ================================================================== 弹层

    /** 打开歌词润色弹窗，需登录。 */
    fun onRefinementPrompt() {
        // 纯音乐无人声，禁止润色歌词
        if (isInstrumental) {
            toast("pure_music_mode_no_lyrics_needed".tr)
            return
        }
        val ctx = this
        launch {
            var isLogin = UserStore.isLogin
            if (!isLogin) isLogin = UserStore.showLoginDialog(ctx)
            if (!isLogin || !ctx.pageAlive) return@launch
            ctx.showRefinementPromptDialog(initialText = ctx.lyrics, maxLength = 1000) { value -> ctx.setLyrics(value) }
        }
    }

    /** 打开全屏歌词编辑弹层。 */
    fun onLyricsInput() {
        showLyricsInputSheet()
    }

    /** 插入歌词结构标签（Verse / Chorus 等）。[cursor] 为编辑框光标位置，< 0 时追加到末尾。 */
    fun openTag(cursor: Int) {
        hideKeyboard()
        var handle: com.sondo.app.ui.overlay.DialogHandle? = null
        handle = showTagOptions { value ->
            val text = lyrics
            val textToInsert = "\n$value\n"
            if (cursor < 0 || cursor > text.length) {
                setLyrics("$text\n$value\n")
            } else {
                setLyrics(text.substring(0, cursor) + textToInsert + text.substring(cursor))
            }
            handle?.dismiss()
        }
    }

    // ================================================================== 提交

    /** 提交音乐生成：登录校验 → 积分校验 → 表单校验 → 使用声明 → 内容审核 → 提交。 */
    fun generateMusic() {
        if (isCreateLoading) return
        if (isSimpleShuffleLoading || isLyricsShuffleLoading || isStyleShuffleLoading) return
        isCreateLoading = true
        hideKeyboard()
        val ctx = this
        launch {
            var isLogin = UserStore.isLogin
            if (!isLogin) isLogin = UserStore.showLoginDialog(ctx)
            if (!ctx.pageAlive) return@launch
            if (!isLogin) {
                ctx.isCreateLoading = false
                return@launch
            }
            val isTryFree = (UserStore.userInfo?.hasTried ?: 0) > 0
            val createMusicPoint = ConfigService.musicCreatePoint
            // 有使用次数不用判断积分
            if (!isTryFree) {
                val userPoint = UserStore.userInfo?.point ?: 0
                // 积分不足：已订阅走积分购买，未订阅走订阅页
                if (userPoint < createMusicPoint || userPoint <= 0) {
                    if (UserStore.isSubscribed) {
                        UmengUtils.logEvent("credit_insufficient_popup")
                        ctx.nav.push(Routes.creditsPurchase)
                    } else {
                        ctx.nav.push(Routes.subscribe)
                    }
                    ctx.isCreateLoading = false
                    return@launch
                }
            }
            if (!ctx.validateForm()) {
                ctx.isCreateLoading = false
                return@launch
            }
            ctx.invalidField = null

            // 撤回后本机标记已清，提交前再问一次。取消不审核、不扣积分，草稿留在输入框。
            val isAgree = UserStore.showAgree(StorageKey.isAgreeUpload, "upload", ctx)
            if (!ctx.pageAlive) return@launch
            if (!isAgree) {
                ctx.isCreateLoading = false
                return@launch
            }

            ctx.showLoading()
            val moderationError = ctx.runModeration()
            if (moderationError != null) {
                ctx.hideLoading()
                ctx.isCreateLoading = false
                ctx.toast(moderationError)
                return@launch
            }

            val tab = ctx.tabIndex
            val result = MusicGenApi.generateMusic(ctx.buildParams(createMusicPoint))
            result.onSuccess {
                // 清除选曲弹窗「我的作品」缓存
                MemoryCacheService.clearByPrefix(MemoryCacheKeys.musicSelectMy)
                // 刷新作品库我的歌曲 + 用户信息（积分）；跑在宿主上，不随本页关闭中断
                val libraryBound = LibraryTabRefreshService.isBound
                AppHost.launch { LibraryTabRefreshService.refreshSong(isCreate = true) }
                AppHost.launch { UserStore.getUserInfo() }
                if (!libraryBound) {
                    // 作品库未挂载时由这里补一次通知权限引导（参考工程在作品库刷新回调里延迟 2s 弹出）
                    AppHost.launch {
                        delayMs(2000)
                        PushService.showNotifiedDialog("music", pager = AppNav.host)
                    }
                }
                ctx.postDelayed(1000) {
                    ctx.hideLoading()
                    ctx.isCreateLoading = false
                    ctx.toast("create_success".tr)
                    // 切到作品库并只关闭本页（Dart leaveCreateAfterSuccess），下层页面保留
                    MainController.leaveCreateAfterSuccess(ctx)
                }
            }.onFailure {
                ctx.isCreateLoading = false
                ctx.hideLoading()
                ctx.toast("request_failed".tr)
            }
            UmengUtils.logEventButtonClick(
                if (tab == 0) "music_smiple_create" else "music_custom_create",
                pageName = PAGE_NAME,
            )
        }
    }

    /** 按当前 Tab 校验表单字段长度与必填项。空白视为未填。 */
    private fun validateForm(): Boolean {
        if (tabIndex == 0) {
            val d = description.trim()
            if (d.isEmpty()) {
                markInvalid(MusicFormField.description, "please_enter_song_description".tr)
                return false
            }
            if (d.length > 200) {
                markInvalid(MusicFormField.description, "text_exceeds_the_limit".trParams("name" to "description".tr))
                return false
            }
        } else {
            val l = lyrics.trim()
            val s = style.trim()
            val t = title.trim()
            if (l.isEmpty() && !isInstrumental) {
                markInvalid(MusicFormField.lyrics, "please_enter_song_lyrics".tr)
                return false
            }
            if (s.isEmpty()) {
                markInvalid(MusicFormField.style, "please_select_song_style".tr)
                return false
            }
            if (!isInstrumental && l.length > 3000) {
                markInvalid(MusicFormField.lyrics, "text_exceeds_the_limit".trParams("name" to "lyrics".tr))
                return false
            }
            if (t.length > titleMaxLength) {
                markInvalid(MusicFormField.title, "text_exceeds_the_limit".trParams("name" to "enter_title".tr))
                return false
            }
        }
        return true
    }

    private suspend fun moderationMessage(text: String): String? =
        MusicGenApi.checkText(text).getOrNull()?.takeIf { it.isNotEmpty() }

    /** 按 Tab 审核不同字段：简单模式审描述，自定义模式审歌词与标题。 */
    private suspend fun runModeration(): String? {
        if (tabIndex == 0) {
            val d = description.trim()
            if (d.isEmpty()) return null
            return moderationMessage(d)
        }
        if (!isInstrumental) {
            val l = lyrics.trim()
            if (l.isNotEmpty()) {
                val err = moderationMessage(l)
                if (err != null) return err
            }
        }
        val t = title.trim()
        if (t.isEmpty()) return null
        return moderationMessage(t)
    }

    /** 构建提交参数。随机人声与当前模式用不到的字段留空；纯音乐清空歌词。 */
    private fun buildParams(point: Int): MusicGenParams {
        if (tabIndex == 0) {
            return MusicGenParams(
                mode = MusicGenMode.simple,
                musicPrompt = description.trim(),
                instrumental = isInstrumental,
                point = point,
            )
        }
        return MusicGenParams(
            mode = MusicGenMode.custom,
            instrumental = isInstrumental,
            musicTitle = title.trim(),
            musicLyric = if (isInstrumental) "" else lyrics.trim(),
            musicVocalGender = if (vocal == "r") "" else vocal,
            tags = style.trim(),
            point = point,
        )
    }

    // ================================================================== UI

    override fun content(): ViewBuilder {
        val ctx = this
        val w = ctx.screenWidth
        return {
            SDUnfocus {
                attr { backgroundColor(MusicGenColors.bgPrimary) }
                // 顶部背景图
                Image {
                    attr {
                        absolutePosition(top = 0f, left = 0f, right = 0f)
                        height(w * 270f / 360f)
                        src(ImageUri.commonAssets(AssetsImage.musicGenBg))
                        resizeCover()
                    }
                }
                View {
                    attr {
                        flex(1f)
                        paddingTop(ctx.statusBarHeight)
                    }
                    // AppBar
                    View {
                        attr {
                            height(56f)
                            allCenter()
                        }
                        View {
                            attr {
                                absolutePosition(left = 0f, top = 4f)
                                size(48f, 48f)
                                allCenter()
                            }
                            event { click { ctx.nav.pop() } }
                            Image {
                                attr {
                                    size(28f, 28f)
                                    src(ImageUri.commonAssets(AssetsImage.backLeft))
                                    resizeContain()
                                    tintColor(Color.WHITE)
                                }
                            }
                        }
                        Text {
                            attr {
                                text("ai_music".tr)
                                fontSize(18f)
                                fontFamily(PoppinsFace.bold)
                                color(Color.WHITE)
                            }
                        }
                    }
                    View { attr { height(10f) } }
                    // 模式切换
                    SDSegmented {
                        attr {
                            margin(0f, 16f, 0f, 16f)
                            segments = listOf("simple".tr, "custom".tr)
                            currentIndex = ctx.tabIndex
                            segmentHeight = 42f
                            containerColor = MusicGenColors.tabContainer
                            selectedColor = MusicGenColors.tabSelected
                            labelColor = Color.WHITE
                            unselectedLabelColor = MusicGenColors.secondaryText
                            labelSize = 14f
                        }
                        event { onChanged { ctx.onSegmentTap(it) } }
                    }
                    View { attr { height(16f) } }
                    PageList {
                        ref { ctx.pageListRef = it }
                        attr {
                            flex(1f)
                            // true = 横向翻页（简单 / 自定义左右切换）；false 是竖向
                            pageDirection(true)
                            pageItemWidth(w)
                            defaultPageIndex(ctx.tabIndex)
                            showScrollerIndicator(false)
                            keepItemAlive(true)
                        }
                        event {
                            pageIndexDidChanged {
                                val index = (it as? JSONObject)?.optInt("index", 0) ?: 0
                                ctx.onPageChanged(index)
                            }
                        }
                        // 简单模式
                        View {
                            attr {
                                width(w)
                                padding(0f, 16f, 0f, 16f)
                            }
                            MusicLabeledInputCard(ctx, isSimple = true)
                        }
                        // 自定义模式
                        View {
                            attr {
                                width(w)
                                padding(0f, 16f, 0f, 16f)
                            }
                            SDKeyboardAwareScroll {
                                ref { ctx.customScrollRef = it }
                                attr {
                                    flex(1f)
                                    // Create 底栏：padding 20 + 48 + 20
                                    marginBottom = 88f
                                    bounces = !AppEnv.isAndroid
                                }
                                content { MusicCustomSections(ctx) }
                            }
                        }
                    }
                }
                MusicCreateBottomBar(ctx)
            }
        }
    }
}
