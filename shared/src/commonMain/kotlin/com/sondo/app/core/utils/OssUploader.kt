package com.sondo.app.core.utils

import com.sondo.app.nativeapi.errorMsg
import com.sondo.app.nativeapi.ok
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class OssUploadException(message: String) : Exception(message)

/** 已完成的分片。ETag 原样使用（含引号）。 */
class OssPartEtag(val partNumber: Int, val etag: String)

/**
 * 直连阿里云 OSS S3 兼容接口：不带业务鉴权头，经宿主页面的 SDHttpModule 发出。
 * 预签名 PUT 必须带精确的 Content-Length（由原生按文件区间计算），不能用 chunked。
 * 取消抛 [CancelledException]，其它失败抛 [OssUploadException]。
 */
object OssUploader {
    private const val RECEIVE_TIMEOUT_MS = 120 * 1000
    private const val SEND_TIMEOUT_MS = 30 * 60 * 1000

    /** 向 initUploadUrl 发 POST（空 body），解析 OSS XML 返回 uploadId。 */
    suspend fun initMultipartUpload(initUploadUrl: String, cancelToken: CancelToken? = null): String {
        throwIfCancelled(cancelToken)
        val id = Uuid.v4()
        cancelToken?.track(id)
        val res = HostNative.http.request(
            id, "POST", initUploadUrl, JSONObject().put("Content-Length", "0"), "", RECEIVE_TIMEOUT_MS,
        )
        cancelToken?.untrack(id)
        ensureSuccess(res, cancelToken, "init multipart upload")
        return parseUploadId(res.optString("body", ""))
    }

    /**
     * PUT 文件的一个区间到 OSS，返回响应头中的 ETag。
     * @param offset 区间起点字节；@param length 区间长度
     */
    suspend fun putPart(
        uploadUrl: String,
        filePath: String,
        offset: Long,
        length: Long,
        cancelToken: CancelToken? = null,
        onSendProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ): String {
        val done = put(uploadUrl, filePath, offset, length, cancelToken, onSendProgress)
        return headerValue(done.optJSONObject("headers"), "etag")
    }

    /** PUT 整个文件直传 OSS（小文件，无需分片）。 */
    suspend fun putFile(
        uploadUrl: String,
        filePath: String,
        cancelToken: CancelToken? = null,
        onSendProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ) {
        put(uploadUrl, filePath, null, null, cancelToken, onSendProgress)
    }

    /** POST XML body 到 completeUrl，完成 OSS 分片合并。 */
    suspend fun completeMultipartUpload(completeUrl: String, parts: List<OssPartEtag>, cancelToken: CancelToken? = null) {
        throwIfCancelled(cancelToken)
        val id = Uuid.v4()
        cancelToken?.track(id)
        val res = HostNative.http.request(
            id, "POST", completeUrl, JSONObject().put("Content-Type", "application/xml"),
            buildCompleteXml(parts), RECEIVE_TIMEOUT_MS,
        )
        cancelToken?.untrack(id)
        ensureSuccess(res, cancelToken, "complete multipart upload")
    }

    private suspend fun put(
        uploadUrl: String,
        filePath: String,
        offset: Long?,
        length: Long?,
        cancelToken: CancelToken?,
        onSendProgress: ((sent: Long, total: Long) -> Unit)?,
    ): JSONObject {
        throwIfCancelled(cancelToken)
        val id = Uuid.v4()
        val params = JSONObject()
            .put("id", id)
            .put("method", "PUT")
            .put("url", uploadUrl)
            .put("headers", JSONObject().put("Content-Type", "application/octet-stream"))
            .put("contentType", "application/octet-stream")
            .put("filePath", filePath)
            .put("timeoutMs", SEND_TIMEOUT_MS)
        if (offset != null) params.put("offset", offset)
        if (length != null) params.put("length", length)
        cancelToken?.track(id)
        val http = HostNative.http
        val done: JSONObject = suspendCoroutine { cont ->
            var finished = false
            var ref: String? = null
            ref = http.upload(params) { e ->
                when (e.optString("event", "")) {
                    "progress" -> if (!finished) onSendProgress?.invoke(e.optLong("sent", 0L), e.optLong("total", 0L))
                    "done" -> if (!finished) {
                        finished = true
                        // 常驻回调用完即注销
                        ref?.let { http.removeCallback(it) }
                        cont.resume(e)
                    }
                }
            }
        }
        cancelToken?.untrack(id)
        ensureSuccess(done, cancelToken, "put")
        return done
    }

    private fun ensureSuccess(res: JSONObject, cancelToken: CancelToken?, what: String) {
        throwIfCancelled(cancelToken)
        if (!res.ok()) {
            if (res.optString("errorType", "") == "cancel") throw CancelledException(cancelToken?.reason ?: "cancelled")
            throw OssUploadException("$what failed: ${res.errorMsg()}")
        }
        val status = res.optInt("status", 0)
        if (status !in 200..299) throw OssUploadException("$what failed: HTTP $status")
    }

    private fun throwIfCancelled(cancelToken: CancelToken?) {
        if (cancelToken?.isCancelled == true) throw CancelledException(cancelToken.reason.ifEmpty { "cancelled" })
    }

    /** 响应头大小写不敏感。 */
    private fun headerValue(headers: JSONObject?, name: String): String {
        if (headers == null) return ""
        for (key in headers.keySet()) {
            if (key.equals(name, ignoreCase = true)) return headers.optString(key, "")
        }
        return ""
    }

    private val uploadIdRegex = Regex("<UploadId>(.*?)</UploadId>")

    private fun parseUploadId(xml: String): String {
        val match = uploadIdRegex.find(xml) ?: throw OssUploadException("Failed to parse UploadId from OSS XML")
        return match.groupValues[1]
    }

    private fun buildCompleteXml(parts: List<OssPartEtag>): String {
        val buf = StringBuilder("<CompleteMultipartUpload>")
        for (p in parts) {
            buf.append("<Part>")
                .append("<PartNumber>").append(p.partNumber).append("</PartNumber>")
                .append("<ETag>").append(p.etag).append("</ETag>")
                .append("</Part>")
        }
        buf.append("</CompleteMultipartUpload>")
        return buf.toString()
    }
}
