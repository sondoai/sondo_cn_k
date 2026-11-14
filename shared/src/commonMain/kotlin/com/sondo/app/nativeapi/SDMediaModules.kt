package com.sondo.app.nativeapi

import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 全局音频播放器（进程内单例，跨页面持续播放；队列与播放模式由 Kuikly 侧 PlayerService 管理）。
 *
 * 事件（[addEventListener]，原生侧向所有已注册页面广播）：
 * - {event:"state", state:"idle"|"loading"|"buffering"|"ready"|"completed", playing: Boolean}
 * - {event:"position", positionMs, bufferedMs, durationMs}   播放中约每 200ms 一次
 * - {event:"duration", durationMs}
 * - {event:"error", msg}
 * - {event:"interruption", begin: Boolean, type:"duck"|"pause"|"unknown"}   原生不自动处理，交给 Kuikly 侧决定
 * - {event:"becomingNoisy"}                                   拔出耳机
 * 无后台播放 / 锁屏控制。
 */
class SDAudioPlayerModule : SDModule() {
    override fun moduleName(): String = MODULE_NAME

    /**
     * 设置音源并准备。
     * params: {uri(http/https/file), headers?:{}, clipStartMs?, clipEndMs?, loopOne: Boolean, autoPlay: Boolean, preload?: Boolean}
     * → {ok, durationMs?, error?}
     * - preload=true（默认）：准备到可播放（READY）才回调，带 durationMs。
     * - preload=false（直播 AAC / .aac，对应 just_audio 的 preload:false）：音源设置完立即回调 {ok:true}（无 durationMs），
     *   缓冲在播放阶段进行；之后的加载失败只经 error 事件上报。
     */
    suspend fun setSource(
        uri: String,
        autoPlay: Boolean = true,
        loopOne: Boolean = false,
        clipStartMs: Long? = null,
        clipEndMs: Long? = null,
        headers: JSONObject? = null,
        preload: Boolean = true,
    ): JSONObject {
        val p = JSONObject().put("uri", uri).put("autoPlay", autoPlay).put("loopOne", loopOne).put("preload", preload)
        if (clipStartMs != null) p.put("clipStartMs", clipStartMs)
        if (clipEndMs != null) p.put("clipEndMs", clipEndMs)
        if (headers != null) p.put("headers", headers)
        return await("setSource", p)
    }

    fun play() = invoke("play")
    fun pause() = invoke("pause")
    fun stop() = invoke("stop")
    fun seek(positionMs: Long) = invoke("seek", JSONObject().put("positionMs", positionMs))
    fun setVolume(volume: Float) = invoke("setVolume", JSONObject().put("volume", volume.toDouble()))
    fun setSpeed(speed: Float) = invoke("setSpeed", JSONObject().put("speed", speed.toDouble()))
    fun setLoopOne(on: Boolean) = invoke("setLoopOne", JSONObject().put("on", on))

    /** sync → {playing, positionMs, bufferedMs, durationMs, state} */
    fun getState(): JSONObject = invokeSyncJson("getState")

    /** 申请 / 释放音频焦点（AVAudioSession active）。→ 是否成功 */
    suspend fun setSessionActive(active: Boolean): Boolean =
        await("setSessionActive", JSONObject().put("active", active)).ok()

    fun release() = invoke("release")

    fun addEventListener(listener: (JSONObject) -> Unit): CallbackRef? = listen("addEventListener", null, listener)

    companion object {
        const val MODULE_NAME = "SDAudioPlayerModule"
    }
}

/**
 * 视频缓存（配合原生视图 SDVideoView 使用；播放本身走视图）。
 * 缓存策略：单文件 ≤ 20MB 才落盘（先发 `Range: bytes=0-0` 探测大小），最多 300 个对象、69 天。
 */
class SDVideoCacheModule : SDModule() {
    override fun moduleName(): String = MODULE_NAME

    /** 预取到磁盘缓存。→ {ok, localPath?} */
    suspend fun prefetch(url: String): JSONObject = await("prefetch", JSONObject().put("url", url))

    /** 已缓存则返回本地路径。→ {ok, localPath?} */
    suspend fun resolve(url: String): String? =
        await("resolve", JSONObject().put("url", url)).optString("localPath", "").ifEmpty { null }

    fun cancelPrefetch(url: String) = invoke("cancelPrefetch", JSONObject().put("url", url))

    suspend fun clear(): Boolean = await("clear").ok()

    companion object {
        const val MODULE_NAME = "SDVideoCacheModule"
    }
}

/**
 * 相册 / 相机 / 文件选择与保存。
 */
class SDMediaPickerModule : SDModule() {
    override fun moduleName(): String = MODULE_NAME

    /**
     * 选图。source: "camera"|"gallery"。统一转成 JPEG（quality 1-100），可限制最长边。
     * → {ok, path, width, height, size} | {ok:false, cancelled:true} | {ok:false, error:"photo_access_denied"|"camera_access_denied"|…}
     */
    suspend fun pickImage(source: String, quality: Int = 100, maxSide: Int = 0): JSONObject =
        await("pickImage", JSONObject().put("source", source).put("quality", quality).put("maxSide", maxSide))

    /** 多选图片。→ {ok, items:[{path,width,height,size}]} */
    suspend fun pickImages(maxCount: Int, quality: Int = 100): JSONObject =
        await("pickImages", JSONObject().put("maxCount", maxCount).put("quality", quality))

    /**
     * 选择音频文件（系统文件选择器），选中后拷贝到 App 临时目录。
     * → {ok, path, name, size, extension} | {ok:false, cancelled:true}
     */
    suspend fun pickAudioFile(extensions: List<String> = listOf("mp3", "m4a", "aac", "wav", "flac")): JSONObject =
        await("pickAudioFile", JSONObject().put("extensions", JSONArray().also { a -> extensions.forEach { a.put(it) } }))

    /** 选视频。→ {ok, path, durationMs, width, height, size} */
    suspend fun pickVideo(): JSONObject = await("pickVideo")

    /** 系统「存储为」对话框。mime: "audio/mpeg"|"video/mp4"… → {ok, savedPath?} */
    suspend fun saveFileAs(srcPath: String, fileName: String, mime: String): JSONObject =
        await("saveFileAs", JSONObject().put("srcPath", srcPath).put("fileName", fileName).put("mime", mime))

    /** 保存到系统相册。type: "image"|"video" → {ok, error?} */
    suspend fun saveToGallery(path: String, type: String, fileName: String? = null): JSONObject {
        val p = JSONObject().put("path", path).put("type", type)
        if (fileName != null) p.put("fileName", fileName)
        return await("saveToGallery", p)
    }

    /** → {ok, width, height}；图片损坏时 ok=false */
    suspend fun getImageSize(path: String): JSONObject = await("getImageSize", JSONObject().put("path", path))

    /** 压缩为 JPEG。→ {ok, path, width, height, size} */
    suspend fun compressImage(path: String, quality: Int, maxSide: Int = 0): JSONObject =
        await("compressImage", JSONObject().put("path", path).put("quality", quality).put("maxSide", maxSide))

    companion object {
        const val MODULE_NAME = "SDMediaPickerModule"
    }
}

/**
 * 媒体处理（Flutter 侧用 ffmpeg-kit / just_waveform，这里用各端系统媒体框架实现）。
 * 长任务带 taskId，可 [cancel]；进度经常驻回调上报 {event:"progress", taskId, progress(0..1)}，结束 {event:"done", ok, path?, error?}。
 */
class SDMediaProcessModule : SDModule() {
    override fun moduleName(): String = MODULE_NAME

    /** 探测媒体信息。→ {ok, durationMs, hasVideo, hasAudio, width?, height?, fps?, format?} */
    suspend fun probe(path: String): JSONObject = await("probe", JSONObject().put("path", path))

    /**
     * MP3 重封装并写入 Xing 头（修正 VBR 时长，对应 `ffmpeg -c copy -write_xing 1`）。
     * 失败时调用方保留原文件。→ {ok, path}
     */
    suspend fun remuxMp3WithXing(src: String, dest: String): JSONObject =
        await("remuxMp3WithXing", JSONObject().put("src", src).put("dest", dest))

    /**
     * 视频流拷贝 + 音频转 AAC 混流（-shortest）。
     * params: {videoPath, audioPath, audioStartMs?, audioDurationMs?, dest, taskId}
     */
    fun muxVideoWithAudio(params: JSONObject, onEvent: (JSONObject) -> Unit): CallbackRef? =
        listen("muxVideoWithAudio", params, onEvent)

    /**
     * 提取波形峰值。
     * params: {audioPath, samplesPerSecond(8–40), taskId}
     * done 事件: {event:"done", ok, durationMs, peaks:[0..1 的浮点数组，每项为该时间片的最大振幅]}
     */
    fun extractWaveform(params: JSONObject, onEvent: (JSONObject) -> Unit): CallbackRef? =
        listen("extractWaveform", params, onEvent)

    /** 裁剪音频片段。params: {src, dest, startMs, endMs, taskId} */
    fun trimAudio(params: JSONObject, onEvent: (JSONObject) -> Unit): CallbackRef? =
        listen("trimAudio", params, onEvent)

    /** 截取视频某一帧为 JPEG。→ {ok, path, width, height} */
    suspend fun videoFrame(videoPath: String, positionMs: Long, dest: String): JSONObject =
        await("videoFrame", JSONObject().put("videoPath", videoPath).put("positionMs", positionMs).put("dest", dest))

    fun cancel(taskId: String) = invoke("cancel", JSONObject().put("taskId", taskId))

    companion object {
        const val MODULE_NAME = "SDMediaProcessModule"
    }
}
