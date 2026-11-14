package com.sondo.music.video.ai.sdk

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用自己的页面是否在前台。
 *
 * 推送用它决定两件事：前台没展示的通知要不要转成应用内提示、点本地通知时要不要把应用拉回前台。
 * 只统计本应用包名下的 Activity：极光的通知中转页、运营商授权页、支付宝收银台都在本进程里，
 * 但它们不算“应用在前台”。
 */
internal object AppForeground : Application.ActivityLifecycleCallbacks {

    private const val APP_PACKAGE_PREFIX = "com.sondo.music.video.ai."

    private val installed = AtomicBoolean(false)

    /** 已 start 未 stop 的应用页面。用集合而不是计数：安装前就在前台的页面只能靠补记，不能重复加。 */
    private val started = HashSet<Int>()

    val isForeground: Boolean
        get() = synchronized(started) { started.isNotEmpty() }

    /**
     * 首次用到时注册。[current] 是调用方所在的页面：注册之前它就已经 start 了，这里补记一次。
     */
    fun ensureInstalled(context: Context?, current: Activity? = null) {
        val app = context?.applicationContext as? Application ?: return
        if (installed.compareAndSet(false, true)) {
            app.registerActivityLifecycleCallbacks(this)
        }
        if (current != null && isAppActivity(current) && isStarted(current)) {
            synchronized(started) { started.add(System.identityHashCode(current)) }
        }
    }

    /** 可能在 Kuikly 线程上被调用（同步方法），只读状态，不碰生命周期注册。 */
    private fun isStarted(activity: Activity): Boolean {
        return try {
            if (activity.isFinishing || activity.isDestroyed) return false
            val owner = activity as? LifecycleOwner ?: return activity.hasWindowFocus()
            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        } catch (_: Throwable) {
            false
        }
    }

    private fun isAppActivity(activity: Activity): Boolean =
        activity.javaClass.name.startsWith(APP_PACKAGE_PREFIX)

    /**
     * 把应用任务带回前台，不新建页面、不改页面栈。
     * 用和桌面图标完全一样的 Intent（MAIN + LAUNCHER + 组件名，不带包名）：系统认出是任务根页面的同一个
     * Intent 就只切到前台。`getLaunchIntentForPackage` 返回的 Intent 带包名，Android 10 及以下会被当成
     * 另一个 Intent，在栈顶再开一个根页面。
     */
    fun bringAppToFront(context: Context) {
        try {
            val app = context.applicationContext
            val component: ComponentName = app.packageManager
                .getLaunchIntentForPackage(app.packageName)?.component ?: return
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            app.startActivity(intent)
        } catch (_: Throwable) {
            // 后台拉起受限等情况下启动失败，不能影响通知点击事件本身。
        }
    }

    override fun onActivityStarted(activity: Activity) {
        if (!isAppActivity(activity)) return
        synchronized(started) { started.add(System.identityHashCode(activity)) }
    }

    override fun onActivityStopped(activity: Activity) {
        synchronized(started) { started.remove(System.identityHashCode(activity)) }
    }

    override fun onActivityDestroyed(activity: Activity) {
        synchronized(started) { started.remove(System.identityHashCode(activity)) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
