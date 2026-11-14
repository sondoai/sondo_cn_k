package com.sondo.music.video.ai.kuikly

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * 进程内的 Kuikly 容器栈：记录所有存活的 [KuiklyRenderActivity] 及其页面名（自底向上）。
 *
 * - Activity 在 onCreate 注册、onDestroy 注销；
 * - 读（[pageNames] / [popTo] 的命中判断）可在任意线程调用（SDNavModule 的同步方法跑在 Kuikly 线程），
 *   真正的 finish 一律切回主线程；
 * - 被要求关闭的页面会立即标记为 leaving，随后的 [pageNames] 不再包含它，
 *   这样 `popTo()` 之后马上 `stack()` 能读到结果，不必等 Activity 真正销毁。
 */
object ActivityStack {

    private val lock = Any()
    private val entries = ArrayList<KuiklyRenderActivity>()
    private val seqGen = AtomicLong(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 应用级「保持亮屏」开关（对应 wakelock：全局生效，而不是只作用于发起调用的那一页）。 */
    @Volatile
    var keepScreenOn: Boolean = false
        private set

    /**
     * 分配栈内序号。进程被回收后系统按「先顶后底」的顺序重建 Activity，
     * 所以序号随 savedInstanceState 保存，重建时沿用旧值，保证栈顺序不乱。
     */
    fun acquireSeq(restored: Long): Long {
        if (restored > 0) {
            while (true) {
                val cur = seqGen.get()
                if (cur >= restored || seqGen.compareAndSet(cur, restored)) break
            }
            return restored
        }
        return seqGen.incrementAndGet()
    }

    fun register(activity: KuiklyRenderActivity) {
        synchronized(lock) {
            if (!entries.contains(activity)) {
                entries.add(activity)
                entries.sortBy { it.stackSeq }
            }
        }
    }

    fun unregister(activity: KuiklyRenderActivity) {
        synchronized(lock) { entries.remove(activity) }
    }

    /** 存活且未被要求关闭的页面，自底向上。 */
    fun live(): List<KuiklyRenderActivity> = synchronized(lock) {
        entries.filter { !it.leaving && !it.isFinishing && !it.isDestroyed }
    }

    fun pageNames(): List<String> = live().map { it.pageName }

    fun top(): KuiklyRenderActivity? = live().lastOrNull()

    fun root(): KuiklyRenderActivity? = live().firstOrNull()

    fun isEmpty(): Boolean = live().isEmpty()

    fun contains(pageName: String): Boolean = live().any { it.pageName == pageName }

    /**
     * 关闭最靠近栈顶的 [pageName] 之上的所有页面；栈内没有该页面时回到根页面。
     * @return 是否命中
     */
    fun popTo(pageName: String): Boolean {
        val list = live()
        if (list.isEmpty()) return false
        val index = list.indexOfLast { it.pageName == pageName }
        finishAbove(list, if (index >= 0) index else 0)
        return index >= 0
    }

    fun popToRoot() {
        val list = live()
        if (list.isNotEmpty()) finishAbove(list, 0)
    }

    /** 关闭当前页并打开新页：先启动新页再结束旧页，旧页在新页画出第一帧之前一直垫在下面，不会闪到更底层的页面。 */
    fun replace(current: KuiklyRenderActivity?, fallback: Context, pageName: String, pageData: JSONObject) {
        runOnMain {
            KuiklyRenderActivity.start(current ?: top() ?: fallback, pageName, pageData)
            current?.finishWithoutTransition()
        }
    }

    /** 清空整个栈并以 [pageName] 作为新的根。 */
    fun resetRoot(fallback: Context, pageName: String, pageData: JSONObject) {
        val list = live()
        // 先同步标记，保证随后的 stack() 不再包含旧页面
        list.forEach { it.markLeaving() }
        runOnMain {
            val starter: Context = list.lastOrNull { !it.isDestroyed } ?: fallback
            KuiklyRenderActivity.start(starter, pageName, pageData)
            // 自底向上结束：被盖住的页面先走，用户看不到中间态；栈顶页留到最后，由新页的入场动画盖住
            list.forEach { it.finishWithoutTransition() }
        }
    }

    /** 结束所有页面（exitApp）。进程不杀，行为同 Flutter 的 SystemNavigator.pop()。 */
    fun finishAll() {
        val list = live()
        list.forEach { it.markLeaving() }
        runOnMain {
            val top = list.lastOrNull()
            list.forEach { if (it !== top) it.finishWithoutTransition() }
            top?.finishAffinity()
        }
    }

    fun setKeepScreenOn(on: Boolean) {
        keepScreenOn = on
        runOnMain {
            synchronized(lock) { entries.toList() }.forEach { it.applyKeepScreenOn(on) }
        }
    }

    private fun finishAbove(list: List<KuiklyRenderActivity>, keepIndex: Int) {
        if (keepIndex >= list.size - 1) return
        val toFinish = list.subList(keepIndex + 1, list.size).toList()
        toFinish.forEach { it.markLeaving() }
        runOnMain {
            // 中间页不可见，直接结束；栈顶页最后结束并保留它自己的退场动画
            for (i in 0 until toFinish.size - 1) toFinish[i].finishWithoutTransition()
            toFinish.last().finish()
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
