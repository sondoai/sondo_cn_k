package com.sondo.app.service

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.data.api.AudioUploadApi
import com.sondo.app.data.api.DictDataApi
import com.sondo.app.data.api.MusicActionApi
import com.sondo.app.data.api.MvActionApi
import com.sondo.app.data.api.OssPresignApi
import com.sondo.app.data.models.MusicStyleModel
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.MvShotRefImg
import com.sondo.app.data.models.OssMultipartCompleteUrlResponse
import com.sondo.app.data.models.OssMultipartInitResponse
import com.sondo.app.data.models.OssMultipartPartUrlsResponse
import com.sondo.app.data.models.OssPresignedResponse
import com.sondo.app.data.models.RoleModel
import com.sondo.app.data.models.SongModel
import com.sondo.app.data.models.jMap
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 作品 / 媒体服务用到的接口，按参考工程数据源的返回约定收口：
 * 操作类 1 成功 / 0 失败；查询类失败给空列表或 null（data 层的 XxxApi 返回 ApiResult）。
 *
 * 对应关系：MusicActionRemoteDataSource → [MusicActionApi]，MvActionRemoteDataSource → [MvActionApi]，
 * OssPresignRemoteDataSource → [OssPresignApi]，AudioUploadRemoteDataSource → [AudioUploadApi]，
 * DictDataRemoteDataSource → [DictDataApi]。
 * data 层没有的三个接口直接在这里发：作品库的 `getMvListByIds`（LibraryListRemoteDataSource.fetchMvListByIds）、
 * 试用任务详情（TrialMvRemoteDataSource.getDetail）、播放计数 `addMusicHit`。
 */
internal object WorksApi {

    // ------------------------------------------------------------------ 音乐

    /** 按 ids 查询当前用户的音乐生成进度。失败返回空列表，轮询下个周期再试。 */
    suspend fun getMusicListByIds(ids: String): List<SongModel> =
        MusicActionApi.getMusicListByIds(ids).getOrNull() ?: emptyList()

    /** 删除歌曲（移入回收站）。 */
    suspend fun deleteSong(musicId: Long): Int = MusicActionApi.deleteSong(musicId).getOrNull() ?: 0

    /** 收藏 / 取消收藏歌曲，collectStatus: 1 收藏、0 取消。 */
    suspend fun collectSong(musicId: Long, collectStatus: Int): Int =
        MusicActionApi.collectSong(musicId, collectStatus).getOrNull() ?: 0

    /** 更新歌曲发布状态，可一并提交标题 / 封面 / 分类（为 null 的字段不提交）。 */
    suspend fun publishOperation(
        musicId: Long? = null,
        musicPublish: Int? = null,
        musicTitle: String? = null,
        musicCoverUrl: String? = null,
        categoryId: String? = null,
        categoryName: String? = null,
    ): Int = MusicActionApi.publishOperation(
        musicId = musicId,
        musicPublish = musicPublish,
        musicTitle = musicTitle,
        musicCoverUrl = musicCoverUrl,
        categoryId = categoryId,
        categoryName = categoryName,
    ).getOrNull() ?: 0

    /** 分享成功后累加歌曲转发计数。 */
    suspend fun addMusicRepostCount(id: Long): Int = MusicActionApi.addMusicRepostCount(id).getOrNull() ?: 0

    /** 播放计数（参考工程里路径写死，不在 ApiPaths）。 */
    suspend fun addMusicHit(id: Long): Boolean = Api.get("/member/music/addMusicHit/$id").isSuccess

    // ------------------------------------------------------------------ MV

    /** 最近使用过的四视图角色。 */
    suspend fun fetchRecentRoles(): List<RoleModel> = MvActionApi.fetchRecentRoles().getOrNull() ?: emptyList()

    /** 各分镜的历史参考图。 */
    suspend fun fetchShotRefImgs(taskId: Long): List<MvShotRefImg> =
        MvActionApi.fetchShotRefImgs(taskId).getOrNull() ?: emptyList()

    suspend fun collectMv(id: Long, collectStatus: Int): Int = MvActionApi.collectMv(id, collectStatus).getOrNull() ?: 0

    suspend fun addMvRepostCount(id: Long): Int = MvActionApi.addMvRepostCount(id).getOrNull() ?: 0

    /** 更新 MV 发布状态；成功返回后端下发的最新发布状态码，失败返回 -1。 */
    suspend fun publishMvOperation(mvId: Long, mvPublish: Int): Int =
        MvActionApi.publishMvOperation(mvId, mvPublish).getOrNull() ?: -1

    /** 删除 MV（移入回收站）。 */
    suspend fun deleteMv(id: Long): Boolean = MvActionApi.deleteMv(id).isSuccess

    /** 触发 MV 分镜合成：genMode 2 走重新合成（isUpdt 标记是否编辑后重新生成），其余走首次合成。 */
    suspend fun mergeMvShot(id: Long, genMode: Int, isUpdt: Int): Boolean =
        MvActionApi.mergeMvShot(id, genMode, isUpdt).isSuccess

    /** 按 id 批量拉 MV（轮询生成中 / 失败项用，只含部分字段）。任一条解析出错则整体为空。 */
    suspend fun fetchMvListByIds(ids: String): List<MvModel> {
        val env = Api.get(ApiPaths.mvListByIds, mapOf("ids" to ids)).getOrNull() ?: return emptyList()
        return try {
            env.optJSONArray("data").jMap { MvModel.fromJson(it) }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    // ------------------------------------------------------------------ 字典

    suspend fun getDictByType(type: String): List<MusicStyleModel> =
        DictDataApi.getDictByType(type).getOrNull() ?: emptyList()

    suspend fun getDictByLanguage(type: String): List<MusicStyleModel> =
        DictDataApi.getDictByLanguage(type).getOrNull() ?: emptyList()

    // ------------------------------------------------------------------ 试用 MV

    /** 试用任务详情（原始 data 对象）；失败返回 null。 */
    suspend fun getTrialDetail(mvId: Long): JSONObject? {
        val env = Api.get("${ApiPaths.trialMvDetail}/$mvId").getOrNull() ?: return null
        return env.optJSONObject("data") ?: JSONObject()
    }

    // ------------------------------------------------------------------ OSS 预签名 / 我的音频

    suspend fun initMultipartUpload(fileName: String, mediaType: String, fileMd5: String): OssMultipartInitResponse? =
        OssPresignApi.initMultipartUpload(fileName, mediaType, fileMd5).getOrNull()

    suspend fun getPartUrls(objectKey: String, uploadId: String, partCount: Int): OssMultipartPartUrlsResponse? =
        OssPresignApi.getPartUrls(objectKey, uploadId, partCount).getOrNull()

    suspend fun getCompleteUrl(objectKey: String, uploadId: String): OssMultipartCompleteUrlResponse? =
        OssPresignApi.getCompleteUrl(objectKey, uploadId).getOrNull()

    suspend fun getPresignedUrl(fileName: String, mediaType: String, fileMd5: String = ""): OssPresignedResponse? =
        OssPresignApi.getPresignedUrl(fileName, mediaType, fileMd5).getOrNull()

    /** 上传完成后登记到「我的音频」。返回 (id, fileUrl)；失败 null。 */
    suspend fun registerUploadComplete(
        objectKey: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        duration: Int,
        fileMd5: String,
    ): Pair<Long, String>? {
        val reg = OssPresignApi.registerUploadComplete(
            objectKey = objectKey,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            duration = duration,
            fileMd5 = fileMd5,
        ).getOrNull() ?: return null
        return reg.id to reg.fileUrl
    }

    /** 批量删除「我的音频」服务端记录。 */
    suspend fun deleteRecord(ids: List<Long>): Boolean = AudioUploadApi.deleteRecord(ids).getOrNull() ?: false
}
