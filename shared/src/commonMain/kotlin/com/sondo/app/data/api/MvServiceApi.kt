package com.sondo.app.data.api

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.data.models.MvModel
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 旧版 MV 静态接口里仍有调用方的部分（Dart: lib/data/service_api/mv_api.dart `MvApi`）。
 * 其余方法在参考工程里没有调用方（功能已迁到各 datasource），未移植。
 */
object MvServiceApi {

    /**
     * 继续生成全片（试看解锁 mv_preview_unlock_flow 使用）。[segments] 非空时才带上。
     * Success(true) = 后端受理；Success(false) = data 为 false / 空；失败：Dart 返回 false。
     */
    suspend fun continueGenMvApi(
        mvId: Long,
        continueModel: Int,
        point: Int,
        segments: String = "",
    ): ApiResult<Boolean> {
        val body = JSONObject().put("id", mvId).put("continueModel", continueModel).put("userNeedPoint", point)
        if (segments.isNotEmpty()) body.put("segments", segments)
        return Api.post(ApiPaths.mvContinueGen, body).map { (it.dataAny() as? Boolean) ?: false }
    }

    /**
     * 获取 MV 详情（试看播放页 mv_preview_play 使用）。
     * 失败、data 为空或缺必填字段：Dart 返回 null。
     */
    suspend fun getMvDetailApiV2(id: Long): ApiResult<MvModel> =
        Api.get("${ApiPaths.mvDetail}/$id").parse { MvModel.fromJsonOrNull(it.dataObject()) }
}
