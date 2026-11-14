package com.sondo.music.video.ai.kuikly

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sondo.music.video.ai.BuildConfig
import com.tencent.kuikly.core.IKuiklyCoreEntry
import com.tencent.kuikly.core.render.android.context.IKuiklyRenderContextHandler
import com.tencent.kuikly.core.render.android.context.KuiklyRenderCommonContextHandler
import com.tencent.kuikly.core.render.android.context.KuiklyRenderCoreExecuteModeBase
import com.tencent.kuikly.core.render.android.context.KuiklyRenderCoreJVMMode
import com.tencent.kuikly.core.render.android.context.KuiklyRenderNativeMethod
import com.tencent.kuikly.core.render.android.exception.ErrorReason
import dalvik.system.DexClassLoader
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 仅开发包启用：成功编译后的 shared DEX 使用独立 ClassLoader，原生宿主与 Kuikly core 保持同一份。
 * 切换时重建全部 Kuikly 页面，页面及业务单例状态重置；应用进程和原生页面栈保持存活。
 */
internal object KuiklyHotReload {
    private const val TAG = "SondoHotReload"
    private const val ENTRY = "com.tencent.kuikly.core.android.KuiklyCoreEntry"
    private const val MAX_DEX_BYTES = 64L * 1024 * 1024
    private val baseUrl = BuildConfig.SONDO_HOT_RELOAD_URL.trim().trimEnd('/')
    private val main = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val applying = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "sondo-hot-reload").apply { isDaemon = true }
    }
    @Volatile private var activeLoader: ClassLoader? = null
    @Volatile private var activeVersion: String? = null
    private var lastError: String? = null

    fun executeMode(): KuiklyRenderCoreExecuteModeBase = activeLoader?.let { ReloadMode(it) }
        ?: KuiklyRenderCoreExecuteModeBase.JVM

    fun register(activity: KuiklyRenderActivity) {
        if (baseUrl.isEmpty() || !started.compareAndSet(false, true)) return
        val app = activity.applicationContext
        Log.i(TAG, "watching $baseUrl; page state resets after each successful shared build")
        executor.scheduleWithFixedDelay({ poll(app) }, 0, 1, TimeUnit.SECONDS)
    }

    private fun poll(context: Context) {
        if (ActivityStack.live().isEmpty() || applying.get()) return
        try {
            val info = JSONObject(getText("$baseUrl/version"))
            if (!info.optBoolean("ready", false)) return
            val version = if (info.has("androidVersion")) info.optString("androidVersion")
                else info.optString("version")
            if (version == activeVersion) return
            require(version.matches(Regex("[0-9a-f]{64}"))) { "invalid Android DEX version" }
            val dex = downloadDex(context, version)
            val parent = requireNotNull(KuiklyHotReload::class.java.classLoader)
            val loader = SharedDexClassLoader(dex, context.codeCacheDir, parent)
            // 验证共享接口和入口类的加载，失败时完全不动当前页面。
            loader.loadClass(ENTRY).asSubclass(IKuiklyCoreEntry::class.java)
                .getDeclaredConstructor().newInstance()
            if (!applying.compareAndSet(false, true)) return
            main.post {
                try {
                    applyVersion(loader, version)
                    lastError = null
                } catch (e: Throwable) {
                    Log.e(TAG, "could not switch to DEX $version", e)
                } finally {
                    applying.set(false)
                }
            }
        } catch (e: Throwable) {
            // 开发服务关闭、编译失败或下载未完成时保持旧代码与旧页面。
            val error = "${e.javaClass.simpleName}: ${e.message}"
            if (error != lastError) Log.w(TAG, "keeping current page: $error")
            lastError = error
        }
    }

    private fun applyVersion(loader: ClassLoader, version: String) {
        val pages = ActivityStack.live()
        val previousLoader = activeLoader
        val previousVersion = activeVersion
        // 子页面可能在销毁时访问根页面的 AppHost；旧页面按栈顶到根顺序销毁。
        pages.asReversed().forEach { it.detachForHotReload() }
        activeLoader = loader
        activeVersion = version
        try {
            pages.forEach { it.attachForHotReload() }
            Log.i(TAG, "applied $version in process ${android.os.Process.myPid()}, routes=${pages.map { it.pageName }}")
        } catch (e: Throwable) {
            pages.asReversed().forEach { it.detachForHotReload() }
            activeLoader = previousLoader
            activeVersion = previousVersion
            pages.forEach { it.attachForHotReload() }
            throw e
        }
    }

    private fun getText(address: String): String = request(address) { connection ->
        connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun downloadDex(context: Context, version: String): File {
        val dir = File(context.codeCacheDir, "kuikly-hot-reload").apply { mkdirs() }
        val target = File(dir, "$version.dex")
        if (target.isFile && sha256(target) == version) return target
        target.delete()
        val temp = File.createTempFile("download-", ".dex", dir)
        try {
            request("$baseUrl/android/classes.dex?version=$version") { connection ->
                connection.inputStream.use { input ->
                    FileOutputStream(temp).use { output ->
                        // Android 14+ 要求动态代码文件在写入内容之前设为只读。
                        check(temp.setReadOnly()) { "cannot mark downloaded DEX read-only" }
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= MAX_DEX_BYTES) { "DEX exceeds development size limit" }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
            }
            check(sha256(temp) == version) { "DEX checksum/version mismatch" }
            check(temp.renameTo(target)) { "cannot publish downloaded DEX" }
            return target
        } finally {
            temp.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun <T> request(address: String, block: (HttpURLConnection) -> T): T {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 2500
        connection.readTimeout = 10000
        connection.useCaches = false
        try {
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}: $address" }
            return block(connection)
        } finally {
            connection.disconnect()
        }
    }

    private class SharedDexClassLoader(dex: File, optimizedDir: File, parent: ClassLoader) :
        DexClassLoader(dex.absolutePath, optimizedDir.absolutePath, null, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            // 插桩运行时与这次业务 DEX 一起切换，避免复用宿主 APK 中较旧的 DevTools 实现。
            // com.tencent.kuikly.core.* 的桥接接口继续由宿主加载，保持跨加载器的类型一致。
            val reloadClass = name.startsWith("com.sondo.app.") || name == ENTRY || name.startsWith("$ENTRY$") ||
                name.startsWith("com.ailuoku6.kuikly.devtools.")
            val type = findLoadedClass(name) ?: if (reloadClass) findClass(name) else super.loadClass(name, false)
            if (resolve) resolveClass(type)
            type
        }
    }

    private class ReloadMode(private val loader: ClassLoader) : KuiklyRenderCoreJVMMode(0) {
        override fun getContextHandler(): IKuiklyRenderContextHandler = ReloadContextHandler(loader)
    }

    private class ReloadContextHandler(loader: ClassLoader) :
        KuiklyRenderCommonContextHandler(), IKuiklyCoreEntry.Delegate {
        private val entry = loader.loadClass(ENTRY).getDeclaredConstructor().newInstance() as IKuiklyCoreEntry

        init {
            entry.delegate = this
        }

        override fun init(contextCode: String) = Unit

        override fun callKotlinMethod(
            methodId: Int, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?, arg4: Any?, arg5: Any?
        ) {
            try {
                entry.callKotlinMethod(methodId, arg0, arg1, arg2, arg3, arg4, arg5)
            } catch (e: Throwable) {
                notifyException(e, ErrorReason.CALL_KOTLIN)
            }
        }

        override fun callNative(
            methodId: Int, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?, arg4: Any?, arg5: Any?
        ): Any? = try {
            callNativeCallback?.invoke(KuiklyRenderNativeMethod.fromInt(methodId),
                listOf(arg0, arg1, arg2, arg3, arg4, arg5))?.toKotlinObject()
        } catch (e: Throwable) {
            notifyException(e, ErrorReason.CALL_NATIVE)
            null
        }

        override fun destroy() {
            entry.delegate = null
            super.destroy()
        }
    }
}
