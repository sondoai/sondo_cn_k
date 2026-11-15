package com.sondo.app.core.constants

/** 跨 Feature 共用的 HTTP API 路径常量（与 Flutter 侧 api_paths.dart 一一对应）。 */
object ApiPaths {
    // --- 文件上传 ---

    const val uploadPresigned = "/infra/file/upload-presigned"
    const val uploadFileCheckV2 = "/infra/file/uploadFileCheckV2"

    // --- 内容审核 ---

    const val checkTextModeration = "/member/moderation/checkTextModerationV1"

    // --- 字典数据 ---

    const val dictDataType = "/system/dict-data/type"
    const val dictDataTypeByLanguage = "/system/dict-data/typeByLanguage"
    const val dictDataMvRecRemark = "/system/dict-data/getMvRecRemark"
    const val dictDataMusicSimplePromptList = "/system/dict-data/musicSimplePromptList"
    const val dictDataMusicRandomLyric = "/system/dict-data/musicRandomLyric"
    const val dictDataMusicStylePromptList = "/system/dict-data/musicStylePromptList"

    // --- 发布列表 ---

    const val mvPublishList = "/member/musicMvTask/getOnePublishlist"
    const val musicNewPublishList = "/member/music/newPublishlist"

    // --- Library 视频详情 ---

    const val mvDetail = "/member/musicMvTask/getInfo"
    const val mvDelete = "/member/musicMvTask/del"
    const val mvAgainMerge = "/member/musicMvTask/againMergeMv"
    const val mvShotList = "/member/musicMvTask/genShotList"
    const val mvCancelShot = "/member/musicMvTask/cancelMvShot"
    const val getShotCustomHistoryPage = "/member/musicMvTask/getShotCustomHistoryPage"
    const val applyShotCustomHistory = "/member/musicMvTask/applyShotCustomHistory"
    const val mvAgainMergeCoverStatus = "/member/musicMvTask/getAgainMergeCoverImg"
    const val mvAgainMergeCoverWithPrompt = "/member/musicMvTask/v1/againMergeCoverImgWithPrompt"
    const val mvRegenCustomShotFlowV2 = "/member/musicMvTask/regenerateCustomShotFlowV2"
    const val mvFeedback = "/member/musicMvTask/feedbackFlowV2"
    const val mvFeedbackRecommendTags = "/member/musicMvTask/feedbackFlowV2/recommendTags/all"
    const val mvFeedbackRate = "/member/musicMvTask/feedbackFlowV2/rate"
    const val mvFeedbackSubmit = "/member/musicMvTask/feedbackFlowV2/submit"

    // --- Library 列表（音乐 / MV）---

    const val musicPage = "/member/music/page"
    const val musicCollectList = "/member/music/collect/list"
    const val musicPolishStream = "/member/music/polishStream/v1"

    const val mvPage = "/member/musicMvTask/page"
    const val mvListByIds = "/member/musicMvTask/getMvListByIds"
    const val mvCollectList = "/member/musicMvTask/collectList"

    // --- 应用配置 / 启动 ---

    const val splashConfig = "/member/splash/config"
    const val appHotOpen = "/app-api/member/music/appHotOpen"
    const val displayGuidePage = "/member/music/displayGuidePage"
    const val luckyWeeklyRetention = "/member/user/luckyWeeklyRetention"

    // 回归落地页欢迎区和功能卡片。`GET ?code=OLD_PEOPLE`，进入页面后再请求。
    const val oldPeopleContent = "/member/content/material-data"

    // 回归周卡商品。客户端再按平台核验商店里的优惠方案。
    const val returningOffer = "/member/product/returning-offer"
    const val countryPricing = "/member/country-pricing/get"
    const val mvConfigs = "/member/musicMvTask/getConfigs"
    // 极光 Registration ID 绑定。baseUrl 已含 `/app-api`，这里不再重复前缀。
    // 国内旧路径 bindUserFcmToken 已关闭（404）。请求体字段名仍是 fcmToken，值传极光 RID。
    // App-Version 由 ApiInterceptor 统一写入，与其他登录后接口一致。
    const val bindUserJPushToken = "/member/user/bindUserJPushToken"

    // --- 支付 ---

    // iOS 支付选项（Apple 商品）。
    const val iosPaymentOptions = "/member/product/iosPaymentOptions"

    // 统一 V2 建单；iOS Apple 传 paymentProvider=APPLE。
    const val iosCreateOrder = "/v2/member/order/createOrder"

    // 回归周卡的 Apple 促销签名。必须用刚建好的订单号，不能在客户端签名。
    const val applePromotionalOffer = "/v2/member/order/apple-promotional-offer"

    // 统一 V2 验单；业务 JSON 加密后放 data/key/iv。
    const val iosVerifyOrder = "/v2/member/order/verifyOrder"

    // 国内支付宝会员商品。`platform=12`。
    const val cnProductList = "/member/product/getList"

    // 开通支付宝自动续费，返回拉起支付宝的链接。
    const val alipaySubscriptionCreate = "/member/alipay/subscription/create"

    // 购买支付宝积分包，返回已签名的 `orderStr`，交给 PayTask.payV2。
    const val alipayTradeCreate = "/member/alipay/trade/create"

    // 查询本人支付宝订单。路径后接 `/{orderId}`。
    const val alipayOrder = "/member/alipay/order"

    // 周期末取消支付宝自动续费。
    const val alipaySubscriptionCancel = "/member/alipay/subscription/cancel"

    // --- 华为支付（鸿蒙）---
    // 约定接口：字段与支付宝同名接口一致，差异见 docs/huawei-pay-api.md（后端按此实现）。
    // 业务码沿用支付宝那一套（1046000001 渠道未开通、1046000005 订单不存在 …）。

    // 购买华为支付积分包，返回商户服务器签好名的 `orderStr`，交给 paymentService.requestPayment。
    const val huaweiTradeCreate = "/member/huawei/trade/create"

    // 开通华为支付自动续费（签约代扣），返回签好名的 `contractStr`，交给 paymentService.requestContract。
    const val huaweiSubscriptionCreate = "/member/huawei/subscription/create"

    // 查询本人华为支付订单。路径后接 `/{orderId}`，回包与 alipayOrder 相同。
    const val huaweiOrder = "/member/huawei/order"

    // 周期末取消华为支付自动续费（解约）。
    const val huaweiSubscriptionCancel = "/member/huawei/subscription/cancel"

    // 当前订阅渠道与是否可取消自动续费。
    const val subscriptionManagement = "/member/order/subscriptionManagement"

    // --- 认证 / 用户 ---

    // 向大陆手机号发短信验证码。成功只表示受理，不代表短信已送达。
    const val sendSmsCode = "/member/auth/send-sms-code"

    // 短信登录；未注册手机号由后端自动注册，客户端不传密码。
    const val smsLogin = "/member/auth/sms-login"

    // 微信授权码换登录结果。未绑手机号时只有 bindToken，此时尚未登录。
    const val wechatLogin = "/member/auth/wechat-login"

    // 用短信验证码把手机号绑到微信 bindToken，成功后才返回正式令牌。
    const val wechatBindMobile = "/member/auth/wechat-bind-mobile"

    // 极光 loginToken 换业务会话。凭证只能提交一次，失败后必须重新取号。
    const val oneClickLogin = "/member/auth/one-click-login"

    // 极光验号代替短信，把手机号绑到微信 bindToken。
    const val wechatBindMobileOneClick = "/member/auth/wechat-bind-mobile-one-click"
    const val authGoogleLogin = "/member/auth/googleLogin"
    const val authEmailLogin = "/member/auth/emailLogin"
    const val authEmailRegister = "/member/auth/emailRegister"
    const val authEmailCaptcha = "/member/auth/emailCaptcha"
    const val authLogout = "/member/auth/logout"
    const val authRefreshToken = "/member/auth/refresh-token"
    const val authDiscordLogin = "/member/auth/discordLoginCallback"
    const val authResetPassword = "/member/auth/resetPassword"
    const val authDelUser = "/member/auth/delUser"

    // 是否有资格自动进回归页。PermitAll，body 只带 RSA 后的 userId（字段 k2）。
    const val returningUser = "/member/auth/returning-user"
    const val userGet = "/member/user/get"
    const val userUpdate = "/member/user/update"
    const val uploadAvatar = "/infra/file/upload"
    const val pointRecordPage = "/member/point/record/page"
    const val exchangeGift = "/member/cdkey/exchangeGift"
    const val shareAndSendPoints = "/member/user/shareAndSendPoints"
    const val musicGenerList = "/system/dict-data/musicGenerList"

    // --- OSS 分片 / 我的音频 ---

    const val uploadMultipartInit = "/infra/file/upload-presigned/multipart/init"
    const val uploadMultipartPartUrls = "/infra/file/upload-presigned/multipart/part-urls"
    const val uploadMultipartCompleteUrl = "/infra/file/upload-presigned/multipart/complete-url"
    const val myAudiosRegister = "/infra/file/my-audios/register"
    const val myAudiosPage = "/infra/file/my-audios/page"
    const val myAudiosDelete = "/infra/file/my-audios/delete"

    // --- 音乐操作 ---

    const val musicListByIds = "/member/music/getMusicListByIds"
    const val musicDelete = "/member/music/del"
    const val musicCollectOperation = "/member/music/collect/collectOperation"
    const val musicPublishOperation = "/member/music/publishOperation"
    const val musicAddRepostCount = "/member/music/addRepostCount"

    // --- MV 操作 ---

    const val mvRecentRoles = "/member/mv/four-view/recent"
    const val mvShotRefImgs = "/member/musicMvTask/getShotImgHistoryListApp"
    const val mvCollectOperation = "/member/mv/collect/collectOperation"
    const val mvAddRepostCount = "/member/musicMvTask/addRepostCount"
    const val mvPublishOperation = "/member/musicMvTask/publishOperation"
    const val mvUpdateInfo = "/member/musicMvTask/updtMvInfo"
    const val mvUpdateShotUrl = "/member/musicMvTask/updt"
    const val mvMergeShot = "/member/musicMvTask/mergeMvShot"
    const val mvRegenerateSynthesis = "/member/musicMvTask/regenerateSynthesisMv"

    // --- 音乐生成 / 详情 / 首页 ---

    const val musicGenerate = "/member/music/generate"
    const val musicDetail = "/member/music/detail"
    const val musicList = "/member/music/list"
    const val musicIndexCategoryPicInfo = "/member/music/indexCategoryPicInfo"

    // DioClient 已含 `/app-api` 前缀时使用；裸 Dio 走 [appHotOpen]。
    const val appHotOpenMember = "/member/music/appHotOpen"

    // --- 试用 MV ---

    const val trialMvTemplates = "/member/trial-mv/templates"
    const val trialMvStart = "/member/trial-mv/start"
    const val trialMvDetail = "/member/trial-mv/detail"
    const val trialMvRetryStage = "/member/trial-mv/retry-stage"
    const val trialMvRetryCover = "/member/trial-mv/retry-cover"
    const val trialMvRetryShot = "/member/trial-mv/retry-shot"

    // --- 广场用户主页 ---

    const val userInfo = "/member/user/info"

    // 账号级「AI生成水印」开关。关闭须带声明版本，由服务端落授权记录。
    const val aigcWatermark = "/member/user/aigc-watermark"

    // 无水印视频导出前校验会员与授权，并写入至少保存六个月的记录。
    const val aigcWatermarkExport = "/member/user/aigc-watermark/export"
    const val userMvPublishList = "/member/musicMvTask/getUserPublishlist"
    const val userMusicPublishList = "/member/music/getUserPublishlist"
    const val mvHit = "/member/musicMvTask/addMvHit"

    // --- 四视图 / 引导 ---

    const val fourViewPage = "/member/mv/four-view/page"
    const val fourViewGetListByIds = "/member/mv/four-view/getListByIds"
    const val fourViewGen = "/member/mv/four-view/gen"
    const val fourViewDelete = "/member/mv/four-view/delete"
    const val onboardingAbAssign = "/member/onboarding-ab/assign"

    // --- MV 生成 ---

    const val genMvCustomTaskFlowV2 = "/member/musicMvTask/genMvCustomTaskFlowV2"
    const val mvDescPolish = "/member/musicMvTask/mvDescPolish"
    const val retryFlowShotV2 = "/member/musicMvTask/retryFlowShotV2"
    const val mvContinueGen = "/member/musicMvTask/continueGenMvFlowV2"

    // --- MV 信息流（think_line_mv）---

    const val mvThinkFlowList = "/member/musicMvTask/getItFlowCustomListFlowV2"
    const val mvThinkFlowRead = "/member/musicMvTask/updtItFlowCustomReadFlowV2"
    const val mvThinkFlowReadAll = "/member/musicMvTask/updtItFlowCustomReadAllFlowV2"
    const val mvThinkFlowRetryStage = "/member/musicMvTask/retryFlowStageFailedShotsFlowV2"
    const val mvThinkFlowRetryCard = "/member/musicMvTask/retryFlowCardFlowV2"

    // --- 推送落地页 ---

    const val pushLandingPage = "/member/push-landing-page/get"

    // --- 回收站 / 邀请 ---

    const val deletedMusicMvPage = "/member/deleted-music-mv/page"
    const val deletedMusicMvRestore = "/member/deleted-music-mv/restore"
    const val userRecommendRecords = "/member/user/recommendRecords"
    const val bindSuperRegCode = "/member/user/bindSuperRegCode"
}

