package com.sondo.app.features.square

import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.base.Routes
import com.sondo.app.features.square.data.SquarePlayArgs
import com.sondo.app.features.square.data.SquareProfileArgs
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 广场相关页面的打开入口（其它 slice 也可以直接用）。 */
object SquareNav {
    /**
     * 打开个人主页（Dart `SquareProfileController.open`）：栈内已有 Profile 则回到它并按新参数重载
     * （避免两页 Profile 叠在一起），否则新开。
     */
    fun openProfile(pager: BasePager, args: SquareProfileArgs, onResult: ((JSONObject?) -> Unit)? = null) {
        if (pager.nav.contains(Routes.profile)) {
            if (pager.pageName != Routes.profile) pager.nav.popTo(Routes.profile)
            pager.emit(Events.SQUARE_PROFILE_RELOAD, args.toJson())
            return
        }
        pager.nav.push(Routes.profile, args.toJson(), onResult)
    }

    /** 打开全屏播放页（`Routes.squarePlay`，可加载更多）。 */
    fun openPlay(pager: BasePager, args: SquarePlayArgs, onResult: ((JSONObject?) -> Unit)? = null) {
        pager.nav.push(Routes.squarePlay, args.toArgs(), onResult)
    }

    /** 打开详情播放页（`Routes.squarePlayDetail`，不加载更多、不显示头像）。 */
    fun openPlayDetail(pager: BasePager, args: SquarePlayArgs, onResult: ((JSONObject?) -> Unit)? = null) {
        pager.nav.push(Routes.squarePlayDetail, args.toArgs(), onResult)
    }
}
