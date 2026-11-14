package com.sondo.app.features.four_view.data

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.http.dataObj
import com.sondo.app.data.api.FileUploadApi
import com.sondo.app.data.api.ModerationApi
import com.sondo.app.data.api.dataAny
import com.sondo.app.data.api.dataArray
import com.sondo.app.data.models.ImageUploadResult
import com.sondo.app.data.models.PageResult
import com.sondo.app.data.models.RoleModel
import com.sondo.app.data.models.RoleStatus
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 四视图角色生成状态（Dart: four_view/domain/entities/four_view_status.dart `FourViewStatus`）。 */
enum class FourViewStatus(val value: Int) {
    /** 排队中 */
    queue(0),

    /** 生成中 */
    generating(1),

    /** 生成成功 */
    success(2),

    /** 生成失败 */
    failed(3);

    companion object {
        /** 未知值兜底为排队中。 */
        fun fromValue(value: Int): FourViewStatus = values().firstOrNull { it.value == value } ?: queue
    }
}

/** 四视图角色业务实体（Dart: four_view/domain/entities/four_view_entity.dart `FourViewEntity`）。 */
data class FourViewEntity(
    val id: Long = 0L,
    /** 四视图生成结果图 URL。 */
    val roleImg: String = "",
    val status: FourViewStatus = FourViewStatus.queue,
    /** 角色外观描述文案。 */
    val roleImgDesc: String = "",
    /** 参考图 URL。 */
    val coverImage: String = "",
    val createTime: Long = 0L,
    val updateTime: Long = 0L,
) {
    val isQueue: Boolean get() = status == FourViewStatus.queue
    val isGenerating: Boolean get() = status == FourViewStatus.generating
    val isFailed: Boolean get() = status == FourViewStatus.failed
    val isSuccess: Boolean get() = status == FourViewStatus.success

    /** 与 RoleModel.toJson 结构一致（页面参数 / 结果）。 */
    fun toJson(): JSONObject = FourViewMapper.toModel(this).toJson()

    companion object {
        fun fromJson(json: JSONObject): FourViewEntity = FourViewMapper.fromModel(RoleModel.fromJson(json))
    }
}

/** 四视图角色分页列表数据；total 为 -1 表示请求失败（Dart `FourViewPageEntity`）。 */
data class FourViewPageEntity(val list: List<FourViewEntity>, val total: Int)

/** RoleModel 与 FourViewEntity 互转（Dart `FourViewMapper`）。 */
object FourViewMapper {
    fun fromModel(model: RoleModel): FourViewEntity = FourViewEntity(
        id = model.id,
        roleImg = model.roleImg,
        status = FourViewStatus.fromValue(model.status.value),
        roleImgDesc = model.roleImgDesc,
        coverImage = model.coverImage,
        createTime = model.createTime,
        updateTime = model.updateTime,
    )

    fun toModel(entity: FourViewEntity): RoleModel = RoleModel(
        id = entity.id,
        roleImg = entity.roleImg,
        status = RoleStatus.fromValue(entity.status.value),
        roleImgDesc = entity.roleImgDesc,
        coverImage = entity.coverImage,
        createTime = entity.createTime,
        updateTime = entity.updateTime,
    )

    fun fromModelList(list: List<RoleModel>): List<FourViewEntity> = list.map { fromModel(it) }
}

/**
 * 四视图接口（Dart: four_view/data/datasources/four_view_remote_datasource.dart + repositories/four_view_repository_impl.dart）。
 * Dart 数据源吞掉异常返回兜底值（列表 `[]`/total -1、bool false），这里把兜底写进 Success，保持页面分支一致。
 */
object FourViewApi {

    /** 分页获取角色列表，`GET /member/mv/four-view/page`。失败：list 空、total -1。 */
    suspend fun getPage(pageNo: Int, pageSize: Int = 10): ApiResult<FourViewPageEntity> {
        val res = Api.get(ApiPaths.fourViewPage, query = mapOf("pageNo" to pageNo, "pageSize" to pageSize))
        val failed = FourViewPageEntity(emptyList(), -1)
        return when (res) {
            is ApiResult.Failure -> ApiResult.Success(failed)
            is ApiResult.Success -> {
                if (res.data.dataAny() == null) return ApiResult.Success(failed)
                val page = try {
                    PageResult.fromData(res.data.dataObj()) { RoleModel.fromJson(it) }
                } catch (e: Throwable) {
                    return ApiResult.Success(failed)
                }
                ApiResult.Success(FourViewPageEntity(FourViewMapper.fromModelList(page.list), page.total))
            }
        }
    }

    /** 按 ID 批量查询角色（轮询生成状态），`GET /member/mv/four-view/getListByIds`。失败：空列表。 */
    suspend fun getByIds(ids: String): ApiResult<List<FourViewEntity>> {
        val res = Api.get(ApiPaths.fourViewGetListByIds, query = mapOf("ids" to ids))
        return when (res) {
            is ApiResult.Failure -> ApiResult.Success(emptyList())
            is ApiResult.Success -> {
                val arr = res.data.dataArray() ?: return ApiResult.Success(emptyList())
                ApiResult.Success(FourViewMapper.fromModelList(RoleModel.listFromJson(arr)))
            }
        }
    }

    /** 创建/重新生成角色，`POST /member/mv/four-view/gen`。Success(true) = code 0。 */
    suspend fun create(
        prompt: String,
        mainCharacterUrl: String? = null,
        channel: String = "sondoMv",
        ratio: String = "16:9",
        imageMode: String = "",
    ): ApiResult<Boolean> {
        val body = JSONObject().put("prompt", prompt)
        if (mainCharacterUrl != null) body.put("mainCharacterUrl", mainCharacterUrl)
        body.put("channel", channel).put("ratio", ratio).put("imageMode", imageMode)
        return when (val res = Api.post(ApiPaths.fourViewGen, body)) {
            is ApiResult.Failure -> ApiResult.Success(false)
            is ApiResult.Success -> ApiResult.Success(res.data.optInt("code", -1) == 0)
        }
    }

    /** 删除角色，`DELETE /member/mv/four-view/delete?id=`。Success(data == true)。 */
    suspend fun delete(id: Long): ApiResult<Boolean> =
        when (val res = Api.delete(ApiPaths.fourViewDelete, query = mapOf("id" to id))) {
            is ApiResult.Failure -> ApiResult.Success(false)
            is ApiResult.Success -> ApiResult.Success((res.data.dataAny() as? Boolean) ?: false)
        }

    /** 上传图片并做敏感检测（本地路径）。 */
    suspend fun uploadImageWithCheck(path: String): ApiResult<ImageUploadResult> = FileUploadApi.uploadImageWithCheck(path)

    /** 文本内容审核，非空返回值表示违规提示。 */
    suspend fun checkText(text: String): ApiResult<String> = ModerationApi.checkText(text)
}
