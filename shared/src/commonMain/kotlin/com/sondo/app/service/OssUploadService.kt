package com.sondo.app.service

import com.sondo.app.core.utils.onHost
import com.sondo.app.base.AppEnv
import com.sondo.app.base.Events
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.core.utils.AudioFfmpegUtil
import com.sondo.app.core.utils.AudioSniffer
import com.sondo.app.core.utils.CancelToken
import com.sondo.app.core.utils.CancelledException
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.HostBinding
import com.sondo.app.core.utils.HostNative
import com.sondo.app.core.utils.OssPartEtag
import com.sondo.app.core.utils.OssUploader
import com.sondo.app.core.utils.nowMs
import com.sondo.app.core.utils.truncateGraphemes
import com.sondo.app.data.models.AudioItemStatus
import com.sondo.app.data.models.AudioUploadItem
import com.sondo.app.data.models.OssMultipartInitResponse
import com.sondo.app.data.models.OssPresignedResponse
import com.sondo.app.data.models.SongModel
import com.sondo.app.nativeapi.SDMediaPickerModule
import com.sondo.app.nativeapi.nativeModule
import com.sondo.app.nativeapi.ok
import com.sondo.app.service.analytics.UmengUtils
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 用户自有音频的选择、校验与上传（对应 Flutter 侧 OssUploadService）。只处理音频，不做图片也不调审核。
 *
 * - `< 10MB` 预签名直传（单次 PUT），`>= 10MB` 分片上传（init → 分片地址 → 每批 3 片并发 PUT → complete）；
 * - 最多 2 个任务同时上传，其余保持 waiting，前面的结束后按列表顺序接上；
 * - 直传和每个分片各自最多 3 次尝试，间隔 1s、2s；进度回写节流 100ms；
 * - 暂停 = 取消当前请求并标记 paused；恢复 / 重试会复用已有的 OSS 分片会话，但每一片都重新上传
 *   （进度从 0 开始），放弃的分片会话不会发 abort —— 与参考工程一致；
 * - 字节已在 OSS 上但登记失败时（ossUploadDone），重试直接走登记；
 * - 任务只在内存里：进程被杀后未完成的上传丢失，历史以服务端「我的音频」列表为准。
 *
 * 状态机：waiting → preparing → uploading → completed | failed | paused；paused / failed → waiting。
 * [tasks] 是普通列表，每次变化广播 [Events.UPLOAD_TASKS_CHANGED] {taskId, removed, item?}。
 * 上传流程跑在宿主页面上，业务页关闭不影响。
 */
object OssUploadService {
    private const val TAG = "OssUploadService"

    /** 分片上传阈值 */
    private const val MULTIPART_THRESHOLD = 10L * 1024 * 1024 // 10 MB

    /** 同时进行中的上传数上限。 */
    private const val MAX_CONCURRENT_UPLOADS = 2

    /** 每批并发的分片数。 */
    private const val PART_BATCH_SIZE = 3

    private const val PROGRESS_THROTTLE_MS = 100L
    private const val MAX_FILE_SIZE = 100L * 1024 * 1024
    private const val MIN_DURATION_SECONDS = 30
    private const val MAX_DURATION_SECONDS = 360

    private class TaskMeta(
        var filePath: String,
        val fileName: String,
        val durationMs: Int,
        val fileSize: Long,
        val fileMd5: String,
    ) {
        var objectKey: String? = null
        var ossUploadId: String? = null

        // 暂停后恢复时复用已有 session 所需的额外字段
        var fileUrl: String? = null
        var partSize: Long? = null

        /** pick 阶段已打过的直传预签名，避免开传时重复请求。 */
        var cachedPresign: OssPresignedResponse? = null

        /** pick 阶段已打过的分片 init，避免开传时重复请求。 */
        var cachedMultipartInit: OssMultipartInitResponse? = null
        var cancelToken = CancelToken()
        var lastProgressMs = 0L
        var isTempFile = false
        var ossUploadDone = false

        /** 发起（或最近一次恢复）这个任务的页面，上传途中的提示弹在它上面。 */
        var ui: UiCtx = UiCtx(null)
    }

    var isInitialized = false
        private set

    private val _tasks = ArrayList<AudioUploadItem>()

    /** 所有本地上传任务（进行中 / 暂停 / 失败 / 已完成），最新的在前。 */
    val tasks: List<AudioUploadItem> get() = _tasks

    private val taskMeta = HashMap<String, TaskMeta>()
    private var isPicking = false
    private val hostBinding = HostBinding()

    /** 正在上传中的 taskId，防止快速双击恢复时并发启动两次上传。 */
    private val activeTaskIds = LinkedHashSet<String>()

    /**
     * 启动时调用。宿主（根页面）重建后，旧宿主上进行中的上传协程已失效：
     * 把这些任务标成 paused（用户可手动恢复），并释放并发名额。
     */
    fun init() {
        isInitialized = true
        if (!hostBinding.needsBind()) return
        isPicking = false
        val stale = activeTaskIds.toList()
        activeTaskIds.clear()
        for (taskId in stale) {
            taskMeta[taskId]?.cancelToken?.cancel("paused")
            val idx = _tasks.indexOfFirst { it.uploadId == taskId }
            if (idx != -1 && _tasks[idx].status.isActive) updateTask(taskId, status = AudioItemStatus.paused)
        }
    }

    /** 服务关闭：取消全部请求并清理临时文件。 */
    fun close() {
        for (meta in taskMeta.values) {
            meta.cancelToken.cancel("service closed")
            if (meta.isTempFile) {
                val path = meta.filePath
                Svc.launch { HostNative.file.delete(path) }
            }
        }
        taskMeta.clear()
    }

    // ─── Pick & upload ─────────────────────────────────────────────────────────

    /**
     * 弹出文件选择器，校验后预签名并上传。
     *
     * @param funName 仅用于统计
     * @return [SongModel]：秒传（预签名 instantComplete）；[AudioUploadItem]：需实际上传，任务已入队；
     *   null：取消、校验失败或出错（已提示）
     */
    suspend fun pickAndUpload(funName: String): Any? {
        if (isPicking) return null
        isPicking = true
        // 提示弹在发起页上；流程本身在宿主页面上跑完
        val ui = UiCtx.capture()
        return onHost { pickAndUploadImpl(funName, ui) }
    }

    private suspend fun pickAndUploadImpl(funName: String, ui: UiCtx): Any? {
        val file = HostNative.file
        var intermediateTemp: String? = null
        var uploadTemp: String? = null
        var remuxedTemp: String? = null
        try {
            val extensions = arrayListOf("mp3", "m4a", "aac", "wav", "flac")
            if (AppEnv.isAndroid) extensions.remove("m4a")

            // 选择器要由发起页弹出（Android 上 activity-result 只投给前台页面）；校验 / 上传仍在宿主上跑
            val picked = ui.native({ JSONObject().put("ok", false).put("cancelled", true) }) {
                nativeModule<SDMediaPickerModule>(SDMediaPickerModule.MODULE_NAME).pickAudioFile(extensions)
            }
            val pickedPath = picked.optString("path", "")
            if (picked.optBoolean("cancelled", false)) {
                logAudioUploadEvent("audio_upload_pick", mapOf("result" to "cancel", "function" to funName))
                return null
            }
            if (!picked.ok() || pickedPath.isEmpty()) {
                logAudioUploadEvent("audio_upload_pick", mapOf("result" to "no_path", "function" to funName))
                ui.toast("audio_pick_failed".tr)
                return null
            }
            ui.showLoading()
            // 原生选择器已把文件拷到 App 临时目录
            intermediateTemp = pickedPath
            val pickedName = picked.optString("name", "").ifEmpty { pickedPath.substringAfterLast('/') }
            val pickedExt = picked.optString("extension", "").lowercase()

            logAudioUploadEvent("audio_upload_pick", mapOf("result" to "success", "function" to funName))
            var audioPath = pickedPath
            val remuxed = AudioFfmpegUtil.remuxMp3IfNeeded(pickedPath)
            if (remuxed != pickedPath) {
                audioPath = remuxed
                remuxedTemp = remuxed
            }

            // 文件大小 ≤ 100 MB（服务端 maxSize 会进一步校验）
            val fileSize = file.size(audioPath)
            if (fileSize > MAX_FILE_SIZE) {
                logAudioUploadEvent("audio_upload_validate", mapOf("result" to "size", "function" to funName))
                ui.toast("music_file_size_exceed_error".trParams("value" to "100"))
                ui.closeLoading()
                return null
            }

            // 音频完整性 & 时长校验
            val probe = HostNative.mediaProcess.probe(audioPath)
            val durationMs = if (probe.ok()) probe.optLong("durationMs", 0L) else 0L
            if (fileSize <= 0L || durationMs <= 0L) {
                logAudioUploadEvent("audio_upload_validate", mapOf("result" to "corrupted", "function" to funName))
                ui.toast("music_file_corrupted".tr)
                ui.closeLoading()
                return null
            }
            val durationSeconds = durationMs / 1000L
            if (durationSeconds < MIN_DURATION_SECONDS) {
                logAudioUploadEvent("audio_upload_validate", mapOf("result" to "duration_short", "function" to funName))
                ui.toast("music_duration_less_error".trParams("value" to "30"))
                ui.closeLoading()
                return null
            }
            if (durationSeconds > MAX_DURATION_SECONDS) {
                logAudioUploadEvent("audio_upload_validate", mapOf("result" to "duration_long", "function" to funName))
                ui.toast("music_duration_exceed_error".trParams("value" to "6"))
                ui.closeLoading()
                return null
            }

            // 扩展名校验：选择器给的 → 文件名后缀 → 路径后缀 → 文件头嗅探
            var rawExt = pickedExt
            if (rawExt.isEmpty() || !extensions.contains(rawExt)) {
                if (pickedName.contains('.')) rawExt = pickedName.substringAfterLast('.').lowercase()
                if (!extensions.contains(rawExt)) rawExt = sniffExtension(audioPath, extensions)
            }
            if (!extensions.contains(rawExt)) {
                logAudioUploadEvent("audio_upload_validate", mapOf("result" to "ext", "function" to funName))
                ui.toast("audio_file_extension_error".tr)
                ui.closeLoading()
                return null
            }

            logAudioUploadEvent("audio_upload_validate", mapOf("result" to "ok", "function" to funName))

            // 校验通过后拷贝到 upload 专用 temp，后续不再依赖选择器给的路径
            val uploadFile = "${HostNative.tempDir()}/audio_upload_${HostNative.uniqueStamp()}.$rawExt"
            if (!file.copy(audioPath, uploadFile)) throw IllegalStateException("copy to upload temp failed")
            uploadTemp = uploadFile
            if (intermediateTemp != uploadFile) {
                file.delete(intermediateTemp)
                intermediateTemp = null
            }
            if (remuxedTemp != null) {
                file.delete(remuxedTemp)
                remuxedTemp = null
            }

            val fileMd5 = file.md5File(uploadFile)
            val localName = pickedName

            // 预签名：instantComplete 即秒传，直接返回 SongModel
            if (fileSize < MULTIPART_THRESHOLD) {
                val presign = WorksApi.getPresignedUrl(localName, "audio", fileMd5)
                if (presign == null) {
                    ui.toast("audio_pick_failed".tr)
                    ui.closeLoading()
                    return null
                }
                if (presign.maxSize > 0L && fileSize > presign.maxSize) {
                    ui.toast("music_file_size_exceed_error".trParams("value" to mbText(presign.maxSize)))
                    ui.closeLoading()
                    return null
                }
                if (presign.isInstantComplete) {
                    file.delete(uploadFile)
                    uploadTemp = null
                    ui.closeLoading()
                    return songFromInstant(presign.fileName.ifEmpty { localName }, presign.fileUrl, durationMs)
                }
                val item = enqueueUploadTask(uploadFile, localName, fileSize, durationMs.toInt(), fileMd5, funName, presign, null, ui)
                ui.closeLoading()
                return item
            }

            val initResp = WorksApi.initMultipartUpload(localName, "audio", fileMd5)
            if (initResp == null) {
                ui.toast("audio_pick_failed".tr)
                ui.closeLoading()
                return null
            }
            if (initResp.maxSize > 0L && fileSize > initResp.maxSize) {
                ui.toast("music_file_size_exceed_error".trParams("value" to mbText(initResp.maxSize)))
                ui.closeLoading()
                return null
            }
            if (initResp.isInstantComplete) {
                file.delete(uploadFile)
                uploadTemp = null
                ui.closeLoading()
                return songFromInstant(initResp.fileName.ifEmpty { localName }, initResp.fileUrl, durationMs)
            }
            val item = enqueueUploadTask(uploadFile, localName, fileSize, durationMs.toInt(), fileMd5, funName, null, initResp, ui)
            ui.closeLoading()
            return item
        } catch (e: Throwable) {
            ui.closeLoading()
            logAudioUploadEvent("audio_upload_pick", mapOf("result" to "error", "function" to funName))
            ui.toast("audio_pick_failed".tr)
            return null
        } finally {
            try {
                if (intermediateTemp != null) file.delete(intermediateTemp)
                if (remuxedTemp != null) file.delete(remuxedTemp)
                // 秒传失败 / 取消路径：清理未入队的 upload temp
                val temp = uploadTemp
                if (temp != null && taskMeta.values.none { it.filePath == temp }) file.delete(temp)
            } catch (e: Throwable) {
                // ignore
            }
            isPicking = false
        }
    }

    /** `maxSize / 1024 / 1024` 的展示文本（与参考工程一样带小数，如 100.0）。 */
    private fun mbText(bytes: Long): String = (bytes / 1024.0 / 1024.0).toString()

    /**
     * 文件头嗅探扩展名：先按 `mime` 包的魔数表（mp3 / aac），再按完整的音频嗅探（wav / flac / m4a）。
     * 后一步比参考工程多认几种格式，只会让内容真实匹配的文件通过。
     */
    private suspend fun sniffExtension(path: String, extensions: List<String>): String {
        val suffix = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        if (extensions.contains(suffix)) return suffix
        return try {
            val head = AudioSniffer.readHead(path, 12)
            val byMime = AudioSniffer.extensionFromHeader(head)
            if (extensions.contains(byMime)) return byMime
            val byKind = AudioSniffer.extensionOf(AudioSniffer.detectFromFile(path))
            if (extensions.contains(byKind)) byKind else byMime
        } catch (e: Throwable) {
            ""
        }
    }

    /** 创建本地上传任务并异步开传。 */
    private fun enqueueUploadTask(
        uploadFile: String,
        localName: String,
        fileSize: Long,
        durationMs: Int,
        fileMd5: String,
        funName: String,
        cachedPresign: OssPresignedResponse?,
        cachedMultipartInit: OssMultipartInitResponse?,
        ui: UiCtx,
    ): AudioUploadItem {
        val taskId = "task_${HostNative.uniqueStamp()}"
        val meta = TaskMeta(uploadFile, localName, durationMs, fileSize, fileMd5)
        meta.isTempFile = true
        meta.ui = ui
        meta.cachedPresign = cachedPresign
        meta.cachedMultipartInit = cachedMultipartInit
        taskMeta[taskId] = meta

        val item = AudioUploadItem.local(taskId = taskId, fileName = localName, fileSize = fileSize, durationMs = durationMs)
        _tasks.add(0, item)
        notifyTask(taskId, item)
        Svc.launch { startUpload(taskId, funName) }
        return item
    }

    /** 秒传响应包装为选曲结果：标题取文件名第一个 `.` 之前，最多 30 个字素。 */
    private fun songFromInstant(fileName: String, fileUrl: String, durationMs: Long): SongModel {
        var title = ""
        if (fileName.isNotEmpty()) {
            title = if (fileName.contains('.')) fileName.split('.').first() else fileName
            title = truncateGraphemes(title, 30)
        }
        val totalSeconds = durationMs / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val durationStr = "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
        return SongModel(
            id = 0L,
            musicTitle = title,
            musicAudioUrl = fileUrl,
            musicCoverUrl = "",
            musicDurationStr = durationStr,
        )
    }

    // ─── Upload flow ───────────────────────────────────────────────────────────

    private suspend fun startUpload(taskId: String, funName: String) {
        if (activeTaskIds.contains(taskId)) return
        // 已达并发上限，保持 waiting；当前活跃任务结束后由 scheduleWaitingTasks 接管
        if (activeTaskIds.size >= MAX_CONCURRENT_UPLOADS) return
        activeTaskIds.add(taskId)

        val meta = taskMeta[taskId]
        if (meta == null) {
            activeTaskIds.remove(taskId)
            return
        }

        // 每次开传换新 token，避免沿用已 cancel 的 token 导致立刻失败
        meta.cancelToken = CancelToken()
        val ct = meta.cancelToken

        try {
            updateTask(taskId, status = AudioItemStatus.preparing)
            val fileSize = meta.fileSize

            if (meta.ossUploadDone && meta.objectKey != null) {
                checkCancelled(ct)
                finishRegister(taskId, meta, fileSize, funName, ct, null)
            } else if (fileSize < MULTIPART_THRESHOLD) {
                uploadDirect(taskId, meta, fileSize, ct, funName)
            } else {
                uploadMultipart(taskId, meta, fileSize, ct, funName)
            }
        } catch (e: CancelledException) {
            // 暂停：保持 paused，禁止被后续逻辑写成 failed
            ensurePausedAfterCancel(taskId)
        } catch (e: Throwable) {
            DevLog.logger("$TAG.startUpload[$taskId]: ${e.message}")
            if (!meta.ossUploadDone) {
                logAudioUploadEvent(
                    "audio_upload_oss",
                    mapOf("result" to "failed", "function" to funName, "file_size" to fileSizeBucket(meta.fileSize)),
                )
            }
            updateTask(taskId, status = AudioItemStatus.failed, lastError = "${e.message}")
            UmengUtils.logEvent(
                "upload_completed",
                mapOf("upload_state" to "failed", "function" to funName, "resource_type" to "audio"),
            )
        } finally {
            activeTaskIds.remove(taskId)
            scheduleWaitingTasks()
        }
    }

    /**
     * 取消收尾时：仅当仍停在 preparing / uploading 才兜底 paused。
     * waiting 可能是用户已点恢复，不能再打回 paused。
     */
    private fun ensurePausedAfterCancel(taskId: String) {
        val idx = _tasks.indexOfFirst { it.uploadId == taskId }
        if (idx == -1) return
        val status = _tasks[idx].status
        if (status == AudioItemStatus.preparing || status == AudioItemStatus.uploading) {
            updateTask(taskId, status = AudioItemStatus.paused)
        }
    }

    /** 已取消则抛出，统一走 startUpload 的暂停分支。 */
    private fun checkCancelled(ct: CancelToken) {
        if (ct.isCancelled) throw CancelledException(ct.reason.ifEmpty { "paused" })
    }

    /** 有任务完成或失败后，自动启动处于等待队列中的下一个任务。 */
    private fun scheduleWaitingTasks() {
        if (activeTaskIds.size >= MAX_CONCURRENT_UPLOADS) return
        var budget = MAX_CONCURRENT_UPLOADS - activeTaskIds.size
        for (task in _tasks.toList()) {
            if (budget <= 0) break
            if (task.status == AudioItemStatus.waiting &&
                taskMeta.containsKey(task.uploadId) &&
                !activeTaskIds.contains(task.uploadId)
            ) {
                val id = task.uploadId
                budget--
                Svc.launch { startUpload(id, "-") }
            }
        }
    }

    /** 小文件（< 10 MB）单次 PUT 直传。 */
    private suspend fun uploadDirect(taskId: String, meta: TaskMeta, fileSize: Long, ct: CancelToken, funName: String) {
        var lastError: Throwable? = null
        for (attempt in 0 until 3) {
            try {
                checkCancelled(ct)
                val cached = meta.cachedPresign
                meta.cachedPresign = null
                val presign = cached ?: WorksApi.getPresignedUrl(meta.fileName, "audio", meta.fileMd5)
                checkCancelled(ct)
                if (presign == null) throw IllegalStateException("获取预签名 URL 失败")
                // 重试时若命中秒传，直接完成本地任务
                if (presign.isInstantComplete) {
                    updateTask(taskId, status = AudioItemStatus.completed, fileUrl = presign.fileUrl, uploadedBytes = fileSize)
                    cleanupTaskMeta(taskId, meta)
                    return
                }
                val uploadUrl = presign.uploadUrl
                if (uploadUrl.isNullOrEmpty()) throw IllegalStateException("缺少 uploadUrl")
                // 文件大小超出服务端允许大小
                if (presign.maxSize > 0L && fileSize > presign.maxSize) {
                    meta.ui.toast("music_file_size_exceed_error".trParams("value" to mbText(presign.maxSize)))
                    throw IllegalStateException("文件超出服务端允许大小（最大 ${presign.maxSize} 字节）")
                }

                meta.objectKey = presign.objectKey
                if (presign.fileUrl.trim().isNotEmpty()) meta.fileUrl = presign.fileUrl

                updateTask(taskId, status = AudioItemStatus.uploading)

                OssUploader.putFile(uploadUrl, meta.filePath, ct) { sent, _ ->
                    val now = nowMs()
                    if (!ct.isCancelled && now - meta.lastProgressMs >= PROGRESS_THROTTLE_MS) {
                        meta.lastProgressMs = now
                        updateTask(taskId, uploadedBytes = sent)
                    }
                }
                // cancel 可能滞后，完成后再次确认
                checkCancelled(ct)

                meta.ossUploadDone = true
                logAudioUploadEvent(
                    "audio_upload_oss",
                    mapOf("result" to "success", "function" to funName, "file_size" to fileSizeBucket(fileSize)),
                )

                finishRegister(taskId, meta, fileSize, funName, ct, null)
                return
            } catch (e: CancelledException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
            }
            // 暂停后不再继续退避重试
            checkCancelled(ct)
            if (attempt < 2) Svc.sleep(1000 shl attempt)
        }
        throw lastError ?: IllegalStateException("直传失败")
    }

    /** 大文件（≥ 10 MB）分片并发上传。 */
    private suspend fun uploadMultipart(taskId: String, meta: TaskMeta, fileSize: Long, ct: CancelToken, funName: String) {
        // 1. 初始化分片 session（暂停后恢复时复用已有 session，不重新发起 init）
        val objectKey: String
        val ossUploadId: String
        val resolvedFileUrl: String
        val partSize: Long

        val existingUploadId = meta.ossUploadId
        val existingObjectKey = meta.objectKey
        val existingPartSize = meta.partSize
        if (!existingUploadId.isNullOrEmpty() && existingObjectKey != null && existingPartSize != null) {
            // 恢复已有 session：直接复用，避免在 OSS 上创建孤立的未完成分片会话
            objectKey = existingObjectKey
            ossUploadId = existingUploadId
            resolvedFileUrl = meta.fileUrl ?: ""
            partSize = existingPartSize
        } else {
            val cached = meta.cachedMultipartInit
            meta.cachedMultipartInit = null
            val initResp = cached ?: WorksApi.initMultipartUpload(meta.fileName, "audio", meta.fileMd5)
            checkCancelled(ct)
            if (initResp == null) throw IllegalStateException("获取预签名 URL 失败")
            // 重试时若命中秒传，直接完成本地任务
            if (initResp.isInstantComplete) {
                updateTask(
                    taskId,
                    id = initResp.id,
                    status = AudioItemStatus.completed,
                    fileUrl = initResp.fileUrl,
                    uploadedBytes = fileSize,
                )
                cleanupTaskMeta(taskId, meta)
                return
            }
            val initUploadUrl = initResp.initUploadUrl
            if (initUploadUrl.isNullOrEmpty()) throw IllegalStateException("缺少 initUploadUrl")
            // 文件大小超出服务端允许大小
            if (initResp.maxSize > 0L && fileSize > initResp.maxSize) {
                meta.ui.toast("music_file_size_exceed_error".trParams("value" to mbText(initResp.maxSize)))
                throw IllegalStateException("文件超出服务端允许大小（最大 ${initResp.maxSize} 字节）")
            }
            checkCancelled(ct)

            val newOssUploadId = OssUploader.initMultipartUpload(initUploadUrl, ct)

            objectKey = initResp.objectKey
            ossUploadId = newOssUploadId
            resolvedFileUrl = initResp.fileUrl
            partSize = initResp.partSize

            meta.objectKey = objectKey
            meta.ossUploadId = ossUploadId
            meta.fileUrl = resolvedFileUrl.ifEmpty { null }
            meta.partSize = partSize
        }

        checkCancelled(ct)
        updateTask(taskId, status = AudioItemStatus.uploading)

        // 2. 计算分片数
        if (partSize <= 0L) throw IllegalStateException("无效的分片大小: $partSize")
        val partCount = ((fileSize + partSize - 1) / partSize).toInt()

        // 3. 获取各分片预签名 PUT URL（每次 resume 均需刷新，旧 URL 可能已过期）
        val partUrlsResp = WorksApi.getPartUrls(objectKey, ossUploadId, partCount)
            ?: throw IllegalStateException("获取分片 URL 失败")
        checkCancelled(ct)

        // 4. 分批（每批 3 片）并发上传；每片按 (partNumber - 1) * partSize 取精确的字节区间
        val completedParts = ArrayList<OssPartEtag>()
        var uploadedBytes = 0L
        val parts = partUrlsResp.parts
        if (parts.size != partCount) {
            throw IllegalStateException("服务端返回 ${parts.size} 个分片 URL，预期 $partCount 个")
        }
        var i = 0
        while (i < parts.size) {
            checkCancelled(ct)
            val batch = parts.subList(i, minOf(i + PART_BATCH_SIZE, parts.size))

            // 实时跟踪本批每片的已发送字节数（partNumber → sentBytes）
            val batchSentBytes = LinkedHashMap<Int, Long>()
            val batchLengths = LinkedHashMap<Int, Long>()
            for (p in batch) {
                val start = (p.partNumber - 1).toLong() * partSize
                batchSentBytes[p.partNumber] = 0L
                batchLengths[p.partNumber] = (fileSize - start).coerceIn(0L, partSize)
            }
            val baseBytes = uploadedBytes

            val outcomes = Svc.awaitAll(
                batch.map { p ->
                    suspend {
                        val start = (p.partNumber - 1).toLong() * partSize
                        uploadPartWithRetry(p.uploadUrl, meta.filePath, start, batchLengths[p.partNumber] ?: 0L, p.partNumber, ct) { sent ->
                            batchSentBytes[p.partNumber] = sent
                            val now = nowMs()
                            if (!ct.isCancelled && now - meta.lastProgressMs >= PROGRESS_THROTTLE_MS) {
                                meta.lastProgressMs = now
                                updateTask(taskId, uploadedBytes = baseBytes + batchSentBytes.values.sum())
                            }
                        }
                    }
                }
            )
            // 任一片被取消按取消处理；否则抛第一处失败
            val errors = outcomes.mapNotNull { it.exceptionOrNull() }
            if (errors.isNotEmpty()) throw errors.firstOrNull { it is CancelledException } ?: errors.first()
            outcomes.forEach { completedParts.add(it.getOrThrow()) }

            uploadedBytes += batchLengths.values.sum()
            // 批次完成后对齐到精确值，避免因并发回调偏差留下误差
            checkCancelled(ct)
            updateTask(taskId, uploadedBytes = uploadedBytes)
            i += PART_BATCH_SIZE
        }

        checkCancelled(ct)

        // 5. 获取合并预签名 URL
        val completeResp = WorksApi.getCompleteUrl(objectKey, ossUploadId)
        if (completeResp == null) {
            logAudioUploadEvent(
                "audio_upload_oss",
                mapOf("result" to "failed", "function" to funName, "file_size" to fileSizeBucket(fileSize)),
            )
            throw IllegalStateException("获取合并 URL 失败")
        }

        // 6. 通知 OSS 合并分片
        completedParts.sortBy { it.partNumber }
        OssUploader.completeMultipartUpload(completeResp.completeUrl, completedParts, ct)

        meta.ossUploadDone = true
        logAudioUploadEvent(
            "audio_upload_oss",
            mapOf("result" to "success", "function" to funName, "file_size" to fileSizeBucket(fileSize)),
        )

        finishRegister(taskId, meta, fileSize, funName, ct, resolvedFileUrl.ifEmpty { completeResp.fileUrl })
    }

    /** 登记到「我的音频」。暂停只拦截登记前；登记已发出则按结果落盘，避免成功后回滚成 paused。 */
    private suspend fun finishRegister(
        taskId: String,
        meta: TaskMeta,
        fileSize: Long,
        funName: String,
        ct: CancelToken,
        fallbackFileUrl: String?,
    ) {
        checkCancelled(ct)
        val objectKey = meta.objectKey
        if (objectKey.isNullOrEmpty()) throw IllegalStateException("缺少 objectKey，无法注册")

        val resolvedFallback = fallbackFileUrl ?: meta.fileUrl ?: ""

        val reg = WorksApi.registerUploadComplete(
            objectKey = objectKey,
            fileName = meta.fileName,
            fileSize = fileSize,
            mimeType = mimeTypeFromFileName(meta.fileName),
            duration = meta.durationMs,
            fileMd5 = meta.fileMd5,
        )

        if (reg == null) {
            logAudioUploadEvent("audio_upload_register", mapOf("result" to "failed", "function" to funName))
            updateTask(
                taskId,
                status = AudioItemStatus.failed,
                lastError = "register failed",
                uploadedBytes = fileSize,
                fileUrl = resolvedFallback.ifEmpty { null },
            )
            UmengUtils.logEvent(
                "upload_completed",
                mapOf("upload_state" to "failed", "function" to funName, "resource_type" to "audio"),
            )
            return
        }

        logAudioUploadEvent("audio_upload_register", mapOf("result" to "success", "function" to funName))

        updateTask(
            taskId,
            id = reg.first,
            status = AudioItemStatus.completed,
            fileUrl = reg.second.ifEmpty { resolvedFallback },
            uploadedBytes = fileSize,
        )
        cleanupTaskMeta(taskId, meta)
        UmengUtils.logEvent(
            "upload_completed",
            mapOf("upload_state" to "success", "function" to funName, "resource_type" to "audio"),
        )
    }

    private suspend fun uploadPartWithRetry(
        uploadUrl: String,
        filePath: String,
        offset: Long,
        length: Long,
        partNumber: Int,
        ct: CancelToken,
        onProgress: (sent: Long) -> Unit,
    ): OssPartEtag {
        var lastError: Throwable? = null
        for (attempt in 0 until 3) {
            try {
                val etag = OssUploader.putPart(uploadUrl, filePath, offset, length, ct) { sent, _ -> onProgress(sent) }
                return OssPartEtag(partNumber, etag)
            } catch (e: CancelledException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
            }
            checkCancelled(ct)
            if (attempt < 2) Svc.sleep(1000 shl attempt)
        }
        throw lastError ?: IllegalStateException("Part $partNumber upload failed")
    }

    // ─── Task management ───────────────────────────────────────────────────────

    /** 暂停：取消进行中的请求；已完成的任务不回退。 */
    fun pauseTask(taskId: String) {
        taskMeta[taskId]?.cancelToken?.cancel("paused")
        val idx = _tasks.indexOfFirst { it.uploadId == taskId }
        if (idx == -1 || _tasks[idx].status == AudioItemStatus.completed) return
        updateTask(taskId, status = AudioItemStatus.paused)
    }

    /** 恢复：先标 waiting；旧上传仍在收尾时由它的 finally 拉起，否则立即开传。 */
    fun resumeTask(taskId: String) {
        val meta = taskMeta[taskId]
        if (meta == null) {
            DevLog.logger("$TAG.resumeTask: meta missing $taskId")
            return
        }
        meta.ui = UiCtx.capture()
        updateTask(taskId, status = AudioItemStatus.waiting)
        if (activeTaskIds.contains(taskId)) return
        Svc.launch { startUpload(taskId, "-") }
    }

    fun retryTask(taskId: String) = resumeTask(taskId)

    /** 移除任务：取消上传、清理临时文件；已完成且有服务端记录（id > 0）时调删除接口。 */
    suspend fun removeItem(item: AudioUploadItem) = onHost { removeItemImpl(item) }

    private suspend fun removeItemImpl(item: AudioUploadItem) {
        val taskId = item.uploadId
        val meta = taskMeta[taskId]
        meta?.cancelToken?.cancel("removed")
        if (meta != null) cleanupTaskMeta(taskId, meta) else taskMeta.remove(taskId)
        if (item.status == AudioItemStatus.completed && item.id > 0L) {
            WorksApi.deleteRecord(listOf(item.id))
        }
        _tasks.removeAll { it.uploadId == taskId }
        Svc.emit(Events.UPLOAD_TASKS_CHANGED, JSONObject().put("taskId", taskId).put("removed", true))
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun updateTask(
        taskId: String,
        id: Long? = null,
        status: AudioItemStatus? = null,
        uploadedBytes: Long? = null,
        fileUrl: String? = null,
        lastError: String? = null,
    ) {
        val idx = _tasks.indexOfFirst { it.uploadId == taskId }
        if (idx == -1) return
        val cur = _tasks[idx]
        val next = cur.copy(
            id = id ?: cur.id,
            status = status ?: cur.status,
            uploadedBytes = uploadedBytes ?: cur.uploadedBytes,
            fileUrl = fileUrl ?: cur.fileUrl,
            lastError = lastError ?: cur.lastError,
        )
        _tasks[idx] = next
        notifyTask(taskId, next)
    }

    private fun notifyTask(taskId: String, item: AudioUploadItem) {
        Svc.emit(
            Events.UPLOAD_TASKS_CHANGED,
            JSONObject().put("taskId", taskId).put("removed", false).put("item", item.toJson()),
        )
    }

    private fun mimeTypeFromFileName(fileName: String): String =
        when (fileName.substringAfterLast('.').lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            else -> "audio/mpeg"
        }

    private suspend fun cleanupTaskMeta(taskId: String, meta: TaskMeta) {
        if (meta.isTempFile) {
            try {
                HostNative.file.delete(meta.filePath)
            } catch (e: Throwable) {
                // ignore
            }
        }
        taskMeta.remove(taskId)
    }

    private fun logAudioUploadEvent(name: String, parameters: Map<String, Any?>) {
        UmengUtils.logEvent(name, parameters)
    }

    /** 文件大小分档（统计用）：< 10MB small，< 50MB medium，否则 large。 */
    fun fileSizeBucket(fileSize: Long): String {
        if (fileSize < 10L * 1024 * 1024) return "small"
        if (fileSize < 50L * 1024 * 1024) return "medium"
        return "large"
    }
}
