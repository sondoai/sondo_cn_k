package com.sondo.music.video.ai

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import com.sondo.music.video.ai.kuikly.AppEventHub
import com.sondo.music.video.ai.kuikly.KuiklyAdapters

/**
 * 应用入口。只做与隐私无关的本地初始化；
 * 友盟 / 极光 / 微信等三方 SDK 必须等用户同意隐私政策后，由 Kuikly 侧通过原生模块触发初始化。
 */
class SondoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        application = this
        // 极光的 :pushcore 子进程也会走到这里，它不承载页面，不需要 Kuikly 与事件中心
        if (!isMainProcess()) return
        KuiklyAdapters.install(this)
        // 前后台 / 深链 / 联网状态的事件中心：只注册进程内的生命周期回调，不联网、不读设备标识
        AppEventHub.install(this)
    }

    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) {
            getProcessName()
        } else {
            val pid = Process.myPid()
            (getSystemService(ACTIVITY_SERVICE) as? ActivityManager)
                ?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        }
        return name == null || name == packageName
    }

    companion object {
        lateinit var application: Application
            private set
    }
}
