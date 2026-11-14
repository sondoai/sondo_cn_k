package com.sondo.app.core.constants

/**
 * 本地存储键（与 Flutter 侧 StorageKey 对应；统一经 [com.sondo.app.core.storage.Sp] 读写）。
 */
object StorageKey {
    const val token = "token"
    // Refresh token
    const val refreshToken = "refresh_token"
    // 测试入口覆盖的主接口地址；空则回落 API_ENV。
    const val debugApiBaseUrl = "debug_api_base_url"
    // Whether user agreed to terms
    const val agreedToTerms = "agreedToTerms"
    // 当前已同意的隐私政策版本。版本变化时会重新展示隐私同意 Gate。
    const val privacyConsentVersion = "privacy_consent_version"
    // 隐私政策同意时间（UTC ISO-8601 字符串），用于审计和设置页展示。
    const val privacyConsentAt = "privacy_consent_at"
    const val photoAlbumAgreement = "photoAlbumAgreement"
    // 是否首次打开
    const val isFirstOpen = "isFirstOpen"
    // 是否显示引导页
    const val isDisplayGuide = "isDisplayGuide"
    // 控制显示引导页版本
    const val splashGuide = "splash_guide_version"
    // 是否显示过评分弹窗 用于防止重复显示
    const val isShowRating = "isShowRating"
    // 是否首次没有积分分享
    const val isShareGetPoint = "isShareGetPoint"
    const val language = "language"
    // 是否显示视频质量介绍
    const val isShowVideoQuality = "is_show_video_quality"
    // fcm token
    const val fcmToken = "fcm_token"
    // 是否显示音频裁剪教程
    const val isCropTutorial = "is_crop_tutorial"
    // 是否显示通知
    const val isShowNotified = "is_show_notified"
    // 是否同意发布
    const val isAgreePublish = "is_agree_publish"
    // 播放器详情页歌词引导是否已显示
    const val playerLyricsGuideShown = "player_lyrics_guide_shown"
    // 故事版编辑页首次操作引导是否已显示
    const val isEditVideoGuideShown = "is_edit_video_guide_shown"
    // 自己 MV 成片播放页首次编辑引导是否已显示
    const val isMvPlaybackEditGuideShown = "is_mv_playback_edit_guide_shown"
    // 存储邀请人的分享码
    const val referralCode = "referral_code"
    // 未结束的支付宝订单，值为 `userId|orderId`。换号后不能拿别人的订单去查。
    const val alipayPendingOrder = "alipay_pending_order"
    // 未结束的支付宝积分包订单。按账号保存 orderId、orderStr、productId，不和自动续费混用。
    const val alipayPendingTrade = "alipay_pending_trade"
    // 是否同意上传使用声明
    const val isAgreeUpload = "is_agree_upload"
    // 是否同意分享使用声明
    const val isAgreeShare = "is_agree_share"
    // 是否同意下载使用声明
    const val isAgreeDown = "is_agree_down"
    // 是否显示MV引导
    const val isMvGuide = "is_mv_guide"
    // 是否已展示音频裁剪引导
    const val isCropGuide = "is_crop_guide"
    // 用户信息字符串
    const val userInfoStr = "user_info_str"
    // 我知道视频质量介绍
    const val iKnowVideoQuality = "i_know_video_quality"
    // 创建mv 是否去掉水印
    const val isRemoveWatermark = "is_remove_watermark"
    // 创建mv 是否去掉字幕
    const val isRemoveSubtitles = "is_remove_subtitles"
    // 创建 MV Premium 模式 Effects 开关
    const val mvEffectsEnabled = "mv_effects_enabled"
    // 创建mv 视频比例
    const val videoAspectRatio = "video_aspect_ratio"
    const val videoQuality = "video_quality"  // 480 720
    // 创建mv 模式 自动、手动
    const val mvMode = "mv_mode"
    // 展示折扣的条件信息-------------------start-----------------------------------------------
    const val isNewUser = "is_new_user"
    const val isEnteredDiscountPage = "is_entered_discount_page"
    const val enteredDiscountPageExpireTime = "entered_discount_page_expire_time"
    const val isEnteredSubscriptionPage = "is_entered_subscription_page"
    // 展示折扣的条件信息-------------------end-----------------------------------------------
    // 回归周卡 10 分钟窗口的到期时间（毫秒）。0 表示还没开始；已写入后不再重置。
    const val recallProductExpireTime = "recall_product_expire_time"
    // 登录后 RSA 加密的 userId。资格查询不带 token，登出仍保留；删号清空。
    const val recallEncryptedUserId = "recall_encrypted_user_id"
    // 是否曾进入过订阅页（试用视频末段卡片分流用，与折扣漏斗解耦）
    const val hasVisitedSubscribePage = "has_visited_subscribe_page"
    // 删除 7天提示是否已显示
    const val isDeleteDialog = "is_show_delete_dialog"
    // 存储上传的图片路径
    const val uploadedImage = "uploaded_image"
    // 存储用户活跃状态
    const val userActive = "user_active_model"
    // MV 详情页已弹出或已评分的 taskId，不再触发末 3 秒评分横幅
    const val mvRatingSkippedIds = "mv_rating_skipped_ids"
    // 新版MV模式 0 旧版 1 新版
    const val newMvMode = "new_mv_mode"
    const val newMvModeDialog = "new_mv_mode_dialog"
    // 音乐选择弹窗上次选中的 Tab 索引（0=my, 1=upload, 2=trending）
    const val musicSelectLastTab = "music_select_last_tab"
    // 主框架试用浮窗活跃任务 ID。
    const val tryFreeActiveMvId = "try_free_active_mv_id"
    // 主框架试用浮窗用户上传头像 URL。
    const val tryFreeActiveCoverImage = "try_free_active_cover_image"
    // 试用等待页倒计时锚点（任务 startTask 时的毫秒时间戳）。
    const val tryFreeWaitStartedAtMs = "try_free_wait_started_at_ms"
}
