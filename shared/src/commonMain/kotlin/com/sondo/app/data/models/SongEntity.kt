package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 歌曲业务实体，用于首页列表与播放器（Dart: lib/shared/domain/entities/song_entity.dart `SongEntity`）。 */
data class SongEntity(
    var id: Long = 0L,
    var musicTitle: String? = "",
    var musicPrompt: String? = null,
    var musicLyric: String? = null,
    /** 转换后歌词（展示用）。 */
    var musicConvertLyric: String? = "",
    var musicAudioUrl: String? = null,
    var musicCoverUrl: String? = null,
    var musicDurationStr: String? = null,
    var musicUid: Long = 0L,
    var musicType: String? = null,
    var musicVocalGender: String? = null,
    /** 音乐生成状态码。 */
    var musicGenerateStatus: Int = 0,
    var musicModelType: Int = 0,
    var musicGenerateTaskId: String? = "",
    var musicPublish: Int = 0,
    var isCollect: Boolean = false,
    var isCollectNum: Int = 0,
    var musicHitNum: Int = 0,
    var repostCount: Int = 0,
    var createTime: Long? = null,
    var updateTime: Long? = null,
    var musicCategory: String? = null,
    var musicCategoryId: Long? = null,
    /** 创作模式：1=简单模式，2=自定义模式。 */
    var musicCreativeMode: Int? = null,
    /** 发布失败错误信息。 */
    var publishErrorMsg: String? = null,
    var isBuyCertificate: Boolean = false,
    /** 生成失败错误信息。 */
    var errorMsg: String? = "",
    /** 是否纯音乐，1=是。 */
    var instrumental: Int = 0,
    /** 作者信息（JSON 对象）。 */
    var userInfo: JSONObject? = null,
) {
    /** 页面间传参用；键与 [SongModel.toJson] 相同。 */
    fun toJson(): JSONObject = toModel().toJson()

    companion object {
        fun fromJson(json: JSONObject): SongEntity = SongModel.fromJson(json).toEntity()

        fun listFromJson(arr: JSONArray?): List<SongEntity> = arr.jMap { fromJson(it) }
    }
}

/** Dart: lib/shared/data/mappers/song_mapper.dart `SongMapper.toEntity`。字段一一对应。 */
fun SongModel.toEntity(): SongEntity = SongEntity(
    id = id,
    musicTitle = musicTitle,
    musicPrompt = musicPrompt,
    musicLyric = musicLyric,
    musicConvertLyric = musicConvertLyric,
    musicAudioUrl = musicAudioUrl,
    musicCoverUrl = musicCoverUrl,
    musicDurationStr = musicDurationStr,
    musicUid = musicUid,
    musicType = musicType,
    musicVocalGender = musicVocalGender,
    musicGenerateStatus = musicGenerateStatus,
    musicModelType = musicModelType,
    musicGenerateTaskId = musicGenerateTaskId,
    musicPublish = musicPublish,
    isCollect = isCollect,
    isCollectNum = isCollectNum,
    createTime = createTime,
    updateTime = updateTime,
    musicHitNum = musicHitNum,
    musicCategory = musicCategory,
    musicCategoryId = musicCategoryId,
    musicCreativeMode = musicCreativeMode,
    publishErrorMsg = publishErrorMsg,
    isBuyCertificate = isBuyCertificate,
    instrumental = instrumental,
    userInfo = userInfo,
    errorMsg = errorMsg,
    repostCount = repostCount,
)

/** Dart: lib/shared/data/mappers/song_mapper.dart `SongMapper.toModel`。 */
fun SongEntity.toModel(): SongModel = SongModel(
    id = id,
    musicTitle = musicTitle,
    musicPrompt = musicPrompt,
    musicLyric = musicLyric,
    musicConvertLyric = musicConvertLyric,
    musicAudioUrl = musicAudioUrl,
    musicCoverUrl = musicCoverUrl,
    musicDurationStr = musicDurationStr,
    musicUid = musicUid,
    musicType = musicType,
    musicVocalGender = musicVocalGender,
    musicGenerateStatus = musicGenerateStatus,
    musicModelType = musicModelType,
    musicGenerateTaskId = musicGenerateTaskId,
    musicPublish = musicPublish,
    isCollect = isCollect,
    isCollectNum = isCollectNum,
    createTime = createTime,
    updateTime = updateTime,
    musicHitNum = musicHitNum,
    musicCategory = musicCategory,
    musicCategoryId = musicCategoryId,
    musicCreativeMode = musicCreativeMode,
    publishErrorMsg = publishErrorMsg,
    isBuyCertificate = isBuyCertificate,
    instrumental = instrumental,
    userInfo = userInfo,
    errorMsg = errorMsg,
    repostCount = repostCount,
)

/** 与 Dart 同名的静态入口。 */
object SongMapper {
    fun toEntity(model: SongModel): SongEntity = model.toEntity()
    fun toModel(entity: SongEntity): SongModel = entity.toModel()
    fun toEntityList(models: List<SongModel>): List<SongEntity> = models.map { it.toEntity() }
    fun toModelList(entities: List<SongEntity>): List<SongModel> = entities.map { it.toModel() }
}
