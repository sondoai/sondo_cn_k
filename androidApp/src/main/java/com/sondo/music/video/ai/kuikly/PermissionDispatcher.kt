package com.sondo.music.video.ai.kuikly

import android.app.Activity
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

/**
 * 运行时权限申请的结果分发。
 *
 * 系统的权限结果只回到 Activity.onRequestPermissionsResult，这里把它路由回发起方：
 * [KuiklyRenderActivity.onRequestPermissionsResult] 调 [dispatch]，原生模块调 [request]。
 *
 * 系统同一时刻只能展示一个权限弹窗（并发的第二个请求会立刻拿到空结果），
 * 所以请求在进程内排队，一个结束再发下一个。全部方法只在主线程调用。
 */
object PermissionDispatcher {

    /**
     * @param granted 每个权限的最终授权状态（以回调时刻的 checkSelfPermission 为准）
     * @param interrupted 系统没有给出结果（弹窗被打断 / Activity 已销毁），调用方不应据此推断「永久拒绝」
     * @param elapsedMs 从发起到拿到结果的耗时；极短说明系统没有弹窗、直接给了结果
     */
    class Result(val granted: Map<String, Boolean>, val interrupted: Boolean, val elapsedMs: Long) {
        val allGranted: Boolean get() = granted.isNotEmpty() && granted.values.all { it }
    }

    private class Pending(
        val activity: WeakReference<Activity>,
        val permissions: Array<String>,
        val callback: (Result) -> Unit,
    ) {
        var requestCode = 0
        var startedAt = 0L
    }

    private const val REQUEST_CODE_BASE = 0x5D00
    private const val REQUEST_CODE_SPAN = 0xFF

    private val queue = ArrayDeque<Pending>()
    private var running: Pending? = null
    private var codeSeq = 0

    @MainThread
    fun request(activity: Activity, permissions: Array<String>, callback: (Result) -> Unit) {
        if (permissions.isEmpty()) {
            callback(Result(emptyMap(), interrupted = false, elapsedMs = 0))
            return
        }
        queue.addLast(Pending(WeakReference(activity), permissions, callback))
        pump()
    }

    /** @return 是否是本分发器发起的请求 */
    @MainThread
    fun dispatch(activity: Activity, requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        val cur = running ?: return false
        if (cur.requestCode != requestCode || cur.activity.get() !== activity) return false
        running = null
        val interrupted = permissions.isEmpty() || grantResults.isEmpty()
        finish(cur, activity, interrupted)
        pump()
        return true
    }

    /** Activity 销毁：丢弃它名下未完成的请求（发起方也随页面一起销毁了，不再回调）。 */
    @MainThread
    fun cancel(activity: Activity) {
        queue.removeAll { it.activity.get().let { a -> a == null || a === activity } }
        val cur = running
        if (cur != null && cur.activity.get().let { it == null || it === activity }) {
            running = null
            pump()
        }
    }

    private fun pump() {
        while (running == null) {
            val next = queue.removeFirstOrNull() ?: return
            val activity = next.activity.get()
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                next.callback(Result(next.permissions.associateWith { false }, interrupted = true, elapsedMs = 0))
                continue
            }
            codeSeq = (codeSeq + 1) % REQUEST_CODE_SPAN
            next.requestCode = REQUEST_CODE_BASE + codeSeq
            next.startedAt = SystemClock.elapsedRealtime()
            running = next
            try {
                ActivityCompat.requestPermissions(activity, next.permissions, next.requestCode)
            } catch (e: Throwable) {
                running = null
                finish(next, activity, interrupted = true)
            }
        }
    }

    private fun finish(pending: Pending, activity: Activity, interrupted: Boolean) {
        val granted = pending.permissions.associateWith {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }
        val elapsed = SystemClock.elapsedRealtime() - pending.startedAt
        try {
            pending.callback(Result(granted, interrupted, elapsed))
        } catch (e: Throwable) {
            // 回调方异常不影响后续排队的请求
        }
    }
}
