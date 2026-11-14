package com.sondo.app.core.http

import com.sondo.app.base.AppEnv
import com.sondo.app.base.AppHost
import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.i18n.I18n
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.Uuid
import com.sondo.app.core.utils.nowMs
import com.sondo.app.core.utils.urlEncode
import com.sondo.app.nativeapi.SDHttpModule
import com.sondo.app.nativeapi.nativeModule
import com.tencent.kuikly.core.log.KLog
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.timer.setTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 单次请求的可选项（对应 Flutter 侧 Dio Options.headers / extra）。
 */
class ReqOptions(
    /** 不带 Authorization（对应 headers: {'noToken': true}）。 */
    val noToken: Boolean = false,
    /** 业务错误不弹全局提示，由调用方自己处理（对应 extra.quietBusinessErrors）。 */
    val quiet: Boolean = false,
    /** 支付验单：与当前登录会话隔离，失败不弹全局错、不清 token、不走 401 续期。 */
    val isolatedPayment: Boolean = false,
    /** 显式凭证（isolatedPayment 时携带原账号 token）。 */
    val authorization: String? = null,
    val timeoutMs: Int = Api.TIMEOUT_LONG,
    /** 覆盖 baseUrl（如 AppConstants.apiOtherUrl）；path 以 http 开头时忽略。 */
    val baseUrl: String? = null,
    val headers: Map<String, String>? = null,
    /** 请求归属的账号代次；默认取发起时的当前代次。 */
    val sessionRevision: Int? = null,
) {
    companion object {
        val Default = ReqOptions()
        val NoToken = ReqOptions(noToken = true)
        val Quiet = ReqOptions(quiet = true)
    }
}

/**
 * 业务 HTTP 客户端（对应 Flutter 侧 DioClient + ApiInterceptor）。
 *
 * - 自动注入 Authorization / tenant-id / language / Request-Source / App-Version / App-Channel / X-Request-Id；
 * - 回包信封 `{code, msg, data}`：code == 0 才算成功，[ApiResult.Success] 里是完整信封；
 * - HTTP 401 或业务 code 401：同账号共享一次刷新，原请求最多重试一次，刷新失效则强制下线；
 * - 业务错误默认弹顶部提示（[ReqOptions.quiet] 可关）。
 *
 * 请求绑定在「当前页面」上：页面关闭后回调不再到达。需要跨页面存活的请求用 AppHost.launch 发起。
 */
object Api {
    const val TIMEOUT_DEFAULT = 30_000
    const val TIMEOUT_SHORT = 2_000
    const val TIMEOUT_LONG = 360_000
    private const val TIMEOUT_REFRESH = 10_000
    private const val TAG = "Api"

    /** Walle 渠道号。空表示不是渠道包，请求不带 App-Channel。启动时由 Bootstrap 写入。 */
    var appChannel: String = ""

    /** 网络是否可用（由连通性监听更新）。 */
    var hasNetwork: Boolean = true

    // —— 由上层注入的钩子（避免 core 反向依赖 service / ui）—— //
    /** 顶部错误提示。 */
    var onErrorToast: ((String) -> Unit)? = null
    /** 会话失效（刷新令牌过期）：执行自动登出。 */
    var onSessionInvalid: (() -> Unit)? = null
    /** 价格比对不一致（code 1004008001）：刷新国家定价。 */
    var onPriceMismatch: (() -> Unit)? = null

    /** 传输层错误不弹提示的路径。 */
    private val quietTransportPaths = setOf(
        "/app/question/stats",
        "/user/profile",
        "/app/practice/stats",
        ApiPaths.displayGuidePage,
        ApiPaths.mvConfigs,
        ApiPaths.authRefreshToken,
        ApiPaths.mvPage,
    )

    /** 回归资格、商品和促销签名失败由购买流程自己提示。 */
    private val recallQuietPaths = setOf(
        ApiPaths.oldPeopleContent,
        ApiPaths.returningUser,
        ApiPaths.returningOffer,
        ApiPaths.applePromotionalOffer,
    )

    // ------------------------------------------------------------------ 对外

    suspend fun get(
        path: String,
        query: Map<String, Any?>? = null,
        opts: ReqOptions = ReqOptions.Default,
    ): ApiResult<JSONObject> = request("GET", path, query, null, opts)

    suspend fun post(
        path: String,
        body: Any? = null,
        query: Map<String, Any?>? = null,
        opts: ReqOptions = ReqOptions.Default,
    ): ApiResult<JSONObject> = request("POST", path, query, body, opts)

    suspend fun put(
        path: String,
        body: Any? = null,
        query: Map<String, Any?>? = null,
        opts: ReqOptions = ReqOptions.Default,
    ): ApiResult<JSONObject> = request("PUT", path, query, body, opts)

    suspend fun delete(
        path: String,
        query: Map<String, Any?>? = null,
        body: Any? = null,
        opts: ReqOptions = ReqOptions.Default,
    ): ApiResult<JSONObject> = request("DELETE", path, query, body, opts)

    /**
     * @param body JSONObject / JSONArray / String / Map<String, Any?> / null
     */
    suspend fun request(
        method: String,
        path: String,
        query: Map<String, Any?>? = null,
        body: Any? = null,
        opts: ReqOptions = ReqOptions.Default,
    ): ApiResult<JSONObject> {
        val rev = opts.sessionRevision ?: AuthSession.revision
        return execute(method, path, query, body, opts, rev, retried = false, tokenOverride = null)
    }

    fun buildUrl(path: String, query: Map<String, Any?>?, baseUrl: String? = null): String {
        val base = if (path.startsWith("http://") || path.startsWith("https://")) path
        else (baseUrl ?: AppConstants.apiBaseUrl) + path
        if (query.isNullOrEmpty()) return base
        val sb = StringBuilder(base)
        var first = !base.contains("?")
        for ((k, v) in query) {
            if (v == null) continue
            if (v is Collection<*>) {
                for (item in v) {
                    sb.append(if (first) '?' else '&'); first = false
                    sb.append(urlEncode(k)).append('=').append(urlEncode(item.toString()))
                }
            } else {
                sb.append(if (first) '?' else '&'); first = false
                sb.append(urlEncode(k)).append('=').append(urlEncode(v.toString()))
            }
        }
        return sb.toString()
    }

    /** 当前公共请求头（流式请求 / 上传等自行发请求的场景复用）。 */
    fun commonHeaders(noToken: Boolean = false): JSONObject {
        val h = JSONObject()
        h.put("Content-Type", "application/json")
        h.put("tenant-id", "1")
        h.put("language", I18n.LANGUAGE)
        h.put("Request-Source", AppEnv.requestSource)
        val ch = appChannel.trim()
        if (ch.isNotEmpty()) h.put("App-Channel", ch)
        h.put("X-Request-Id", Uuid.v4())
        if (AppEnv.appVersionName.isNotEmpty()) h.put("App-Version", AppEnv.appVersionName)
        if (!noToken) {
            val token = AuthSession.accessToken
            if (token.isNotEmpty()) h.put("Authorization", token)
        }
        return h
    }

    // ------------------------------------------------------------------ 内部

    private fun encodeBody(body: Any?): String? = when (body) {
        null -> null
        is String -> body
        is JSONObject -> body.toString()
        is JSONArray -> body.toString()
        is Map<*, *> -> {
            val o = JSONObject()
            for ((k, v) in body) if (v != null) o.put(k.toString(), v)
            o.toString()
        }
        else -> body.toString()
    }

    private suspend fun execute(
        method: String,
        path: String,
        query: Map<String, Any?>?,
        body: Any?,
        opts: ReqOptions,
        rev: Int,
        retried: Boolean,
        tokenOverride: String?,
    ): ApiResult<JSONObject> {
        // 旧账号排队的请求不能借用新凭证发送；支付验单携带原账号显式凭证，不受此限。
        if (!opts.isolatedPayment && !AuthSession.isCurrent(rev)) {
            return ApiResult.Failure(ApiException(ApiErrorType.CANCEL, message = "request_cancelled".tr))
        }

        val headers = commonHeaders(noToken = true)
        opts.headers?.forEach { (k, v) -> headers.put(k, v) }
        var requestToken = ""
        if (opts.authorization != null) {
            requestToken = opts.authorization
        } else if (!opts.noToken) {
            requestToken = tokenOverride ?: AuthSession.accessToken
        }
        if (requestToken.isNotEmpty()) headers.put("Authorization", requestToken)
        if (retried) headers.put("X-Request-Id", Uuid.v4() + "-r")

        val url = buildUrl(path, query, opts.baseUrl)
        val http: SDHttpModule = nativeModule(SDHttpModule.MODULE_NAME)
        val reqId = Uuid.v4()
        if (AppEnv.isDebug) KLog.i(TAG, "--> $method $path")
        val res = http.request(reqId, method, url, headers, encodeBody(body), opts.timeoutMs)

        // ---------- 传输层失败 ----------
        if (!res.optBoolean("ok", false)) {
            val errType = res.optString("errorType", "other")
            val type = when (errType) {
                "timeout" -> ApiErrorType.TIMEOUT
                "cancel" -> ApiErrorType.CANCEL
                else -> ApiErrorType.NETWORK
            }
            val msg = when (type) {
                ApiErrorType.TIMEOUT -> "network_timeout".tr
                ApiErrorType.CANCEL -> "request_cancelled".tr
                else -> ""
            }
            if (AppEnv.isDebug) KLog.e(TAG, "<-- $method $path transport error: $errType ${res.optString("error")}")
            if (!opts.isolatedPayment) toastTransport(path, opts, msg)
            return ApiResult.Failure(ApiException(type, message = msg))
        }

        val status = res.optInt("status", 0)
        val text = res.optString("body", "")
        val json: JSONObject? = parseObject(text)
        if (AppEnv.isDebug) KLog.i(TAG, "<-- $status $method $path code=${json?.optInt("code", -1)}")

        // ---------- 支付校验：与会话隔离，原样交给调用方 ----------
        if (opts.isolatedPayment) {
            if (json == null || status !in 200..299) {
                return ApiResult.Failure(ApiException(ApiErrorType.BAD_RESPONSE, httpStatus = status, body = json))
            }
            return ApiResult.Success(json)
        }

        // ---------- HTTP 非 2xx ----------
        if (status !in 200..299) {
            if (status == 401) {
                val err = ApiException(ApiErrorType.UNAUTHORIZED, 401, "unauthorized_login_again".tr, status, json)
                return recoverUnauthorized(method, path, query, body, opts, rev, retried, requestToken, err)
            }
            val msg = when (status) {
                403 -> "no_permission_to_access".tr
                404 -> "resource_not_found".tr
                500 -> "internal_server_error".tr
                else -> "network_timeout".tr
            }
            toastTransport(path, opts, msg)
            return ApiResult.Failure(ApiException(ApiErrorType.BAD_RESPONSE, message = msg, httpStatus = status, body = json))
        }

        // ---------- 网关 / 错环境返回 HTML 或空 body ----------
        if (json == null) {
            return ApiResult.Failure(ApiException(ApiErrorType.BAD_RESPONSE, httpStatus = status))
        }

        val code = json.optInt("code", -1)
        if (code == 401) {
            val err = ApiException(ApiErrorType.UNAUTHORIZED, 401, json.optString("msg", ""), status, json)
            return recoverUnauthorized(method, path, query, body, opts, rev, retried, requestToken, err)
        }
        if (code != 0) {
            val msg = json.optString("msg", "")
            if (!opts.quiet && path !in recallQuietPaths) toast(msg)
            // 价格比对不一致，刷新配置
            if (code == 1004008001) onPriceMismatch?.invoke()
            return ApiResult.Failure(ApiException(ApiErrorType.BUSINESS, code, msg, status, json))
        }
        return ApiResult.Success(json)
    }

    private fun parseObject(text: String): JSONObject? {
        val t = text.trim()
        if (!t.startsWith("{")) return null
        return try { JSONObject(t) } catch (e: Throwable) { null }
    }

    private fun toast(msg: String) {
        if (msg.isEmpty()) return
        onErrorToast?.invoke(msg)
    }

    private fun toastTransport(path: String, opts: ReqOptions, msg: String) {
        if (opts.quiet || path in quietTransportPaths || path in recallQuietPaths) return
        toast(msg)
    }

    // ------------------------------------------------------------------ 401 恢复

    /** HTTP 401 与业务 401 共用恢复流程；每个请求最多重试一次。 */
    private suspend fun recoverUnauthorized(
        method: String,
        path: String,
        query: Map<String, Any?>?,
        body: Any?,
        opts: ReqOptions,
        rev: Int,
        retried: Boolean,
        requestToken: String,
        error: ApiException,
    ): ApiResult<JSONObject> {
        val fail = ApiResult.Failure(error)
        val isRefresh = path.endsWith(ApiPaths.authRefreshToken)
        if (opts.noToken && !isRefresh) {
            // 匿名请求被拒：提示后端文案或「登录失败」。
            // noToken + quiet 只有启动页 / 应用火爆 / 引导分流，Dart 走无拦截器的 Dio，不提示。
            if (AuthSession.isCurrent(rev) && !opts.quiet && path !in recallQuietPaths) {
                toast(error.serverMsg.trim().ifEmpty { "login_error".tr })
            }
        }
        if (opts.noToken || isRefresh || !AuthSession.isCurrent(rev)) return fail

        val currentToken = AuthSession.accessToken
        if (retried || currentToken.isEmpty()) {
            if (requestToken == currentToken) {
                val refreshToken = AuthSession.refreshToken
                val key = refreshKey(rev, refreshToken)
                if (!isRefreshing(key)) {
                    invalidateSession(rev)
                } else {
                    // 另一轮刷新正在验证凭证时，由其结果决定是否失效。
                    val code = awaitRefresh(key)
                    if (code == 401 && AuthSession.refreshToken == refreshToken) invalidateSession(rev)
                }
            }
            return fail
        }

        // 同账号的另一请求已刷新过时，复用新凭证，不再次刷新旧 token。
        var retryToken = currentToken
        if (requestToken == currentToken) {
            val refreshToken = AuthSession.refreshToken
            if (refreshToken.isEmpty()) {
                invalidateSession(rev)
                return fail
            }
            val code = refreshForSession(refreshToken, rev)
            if (!AuthSession.isCurrent(rev)) return fail
            if (code != 0) {
                if (code == 401 && AuthSession.refreshToken == refreshToken) invalidateSession(rev)
                return fail
            }
            retryToken = AuthSession.accessToken
        }
        if (!AuthSession.isCurrent(rev) || retryToken.isEmpty()) return fail
        // 重试仍走完整流程，业务失败照常变成 Failure。
        return execute(method, path, query, body, opts, rev, retried = true, tokenOverride = retryToken)
    }

    private fun invalidateSession(rev: Int) {
        if (!AuthSession.isCurrent(rev)) return
        val hook = onSessionInvalid
        if (hook != null) hook() else AuthSession.clear()
    }

    private class RefreshEntry(val startedAt: Long) {
        /** 等待者（pagerId, 回调）。 */
        val waiters = ArrayList<Pair<String, (Int) -> Unit>>()
    }

    // 进行中的刷新：key → 等待者。全部页面共享，同在 Kuikly 线程。
    private val refreshing = HashMap<String, RefreshEntry>()

    private fun refreshKey(rev: Int, refreshToken: String) = "$rev|$refreshToken"

    /** 是否有进行中的刷新；超时未结束的条目（宿主定时器也丢了）直接清掉。 */
    private fun isRefreshing(key: String): Boolean {
        val entry = refreshing[key] ?: return false
        if (nowMs() - entry.startedAt > TIMEOUT_REFRESH + 2_000) {
            finishRefresh(key, entry, 500)
            return false
        }
        return true
    }

    private fun finishRefresh(key: String, entry: RefreshEntry, code: Int) {
        if (refreshing[key] !== entry) return
        refreshing.remove(key)
        entry.waiters.forEach { (pagerId, cb) ->
            // 切回等待者自己的页面上下文再恢复
            setTimeout(pagerId, 0) { cb(code) }
        }
    }

    private suspend fun awaitRefresh(key: String): Int {
        val waiters = refreshing[key]?.waiters ?: return 500
        val pagerId = (PagerManager.getCurrentPager() as com.tencent.kuikly.core.pager.Pager).pagerId
        return suspendCoroutine { cont ->
            var done = false
            val finish: (Int) -> Unit = { code ->
                if (!done) {
                    done = true
                    cont.resume(code)
                }
            }
            waiters.add(pagerId to finish)
            // 发起刷新的页面若中途销毁，等待者不能永久挂起。
            setTimeout(pagerId, TIMEOUT_REFRESH + 2_000) { finish(500) }
        }
    }

    /** 流式调用与普通请求共用当前会话的刷新任务。返回是否刷新成功。 */
    suspend fun ensureRefreshed(refreshToken: String, expectedRevision: Int? = null): Boolean {
        val rev = expectedRevision ?: AuthSession.revision
        if (!AuthSession.isCurrent(rev)) return false
        val code = refreshForSession(refreshToken, rev)
        if (!AuthSession.isCurrent(rev)) return false
        if (code == 0) return true
        if (code == 401 && AuthSession.refreshToken == refreshToken) invalidateSession(rev)
        return false
    }

    /** @return 0 成功 / 401 刷新令牌失效 / 409 会话已变 / 500 其他失败 */
    private suspend fun refreshForSession(token: String, rev: Int): Int {
        if (token.isEmpty() || !AuthSession.isCurrent(rev) || AuthSession.refreshToken != token) return 401
        val key = refreshKey(rev, token)
        if (!isRefreshing(key)) startRefresh(key, token, rev)
        return awaitRefresh(key)
    }

    /**
     * 刷新跑在宿主页面上：发起它的页面中途关闭，回调也不会丢；
     * 另挂一个宿主定时器兜底（Dart 10s 总超时），无论如何都会清掉 key 并唤醒等待者。
     */
    private fun startRefresh(key: String, token: String, rev: Int) {
        val entry = RefreshEntry(nowMs())
        refreshing[key] = entry
        val hostId = try {
            AppHost.pager.pagerId
        } catch (e: Throwable) {
            ""
        }
        if (hostId.isEmpty()) {
            finishRefresh(key, entry, 500)
            return
        }
        setTimeout(hostId, TIMEOUT_REFRESH + 1_000) { finishRefresh(key, entry, 500) }
        AppHost.launch {
            val code = try {
                performRefresh(token, rev)
            } catch (e: Throwable) {
                500
            }
            finishRefresh(key, entry, code)
        }
    }

    private suspend fun performRefresh(token: String, rev: Int): Int {
        val http: SDHttpModule = nativeModule(SDHttpModule.MODULE_NAME)
        val url = buildUrl(ApiPaths.authRefreshToken, mapOf("refreshToken" to token))
        val res = http.request(Uuid.v4(), "POST", url, commonHeaders(noToken = true), null, TIMEOUT_REFRESH)
        if (!res.optBoolean("ok", false)) return 500
        val status = res.optInt("status", 0)
        val body = parseObject(res.optString("body", ""))
        if (status == 401 || body?.optInt("code", -1) == 401) return 401
        if (body == null || status !in 200..299) return 500
        if (body.optInt("code", -1) != 0) {
            // Dart 刷新请求同样经过拦截器：业务失败弹后端文案
            toast(body.optString("msg", ""))
            return 500
        }
        val data = body.optJSONObject("data") ?: return 500
        val newToken = data.optString("accessToken", "")
        val newRefresh = data.optString("refreshToken", "")
        if (newToken.trim().isEmpty() || newRefresh.trim().isEmpty()) return 500
        val committed = AuthSession.commitRefresh(rev, token, newToken, newRefresh)
        return if (committed) 0 else 409
    }
}
