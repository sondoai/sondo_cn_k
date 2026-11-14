package com.sondo.app.data.models

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 用户界面停留状态（Dart: lib/data/models/user_active_model.dart `UserActiveModel`）。 */
data class UserActiveModel(
    var libraryTabIndex: Int = 0,
    var musicTabIndex: Int = 0,
    var musciExpanded: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("libraryTabIndex", libraryTabIndex)
        .put("musicTabIndex", musicTabIndex)
        .put("musciExpanded", musciExpanded)

    companion object {
        fun fromJson(json: JSONObject): UserActiveModel = UserActiveModel(
            libraryTabIndex = json.jInt("libraryTabIndex", 0),
            musicTabIndex = json.jInt("musicTabIndex", 0),
            musciExpanded = json.jBool("musciExpanded", false),
        )

        fun listFromJson(arr: JSONArray?): List<UserActiveModel> = arr.jMap { fromJson(it) }
    }
}
