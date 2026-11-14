package com.sondo.app.features.member

import com.sondo.app.base.BasePager
import com.sondo.app.service.pay.PayOutcome
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 会员页对 `Events.PAY_RESULT` 的统一处理：本页发起的购买有了结果就结束「请求中」；
 * 新订单成功但 PayService 没能关页（`pageClosed == false`）时自己带 `{"result": true}` 返回。
 */
internal fun BasePager.memberPayResult(json: JSONObject, pageName: String, onSettled: (PayOutcome) -> Unit) {
    val outcome = PayOutcome.fromJson(json)
    if (outcome.type != PayOutcome.TYPE_PURCHASE || !outcome.pageName.equals(pageName, ignoreCase = true)) return
    onSettled(outcome)
    if (outcome.isSuccess && outcome.isNewOrder && !outcome.pageClosed && isPageVisible) {
        nav.pop(JSONObject().put("result", true))
    }
}
