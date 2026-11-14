package com.sondo.app.features.edit_video.state

import com.sondo.app.base.BasePager
import com.sondo.app.base.Events
import com.sondo.app.data.models.MvShot
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 思考线失败分镜重试页状态（Dart `RetryShotsController`）：复刻编辑页的分镜精修，不含导出选项与合成。
 * Recreate 走 retryFlowShotV2（isUpdt 0，积分为分镜 retryNeedPoint）。
 */
class RetryShotsState(pager: BasePager) : StoryboardState(pager) {

    override val retryMode: Boolean = true

    /** 还有其他失败镜头时显示 Next。 */
    val showNextFailed: Boolean get() = hasOtherFailedShots(shotList, shotIndex)

    /** 当前镜头失败原因；非失败为空。后端 errorMsg 已按 language 本地化。 */
    val currentFailReason: String
        get() {
            val shot = currentShot ?: return ""
            if (shot.status != retryShotFailedStatus) return ""
            return shot.errorMsg ?: ""
        }

    /** 进页落在第一个失败镜头；没有失败则从 0 开始。 */
    override fun initialShotIndex(): Int = firstFailedShotIndex(shotList) ?: 0

    override fun shouldScrollOnFirstSelect(index: Int): Boolean = true

    override fun onShotListChanged(result: List<MvShot>) {
        // 判断是否所有分镜生成完成
        shotIsCompleted = result.none { it.status != 2 }
    }

    override fun recreatePoint(shot: MvShot): Int = shot.retryNeedPoint

    /** 重试积分为 0 不拦余额；否则不够则去购买。 */
    override fun isPointInsufficient(userPoint: Int, needPoint: Int): Boolean =
        needPoint > 0 && (userPoint < needPoint || userPoint <= 0)

    override fun onRecreateAccepted() {
        notifyThinkLineShotRetried()
    }

    /** 切到下一个失败镜头，不要求已修改当前镜头。 */
    fun onNextFailedShot() {
        val index = nextFailedShotIndex(shotList, shotIndex) ?: return
        onSelectShot(index, isFirst = true)
        scrollShotIntoView(index)
    }

    /** Recreate 成功当下通知思考线恢复轮询（思考线按 mvId 匹配自己的 taskId，不匹配忽略）。 */
    private fun notifyThinkLineShotRetried() {
        pager.emit(Events.MV_SHOT_RETRIED, JSONObject().put("mvId", mvId))
    }

    fun popRetryPage() {
        pager.nav.pop()
    }
}
