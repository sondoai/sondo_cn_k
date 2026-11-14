package com.sondo.app.features.my.data

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.data.api.AudioUploadApi
import com.sondo.app.data.models.AudioUploadItem
import com.sondo.app.data.models.ModelDate
import com.sondo.app.data.models.PageResult
import com.sondo.app.data.models.RecommendModel
import com.sondo.app.data.models.TrashItemModel
import com.sondo.app.data.models.jRaw
import com.sondo.app.data.models.jsonValueOrNull
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/*
 * 「我的」模块的数据层（Dart: lib/features/my/data + domain）。
 *
 * Dart 的 entity 与 Legacy model 字段一一对应（AudioUploadEntity ↔ AudioUploadItem、TrashItemEntity ↔ TrashItemModel、
 * ReferralRecordEntity ↔ RecommendModel），mapper 只是搬字段，这里直接使用 data/models 里的模型。
 */

/** 我的音频（Dart: MyAudioRemoteDataSource / MyAudioRepositoryImpl）。 */
object MyAudioApi {
    /**
     * 分页查询上传列表，`GET /infra/file/my-audios/page`。
     * 失败：Dart 返回 `total: -1`（页面据此重试 3 次）。
     */
    suspend fun listPage(pageNo: Int, pageSize: Int): ApiResult<PageResult<AudioUploadItem>> =
        AudioUploadApi.listPage(pageNo, pageSize)
}

/** 回收站（Dart: TrashRemoteDataSource / TrashRepositoryImpl）。 */
object TrashApi {
    /**
     * 分页获取回收站列表，`GET /member/deleted-music-mv/page`。
     * @param bizType 1 = 音乐，2 = MV
     */
    suspend fun getTrashList(pageNo: Int, pageSize: Int, bizType: Int): ApiResult<PageResult<TrashItemModel>> =
        Api.get(
            ApiPaths.deletedMusicMvPage,
            query = mapOf("pageNo" to pageNo, "pageSize" to pageSize, "bizType" to bizType),
        ).map { env ->
            val data = jsonValueOrNull(env.opt("data")) as? JSONObject
            PageResult.fromData(data) { json ->
                // MV 记录的 id / 标题字段名不同（mvId / mvTitle）
                val idRaw = json.jRaw("id") ?: json.jRaw("mvId")
                val titleRaw = json.jRaw("title") ?: json.jRaw("mvTitle")
                TrashItemModel(
                    id = (idRaw as? Number)?.toLong() ?: idRaw?.toString()?.toLongOrNull() ?: 0L,
                    title = titleRaw?.toString() ?: "",
                    deleteTime = (json.jRaw("deleteTime") as? Number)?.toLong()
                        ?: ModelDate.parseAny(json.jRaw("deleteTime")) ?: 0L,
                    bizType = (json.jRaw("bizType") as? Number)?.toInt() ?: 0,
                )
            }
        }

    /**
     * 恢复回收站条目，`POST /member/deleted-music-mv/restore`。
     * 成功（code == 0）为 Success(true)；失败：Dart 返回 false。
     */
    suspend fun recover(id: Long, bizType: Int): ApiResult<Boolean> =
        Api.post(ApiPaths.deletedMusicMvRestore, JSONObject().put("id", id).put("bizType", bizType)).map { true }
}

/** 邀请奖励（Dart: ReferralRemoteDataSource / ReferralRepositoryImpl）。绑定上级邀请码走 UserStore.bindSuperRegCode。 */
object ReferralApi {
    /**
     * 分页获取邀请记录，`GET /member/user/recommendRecords`。
     * 失败：Dart 返回 `total: -1` 的空页。
     */
    suspend fun getRecommendRecords(pageNo: Int, pageSize: Int): ApiResult<PageResult<RecommendModel>> =
        when (
            val r = Api.get(ApiPaths.userRecommendRecords, query = mapOf("pageNo" to pageNo, "pageSize" to pageSize)).map { env ->
                PageResult.fromData(jsonValueOrNull(env.opt("data")) as? JSONObject) { RecommendModel.fromJson(it) }
            }
        ) {
            is ApiResult.Success -> r
            // 与 Dart 一致：失败当空页（total = -1），记录页显示空态而非错误重试
            is ApiResult.Failure -> ApiResult.Success(PageResult(emptyList(), -1))
        }
}

/** 邀请奖励 FAQ 条目，question / answer 为 i18n key（Dart: FaqItem）。 */
class FaqItem(
    val question: String,
    val answer: String,
    val params: Map<String, String> = emptyMap(),
)

/** 个人中心菜单项（Dart: MineItem）。 */
enum class MineItem { updateUser, copyId, feedback, contactUs, activationCode, sendEmail, referral, trash, settings }

/** 设置页菜单项（Dart: SettingItem）。 */
enum class SettingItem { termsOfUse, privacyPolicy, withdrawAiDataConsent, logout, unsubscribe, deleteAccount }
