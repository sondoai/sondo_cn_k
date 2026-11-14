package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 回收站单条：音乐或 MV（Dart: lib/data/models/trash_item_model.dart `TrashItemModel`）。 */
data class TrashItemModel(
    var id: Long = 0L,
    var title: String = "",
    var bizType: Int = 0,
    var deleteTime: Long = 0L,
) {
    /** 删除时间加 7 天后距当前时间的整天数（向零取整）。 */
    val remainingDays: Int
        get() {
            val diff = deleteTime + 7L * 86_400_000L - ModelDate.now()
            return (diff / 86_400_000L).toInt()
        }

    val isMusic: Boolean get() = bizType == 1

    /** 与 Dart 一致：deleteTime 以 `remainingDays` 为键输出。 */
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("remainingDays", deleteTime)
        .put("bizType", bizType)

    companion object {
        fun fromJson(json: JSONObject): TrashItemModel = TrashItemModel(
            id = json.jLong("id", 0L),
            title = json.jStr("title"),
            deleteTime = json.jLong("deleteTime", 0L),
            bizType = json.jInt("bizType", 0),
        )

        fun listFromJson(arr: JSONArray?): List<TrashItemModel> = arr.jMap { fromJson(it) }
    }
}
