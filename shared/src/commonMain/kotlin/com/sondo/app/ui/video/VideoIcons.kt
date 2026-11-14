package com.sondo.app.ui.video

import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.Canvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 参考工程用的 Material 图标（Icons.play_arrow_rounded 等）没有图片资源，这里用 Canvas 画出同等形状。 */
enum class VideoIconKind { PLAY, PAUSE, REFRESH, ERROR_OUTLINE, VOLUME_UP, VOLUME_OFF, SPEED, FULLSCREEN }

/** 按 Material 24dp 网格绘制的视频图标；[size] 为外框边长。 */
fun ViewContainer<*, *>.VideoIcon(kind: VideoIconKind, size: Float, color: Color = Color.WHITE) {
    Canvas({
        attr {
            size(size, size)
            touchEnable(false)
        }
    }) { ctx, w, _ ->
        val u = w / 24f
        ctx.beginPath()
        when (kind) {
            VideoIconKind.PLAY -> {
                // play_arrow_rounded：圆角三角形
                ctx.fillStyle(color)
                ctx.moveTo(8f * u, 6.6f * u)
                ctx.lineTo(8f * u, 17.4f * u)
                ctx.quadraticCurveTo(8f * u, 19.2f * u, 9.6f * u, 18.2f * u)
                ctx.lineTo(18f * u, 12.9f * u)
                ctx.quadraticCurveTo(19.4f * u, 12f * u, 18f * u, 11.1f * u)
                ctx.lineTo(9.6f * u, 5.8f * u)
                ctx.quadraticCurveTo(8f * u, 4.8f * u, 8f * u, 6.6f * u)
                ctx.closePath()
                ctx.fill()
            }
            VideoIconKind.PAUSE -> {
                ctx.fillStyle(color)
                ctx.moveTo(6f * u, 5f * u); ctx.lineTo(10f * u, 5f * u); ctx.lineTo(10f * u, 19f * u); ctx.lineTo(6f * u, 19f * u)
                ctx.closePath()
                ctx.moveTo(14f * u, 5f * u); ctx.lineTo(18f * u, 5f * u); ctx.lineTo(18f * u, 19f * u); ctx.lineTo(14f * u, 19f * u)
                ctx.closePath()
                ctx.fill()
            }
            VideoIconKind.REFRESH -> {
                ctx.strokeStyle(color)
                ctx.lineWidth(2f * u)
                ctx.lineCapRound()
                val cx = 12f * u
                val cy = 12f * u
                val r = 7f * u
                ctx.arc(cx, cy, r, (-PI / 4).toFloat(), (PI * 1.6).toFloat(), false)
                ctx.stroke()
                // 箭头
                val ax = cx + r * cos(-PI / 4).toFloat()
                val ay = cy + r * sin(-PI / 4).toFloat()
                ctx.beginPath()
                ctx.fillStyle(color)
                ctx.moveTo(ax + 3.2f * u, ay - 4.2f * u)
                ctx.lineTo(ax + 3.2f * u, ay + 1.6f * u)
                ctx.lineTo(ax - 2.6f * u, ay + 1.6f * u)
                ctx.closePath()
                ctx.fill()
            }
            VideoIconKind.ERROR_OUTLINE -> {
                ctx.strokeStyle(color)
                ctx.lineWidth(2f * u)
                ctx.arc(12f * u, 12f * u, 9f * u, 0f, (PI * 2).toFloat(), false)
                ctx.stroke()
                ctx.beginPath()
                ctx.lineCapRound()
                ctx.moveTo(12f * u, 7.5f * u)
                ctx.lineTo(12f * u, 13f * u)
                ctx.stroke()
                ctx.beginPath()
                ctx.fillStyle(color)
                ctx.arc(12f * u, 16.5f * u, 1.2f * u, 0f, (PI * 2).toFloat(), false)
                ctx.fill()
            }
            VideoIconKind.VOLUME_UP, VideoIconKind.VOLUME_OFF -> {
                ctx.fillStyle(color)
                ctx.moveTo(3f * u, 9f * u); ctx.lineTo(7f * u, 9f * u); ctx.lineTo(12f * u, 4.5f * u)
                ctx.lineTo(12f * u, 19.5f * u); ctx.lineTo(7f * u, 15f * u); ctx.lineTo(3f * u, 15f * u)
                ctx.closePath()
                ctx.fill()
                ctx.beginPath()
                ctx.strokeStyle(color)
                ctx.lineWidth(1.8f * u)
                ctx.lineCapRound()
                if (kind == VideoIconKind.VOLUME_UP) {
                    ctx.arc(12f * u, 12f * u, 4f * u, (-PI / 3.5).toFloat(), (PI / 3.5).toFloat(), false)
                    ctx.stroke()
                    ctx.beginPath()
                    ctx.arc(12f * u, 12f * u, 7.5f * u, (-PI / 3.2).toFloat(), (PI / 3.2).toFloat(), false)
                    ctx.stroke()
                } else {
                    ctx.moveTo(15f * u, 9f * u); ctx.lineTo(21f * u, 15f * u)
                    ctx.moveTo(21f * u, 9f * u); ctx.lineTo(15f * u, 15f * u)
                    ctx.stroke()
                }
            }
            VideoIconKind.SPEED -> {
                ctx.strokeStyle(color)
                ctx.lineWidth(2f * u)
                ctx.lineCapRound()
                ctx.arc(12f * u, 14f * u, 8.5f * u, (PI * 0.85).toFloat(), (PI * 2.15).toFloat(), false)
                ctx.stroke()
                ctx.beginPath()
                ctx.moveTo(12f * u, 14f * u)
                ctx.lineTo(16.5f * u, 9f * u)
                ctx.stroke()
            }
            VideoIconKind.FULLSCREEN -> {
                ctx.strokeStyle(color)
                ctx.lineWidth(2f * u)
                ctx.lineCapRound()
                ctx.moveTo(5f * u, 10f * u); ctx.lineTo(5f * u, 5f * u); ctx.lineTo(10f * u, 5f * u)
                ctx.moveTo(14f * u, 5f * u); ctx.lineTo(19f * u, 5f * u); ctx.lineTo(19f * u, 10f * u)
                ctx.moveTo(19f * u, 14f * u); ctx.lineTo(19f * u, 19f * u); ctx.lineTo(14f * u, 19f * u)
                ctx.moveTo(10f * u, 19f * u); ctx.lineTo(5f * u, 19f * u); ctx.lineTo(5f * u, 14f * u)
                ctx.stroke()
            }
        }
    }
}
