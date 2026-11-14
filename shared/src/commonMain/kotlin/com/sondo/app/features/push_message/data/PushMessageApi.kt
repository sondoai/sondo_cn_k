package com.sondo.app.features.push_message.data

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.http.dataObj
import com.sondo.app.data.models.PushMessageModel
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 推送落地页数据（Dart `PushMessageRemoteDataSource` + `PushMessageRepositoryImpl`）。 */
object PushMessageApi {
    /** 按落地页 id 拉取推送消息详情；业务码非 0 或请求失败为 Failure（Dart 返回 null → Error('request_failed')）。 */
    suspend fun fetchPushMessage(id: Long): ApiResult<PushMessageModel> =
        Api.get(ApiPaths.pushLandingPage, query = mapOf("id" to id))
            .map { PushMessageModel.fromJson(it.dataObj() ?: JSONObject()) }
}
