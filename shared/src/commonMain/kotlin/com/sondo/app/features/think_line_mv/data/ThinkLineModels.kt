package com.sondo.app.features.think_line_mv.data

import com.sondo.app.data.models.jBool
import com.sondo.app.data.models.jStr
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 故事线情节片段（Dart: features/edit_video/presentation/widgets/ai_status_item.dart `SegmentItem`）。 */
data class SegmentItem(
    var emotion: String = "",
    var timestampRange: String = "",
    var storyContent: String = "",
    var isUpdate: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("emotion", emotion)
        .put("timestamp_range", timestampRange)
        .put("story_content", storyContent)
        .put("is_update", isUpdate)

    companion object {
        fun fromJson(json: JSONObject): SegmentItem = SegmentItem(
            emotion = json.jStr("emotion"),
            timestampRange = json.jStr("timestamp_range"),
            storyContent = json.jStr("story_content"),
            isUpdate = json.jBool("is_update", false),
        )

        fun listFromJson(arr: JSONArray?): List<SegmentItem> {
            if (arr == null) return emptyList()
            val out = ArrayList<SegmentItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(fromJson(o))
            }
            return out
        }

        fun listToJson(list: List<SegmentItem>): JSONArray {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr
        }
    }
}

/** MV 信息流页路由参数（Dart `ThinkLineMvArgs`）：`{id, type?}`。 */
class ThinkLineMvArgs(
    /** MV 任务 id；0 表示参数缺失，页面不发起请求。 */
    val id: Long,
    /** 进入来源：`create` 创建完成后进入（不触发全部已读）、`push` 推送进入、空串为资源库等其他入口。 */
    val type: String = "",
) {
    companion object {
        fun fromParams(params: JSONObject): ThinkLineMvArgs {
            val raw = params.opt("id")
            val id = when (raw) {
                is Number -> raw.toLong()
                is String -> raw.trim().toLongOrNull() ?: 0L
                else -> 0L
            }
            return ThinkLineMvArgs(id, params.optString("type", ""))
        }
    }
}
