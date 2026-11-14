package com.sondo.app.core.storage

import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 轻量键值存储（对应 Flutter 侧 GetStorage / SpUtil）。
 * 底层走 Kuikly SharedPreferencesModule；所有页面共享同一份内存缓存（同在 Kuikly 线程，无并发问题）。
 * 值统一按字符串落盘，类型在读取时转换。
 */
object Sp {
    private val cache = HashMap<String, String?>()
    private const val PREFIX = "sondo_"

    private fun module(): SharedPreferencesModule =
        PagerManager.getCurrentPager().acquireModule(SharedPreferencesModule.MODULE_NAME)

    private fun raw(key: String): String? {
        if (cache.containsKey(key)) return cache[key]
        val v = module().getItem(PREFIX + key)
        val value = v.ifEmpty { null }
        cache[key] = value
        return value
    }

    private fun write(key: String, value: String?) {
        cache[key] = value
        module().setItem(PREFIX + key, value ?: "")
    }

    fun has(key: String): Boolean = raw(key) != null

    fun remove(key: String) = write(key, null)

    fun getString(key: String, def: String = ""): String = raw(key) ?: def
    fun setString(key: String, value: String?) = write(key, value)

    fun getInt(key: String, def: Int = 0): Int = raw(key)?.toIntOrNull() ?: def
    fun setInt(key: String, value: Int) = write(key, value.toString())

    fun getLong(key: String, def: Long = 0L): Long = raw(key)?.toLongOrNull() ?: def
    fun setLong(key: String, value: Long) = write(key, value.toString())

    fun getDouble(key: String, def: Double = 0.0): Double = raw(key)?.toDoubleOrNull() ?: def
    fun setDouble(key: String, value: Double) = write(key, value.toString())

    fun getBool(key: String, def: Boolean = false): Boolean = when (raw(key)) {
        "1", "true" -> true
        "0", "false" -> false
        else -> def
    }
    fun setBool(key: String, value: Boolean) = write(key, if (value) "1" else "0")

    fun getJson(key: String): JSONObject? {
        val s = raw(key) ?: return null
        return try { JSONObject(s) } catch (e: Throwable) { null }
    }
    fun setJson(key: String, value: JSONObject?) = write(key, value?.toString())

    fun getJsonArray(key: String): JSONArray? {
        val s = raw(key) ?: return null
        return try { JSONArray(s) } catch (e: Throwable) { null }
    }
    fun setJsonArray(key: String, value: JSONArray?) = write(key, value?.toString())

    fun getStringList(key: String): List<String> {
        val arr = getJsonArray(key) ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.optString(i) ?: "")
        return out
    }
    fun setStringList(key: String, value: List<String>) {
        val arr = JSONArray()
        value.forEach { arr.put(it) }
        setJsonArray(key, arr)
    }
}
