package com.sondo.music.video.ai.sdk.push

import android.content.Context
import cn.jpush.android.api.NotificationMessage
import cn.jpush.android.service.JCommonService
import cn.jpush.android.service.JPushMessageReceiver

/**
 * 极光推送回调入口（清单里按 `cn.jpush.android.intent.RECEIVER_MESSAGE` 注册，运行在主进程）。
 * 只把回调转给 [JPushManager]，不在这里做业务。
 *
 * 没用到的能力不重写：自定义消息、标签 / 别名、应用内消息、地理围栏。
 */
class SondoJPushReceiver : JPushMessageReceiver() {

    override fun onRegister(context: Context, registrationId: String?) {
        JPushManager.onRegistered(registrationId)
    }

    override fun onConnected(context: Context, isConnected: Boolean) {
        JPushManager.onConnectionChanged(context, isConnected)
    }

    override fun onNotifyMessageArrived(context: Context, message: NotificationMessage?) {
        if (message != null) JPushManager.onNotificationArrived(message)
    }

    override fun onNotifyMessageUnShow(context: Context, message: NotificationMessage?) {
        if (message != null) JPushManager.onNotificationUnShow(message)
    }

    /**
     * 不调 super（和参考工程用的极光 Flutter 插件一致）：super 是给旧版广播接收方式兜底的默认处理。
     * 通知内容交给 Kuikly 侧决定跳哪；远程推送把应用带回前台由极光自己的跳转逻辑完成。
     */
    override fun onNotifyMessageOpened(context: Context, message: NotificationMessage?) {
        if (message != null) JPushManager.onNotificationOpened(context, message)
    }

    override fun isNeedShowNotification(
        context: Context,
        message: NotificationMessage?,
        processName: String?,
    ): Boolean = JPushManager.shouldShowNotification(context)
}

/**
 * 极光长连接所在的 Service，跑在 `:pushcore` 进程。极光要求应用自己继承 JCommonService 并在清单里声明，
 * 这样推送通道在更多机型上保持得更稳；类里不需要任何代码。
 */
class SondoJPushService : JCommonService()
