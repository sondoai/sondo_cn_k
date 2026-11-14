package com.sondo.music.video.ai.kuikly.adapter

import android.graphics.Typeface
import android.util.Log
import com.sondo.music.video.ai.SondoApplication
import com.tencent.kuikly.core.render.android.adapter.IKRFontAdapter
import java.util.concurrent.ConcurrentHashMap

/*
 * ============================================================================================
 * Kuikly fontFamily → 字体文件（androidApp/src/main/assets/fonts/，与参考工程 pubspec.yaml 的 fonts 段一致）
 * ============================================================================================
 *
 * 可用的 fontFamily 名（不区分大小写；空格、`-`、`_` 可有可无，"Poppins-SemiBold" ≡ "poppins semibold" ≡ "PoppinsSemiBold"）：
 *
 *   Poppins                     poppins_regular.ttf      (400)
 *   Poppins-Thin                poppins_thin.ttf         (100)
 *   Poppins-Light               poppins_light.ttf        (300)
 *   Poppins-Regular             poppins_regular.ttf      (400)
 *   Poppins-Medium              poppins_medium.ttf       (500)
 *   Poppins-SemiBold            poppins_semibold.ttf     (600)
 *   Poppins-Bold                poppins_bold.ttf         (700)
 *
 *   Montserrat                  Montserrat-Medium.otf    (500，参考工程里该字族最轻的一档；Flutter 的 400 也落到它)
 *   Montserrat-Medium           Montserrat-Medium.otf    (500)
 *   Montserrat-SemiBold         Montserrat-SemiBold.otf  (600)
 *   Montserrat-Bold             Montserrat-Bold.otf      (700)
 *
 *   Anton                       anton_regular.ttf
 *   Bangers                     Bangers.ttf
 *   LemonMilk                   LemonMilk-1.otf
 *   MomcakeBold                 MOMCAKE-Bold.ttf
 *   MomcakeThin                 MOMCAKE-Thin.ttf
 *   MonotonRegular              Monoton-Regular.ttf
 *   MontserratBlack             Montserrat-Black.ttf
 *   MontserratBlackItalic       MontserratAlternates-BlackItalic.ttf
 *   Roboto                      roboto_medium_numbers.ttf   (只含数字的子集)
 *   Carter                      Carter-One.ttf
 *   PaytoneOne                  PaytoneOne.ttf              (文件在，参考工程未声明)
 *
 * 字重也可以写成数字后缀：`Poppins-600` / `Poppins-w600` ≡ `Poppins-SemiBold`，`Montserrat-700` ≡ `Montserrat-Bold`。
 * 没有对应文件的档位取最接近的一档（Poppins-800 → Bold，Poppins-200 → Thin，Montserrat-400 → Medium），规则同 Flutter。
 *
 * 用法注意（shared 侧）：
 * - Kuikly 的 fontWeight 在 Android 上是给文字加描边实现的「伪粗体」，与字体文件无关，适配器也拿不到 fontWeight。
 *   所以要还原设计稿的字重，请用带字重的字族名（`fontFamily("Poppins-SemiBold")`），并且**不要再同时设置 fontWeight**，
 *   否则会在真粗体上再叠一层描边。只写 `fontFamily("Poppins")` + `fontWeightBold()` 得到的是 Regular 加描边。
 * - 这些字体只含拉丁字符，中文自动回落到系统字体。
 * - 不认识的名字返回 null，Kuikly 会按系统字族名再找一次（如 "sans-serif-medium"），找不到就是系统默认字体。
 * ============================================================================================
 */
object KRFontAdapter : IKRFontAdapter {

    private const val TAG = "KRFontAdapter"
    private const val FONT_DIR = "fonts/"

    /** 归一化后的字族名 → (字重 → 文件名)。单一字重的字族只有一项。 */
    private val families: Map<String, Map<Int, String>> = mapOf(
        "poppins" to mapOf(
            100 to "poppins_thin.ttf",
            300 to "poppins_light.ttf",
            400 to "poppins_regular.ttf",
            500 to "poppins_medium.ttf",
            600 to "poppins_semibold.ttf",
            700 to "poppins_bold.ttf",
        ),
        "montserrat" to mapOf(
            500 to "Montserrat-Medium.otf",
            600 to "Montserrat-SemiBold.otf",
            700 to "Montserrat-Bold.otf",
        ),
        "anton" to mapOf(400 to "anton_regular.ttf"),
        "bangers" to mapOf(400 to "Bangers.ttf"),
        "lemonmilk" to mapOf(400 to "LemonMilk-1.otf"),
        "momcakebold" to mapOf(400 to "MOMCAKE-Bold.ttf"),
        "momcakethin" to mapOf(400 to "MOMCAKE-Thin.ttf"),
        "monotonregular" to mapOf(400 to "Monoton-Regular.ttf"),
        "montserratblack" to mapOf(400 to "Montserrat-Black.ttf"),
        "montserratblackitalic" to mapOf(400 to "MontserratAlternates-BlackItalic.ttf"),
        "roboto" to mapOf(400 to "roboto_medium_numbers.ttf"),
        "carter" to mapOf(400 to "Carter-One.ttf"),
        "paytoneone" to mapOf(400 to "PaytoneOne.ttf"),
    )

    /** 字重名后缀（归一化后）→ 数值。长的排前面，避免 "semibold" 被 "bold" 抢先匹配。 */
    private val weightSuffixes: List<Pair<String, Int>> = listOf(
        "extralight" to 200,
        "ultralight" to 200,
        "extrabold" to 800,
        "ultrabold" to 800,
        "semibold" to 600,
        "demibold" to 600,
        "regular" to 400,
        "normal" to 400,
        "medium" to 500,
        "light" to 300,
        "black" to 900,
        "heavy" to 900,
        "thin" to 100,
        "bold" to 700,
    )

    /** 解析结果缓存（含「没有这个字体」）。Typeface 创建一次后全局复用。 */
    private val cache = ConcurrentHashMap<String, TypefaceHolder>()

    private class TypefaceHolder(val typeface: Typeface?)

    override fun getTypeface(fontFamily: String, result: (Typeface?) -> Unit) {
        // 必须同步回调：Kuikly 的 TypeFaceLoader 在调用返回后立刻读取结果
        if (fontFamily.isEmpty()) {
            result(null)
            return
        }
        val holder = cache[fontFamily] ?: resolve(fontFamily).also { cache[fontFamily] = it }
        result(holder.typeface)
    }

    private fun resolve(fontFamily: String): TypefaceHolder {
        val file = fileFor(normalize(fontFamily)) ?: return TypefaceHolder(null)
        return try {
            TypefaceHolder(Typeface.createFromAsset(SondoApplication.application.assets, FONT_DIR + file))
        } catch (e: Throwable) {
            Log.e(TAG, "load font failed: $fontFamily -> $file", e)
            TypefaceHolder(null)
        }
    }

    private fun normalize(name: String): String =
        name.lowercase().filter { it.isLetterOrDigit() }

    private fun fileFor(key: String): String? {
        // 1) 字族名本身（"MontserratBlack"、"MomcakeBold" 这类名字里带字重词的单字重字族要先于后缀解析命中）
        families[key]?.let { return pick(it, 400) }
        // 2) 字族名 + 字重后缀
        for ((family, weights) in families) {
            if (weights.size < 2 || !key.startsWith(family)) continue
            val weight = parseWeight(key.substring(family.length)) ?: continue
            return pick(weights, weight)
        }
        return null
    }

    private fun parseWeight(suffix: String): Int? {
        if (suffix.isEmpty()) return null
        weightSuffixes.firstOrNull { it.first == suffix }?.let { return it.second }
        // "600" / "w600"
        return suffix.removePrefix("w").toIntOrNull()?.takeIf { it in 1..1000 }
    }

    /**
     * 取最接近的字重（规则同 CSS / Flutter 的字重匹配）：
     * 目标 ≤ 500 时先向轻的方向找（400 与 500 互为首选替补），目标 > 500 时先向重的方向找。
     */
    private fun pick(weights: Map<Int, String>, target: Int): String {
        weights[target]?.let { return it }
        val available = weights.keys.sorted()
        if (target == 400) weights[500]?.let { return it }
        if (target == 500) weights[400]?.let { return it }
        val lighter = available.lastOrNull { it < target }
        val heavier = available.firstOrNull { it > target }
        val chosen = if (target <= 500) lighter ?: heavier else heavier ?: lighter
        return weights.getValue(chosen ?: available.first())
    }
}
