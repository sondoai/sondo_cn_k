package com.sondo.app.service

import com.sondo.app.core.utils.nowMs

/** 内存缓存 key 约定：表单 `form:<pageKey>`，接口 `api:<name>`。 */
object MemoryCacheKeys {
    const val formPrefix = "form:"
    const val apiPrefix = "api:"

    /** MV 创作页表单草稿。 */
    const val musicVideoGenForm = "music_video_gen"

    /** 音乐创作页表单草稿。 */
    const val musicGenForm = "music_gen"

    /** MV 风格模板选择页 UI 会话态（静音、浏览位置）。 */
    const val mvStyleTemplateUi = "mv_style_template_ui"

    /** 选曲弹窗 My Tab：`music_select:my:<type>::<keyword>` */
    const val musicSelectMy = "music_select:my:"

    /** 选曲弹窗 Trending Tab：`music_select:trending:<type>` */
    const val musicSelectTrending = "music_select:trending:"

    /** 选曲弹窗 Upload Tab：`music_select:upload:<type>` */
    const val musicSelectUpload = "music_select:upload:"

    fun musicSelectMyKey(type: String, keyword: String = ""): String = "$musicSelectMy$type::$keyword"

    /** 按业务类型失效 My Tab（含该 type 下全部 keyword）。 */
    fun musicSelectMyTypePrefix(type: String): String = "$musicSelectMy$type::"

    fun musicSelectTrendingKey(type: String): String = "$musicSelectTrending$type"

    fun musicSelectUploadKey(type: String): String = "$musicSelectUpload$type"
}

/** 内存缓存条目；[expireAt] 为 null 表示会话级不过期。 */
class MemoryCacheEntry(val data: Any?, val expireAt: Long?, val updatedAt: Long) {
    val isExpired: Boolean get() = expireAt != null && expireAt <= nowMs()
}

/**
 * 全局纯内存 KV 缓存：TTL + LRU 上限，杀进程即丢。
 * 仅作哑存储；页面草稿由页面在销毁 / 创建时显式读写。登出时整体清空。
 */
object MemoryCacheService {
    /** 全局条目上限，超出按 LRU 淘汰。 */
    const val maxEntries = 64

    /** [putApi] 未传 ttl 时的默认过期时间（5 分钟）。 */
    const val defaultApiTtlMs = 5 * 60 * 1000L

    // LinkedHashMap 保持插入顺序：队首是最久未用的条目。
    @PublishedApi
    internal val store = LinkedHashMap<String, MemoryCacheEntry>()

    /** 写入任意 key；[ttlMs] 为 null 表示会话级不过期。 */
    fun put(key: String, data: Any?, ttlMs: Long? = null) {
        val now = nowMs()
        store.remove(key)
        store[key] = MemoryCacheEntry(data, if (ttlMs == null) null else now + ttlMs, now)
        evictIfNeeded()
    }

    /** 读取；过期或类型不符时删除并返回 null。 */
    inline fun <reified T> get(key: String): T? {
        val entry = store[key] ?: return null
        if (entry.isExpired) {
            store.remove(key)
            return null
        }
        val data = entry.data
        if (data !is T) {
            store.remove(key)
            return null
        }
        // 读命中刷新 LRU 顺序
        store.remove(key)
        store[key] = entry
        return data
    }

    /** 是否存在且未过期。 */
    fun contains(key: String): Boolean {
        val entry = store[key] ?: return false
        if (entry.isExpired) {
            store.remove(key)
            return false
        }
        return true
    }

    fun remove(key: String) {
        store.remove(key)
    }

    fun clearByPrefix(prefix: String) {
        val keys = store.keys.filter { it.startsWith(prefix) }
        keys.forEach { store.remove(it) }
    }

    fun clearAll() = store.clear()

    /** 表单草稿；默认会话级不过期。 */
    fun putForm(pageKey: String, data: Any?, ttlMs: Long? = null) = put("${MemoryCacheKeys.formPrefix}$pageKey", data, ttlMs)

    inline fun <reified T> getForm(pageKey: String): T? = get<T>("${MemoryCacheKeys.formPrefix}$pageKey")

    fun removeForm(pageKey: String) = remove("${MemoryCacheKeys.formPrefix}$pageKey")

    /** 接口结果；默认 [defaultApiTtlMs]。 */
    fun putApi(apiKey: String, data: Any?, ttlMs: Long? = null) =
        put("${MemoryCacheKeys.apiPrefix}$apiKey", data, ttlMs ?: defaultApiTtlMs)

    inline fun <reified T> getApi(apiKey: String): T? = get<T>("${MemoryCacheKeys.apiPrefix}$apiKey")

    fun removeApi(apiKey: String) = remove("${MemoryCacheKeys.apiPrefix}$apiKey")

    /** 当前条目数（含未读过的过期项）。 */
    val debugLength: Int get() = store.size

    private fun evictIfNeeded() {
        while (store.size > maxEntries) {
            store.remove(store.keys.first())
        }
    }
}
