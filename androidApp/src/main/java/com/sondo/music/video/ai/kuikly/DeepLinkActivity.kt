package com.sondo.music.video.ai.kuikly

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * 深链入口（`sondo://…` 与 App Link `https://go.sondo.vip/link`）。
 *
 * 参考工程把这两个 intent-filter 挂在 singleTask 的 MainActivity 上；Kuikly 宿主是「一页一个 Activity」，
 * 如果直接挂在页面容器上，每次点链接都会在栈顶多出一个根页面（standard），或者把整个页面栈清掉（singleTask）。
 * 所以用一个不可见的中转 Activity：只负责把 URL 交给 [AppEventHub]，然后
 * - App 已在运行：把现有任务带到前台，页面栈原样保留；
 * - 冷启动：拉起启动页，URL 暂存，等 Kuikly 侧经 getInitialLink 读取（投递规则见 [AppEventHub.onDeepLink]）。
 *
 * URL 是否合法、何时处理（隐私同意之后）由 Kuikly 侧决定，这里不解析也不联网。
 */
class DeepLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            handle(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "handle deep link failed", e)
        }
        finish()
        overridePendingTransition(0, 0)
    }

    private fun handle(intent: Intent?) {
        val url = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString.orEmpty()
        if (url.isNotEmpty()) AppEventHub.onDeepLink(url)

        val running = ActivityStack.top()
        if (running != null) {
            // 浏览器等以 NEW_TASK 拉起时本 Activity 已经在 App 任务里，finish 后自然露出栈顶页面；
            // 被拉进调用方任务时，需要主动把 App 任务带到前台
            if (running.taskId != taskId) bringAppTaskToFront(running.taskId)
            return
        }
        if (hasRestorablePages()) return
        launchRoot()
    }

    /** 进程被回收后任务里还留着待恢复的页面：finish 后系统会恢复它们，不需要再拉一个根页面。 */
    private fun hasRestorablePages(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return try {
            for (task in am.appTasks) {
                val info = task.taskInfo
                val mine = taskIdOf(info) == taskId
                val others = if (mine) info.numActivities - 1 else info.numActivities
                if (others > 0) {
                    if (!mine) task.moveToFront()
                    return true
                }
            }
            false
        } catch (e: Throwable) {
            false
        }
    }

    private fun bringAppTaskToFront(targetTaskId: Int) {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        try {
            am.appTasks.firstOrNull { taskIdOf(it.taskInfo) == targetTaskId }?.moveToFront()
        } catch (e: Throwable) {
            Log.w(TAG, "moveToFront failed", e)
        }
    }

    private fun launchRoot() {
        // 与桌面图标完全相同的 Intent（MAIN + LAUNCHER + 组件名，不带包名）：
        // 之后用户再点桌面图标时系统认得是同一个入口，只把任务切到前台，不会再叠一个根页面
        val component = packageManager.getLaunchIntentForPackage(packageName)?.component
            ?: ComponentName(this, KuiklyLaunchActivity::class.java)
        val launch = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
    }

    @Suppress("DEPRECATION")
    private fun taskIdOf(info: ActivityManager.RecentTaskInfo): Int =
        if (Build.VERSION.SDK_INT >= 29) info.taskId else info.id

    companion object {
        private const val TAG = "DeepLink"
    }
}
