package com.sondo.app.data.api

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 跨 Feature 共用的文本内容审核
 * （Dart: lib/shared/data/datasources/moderation_remote_datasource.dart `ModerationRemoteDataSource`，
 * 并入 lib/shared/data/repositories/moderation_repository_impl.dart）。
 */
object ModerationApi {

    /**
     * 文本内容审核。Success 里非空字符串表示违规提示，空字符串表示通过。
     * 失败：Dart 返回空字符串（按通过处理）。
     */
    suspend fun checkText(text: String): ApiResult<String> =
        Api.post(ApiPaths.checkTextModeration, JSONObject().put("text", text))
            .map { (it.dataAny() as? String) ?: "" }
}
