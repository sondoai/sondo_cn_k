package com.sondo.app.service

import com.sondo.app.base.AppHost
import com.sondo.app.core.utils.HostBinding
import com.tencent.kuikly.core.log.KLog

/**
 * 轮询服务：统一管理所有定时轮询任务（对应 Flutter 侧 PollingService）。
 *
 * 定时器与任务协程都挂在宿主页面上，跨页面存活。每个 tick：上一次还没跑完就跳过；
 * `shouldContinue()` 为 false 自动停止并移除；任务抛错只记日志，继续轮询。
 */
object PollingService {
    private const val TAG = "PollingService"

    private class PollingTask(
        val taskId: String,
        val intervalMs: Int,
        val task: suspend () -> Unit,
        val shouldContinue: (() -> Boolean)?,
    ) {
        var timerRef: String? = null
        var isExecuting = false
        var isStopped = false
        val isActive: Boolean get() = timerRef != null

        fun cancel() {
            Svc.clearTimeout(timerRef)
            timerRef = null
            isStopped = true
        }
    }

    private val tasks = LinkedHashMap<String, PollingTask>()
    private var isClosed = false
    private val hostBinding = HostBinding()

    /**
     * 启动时调用。宿主（根页面）重建后，旧宿主上的定时器和执行中的协程都已失效：
     * 这里给仍然登记着的任务重新挂上定时器。
     */
    fun init() {
        isClosed = false
        if (!hostBinding.needsBind()) return
        tasks.values.forEach {
            it.timerRef = null
            it.isExecuting = false
            if (!it.isStopped) startTimer(it)
        }
    }

    /** 关闭服务并停止全部任务（宿主销毁时）。 */
    fun close() {
        isClosed = true
        stopAllTasks()
    }

    /**
     * 注册轮询任务；同 id 的旧任务先停止。
     * @param startImmediately 是否立即执行一次（默认 true）
     */
    fun registerTask(
        taskId: String,
        intervalMs: Int,
        task: suspend () -> Unit,
        shouldContinue: (() -> Boolean)? = null,
        startImmediately: Boolean = true,
    ) {
        if (isClosed) {
            KLog.e(TAG, "服务已关闭，无法注册任务 [$taskId]")
            return
        }
        if (tasks.containsKey(taskId)) stopTask(taskId)
        val pollingTask = PollingTask(taskId, intervalMs, task, shouldContinue)
        tasks[taskId] = pollingTask
        if (startImmediately) executeTask(pollingTask)
        startTimer(pollingTask)
    }

    private fun startTimer(t: PollingTask) {
        if (isClosed || t.isStopped) return
        Svc.clearTimeout(t.timerRef)
        t.timerRef = Svc.setTimeout(t.intervalMs) {
            t.timerRef = null
            if (isClosed || t.isStopped) return@setTimeout
            // 先续上下一拍再执行，等价于 Timer.periodic
            startTimer(t)
            executeTask(t)
        }
    }

    private fun executeTask(t: PollingTask) {
        if (isClosed || t.isStopped) return
        if (t.isExecuting) return
        val check = t.shouldContinue
        if (check != null && !check()) {
            stopIfSame(t)
            return
        }
        t.isExecuting = true
        // registerTask 可能在业务页上下文里被调用：任务必须切到宿主页面上跑，请求才不会随业务页销毁而丢失
        AppHost.launch {
            try {
                t.task()
            } catch (e: Throwable) {
                // 出错时继续轮询，不停止任务
                KLog.e(TAG, "任务 [${t.taskId}] 执行失败: ${e.message}")
            }
            t.isExecuting = false
            if (check != null && !check()) stopIfSame(t)
        }
    }

    /** 只停自己：同 id 已被重新注册时，旧任务收尾不能把新任务带走。 */
    private fun stopIfSame(t: PollingTask) {
        if (tasks[t.taskId] === t) stopTask(t.taskId) else t.cancel()
    }

    fun stopTask(taskId: String) {
        val task = tasks.remove(taskId) ?: return
        task.cancel()
    }

    fun stopAllTasks() {
        if (tasks.isEmpty()) return
        tasks.keys.toList().forEach { stopTask(it) }
    }

    fun restartTask(taskId: String) {
        val task = tasks[taskId] ?: return
        task.isStopped = false
        startTimer(task)
    }

    fun isTaskActive(taskId: String): Boolean {
        val task = tasks[taskId]
        return task != null && task.isActive && !task.isStopped
    }

    val activeTaskCount: Int get() = tasks.values.count { it.isActive && !it.isStopped }

    val allTaskIds: List<String> get() = tasks.keys.toList()

    /** 暂停所有轮询（参考工程里没有调用方，保留同名能力）。 */
    fun pauseAllTasks() {
        tasks.values.forEach {
            Svc.clearTimeout(it.timerRef)
            it.timerRef = null
        }
    }

    fun resumeAllTasks() {
        tasks.values.forEach { if (!it.isStopped) startTimer(it) }
    }
}
