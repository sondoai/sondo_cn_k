package com.sondo.music.video.ai.kuikly.adapter

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import com.tencent.kuikly.core.render.android.KuiklyRenderViewContext
import com.tencent.kuikly.core.render.android.adapter.HRImageLoadOption
import com.tencent.kuikly.core.render.android.adapter.IKRImageAdapter
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Kuikly 图片加载适配器（Glide）。
 *
 * 支持的 src：
 * - `http(s)://…`            网络图，Glide 磁盘缓存（原始数据）+ 内存缓存
 * - `assets://common/…`      shared 模块资源。`ImageUri.commonAssets("images/x.png")` 在 Android 上原样到达为
 *                            `assets://common/images/x.png`（JVM 执行模式不做路径改写），对应 APK 里的
 *                            `assets/common/images/x.png` → 按 `file:///android_asset/common/images/x.png` 加载；
 *                            `assets://<其它>/…` 同理映射到 `assets/<其它>/…`
 * - `file://…` / 绝对路径      本地文件
 * - `content://…`            ContentProvider（相册选择结果等）
 * - `data:image/…;base64,…`  内联图片
 * - 不带 scheme 的相对路径      当作 common 资源（`images/x.png` ≡ `assets://common/images/x.png`）
 *
 * GIF 与动图 WebP（API 28+）解码为 Animatable，由 Kuikly 的 Image 视图负责启动 / 停止播放。
 * 不支持 SVG（Glide 不带 SVG 解码器）。
 *
 * 尺寸策略：Kuikly 给了目标尺寸（needResize）时按视图大小降采样，但**不放大**——
 * 参考工程的资源只有 1x，全屏背景若按屏幕像素放大解码会白占内存；缩放 / 裁剪交给 ImageView 的 scaleType 在绘制时完成。
 * .9 图 / capInsets / 需要回报原始分辨率的图（needResize=false）按原尺寸解码。
 */
class KRImageAdapter(context: Context) : IKRImageAdapter {

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Deprecated("由带 imageParams 的重载统一处理")
    override fun fetchDrawable(imageLoadOption: HRImageLoadOption, callback: (drawable: Drawable?) -> Unit) {
        fetchDrawable(imageLoadOption, null, callback)
    }

    override fun fetchDrawable(
        imageLoadOption: HRImageLoadOption,
        imageParams: JSONObject?,
        callback: (drawable: Drawable?) -> Unit,
    ) {
        val model = resolveModel(imageLoadOption.src)
        if (model == null) {
            callback(null)
            return
        }
        // Glide 的 into(Target) 必须在主线程；本方法可能从子线程进来（如 MemoryCacheModule.cacheImage）
        if (Looper.myLooper() == Looper.getMainLooper()) {
            load(model, imageLoadOption, callback)
        } else {
            mainHandler.post { load(model, imageLoadOption, callback) }
        }
    }

    /** Glide 解码出的位图按屏幕密度创建，intrinsic 尺寸就是像素值，不需要再乘 density。 */
    override fun getDrawableWidth(kuiklyRenderViewContext: KuiklyRenderViewContext, drawable: Drawable): Float =
        drawable.intrinsicWidth.toFloat()

    override fun getDrawableHeight(kuiklyRenderViewContext: KuiklyRenderViewContext, drawable: Drawable): Float =
        drawable.intrinsicHeight.toFloat()

    /** src → Glide 能识别的 model（String / Uri / File）；无法识别返回 null。 */
    private fun resolveModel(rawSrc: String): Any? {
        val src = rawSrc.trim()
        if (src.isEmpty()) return null
        return when {
            src.startsWith(HRImageLoadOption.SCHEME_HTTP, ignoreCase = true) ||
                src.startsWith(HRImageLoadOption.SCHEME_HTTPS, ignoreCase = true) -> src
            src.startsWith(HRImageLoadOption.SCHEME_ASSETS) ->
                assetUri(src.substring(HRImageLoadOption.SCHEME_ASSETS.length))
            src.startsWith(HRImageLoadOption.SCHEME_BASE64) -> src
            src.startsWith(HRImageLoadOption.SCHEME_FILE) -> {
                if (src.startsWith(ANDROID_ASSET_PREFIX)) {
                    Uri.parse(src)
                } else {
                    // 调用方多半是直接拼的 "file://" + 路径（没有做百分号编码），按原样取路径
                    val path = src.substring(HRImageLoadOption.SCHEME_FILE.length)
                    val file = File(path)
                    if (file.exists()) file else Uri.parse(src).path?.let { File(it) }
                }
            }
            src.startsWith("/") -> File(src)
            src.startsWith("content://") || src.startsWith("android.resource://") -> Uri.parse(src)
            src.contains("://") -> null
            else -> assetUri("common/$src")
        }
    }

    /** 不做百分号编码：Glide 的 AssetUriLoader 直接截取 URI 字符串当作 assets 路径。 */
    private fun assetUri(assetPath: String): Uri = Uri.parse(ANDROID_ASSET_PREFIX + assetPath.trimStart('/'))

    private fun load(model: Any, option: HRImageLoadOption, callback: (drawable: Drawable?) -> Unit) {
        try {
            var request = Glide.with(appContext)
                .asDrawable()
                .load(model)
                // Kuikly 的模糊 / .9 图处理会读像素或画到软件画布上，硬件位图不支持
                .disallowHardwareConfig()
                // Glide 自带的 HttpURLConnection 默认 2.5s 超时，弱网下大图几乎必失败
                .timeout(NETWORK_TIMEOUT_MS)
                .dontTransform()

            val width = option.requestWidth
            val height = option.requestHeight
            request = if (option.needResize && width > 0 && height > 0) {
                request.override(width, height).downsample(
                    if (option.scaleType == ImageView.ScaleType.FIT_CENTER ||
                        option.scaleType == ImageView.ScaleType.CENTER_INSIDE
                    ) {
                        DownsampleStrategy.CENTER_INSIDE
                    } else {
                        CoverWithoutUpscale
                    }
                )
            } else {
                request.override(Target.SIZE_ORIGINAL)
            }

            request
                .into(object : CustomTarget<Drawable>() {
                    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                        callback(resource)
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        callback(null)
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        // 应用级 RequestManager 不会主动 clear；这里不回调，避免把「清理」误报成加载失败
                    }
                })
        } catch (e: Throwable) {
            callback(null)
        }
    }

    /** 降采样到「刚好能铺满目标区域」为止，源图比目标小时保持原尺寸（等价于不放大的 CENTER_OUTSIDE）。 */
    private object CoverWithoutUpscale : DownsampleStrategy() {
        override fun getScaleFactor(sourceWidth: Int, sourceHeight: Int, requestedWidth: Int, requestedHeight: Int): Float {
            val widthRatio = requestedWidth / sourceWidth.toFloat()
            val heightRatio = requestedHeight / sourceHeight.toFloat()
            return min(1f, max(widthRatio, heightRatio))
        }

        override fun getSampleSizeRounding(
            sourceWidth: Int,
            sourceHeight: Int,
            requestedWidth: Int,
            requestedHeight: Int,
        ): SampleSizeRounding = SampleSizeRounding.QUALITY
    }

    companion object {
        private const val ANDROID_ASSET_PREFIX = "file:///android_asset/"
        private const val NETWORK_TIMEOUT_MS = 15_000
    }
}
