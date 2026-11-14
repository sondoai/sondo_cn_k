package com.sondo.app.data.api

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.ApiErrorType
import com.sondo.app.core.http.ApiException
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.http.AuthSession
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.StreamPolish
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 流式请求句柄。[close] 后不再有任何回调（包括 onDone）。 */
class StreamHandle internal constructor() {
    internal var polish: StreamPolish? = null

    /** 已结束（正常结束、出错或被关闭）。 */
    val isFinished: Boolean get() = polish?.isFinished ?: true

    fun close() {
        polish?.dispose()
    }

    fun cancel() = close()

    fun dispose() = close()
}

/**
 * 流式润色接口（SSE 风格）。
 *
 * 传输与增量解析由 [StreamPolish]（core/utils，对应 Dart lib/core/utils/stream_polish.dart）完成：
 * Kuikly `NetworkModule.httpStreamRequest` 按网络分片回调文本，按行拼接后解析；
 * 流里出现业务 401 时共享一次 `Api.ensureRefreshed`，然后以错误结束（不自动重发）。
 *
 * 端点：
 * - 歌词润色 `POST /member/music/polishStream/v1`，body `{data, type: 1}`
 *   （Dart: features/music/data/datasources/music_gen_remote_datasource.dart `polishLyricsStream`）；
 * - MV 描述润色 `POST /member/musicMvTask/mvDescPolish`，body `{prompt}`
 *   （Dart: shared/data/datasources/dict_data_remote_datasource.dart `polishDescriptionStream`）。
 */
object PolishStreamApi {

    /**
     * 发起流式 POST。
     *
     * @param onDelta `{type:"delta", text}` 的文本片段（追加到输入框）。
     * @param onMeta `{type:"meta", quota}` 的配额对象（subscribed / needSubscribe / dailyRemaining /
     *   trialRemaining / subscribeHintZh）。
     * @param onError 与 Dart `StreamPolish.onError` 相同的即时回调：`"error"` = 额度/订阅限制
     *   （`{type:"error"}`，文案由调用方按最近一次 meta 决定）；`""` = 解析失败、流出错、请求没连上或业务 401。
     *   可能在 [onDone] 之前被调用多次。
     * @param onDone 流结束时恰好回调一次：
     *   - `Success(完整文本)`：全程没有错误；
     *   - `Failure(type = BUSINESS, message = "error")`：出现过额度/订阅限制；
     *   - `Failure(type = BAD_RESPONSE)`：出现过其它错误（[onError] 收到过 `""`）；
     *   - `Failure(type = CANCEL)`：账号会话已变（退出/换号），此时不会有文本、配额和 [onError] 回调。
     * @param expectedRevision 发起时的账号代次；默认取当前。
     */
    fun polishStream(
        path: String,
        body: JSONObject,
        onDelta: (String) -> Unit,
        onDone: (ApiResult<String>) -> Unit,
        onMeta: ((JSONObject) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        expectedRevision: Int? = null,
    ): StreamHandle {
        val revision = expectedRevision ?: AuthSession.revision
        val handle = StreamHandle()
        val text = StringBuilder()
        var lastError: String? = null
        handle.polish = StreamPolish.open(
            path = path,
            body = body,
            onEvent = { delta ->
                text.append(delta)
                onDelta(delta)
            },
            onStart = { quota -> onMeta?.invoke(quota) },
            onError = { msg ->
                // 额度限制优先级高于随后可能出现的解析错误
                if (lastError != "error") lastError = msg
                onError?.invoke(msg)
            },
            onDone = {
                val err = lastError
                val result: ApiResult<String> = when {
                    !AuthSession.isCurrent(revision) ->
                        ApiResult.Failure(ApiException(ApiErrorType.CANCEL, message = "request_cancelled".tr))
                    err == "error" ->
                        ApiResult.Failure(ApiException(ApiErrorType.BUSINESS, message = "error"))
                    err != null ->
                        ApiResult.Failure(ApiException(ApiErrorType.BAD_RESPONSE, message = err))
                    else -> ApiResult.Success(text.toString())
                }
                onDone(result)
            },
            expectedRevision = revision,
        )
        return handle
    }

    /** 流式润色歌词：`POST /member/music/polishStream/v1`，body `{data: lyrics, type: 1}`。 */
    fun polishLyricsStream(
        lyrics: String,
        onDelta: (String) -> Unit,
        onDone: (ApiResult<String>) -> Unit,
        onMeta: ((JSONObject) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        expectedRevision: Int? = null,
    ): StreamHandle = polishStream(
        path = ApiPaths.musicPolishStream,
        body = JSONObject().put("data", lyrics).put("type", 1),
        onDelta = onDelta,
        onDone = onDone,
        onMeta = onMeta,
        onError = onError,
        expectedRevision = expectedRevision,
    )

    /** 流式润色 MV 描述：`POST /member/musicMvTask/mvDescPolish`，body `{prompt}`。 */
    fun polishMvDescStream(
        description: String,
        onDelta: (String) -> Unit,
        onDone: (ApiResult<String>) -> Unit,
        onMeta: ((JSONObject) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        expectedRevision: Int? = null,
    ): StreamHandle = polishStream(
        path = ApiPaths.mvDescPolish,
        body = JSONObject().put("prompt", description),
        onDelta = onDelta,
        onDone = onDone,
        onMeta = onMeta,
        onError = onError,
        expectedRevision = expectedRevision,
    )
}
