package com.sondo.app.data.api

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.data.models.AudioUploadItem
import com.sondo.app.data.models.PageResult
import com.sondo.app.data.models.toJsonLongArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 我的音频上传记录
 * （Dart: lib/shared/data/datasources/audio_upload_remote_datasource.dart `AudioUploadRemoteDataSource`）。
 */
object AudioUploadApi {

    /**
     * 分页拉取「我的音频上传」记录。
     * 失败（含 data 不是对象）：Dart 返回 `PageData(list: [], total: -1)`。
     */
    suspend fun listPage(pageNo: Int, pageSize: Int = 20): ApiResult<PageResult<AudioUploadItem>> =
        Api.get(ApiPaths.myAudiosPage, query = mapOf("pageNo" to pageNo, "pageSize" to pageSize))
            .parse { env -> env.dataObject()?.let { d -> PageResult.fromData(d) { AudioUploadItem.fromJson(it) } } }

    /**
     * 批量删除「我的音频上传」服务端记录（仅对已入库的已完成记录调用）。
     * 成功为 Success(true)；失败：Dart 返回 false。
     */
    suspend fun deleteRecord(ids: List<Long>): ApiResult<Boolean> =
        Api.post(ApiPaths.myAudiosDelete, JSONObject().put("ids", ids.toJsonLongArray())).asOk()
}
