package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 视频内容分类。舞蹈已下线，只保留 MV（Dart: lib/shared/domain/entities/video_content_type.dart）。 */
enum class VideoContentType {
    /** AI 音乐视频。 */
    mv;

    /** 转换为 Data 层 [MvType]，仅在 Mapper 边界使用。 */
    fun toMvType(): MvType = when (this) {
        mv -> MvType.mv
    }

    companion object {
        /** 从 Data 层 [MvType] 转换，仅在 Mapper 边界使用。 */
        fun fromMvType(type: MvType): VideoContentType = when (type) {
            MvType.mv -> mv
        }
    }
}

/**
 * MV / 舞蹈视频公共展示字段（Dart: lib/shared/domain/entities/video_content_base_entity.dart
 * `VideoContentBaseEntity`）。Dart 里是父类；这里是接口，由 [VideoContentEntity] 实现。
 */
interface VideoContentBaseEntity {
    val id: Long
    val userId: Long
    /** 关联音频 URL。 */
    val audioUrl: String
    /** 订阅时长档位标识。 */
    val subDurationScope: String
    val title: String
    val artist: String
    /** 封面图 URL。 */
    val coverImage: String
    /** 生成结果视频 URL。 */
    val resultUrl: String
    /** 首帧图 URL。 */
    val firstFrameImg: String
    /** 画幅比例，如 16:9、9:16。 */
    val imageComp: String
    /** 视频清晰度，如 480。 */
    val quality: Int
    /** MV 版本，2 表示 Ultra（Seedance）模式。 */
    val mvVersion: Int
    val mvPublish: VideoPublishStatus
    val collectNum: Int
    val repostCount: Int
    val mvHitNum: Int
    val isCollect: Boolean
    val isBuyCertificate: Boolean
    val nickname: String
    val avatar: String
    val createTime: Long
    /** 更新时间戳（毫秒）。 */
    val updateTime: Long
    /** 是否使用四视图生成，1=是。 */
    val useFourViewGen: Int
    /** 错误信息。 */
    val errorMsg: String
    /** 连续生成完整视频所需积分。 */
    val continueFullMvNeedPoint: Int
    /** 收费模式，0=两步收费，1=提前收费；null 表示其他模式。 */
    val chargingModel: Int?
    /** 是否更新标记。 */
    val isUpdt: Int

    /** 默认封面回退：首帧图 → 封面图。 */
    val cover: String get() = if (firstFrameImg.isNotEmpty()) firstFrameImg else coverImage

    /** 昵称脱敏展示，邮箱类昵称隐藏域名。 */
    val nicknameStr: String
        get() {
            if (nickname.isNotEmpty() && nickname.contains("@")) {
                return "${nickname.split("@").first()}@***"
            }
            return nickname
        }
}

/** MV 视频统一展示实体（Dart: lib/shared/domain/entities/video_content_entity.dart `VideoContentEntity`）。 */
data class VideoContentEntity(
    var type: VideoContentType = VideoContentType.mv,
    override var id: Long = 0L,
    override var userId: Long = 0L,
    override var audioUrl: String = "",
    override var subDurationScope: String = "",
    override var title: String = "",
    override var artist: String = "",
    override var coverImage: String = "",
    override var resultUrl: String = "",
    override var firstFrameImg: String = "",
    override var imageComp: String = "16:9",
    override var quality: Int = 480,
    override var mvVersion: Int = 0,
    override var mvPublish: VideoPublishStatus = VideoPublishStatus.unpublish,
    override var collectNum: Int = 0,
    override var repostCount: Int = 0,
    override var mvHitNum: Int = 0,
    override var isCollect: Boolean = false,
    override var isBuyCertificate: Boolean = false,
    override var nickname: String = "",
    override var avatar: String = "",
    override var createTime: Long = 0L,
    override var updateTime: Long = 0L,
    override var useFourViewGen: Int = 0,
    override var errorMsg: String = "",
    override var continueFullMvNeedPoint: Int = 0,
    override var chargingModel: Int? = null,
    override var isUpdt: Int = 0,
    /** 关联音乐 ID，仅 MV 有效，本地歌曲时为空。 */
    var musicId: Long? = null,
    /** MV 生成状态，仅 MV 有效。 */
    var mvStatus: MvStatus? = null,
    /** 生成模式，仅 MV 有效。 */
    var genMode: Int = 0,
    /** 信息流版本号，仅 MV 有效。 */
    var informationFlowVersion: Int = 1,
    /** 高级版 MV 类型，仅 MV 有效。 */
    var modelType: Int = 0,
    /** 四视图图片，仅 MV 有效。 */
    var fourViewImg: String = "",
    /** MV 类型，0=文生视频，1=文生图→图生视频。 */
    var mvType: Int? = 0,
    /** 是否试用MV */
    var trialMvFlag: Boolean = false,
    /** 试用阶段：1四视图 2图片 3视频 4合成 */
    var trialStage: Int = 0,
    /** 试用模板组ID */
    var trialTemplateId: Long = 0L,
    /** 任务阶段，0=图片生成，1=视频生成。 */
    var taskPhase: Int? = 0,
    /** 故事板分镜提示词是否完成，0=未完成，1=完成。 */
    var storyboardStatus: Int? = 0,
    /** MV 风格标识。 */
    var mvStyle: String? = null,
    /** 动态图 URL。 */
    var dynamicGraph: String? = null,
    /** 是否展示标题，0=否，1=是。 */
    var isTitle: Int = 0,
    /** 是否展示作者名称，0=否，1=是。 */
    var isName: Int = 0,
    /** 是否展示歌词，0=否，1=是，null=不展示选项。 */
    var isLyric: Int? = null,
    /** 是否去除水印，0=否，1=是。 */
    var isWatermark: Int = 0,
    /** 封面提示词。 */
    var coverPrompt: String = "",
    /** 做同款剧情，仅 MV 有效。null 或空白表示没有可复用描述。 */
    var customStoryPlot: String? = null,
) : VideoContentBaseEntity {
    val isMv: Boolean get() = type == VideoContentType.mv

    /** 非空白剧情才展示做同款。 */
    val canMakeSame: Boolean get() = customStoryPlot?.trim()?.isNotEmpty() ?: false

    /** 时长展示：用 [subDurationScope] 区间差。 */
    val durationStr: String
        get() {
            val parts = subDurationScope.split(",")
            if (parts.size != 2) return "00:00"
            fun parseTime(time: String): Int {
                val t = time.split(":")
                val minutes = t.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                val seconds = t.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                return minutes * 60 + seconds
            }
            val diff = parseTime(parts[1]) - parseTime(parts[0])
            val totalMinutes = (diff / 60) % 60
            val totalSeconds = diff % 60
            return "${totalMinutes.toString().padStart(2, '0')}:${totalSeconds.toString().padStart(2, '0')}"
        }

    /** 创建时间展示。 */
    val timeStr: String get() = ModelDate.formatDateTime(createTime)

    /** 创建时间英文格式 `MMM d, yyyy`。 */
    val createTimeStr: String
        get() {
            if (createTime == 0L) return ""
            return ModelDate.formatMMMdyyyy(createTime)
        }

    /** 是否处于图片生成阶段（仅 MV）。 */
    val isImagePhase: Boolean get() = isMv && taskPhase == 0

    /** 是否为旧版数据（仅 MV，genMode=0）。 */
    val isOldData: Boolean get() = isMv && genMode == 0

    /** 是否为四视图生成（无首帧时 useFourViewGen=1）。 */
    val isFourViewGen: Boolean
        get() {
            if (firstFrameImg.isEmpty()) return useFourViewGen == 1
            return false
        }

    /** 是否可进入信息流（仅 MV，特定状态）。 */
    val isFlowData: Boolean
        get() {
            if (!isMv || mvStatus == null) return false
            return mvStatus == MvStatus.queue ||
                mvStatus == MvStatus.generating ||
                mvStatus == MvStatus.waitingConfirm ||
                mvStatus == MvStatus.imageFailed ||
                mvStatus == MvStatus.videoFailed
        }

    /** 收费模式，0=两步收费，1=提前收费。 */
    val chargModel: Int get() = chargingModel ?: 0

    /** 页面间传参用（Dart 实体没有 JSON；键名与属性名一致，mvStatus / mvPublish 存数值）。 */
    fun toJson(): JSONObject = JSONObject()
        .put("type", type.name)
        .put("id", id)
        .put("userId", userId)
        .put("audioUrl", audioUrl)
        .put("subDurationScope", subDurationScope)
        .put("title", title)
        .put("artist", artist)
        .put("coverImage", coverImage)
        .put("resultUrl", resultUrl)
        .put("firstFrameImg", firstFrameImg)
        .put("imageComp", imageComp)
        .put("quality", quality)
        .put("mvVersion", mvVersion)
        .put("collectNum", collectNum)
        .put("repostCount", repostCount)
        .put("mvHitNum", mvHitNum)
        .put("isCollect", isCollect)
        .put("isBuyCertificate", isBuyCertificate)
        .put("nickname", nickname)
        .put("avatar", avatar)
        .put("createTime", createTime)
        .put("updateTime", updateTime)
        .put("useFourViewGen", useFourViewGen)
        .put("errorMsg", errorMsg)
        .put("continueFullMvNeedPoint", continueFullMvNeedPoint)
        .put("chargingModel", chargingModel)
        .put("isUpdt", isUpdt)
        .put("musicId", musicId)
        .put("genMode", genMode)
        .put("informationFlowVersion", informationFlowVersion)
        .put("modelType", modelType)
        .put("fourViewImg", fourViewImg)
        .put("mvType", mvType)
        .put("trialMvFlag", trialMvFlag)
        .put("trialStage", trialStage)
        .put("trialTemplateId", trialTemplateId)
        .put("taskPhase", taskPhase)
        .put("storyboardStatus", storyboardStatus)
        .put("mvStyle", mvStyle)
        .put("dynamicGraph", dynamicGraph)
        .put("isTitle", isTitle)
        .put("isName", isName)
        .put("isLyric", isLyric)
        .put("isWatermark", isWatermark)
        .put("coverPrompt", coverPrompt)
        .put("customStoryPlot", customStoryPlot)
        .put("mvStatus", mvStatus?.value)
        .put("mvPublish", VideoPublishStatus.toValue(mvPublish))

    companion object {
        /** 与 [toJson] 对应。 */
        fun fromJson(json: JSONObject): VideoContentEntity = VideoContentEntity(
            type = VideoContentType.mv,
            id = json.jLong("id", 0L),
            userId = json.jLong("userId", 0L),
            audioUrl = json.jStr("audioUrl"),
            subDurationScope = json.jStr("subDurationScope"),
            title = json.jStr("title"),
            artist = json.jStr("artist"),
            coverImage = json.jStr("coverImage"),
            resultUrl = json.jStr("resultUrl"),
            firstFrameImg = json.jStr("firstFrameImg"),
            imageComp = json.jStr("imageComp", "16:9"),
            quality = json.jInt("quality", 480),
            mvVersion = json.jInt("mvVersion", 0),
            collectNum = json.jInt("collectNum", 0),
            repostCount = json.jInt("repostCount", 0),
            mvHitNum = json.jInt("mvHitNum", 0),
            isCollect = json.jBool("isCollect", false),
            isBuyCertificate = json.jBool("isBuyCertificate", false),
            nickname = json.jStr("nickname"),
            avatar = json.jStr("avatar"),
            createTime = json.jLong("createTime", 0L),
            updateTime = json.jLong("updateTime", 0L),
            useFourViewGen = json.jInt("useFourViewGen", 0),
            errorMsg = json.jStr("errorMsg"),
            continueFullMvNeedPoint = json.jInt("continueFullMvNeedPoint", 0),
            chargingModel = json.jIntOrNull("chargingModel"),
            isUpdt = json.jInt("isUpdt", 0),
            musicId = json.jLongOrNull("musicId"),
            genMode = json.jInt("genMode", 0),
            informationFlowVersion = json.jInt("informationFlowVersion", 1),
            modelType = json.jInt("modelType", 0),
            fourViewImg = json.jStr("fourViewImg"),
            mvType = json.jIntOrNull("mvType"),
            trialMvFlag = json.jBool("trialMvFlag", false),
            trialStage = json.jInt("trialStage", 0),
            trialTemplateId = json.jLong("trialTemplateId", 0L),
            taskPhase = json.jIntOrNull("taskPhase"),
            storyboardStatus = json.jIntOrNull("storyboardStatus"),
            mvStyle = json.jStrOrNull("mvStyle"),
            dynamicGraph = json.jStrOrNull("dynamicGraph"),
            isTitle = json.jInt("isTitle", 0),
            isName = json.jInt("isName", 0),
            isLyric = json.jIntOrNull("isLyric"),
            isWatermark = json.jInt("isWatermark", 0),
            coverPrompt = json.jStr("coverPrompt"),
            customStoryPlot = json.jStrOrNull("customStoryPlot"),
            mvStatus = json.jIntOrNull("mvStatus")?.let { MvStatus.getStatus(it) },
            mvPublish = VideoPublishStatus.parse(json.jRaw("mvPublish")),
        )

        fun listFromJson(arr: JSONArray?): List<VideoContentEntity> = arr.jMap { fromJson(it) }

        /** 构造 MV 展示实体（Dart `VideoContentEntity.mv`）。 */
        fun mv(
            id: Long,
            musicId: Long? = null,
            userId: Long,
            title: String,
            artist: String = "",
            audioUrl: String,
            coverImage: String = "",
            status: MvStatus,
            resultUrl: String = "",
            firstFrameImg: String = "",
            subDurationScope: String,
            imageComp: String = "16:9",
            genMode: Int = 0,
            informationFlowVersion: Int = 1,
            createTime: Long = 0L,
            updateTime: Long = 0L,
            quality: Int = 480,
            isCollect: Boolean = false,
            collectNum: Int = 0,
            repostCount: Int = 0,
            mvHitNum: Int = 0,
            mvPublish: VideoPublishStatus = VideoPublishStatus.unpublish,
            isBuyCertificate: Boolean = false,
            avatar: String = "",
            nickname: String = "",
            modelType: Int = 0,
            mvVersion: Int = 0,
            fourViewImg: String = "",
            mvType: Int? = 0,
            trialMvFlag: Boolean = false,
            trialStage: Int = 0,
            trialTemplateId: Long = 0L,
            taskPhase: Int? = 0,
            storyboardStatus: Int? = 0,
            errorMsg: String? = null,
            mvStyle: String? = null,
            dynamicGraph: String? = null,
            chargingModel: Int? = null,
            isUpdt: Int = 0,
            isTitle: Int = 0,
            isName: Int = 0,
            isLyric: Int? = null,
            isWatermark: Int = 0,
            coverPrompt: String = "",
            continueFullMvNeedPoint: Int = 0,
            useFourViewGen: Int = 0,
            customStoryPlot: String? = null,
        ): VideoContentEntity = VideoContentEntity(
            type = VideoContentType.mv,
            id = id,
            musicId = musicId,
            userId = userId,
            title = title,
            artist = artist,
            audioUrl = audioUrl,
            coverImage = coverImage,
            mvStatus = status,
            resultUrl = resultUrl,
            firstFrameImg = firstFrameImg,
            subDurationScope = subDurationScope,
            imageComp = imageComp,
            genMode = genMode,
            informationFlowVersion = informationFlowVersion,
            createTime = createTime,
            updateTime = updateTime,
            quality = quality,
            isCollect = isCollect,
            collectNum = collectNum,
            repostCount = repostCount,
            mvHitNum = mvHitNum,
            mvPublish = mvPublish,
            isBuyCertificate = isBuyCertificate,
            avatar = avatar,
            nickname = nickname,
            modelType = modelType,
            mvVersion = mvVersion,
            fourViewImg = fourViewImg,
            mvType = mvType,
            trialMvFlag = trialMvFlag,
            trialStage = trialStage,
            trialTemplateId = trialTemplateId,
            taskPhase = taskPhase,
            storyboardStatus = storyboardStatus,
            errorMsg = errorMsg ?: "",
            mvStyle = mvStyle,
            dynamicGraph = dynamicGraph,
            chargingModel = chargingModel,
            isUpdt = isUpdt,
            isTitle = isTitle,
            isName = isName,
            isLyric = isLyric,
            isWatermark = isWatermark,
            coverPrompt = coverPrompt,
            continueFullMvNeedPoint = continueFullMvNeedPoint,
            useFourViewGen = useFourViewGen,
            customStoryPlot = customStoryPlot,
        )
    }
}

/**
 * MvModel → 展示实体（Dart: lib/shared/domain/mappers/video_content_mapper.dart `fromMvModel`）。
 *
 * 与 Dart 一致，以下字段不映射（丢失）：unlock、trialStartSeconds、trialEndSeconds、trialMvFlag、
 * trialStage、trialTemplateId、capcutSwitchVisible、capcutEnabled、updateTime。
 */
fun MvModel.toEntity(): VideoContentEntity = VideoContentEntity.mv(
    id = id,
    musicId = musicId,
    userId = userId,
    title = title,
    artist = artist,
    audioUrl = audioUrl,
    coverImage = coverImage ?: "",
    status = status,
    resultUrl = resultUrl ?: "",
    firstFrameImg = firstFrameImg ?: "",
    subDurationScope = subDurationScope,
    imageComp = imageComp,
    genMode = genMode,
    informationFlowVersion = informationFlowVersion,
    createTime = createTime,
    quality = quality,
    isCollect = isCollect,
    collectNum = collectNum,
    repostCount = repostCount,
    mvHitNum = mvHitNum,
    mvPublish = mvPublish,
    isBuyCertificate = isBuyCertificate ?: false,
    avatar = avatar,
    nickname = nickname,
    modelType = modelType,
    mvVersion = mvVersion,
    fourViewImg = fourViewImg,
    mvType = mvType,
    taskPhase = taskPhase,
    storyboardStatus = storyboardStatus,
    errorMsg = errorMsg,
    mvStyle = mvStyle,
    dynamicGraph = dynamicGraph,
    chargingModel = chargingModel,
    isUpdt = isUpdt ?: 0,
    isTitle = isTitle,
    isName = isName,
    isLyric = isLyric,
    isWatermark = isWatermark,
    coverPrompt = coverPrompt,
    continueFullMvNeedPoint = continueFullMvNeedPoint,
    useFourViewGen = useFourViewGen,
    customStoryPlot = customStoryPlot,
)

/**
 * 展示实体 → MvModel（Dart: `VideoContentMapper.toMvModel`）。
 * 空串转回 null（coverImage / resultUrl / firstFrameImg / errorMsg）；试看与特效字段取 MvModel 默认值。
 */
fun VideoContentEntity.toMvModel(): MvModel = MvModel(
    id = id,
    musicId = musicId,
    userId = userId,
    title = title,
    artist = artist,
    audioUrl = audioUrl,
    coverImage = coverImage.ifEmpty { null },
    status = mvStatus ?: MvStatus.success,
    resultUrl = resultUrl.ifEmpty { null },
    firstFrameImg = firstFrameImg.ifEmpty { null },
    subDurationScope = subDurationScope,
    imageComp = imageComp,
    genMode = genMode,
    informationFlowVersion = informationFlowVersion,
    createTime = createTime,
    quality = quality,
    isCollect = isCollect,
    collectNum = collectNum,
    repostCount = repostCount,
    mvHitNum = mvHitNum,
    mvPublish = mvPublish,
    isBuyCertificate = isBuyCertificate,
    avatar = avatar,
    nickname = nickname,
    modelType = modelType,
    mvVersion = mvVersion,
    fourViewImg = fourViewImg,
    mvType = mvType,
    taskPhase = taskPhase,
    storyboardStatus = storyboardStatus,
    errorMsg = errorMsg.ifEmpty { null },
    mvStyle = mvStyle,
    dynamicGraph = dynamicGraph,
    chargingModel = chargingModel,
    isUpdt = isUpdt,
    isTitle = isTitle,
    isName = isName,
    isLyric = isLyric,
    isWatermark = isWatermark,
    coverPrompt = coverPrompt,
    continueFullMvNeedPoint = continueFullMvNeedPoint,
    useFourViewGen = useFourViewGen,
    customStoryPlot = customStoryPlot,
)

/** MV Model ↔ 统一展示实体转换（Dart: lib/shared/domain/mappers/video_content_mapper.dart）。 */
object VideoContentMapper {
    fun fromMvModel(model: MvModel): VideoContentEntity = model.toEntity()
    fun toMvModel(entity: VideoContentEntity): MvModel = entity.toMvModel()
    fun fromMvModelList(models: List<MvModel>): List<VideoContentEntity> = models.map { it.toEntity() }
    fun toMvModelList(entities: List<VideoContentEntity>): List<MvModel> = entities.map { it.toMvModel() }
}

/**
 * SquareModel ↔ VideoContentEntity（Dart: lib/shared/domain/adapters/video_content_legacy_adapter.dart）。
 * 经 `SquareModel.mvModel` 中转，所以 audioUrl=""、status=success，resultUrl / mvVersion 丢失。
 */
object VideoContentLegacyAdapter {
    fun fromSquareModel(model: SquareModel): VideoContentEntity = VideoContentMapper.fromMvModel(model.mvModel)

    fun toSquareModel(entity: VideoContentEntity): SquareModel = SquareModel.fromMv(VideoContentMapper.toMvModel(entity))

    fun toSquareList(list: List<VideoContentEntity>): List<SquareModel> = list.map { toSquareModel(it) }

    fun fromSquareList(list: List<SquareModel>): List<VideoContentEntity> = list.map { fromSquareModel(it) }
}

fun SquareModel.toEntity(): VideoContentEntity = VideoContentLegacyAdapter.fromSquareModel(this)

fun VideoContentEntity.toSquareModel(): SquareModel = VideoContentLegacyAdapter.toSquareModel(this)
