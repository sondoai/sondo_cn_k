package com.sondo.app.features.music.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.sondo.app.data.models.SongEntity

/** 音乐生成模式（Dart: features/music/domain/entities/music_gen_params.dart `MusicGenMode`）。 */
enum class MusicGenMode {
    /** 简单模式：仅描述生成。 */
    simple,

    /** 自定义模式：歌词 + 风格 + 标题。 */
    custom,
}

/** 音乐生成请求参数（Dart `MusicGenParams`）。 */
data class MusicGenParams(
    var mode: MusicGenMode = MusicGenMode.simple,
    /** 简单模式描述文本。 */
    var musicPrompt: String = "",
    /** 是否纯音乐（无人声）。 */
    var instrumental: Boolean = false,
    var musicTitle: String = "",
    var musicLyric: String = "",
    /** 人声性别。空字符串表示随机，组包时不传，由后端按随机处理。 */
    var musicVocalGender: String = "",
    /** 风格标签，逗号分隔。 */
    var tags: String = "",
    /** 本次生成消耗积分。 */
    var point: Int = 0,
) {
    /** 后端生成类型：简单=2，自定义=3。 */
    val generateType: Int get() = if (mode == MusicGenMode.simple) 2 else 3
}

/** 组装生成请求体。空白字段不传：后端必填项拒绝空串，缺省表示当前模式不用该字段。 */
fun buildMusicGenerateBody(params: MusicGenParams): JSONObject {
    val prompt = params.musicPrompt.trim()
    val tags = params.tags.trim()
    val vocal = params.musicVocalGender.trim()
    val title = params.musicTitle.trim()
    val lyric = params.musicLyric.trim()
    val body = JSONObject()
        .put("generateType", params.generateType)
        .put("instrumental", params.instrumental)
        .put("userNeedPoint", params.point)
    if (prompt.isNotEmpty()) body.put("musicPrompt", prompt)
    if (tags.isNotEmpty()) body.put("tags", tags)
    if (vocal.isNotEmpty()) body.put("musicVocalGender", vocal)
    if (title.isNotEmpty()) body.put("musicTitle", title)
    if (lyric.isNotEmpty()) body.put("musicLyric", lyric)
    return body
}

/** 歌曲详情实体（Dart: features/music/domain/entities/song_detail_entity.dart `SongDetailEntity`）。 */
data class SongDetailEntity(
    var lyricDetail: JSONArray? = null,
    var musicDetail: SongEntity = SongEntity(),
    var user: JSONObject? = null,
)
