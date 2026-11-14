package com.sondo.app.data.api

import com.sondo.app.core.http.ApiResult

/**
 * 旧版通用静态接口（Dart: lib/data/service_api/common_api.dart `CommonApi`）。
 * 只保留清单里标记为仍在使用的 `cancelRecreateApi`；其余方法没有调用方（功能已迁到各 datasource），未移植。
 */
object CommonServiceApi {

    /**
     * 取消重新生成分镜。[genMode] 1 模板 / 2 自定义。
     * 成功返回后端新 status（data 为空时 -1）；失败：Dart 返回 -1。与 [MvActionApi.cancelRecreate] 同一接口。
     */
    suspend fun cancelRecreateApi(shotId: Long, genMode: Int): ApiResult<Int> =
        MvActionApi.cancelRecreate(shotId, genMode)
}
