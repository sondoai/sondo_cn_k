package com.sondo.app.data.models

/** 解锁入口。信息流成功后不改列表状态，播放页和资源库才会标生成中（Dart: lib/shared/domain/mv_preview_unlock.dart）。 */
enum class MvPreviewUnlockEntry { feed, player, library }

/** 进度条落点。试看文件的 0 对应 [MvPreviewTimeline.startSec]。 */
enum class PreviewSeekAction { seek, snapStartAndDialog, snapEndAndDialog }

/** 拖动落点结果（Dart: lib/shared/domain/mv_preview_unlock.dart `PreviewSeekResult`）。 */
data class PreviewSeekResult(
    var action: PreviewSeekAction = PreviewSeekAction.seek,
    var positionSec: Double = 0.0,
)

/** 整支 MV 时间轴上的可播区间。起止缺失或无效时不拦截拖动（Dart: `MvPreviewTimeline`）。 */
data class MvPreviewTimeline(
    var startSec: Double = 0.0,
    var endSec: Double = 0.0,
    var totalSec: Double = 0.0,
) {
    val hasRange: Boolean get() = totalSec > 0 && endSec > startSec && startSec >= 0 && endSec <= totalSec + 0.05

    /** 拖入试看前方回到起点并弹窗，拖入后方停在终点并弹窗。 */
    fun seekTo(targetSec: Double): PreviewSeekResult {
        val total = if (totalSec < 0) 0.0 else totalSec
        if (!hasRange) {
            return PreviewSeekResult(PreviewSeekAction.seek, targetSec.coerceIn(0.0, total))
        }
        if (targetSec < startSec) return PreviewSeekResult(PreviewSeekAction.snapStartAndDialog, startSec)
        if (targetSec > endSec) return PreviewSeekResult(PreviewSeekAction.snapEndAndDialog, endSec)
        return PreviewSeekResult(PreviewSeekAction.seek, targetSec)
    }
}

/** `subDurationScope` 形如 `01:05,01:40`。起点是原音频绝对秒，总长是区间差。返回 (start, total)。 */
private fun mvPreviewScope(subDurationScope: String): Pair<Double, Double> {
    val parts = subDurationScope.split(",")
    if (parts.size != 2) return 0.0 to 0.0
    fun parse(time: String): Double {
        val pieces = time.split(":")
        if (pieces.size < 2) return 0.0
        val minutes = pieces[0].trim().toIntOrNull() ?: 0
        val seconds = pieces[1].trim().toIntOrNull() ?: 0
        return (minutes * 60 + seconds).toDouble()
    }
    val start = parse(parts[0])
    val end = parse(parts[1])
    val diff = end - start
    return start to (if (diff < 0) 0.0 else diff)
}

/** `subDurationScope` 形如 `00:00,02:36`，返回整支 MV 秒数。 */
fun mvPreviewTotalSeconds(subDurationScope: String): Double = mvPreviewScope(subDurationScope).second

fun mvPreviewTimelineOf(mv: MvModel): MvPreviewTimeline {
    val (scopeStart, scopeTotal) = mvPreviewScope(mv.subDurationScope)
    // scope 缺失时仍尽量画出试看白段与锁标，避免整条进度条空白。
    if (scopeTotal <= 0) {
        val total = if (mv.trialEndSeconds > mv.trialStartSeconds) mv.trialEndSeconds else 0.0
        return MvPreviewTimeline(startSec = mv.trialStartSeconds, endSec = mv.trialEndSeconds, totalSec = total)
    }
    // 试看秒数是原音频绝对时间，进度条 0 点是所选区间起点。
    return MvPreviewTimeline(
        startSec = mv.trialStartSeconds - scopeStart,
        endSec = mv.trialEndSeconds - scopeStart,
        totalSec = scopeTotal,
    )
}

/** 积分够就直接扣。不够时，已订阅去买积分，未订阅去订阅页。 */
enum class UnlockPayDecision { subscribe, buyCredits, deductPoints }

fun decideUnlockPay(isMember: Boolean, points: Int, needPoints: Int): UnlockPayDecision {
    if (points >= needPoints) return UnlockPayDecision.deductPoints
    if (isMember) return UnlockPayDecision.buyCredits
    return UnlockPayDecision.subscribe
}

/** 试看已合成且仍锁定时，资源库只留解锁和删除。 */
fun showsPreviewUnlockLibraryActions(unlock: Int?, status: MvStatus): Boolean =
    unlock == 0 && status == MvStatus.success

fun shouldMarkGeneratingAfterUnlock(entry: MvPreviewUnlockEntry): Boolean =
    entry == MvPreviewUnlockEntry.player || entry == MvPreviewUnlockEntry.library

fun shouldLeavePlayerAfterUnlock(entry: MvPreviewUnlockEntry): Boolean = entry == MvPreviewUnlockEntry.player
