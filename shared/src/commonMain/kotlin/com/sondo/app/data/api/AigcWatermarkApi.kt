package com.sondo.app.data.api

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.http.ReqOptions
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 保存账号上的下载水印开关，并在无水印导出前记账
 * （Dart: lib/shared/data/datasources/aigc_watermark_remote_datasource.dart `AigcWatermarkRemoteDataSource`）。
 * 两个接口都不弹全局业务错误。
 */
object AigcWatermarkApi {

    /**
     * 关闭时带上声明版本。服务端写入授权记录成功后才算关闭。
     * 成功为 Success(true)；失败：Dart 返回 false。
     */
    suspend fun save(enabled: Boolean, statementVersion: String? = null): ApiResult<Boolean> {
        val body = JSONObject().put("enabled", enabled)
        if (!enabled) body.put("statementVersion", statementVersion ?: AppConstants.aigcStatementVersion)
        return Api.post(ApiPaths.aigcWatermark, body, opts = ReqOptions.Quiet).asOk()
    }

    /**
     * 提供无水印视频前记账。失败时调用方不得交付无水印文件。
     * 成功为 Success(true)；失败：Dart 返回 false。
     */
    suspend fun confirmUnmarkedExport(contentId: String, contentType: String): ApiResult<Boolean> =
        Api.post(
            ApiPaths.aigcWatermarkExport,
            JSONObject().put("contentId", contentId).put("contentType", contentType),
            opts = ReqOptions.Quiet,
        ).asOk()
}
