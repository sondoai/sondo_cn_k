package com.sondo.app.base

/**
 * 页面名常量（= Kuikly @Page 名）。命名与 Flutter 侧 Routes 一一对应：
 * 路由 `/music/detail` → 页面名 `music_detail`。
 */
object Routes {
    /** 根页面（主框架：首页 / 创作 / 作品库）。同时承载隐私同意 Gate 与应用级服务。 */
    const val root = "launch"
    const val guide = "guide"
    const val emailLogin = "email_login"
    const val emailRegister = "email_register"
    /** 手机号 / 微信 / 一键登录页（Flutter 侧为弹层，这里是独立透明页） */
    const val login = "login"
    const val musicCreate = "music_create"
    const val musicVideoGen = "music_video_gen"
    const val mvActivity = "mv_activity"
    const val mvStyleTemplateSelect = "mv_style_template_select"
    const val musicDetail = "music_detail"
    const val musicGenre = "music_genre"
    const val mine = "mine"
    const val subscribe = "subscribe"
    const val creditsRecord = "credits_record"
    const val creditsPurchase = "credits_purchase"
    const val storyLine = "story_line"
    const val thinkLineMv = "think_line_mv"
    const val editVideo = "edit_video"
    const val retryShots = "retry_shots"
    const val shotHistory = "shot_history"
    const val profile = "profile"
    const val square = "square"
    const val squarePlay = "square_play"
    const val squarePlayDetail = "square_play_detail"
    const val referral = "referral"
    const val referralRecord = "referral_record"
    const val fourView = "four_view"
    const val fourViewForm = "four_view_form"
    const val setting = "setting"
    const val trash = "trash"
    const val discount = "discount"
    const val recall = "recall"
    const val recallProduct = "recall_product"
    const val myAudio = "my_audio"
    const val pushMessage = "push_message"
    const val createMenu = "create_menu"
    const val tryFreeCreate = "try_free_create"
    const val tryFreeGenWaiting = "try_free_gen_waiting"
    const val mvListDetail = "mv_list_detail"
    const val mvPreviewPlay = "mv_preview_play"
    /** 内置网页（隐私政策 / 用户协议等） */
    const val webview = "webview"

    /** 入场转场（对应 GetPage.transition）。 */
    fun transitionOf(page: String): String? = when (page) {
        musicDetail, musicCreate, musicVideoGen, mvActivity -> Nav.TRANSITION_BOTTOM
        tryFreeCreate, tryFreeGenWaiting -> Nav.TRANSITION_FADE
        // 透明页：容器无转场，入场动画由页内底部弹层承担
        createMenu, login -> Nav.TRANSITION_NONE
        else -> null
    }

    /** 透明背景页（盖在上一页之上）。 */
    fun isTranslucent(page: String): Boolean = page == createMenu || page == login
}
