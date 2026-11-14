package com.sondo.music.video.ai.kuikly.module

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sondo.music.video.ai.kuikly.PermissionDispatcher
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONObject

/**
 * 运行时权限（契约：shared/nativeapi/SDAppModule.kt 里的 SDPermissionModule）。
 * 用途说明弹窗由 Kuikly 侧负责，这里只做查询与系统申请。
 *
 * 权限名 → Android 权限：
 * - notification   API 33+：POST_NOTIFICATIONS；更低版本没有运行时权限，看系统通知开关
 * - camera         CAMERA
 * - microphone     RECORD_AUDIO
 * - photos         API 33+：READ_MEDIA_IMAGES / READ_MEDIA_VIDEO（API 34+ 仅授予「所选照片」时为 limited）；更低：READ_EXTERNAL_STORAGE
 * - photosAddOnly  API 29+ 写相册不需要权限 → granted；API ≤ 28：WRITE_EXTERNAL_STORAGE
 * - storage        API 33+ 恒 granted；29–32：READ_EXTERNAL_STORAGE；≤ 28：READ + WRITE_EXTERNAL_STORAGE
 * - tracking       恒 granted（iOS ATT 专用）
 *
 * 只处理清单里**实际声明**了的权限。某项能力对应的权限一个都没声明时（本工程沿用参考工程的做法：
 * 相机走系统相机 Intent、相册走系统选择器，不声明 CAMERA / READ_MEDIA_*），说明这项能力不需要运行时授权：
 * - status → denied：与参考工程 permission_handler 的表现一致，Kuikly 侧据此先弹用途说明（合规要求）；
 * - request → granted：没有系统弹窗可弹，调用方可以直接继续（打开系统相机 / 选择器）。
 *
 * denied 与 permanentlyDenied 的区分：系统不提供直接查询，按惯例用 shouldShowRequestPermissionRationale
 * 加一个本地标记（SharedPreferences）推断，标记只在「确实不会再弹窗」时写入，授权后清除。
 */
class SDPermissionModule : SDCoreBaseModule() {

    override fun handle(method: String, p: JSONObject, callback: KuiklyRenderCallback?): Any? {
        when (method) {
            "status" -> reply(callback, statusResult(p.optString("permission")))
            "request" -> request(p.optString("permission"), callback)
            else -> return unknown(method, callback)
        }
        return null
    }

    // ------------------------------------------------------------------ 权限映射

    private sealed class Spec {
        /** 不需要任何授权。 */
        object Granted : Spec()

        /** 这项能力对应的权限都没有在清单里声明。 */
        object Undeclared : Spec()

        object Notification : Spec()
        class Runtime(val permissions: List<String>, val limitedBy: String? = null) : Spec()
        object Unknown : Spec()
    }

    private fun resolve(name: String): Spec {
        val sdk = Build.VERSION.SDK_INT
        val wanted: List<String> = when (name) {
            "tracking" -> return Spec.Granted
            "notification" -> return Spec.Notification
            "camera" -> listOf(Manifest.permission.CAMERA)
            "microphone" -> listOf(Manifest.permission.RECORD_AUDIO)
            "photos" -> if (sdk >= 33) {
                listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            "photosAddOnly" -> if (sdk >= 29) return Spec.Granted else listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            "storage" -> when {
                sdk >= 33 -> return Spec.Granted
                sdk >= 29 -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            else -> return Spec.Unknown
        }
        val declared = wanted.filter { isDeclared(it) }
        if (declared.isEmpty()) return Spec.Undeclared
        val limitedBy = if (name == "photos" && sdk >= 34 && isDeclared(READ_MEDIA_VISUAL_USER_SELECTED)) {
            READ_MEDIA_VISUAL_USER_SELECTED
        } else {
            null
        }
        return Spec.Runtime(declared, limitedBy)
    }

    // ------------------------------------------------------------------ status

    private fun statusResult(name: String): JSONObject {
        return when (val spec = resolve(name)) {
            Spec.Granted -> result(GRANTED)
            Spec.Undeclared -> result(DENIED)
            Spec.Notification -> result(notificationStatus())
            is Spec.Runtime -> result(runtimeStatus(name, spec))
            Spec.Unknown -> SDCoreKit.fail("unknown permission: $name").put("status", DENIED)
        }
    }

    private fun notificationStatus(): String {
        val enabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT >= 33 && isDeclared(Manifest.permission.POST_NOTIFICATIONS)) {
            if (enabled && isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
                setBlocked("notification", false)
                return GRANTED
            }
            return deniedKind("notification", listOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        // Android 12 及以下没有通知的运行时权限：开关关了只能去系统设置里打开
        return if (enabled) GRANTED else PERMANENTLY_DENIED
    }

    private fun runtimeStatus(name: String, spec: Spec.Runtime): String {
        if (spec.permissions.all { isGranted(it) }) {
            setBlocked(name, false)
            return GRANTED
        }
        if (spec.limitedBy != null && isGranted(spec.limitedBy)) return LIMITED
        return deniedKind(name, spec.permissions)
    }

    private fun deniedKind(name: String, permissions: List<String>): String {
        val act = activity
        if (act != null && showsRationale(act, permissions)) return DENIED
        return if (prefs().getBoolean(blockedKey(name), false)) PERMANENTLY_DENIED else DENIED
    }

    // ------------------------------------------------------------------ request

    private fun request(name: String, callback: KuiklyRenderCallback?) {
        val spec = resolve(name)
        val permissions: List<String> = when (spec) {
            Spec.Granted, Spec.Undeclared -> return reply(callback, result(GRANTED))
            Spec.Unknown -> return reply(callback, SDCoreKit.fail("unknown permission: $name").put("status", DENIED))
            Spec.Notification -> {
                if (Build.VERSION.SDK_INT < 33 || !isDeclared(Manifest.permission.POST_NOTIFICATIONS)) {
                    return reply(callback, result(notificationStatus()))
                }
                listOf(Manifest.permission.POST_NOTIFICATIONS)
            }
            is Spec.Runtime -> spec.permissions + listOfNotNull(spec.limitedBy)
        }
        val current = currentStatus(name, spec)
        if (current == GRANTED) return reply(callback, result(GRANTED))
        val act = activity
        if (act == null || act.isFinishing || act.isDestroyed) return reply(callback, result(current))

        val rationaleBefore = showsRationale(act, permissions)
        PermissionDispatcher.request(act, permissions.toTypedArray()) { res ->
            if (!res.interrupted && !res.allGranted) {
                val rationaleAfter = showsRationale(act, permissions)
                // 「以后不会再弹窗」的判定：拒绝后系统不再建议解释，并且
                //  - 申请前还建议解释（这次是第二次拒绝 / 勾了不再询问），或
                //  - 结果瞬间返回（系统没弹窗直接拒绝），或
                //  - Android 10 及以下（没有「点空白处关闭不计入拒绝」的行为）。
                // Android 11+ 首次弹窗被点空白关闭时三条都不满足，仍可再次申请，不能误判成永久拒绝。
                val blocked = !rationaleAfter &&
                    (rationaleBefore || res.elapsedMs < AUTO_DENY_THRESHOLD_MS || Build.VERSION.SDK_INT < 30)
                if (blocked) setBlocked(name, true)
            }
            reply(callback, result(currentStatus(name, spec)))
        }
    }

    private fun currentStatus(name: String, spec: Spec): String = when (spec) {
        Spec.Notification -> notificationStatus()
        is Spec.Runtime -> runtimeStatus(name, spec)
        else -> GRANTED
    }

    // ------------------------------------------------------------------ helpers

    private fun result(status: String): JSONObject = SDCoreKit.ok().put("status", status)

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun showsRationale(activity: Activity, permissions: List<String>): Boolean =
        permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }

    private fun prefs(): SharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun blockedKey(name: String) = "blocked_$name"

    private fun setBlocked(name: String, blocked: Boolean) {
        val prefs = prefs()
        if (prefs.getBoolean(blockedKey(name), false) == blocked) return
        prefs.edit().putBoolean(blockedKey(name), blocked).apply()
    }

    companion object {
        const val MODULE_NAME = "SDPermissionModule"

        private const val GRANTED = "granted"
        private const val LIMITED = "limited"
        private const val DENIED = "denied"
        private const val PERMANENTLY_DENIED = "permanentlyDenied"

        private const val PREFS = "sd_permission"
        private const val READ_MEDIA_VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

        /** 系统不弹窗直接给结果时，往返只有几十毫秒；真人操作弹窗不可能这么快。 */
        private const val AUTO_DENY_THRESHOLD_MS = 350L

        @Volatile
        private var declaredPermissions: Set<String>? = null

        /** 清单（含各 SDK 合并进来的部分）里声明的权限。 */
        private fun isDeclared(permission: String): Boolean {
            val cached = declaredPermissions ?: loadDeclared().also { declaredPermissions = it }
            return permission in cached
        }

        private fun loadDeclared(): Set<String> {
            return try {
                val ctx = SDCoreKit.app
                val info = if (Build.VERSION.SDK_INT >= 33) {
                    ctx.packageManager.getPackageInfo(
                        ctx.packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
                }
                info.requestedPermissions?.toSet() ?: emptySet()
            } catch (e: Throwable) {
                emptySet()
            }
        }
    }
}
