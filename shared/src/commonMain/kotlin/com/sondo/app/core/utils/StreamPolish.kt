package com.sondo.app.core.utils

import com.sondo.app.core.http.Api
import com.sondo.app.core.http.AuthSession
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

/**
 * 润色流（SSE 风格）的增量解析器，对应 Flutter 侧 StreamPolish。
 *
 * 用法：
 * ```
 * polish = StreamPolish.open(ApiPaths.musicPolishStream, JSONObject().put("data", lyrics).put("type", 1),
 *     onEvent = { text -> 追加到输入框 }, onStart = { quota -> }, onError = { msg -> }, onDone = { 关闭 loading })
 * // 页面关闭 / 重新发起前
 * polish?.dispose()
 * ```
 * 规则：
 * - 文本分片按行切分；空行以及 `:`、`event:`、`id:`、`retry:` 开头的行跳过；
 *   负载 = `data:` 之后的文本（去掉左侧空白），没有前缀时为整行；空负载和 `[DONE]` 跳过；
 * - 负载必须是 JSON 对象：`{code:401}` → 共享一次凭证刷新后 `onError("")` 并结束（不自动重发）；
 *   `{type:"delta",text}` → `onEvent(text)`；`{type:"meta",quota}` → `onStart(quota)`；
 *   `{type:"error"}` → `onError("error")`（额度 / 订阅限制，文案由调用方按最近一次 meta 决定）；
 *   解析失败 → `onError("")`；
 * - 流出错 → `onError("")` + 结束；流结束 → `onDone()` 恰好一次；
 * - 会话守卫：创建后账号代次变了，不再投递文本 / 配额 / 错误，只 `onDone()` 释放加载态。
 *
 * 流绑定在发起页面上（页面销毁后回调不再到达）。
 */
class StreamPolish(
    private val onEvent: (String) -> Unit,
    private val onStart: ((JSONObject) -> Unit)? = null,
    private val onError: ((String) -> Unit)? = null,
    private val onDone: (() -> Unit)? = null,
    expectedRevision: Int? = null,
) {
    private val sessionRevision: Int = expectedRevision ?: AuthSession.revision
    private var finished = false
    private var processingError = false
    private var pendingDone = false
    private var handle: NetworkModule.StreamRequestHandle? = null
    private var pager: Pager? = null
    private val buffer = StringBuilder()

    val isFinished: Boolean get() = finished

    /** 喂入一段文本分片（边界由网络决定）。 */
    fun feed(chunk: String) {
        if (finished) return
        buffer.append(chunk)
        while (true) {
            val idx = indexOfLineBreak()
            if (idx < 0) break
            val line = buffer.substring(0, idx)
            // \r\n 算一个换行
            val skip = if (buffer[idx] == '\r' && idx + 1 < buffer.length && buffer[idx + 1] == '\n') 2 else 1
            buffer.deleteRange(0, idx + skip)
            handleStreamEvent(line)
            if (finished) return
        }
    }

    /** 流正常结束：冲刷最后一行后收尾。 */
    fun end() {
        if (finished) return
        if (buffer.isNotEmpty()) {
            val last = buffer.toString()
            buffer.clear()
            handleStreamEvent(last)
        }
        // 业务 401 可能仍在等待刷新，先让它完成归属检查和错误处理。
        if (processingError) pendingDone = true else complete()
    }

    /** 流出错。[statusCode] 为 401 时先走凭证刷新。 */
    fun fail(statusCode: Int? = null) {
        if (!canEmit()) return
        if (statusCode == 401) {
            processErrorResponse()
            return
        }
        onError?.invoke("")
        complete()
    }

    /** 主动结束：关闭连接，不再回调（包括 onDone）。 */
    fun dispose() {
        finished = true
        handle?.close()
        handle = null
    }

    // 行尾可能是 \n、\r\n 或单独的 \r；末尾的 \r 先留着等下一片，避免把 \r\n 拆成两行
    private fun indexOfLineBreak(): Int {
        for (i in 0 until buffer.length) {
            val c = buffer[i]
            if (c == '\n') return i
            if (c == '\r') return if (i + 1 < buffer.length) i else -1
        }
        return -1
    }

    private fun canEmit(): Boolean {
        if (finished) return false
        if (AuthSession.isCurrent(sessionRevision)) return true
        // 旧流只通知结束以释放加载态，不再传递文本、配额或错误提示。
        complete()
        return false
    }

    private fun handleStreamEvent(event: String) {
        if (!canEmit() || processingError) return
        val line = event.trim()
        // SSE 的空行、心跳及控制字段不承载业务 JSON。
        if (line.isEmpty() || line.startsWith(":") || line.startsWith("event:") ||
            line.startsWith("id:") || line.startsWith("retry:")
        ) return
        // 错误与正常事件先用同一规则提取负载，同时兼容直接返回的 JSON。
        val payload = if (line.startsWith("data:")) line.substring(5).trimStart() else line
        if (payload.isEmpty() || payload == "[DONE]") return
        try {
            if (!payload.startsWith("{")) throw IllegalArgumentException("Expected a JSON object")
            val json = JSONObject(payload)
            if (json.has("code") && json.optInt("code", 0) == 401) {
                processErrorResponse()
                return
            }
            when (json.optString("type", "")) {
                "delta" -> onEvent(json.optString("text", ""))
                "meta" -> onStart?.invoke(json.optJSONObject("quota") ?: JSONObject())
                "error" -> onError?.invoke("error")
            }
        } catch (e: Throwable) {
            if (canEmit()) onError?.invoke("")
            DevLog.logger("Error parsing event: ${e.message}")
        }
    }

    private fun processErrorResponse() {
        if (!canEmit()) return
        processingError = true
        val refreshToken = AuthSession.refreshToken
        val finishError = {
            if (canEmit()) onError?.invoke("")
            processingError = false
            pendingDone = false
            complete()
        }
        val host = pager
        if (host == null) {
            finishError()
            return
        }
        host.lifecycleScope.launch {
            try {
                Api.ensureRefreshed(refreshToken, sessionRevision)
            } catch (e: Throwable) {
                DevLog.logger("polish refresh error: ${e.message}")
            }
            finishError()
        }
    }

    private fun complete() {
        if (finished) return
        dispose()
        onDone?.invoke()
    }

    companion object {
        /** 连续这么久没有数据即视为超时（与业务客户端 360s 接收超时一致）。 */
        private const val IDLE_TIMEOUT_SECONDS = 360

        /**
         * 发起流式 POST 并开始解析。[path] 相对业务 baseUrl（如 ApiPaths.musicPolishStream / ApiPaths.mvDescPolish）。
         */
        fun open(
            path: String,
            body: JSONObject,
            onEvent: (String) -> Unit,
            onStart: ((JSONObject) -> Unit)? = null,
            onError: ((String) -> Unit)? = null,
            onDone: (() -> Unit)? = null,
            expectedRevision: Int? = null,
        ): StreamPolish {
            val polish = StreamPolish(onEvent, onStart, onError, onDone, expectedRevision)
            val pager = PagerManager.getCurrentPager() as Pager
            polish.pager = pager
            val network: NetworkModule = pager.acquireModule(NetworkModule.MODULE_NAME)
            polish.handle = network.httpStreamRequest(
                url = Api.buildUrl(path, null),
                isPost = true,
                param = body,
                headers = Api.commonHeaders(),
                timeout = IDLE_TIMEOUT_SECONDS,
            ) { event, data, response ->
                when (event) {
                    "data" -> {
                        // 网关直接回 401（非流式 JSON）时同样会落到 data 里，由行解析处理
                        if (response?.statusCode == 401) polish.fail(401) else polish.feed(data)
                    }
                    "complete" -> polish.end()
                    "error" -> polish.fail(response?.statusCode)
                }
            }
            return polish
        }
    }
}
