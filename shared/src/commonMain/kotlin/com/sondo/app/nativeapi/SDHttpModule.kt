package com.sondo.app.nativeapi

import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 通用 HTTP 能力（Kuikly 自带 NetworkModule 只支持 GET/POST 且要求 JSON 回包，这里补齐
 * PUT/DELETE、任意回包、文件上传下载与取消）。
 */
class SDHttpModule : SDModule() {
    override fun moduleName(): String = MODULE_NAME

    /**
     * 发起请求。
     * params: {id, method, url, headers:{}, body?: String, timeoutMs}
     * result: {ok, status, headers:{}, body: String, error, errorType: "timeout"|"network"|"cancel"|"other"}
     *   - ok 仅表示拿到了 HTTP 响应（任意状态码）；传输层失败时 ok=false。
     */
    suspend fun request(
        id: String,
        method: String,
        url: String,
        headers: JSONObject,
        body: String?,
        timeoutMs: Int,
    ): JSONObject {
        val p = JSONObject()
            .put("id", id)
            .put("method", method)
            .put("url", url)
            .put("headers", headers)
            .put("timeoutMs", timeoutMs)
        if (body != null) p.put("body", body)
        return await("request", p)
    }

    /** 取消进行中的请求 / 上传 / 下载。 */
    fun cancel(id: String) {
        invoke("cancel", JSONObject().put("id", id))
    }

    /**
     * 上传本地文件。
     * params: {id, method: "PUT"|"POST", url, headers:{}, filePath, contentType?,
     *          multipart?: Boolean, fieldName?: String, fileName?: String, formFields?: {},
     *          offset?: Long, length?: Long（分片上传时指定文件区间）, timeoutMs}
     * 事件: {event:"progress", sent, total} … {event:"done", ok, status, headers:{}, body, error}
     */
    fun upload(params: JSONObject, onEvent: (JSONObject) -> Unit): CallbackRef? =
        listen("upload", params, onEvent)

    /**
     * 下载到本地文件。
     * params: {id, url, headers?:{}, savePath?: String（空则存入缓存目录）, fileName?: String, timeoutMs}
     * 事件: {event:"progress", received, total} … {event:"done", ok, path, status, error}
     */
    fun download(params: JSONObject, onEvent: (JSONObject) -> Unit): CallbackRef? =
        listen("download", params, onEvent)

    /** GET `Range: bytes=0-0` 探测资源大小（5s 超时）。→ 字节数，未知为 -1 */
    suspend fun probeContentLength(url: String): Long =
        await("probeContentLength", JSONObject().put("url", url)).optLong("length", -1L)

    companion object {
        const val MODULE_NAME = "SDHttpModule"
    }
}
