package com.sondo.music.video.ai.kuikly.adapter

import android.app.Activity
import android.content.Context
import com.sondo.music.video.ai.kuikly.KuiklyRenderActivity
import com.tencent.kuikly.core.render.android.adapter.IKRRouterAdapter
import org.json.JSONObject

/** Kuikly RouterModule 的宿主实现：每个 Kuikly 页面一个 [KuiklyRenderActivity]。 */
class KRRouterAdapter : IKRRouterAdapter {

    override fun openPage(context: Context, pageName: String, pageData: JSONObject) {
        KuiklyRenderActivity.start(context, pageName, pageData)
    }

    override fun closePage(context: Context) {
        (context as? Activity)?.finish()
    }
}
