package com.sondo.app.base

/**
 * 跨页面事件名（经 Kuikly NotifyModule 广播到所有存活页面）。
 *
 * 约定：全局单例（UserStore / ConfigStore / 各 Service）只保存普通数据，
 * 数据变化后 `EventBus.post(Events.X)`；页面在 created() 里 `on(Events.X) { 同步到自己的 observable }`。
 * 不要在单例里声明 observable —— 响应式字段归属于单个 Pager。
 */
object Events {
    /** 登录态 / 用户资料变化。data: {login: Boolean} */
    const val USER_CHANGED = "sd.user.changed"
    /** 强制下线（刷新令牌失效）。 */
    const val SESSION_INVALID = "sd.session.invalid"
    /** 远端配置刷新完成。 */
    const val CONFIG_CHANGED = "sd.config.changed"
    /** 页面返回结果。data: {reqId: String, result: JSONObject?} */
    const val PAGE_RESULT = "sd.nav.result"
    /** 请求回到根页并切换 Tab。data: {tab: Int} */
    const val SWITCH_TAB = "sd.main.switchTab"
    /** 网络连通性变化。data: {connected: Boolean} */
    const val NETWORK_CHANGED = "sd.network.changed"
    /** App 前后台切换。data: {foreground: Boolean} */
    const val APP_LIFECYCLE = "sd.app.lifecycle"

    // 播放器
    const val PLAYER_STATE = "sd.player.state"
    const val PLAYER_POSITION = "sd.player.position"

    // 作品数据变化（列表需刷新）
    const val MUSIC_LIST_CHANGED = "sd.music.listChanged"
    const val MV_LIST_CHANGED = "sd.mv.listChanged"
    const val MUSIC_TASK_UPDATED = "sd.music.taskUpdated"
    const val MV_TASK_UPDATED = "sd.mv.taskUpdated"
    const val POINTS_CHANGED = "sd.points.changed"

    // 支付
    /**
     * 一次支付 / 恢复购买有了结果（PayService 广播，页面用 PayOutcome.fromJson 还原）。
     * data: {type: "purchase"|"restore", channel: "apple"|"alipay"|"huawei",
     *   status: "success"|"canceled"|"pending"|"failed"|"timeout"|"superseded", reason, pageName, productId, orderId,
     *   subscription, isNewOrder, userInitiated, late, pageClosed, message, code?,
     *   successCount / failureCount / hasSubscribe（仅 restore）}
     */
    const val PAY_RESULT = "sd.pay.result"

    // 账号 / 配置（service 层）
    /** 幸运周卡 / 回归周卡倒计时每秒变化。data: {remainingSeconds: Int, recallRemainingSeconds: Int} */
    const val OFFER_COUNTDOWN = "sd.offer.countdown"
    /** 推送状态变化（权限 / Registration ID）。data: {isNotified: Boolean, token: String} */
    const val PUSH_STATE = "sd.push.state"
    /** 前台收到推送，根页面展示应用内横幅，点击后调 PushService.handleForegroundTap(extras)。data: {title, body, extras: JSONObject} */
    const val PUSH_FOREGROUND = "sd.push.foreground"
    /** 登录流程状态变化（登录页按钮防连点）。data: {isLoggingIn: Boolean} */
    const val LOGIN_STATE = "sd.login.state"

    // 作品 / 媒体服务（MusicService / MvService / PlayerService / OssUploadService / TryFreeTaskService）
    /** 原始播放器状态（含试听轨道，不含高频进度）。data: AppAudioPlayerState.toJson() */
    const val AUDIO_STATE = "sd.audio.state"
    /** 操作面板里的歌曲变化（PlayerService.setSong）。data: {updateId: String, song?: SongModel json} */
    const val PLAYER_SONG_CHANGED = "sd.player.songChanged"
    /** 歌曲分享 / 下载进行中的集合变化。data: {sharing: [id…], downloading: [id…]} */
    const val MUSIC_BUSY_CHANGED = "sd.music.busyChanged"
    /** MV 分享 / 下载进行中的集合变化。data: {sharing: [url…], downloading: [url…]} */
    const val MV_BUSY_CHANGED = "sd.mv.busyChanged"
    /** MV 字典 / 缓存刷新。data: {kind: "template"|"tags"|"ideas"|"roles"|"shotImages"} */
    const val MV_DICT_CHANGED = "sd.mv.dictChanged"
    /** MV 更多面板当前条目变化。data: MvModel json */
    const val MV_CURRENT_CHANGED = "sd.mv.currentChanged"
    /** 试用 MV 活跃任务状态变化。data: TryFreeTaskService.snapshot() */
    const val TRY_FREE_CHANGED = "sd.tryFree.changed"
    /** 音频上传任务变化。data: {taskId: String, removed: Boolean, item?: AudioUploadItem json} */
    const val UPLOAD_TASKS_CHANGED = "sd.upload.tasksChanged"
    /** 已打开的创作页收到新参数。data: {page: String, args: JSONObject} */
    const val CREATE_ARGUMENTS = "sd.create.arguments"

    /** 栈内已有广场个人主页时，回到它并按新参数重载。data: {userId: Long, type: "mv"|"music"} */
    const val SQUARE_PROFILE_RELOAD = "sd.square.profileReload"

    /**
     * 失败分镜重试页（retry_shots）Recreate 已受理：思考线页按 mvId 匹配自己的 taskId 后恢复轮询
     * （Dart ThinkLineMvController.resumePollingAfterShotRetry）。data: {mvId: Long}
     */
    const val MV_SHOT_RETRIED = "sd.mv.shotRetried"

    /** Android 导航方式 / 底部 inset 变化（由 AppBootstrap 的唯一 SDAppModule 监听转发）。data: {threeButton, bottomInset} */
    const val SYSTEM_NAV = "sd.app.systemNav"
}
