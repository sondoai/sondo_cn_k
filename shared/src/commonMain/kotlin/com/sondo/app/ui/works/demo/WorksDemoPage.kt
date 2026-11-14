package com.sondo.app.ui.works.demo

import com.sondo.app.base.BasePager
import com.sondo.app.core.utils.nowMs
import com.sondo.app.data.models.AudioItemStatus
import com.sondo.app.data.models.AudioUploadItem
import com.sondo.app.data.models.MvModel
import com.sondo.app.data.models.MvStatus
import com.sondo.app.data.models.SongModel
import com.sondo.app.service.MvActionsRequest
import com.sondo.app.service.PlayerService
import com.sondo.app.service.SongActionsRequest
import com.sondo.app.ui.SDColors
import com.sondo.app.ui.atoms.SDSongSkeleton
import com.sondo.app.ui.atoms.SDSongSkeletonAttr
import com.sondo.app.ui.dialog.UsageStatementType
import com.sondo.app.ui.dialog.showUsageStatement
import com.sondo.app.ui.nav.SDNavBack
import com.sondo.app.ui.works.SDAudioTaskCard
import com.sondo.app.ui.works.SDMusicItem
import com.sondo.app.ui.works.SDMvItem
import com.sondo.app.ui.works.SDNavPlayer
import com.sondo.app.ui.works.SDNavPoints
import com.sondo.app.ui.works.SDNavRootAppBar
import com.sondo.app.ui.works.SDSongCard
import com.sondo.app.ui.works.SDSongItem
import com.sondo.app.ui.works.WorksUiInstaller
import com.sondo.app.ui.works.showDialogActionsMv
import com.sondo.app.ui.works.showDialogActionsSong
import com.sondo.app.ui.works.showDialogEditSong
import com.sondo.app.ui.works.showDialogEditUser
import com.sondo.app.ui.works.showReportTagOptions
import com.sondo.app.ui.dialog.showMvPreviewUnlock
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 作品组件演示页（模拟数据，供模拟器冒烟）：`nav.push("works_demo")`。
 * 列表项都注册了 onTap（只弹 toast），不会调用真实接口；面板按钮回调同样只 toast。
 */
@Page("works_demo")
internal class WorksDemoPage : BasePager() {
    private var audioProgress by observable(0.2)
    private var playingUploadId by observable("")

    private val cover = "https://picsum.photos/seed/sondo/300/300"
    private val wide = "https://picsum.photos/seed/sondomv/480/270"
    private val tall = "https://picsum.photos/seed/sondomvt/270/480"

    private fun song(id: Long, status: Int, title: String, collect: Boolean = false, publish: Int = 0) = SongModel(
        id = id,
        musicTitle = title,
        musicCoverUrl = cover,
        musicGenerateStatus = status,
        musicAudioUrl = "https://example.com/a.mp3",
        isCollect = collect,
        isCollectNum = 1234,
        musicHitNum = 56789,
        repostCount = 12,
        musicPublish = publish,
        createTime = nowMs(),
    )

    private fun mv(id: Long, status: MvStatus, comp: String = "16:9", trial: Boolean = false, unlock: Int? = null) = MvModel(
        id = id,
        title = "Demo MV #$id ${status.name}",
        coverImage = if (comp == "9:16") tall else wide,
        status = status,
        imageComp = comp,
        subDurationScope = "00:10,02:46",
        trialMvFlag = trial,
        unlock = unlock,
        continueFullMvNeedPoint = 30,
        mvHitNum = 9876,
        collectNum = 321,
        repostCount = 7,
        createTime = nowMs(),
        resultUrl = "https://example.com/v.mp4",
    )

    private val mySongs by lazy { listOf(1, 2, 3, 4, 5).map { song(100L + it, it, "My song status $it") } }
    private val mvs by lazy {
        listOf(
            mv(1, MvStatus.success),
            mv(2, MvStatus.success, "9:16"),
            mv(3, MvStatus.generating),
            mv(4, MvStatus.waitingConfirm),
            mv(5, MvStatus.mergeFailed),
            mv(6, MvStatus.failed),
            mv(7, MvStatus.generating, trial = true),
            mv(8, MvStatus.failed, trial = true),
            mv(9, MvStatus.success, unlock = 0),
        )
    }

    override fun created() {
        super.created()
        WorksUiInstaller.install()
        tickUpload()
    }

    private fun tickUpload() {
        postDelayed(500) {
            audioProgress = if (audioProgress >= 1.0) 0.0 else audioProgress + 0.05
            tickUpload()
        }
    }

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            attr { backgroundColor(Color(0xFF0A0A0AL)) }
            SDNavRootAppBar {
                attr { title = "Works Demo" }
                event { onTapHelp { ctx.toast("help") } }
            }
            List {
                attr { flex(1f) }
                View {
                    attr { padding(0f, 16f, 40f, 16f) }
                    demoTitle("SDNavBack / SDNavPoints(isOnlyPoints)")
                    View {
                        attr {
                            flexDirectionRow()
                            alignItemsCenter()
                        }
                        SDNavBack()
                        SDNavPoints { attr { isOnlyPoints = true } }
                    }
                    demoTitle("SDNavPlayer")
                    SDNavPlayer()

                    demoTitle("SDMusicItem (isMy: status 1..5)")
                    ctx.mySongs.forEach { s ->
                        SDMusicItem {
                            attr {
                                item = s
                                isMy = true
                                marginBottom(20f)
                            }
                            event { onTap { t -> ctx.toast("music ${s.id}: $t") } }
                        }
                    }
                    demoTitle("SDMusicItem (other)")
                    SDMusicItem {
                        attr {
                            item = ctx.song(200, 3, "Other song", collect = true)
                            isMy = false
                        }
                        event { onTap { t -> ctx.toast("other: $t") } }
                    }

                    demoTitle("SDMvItem (isMy)")
                    ctx.mvs.forEach { m ->
                        SDMvItem {
                            attr {
                                item = m
                                isMy = true
                                marginBottom(20f)
                            }
                            event { onTap { t -> ctx.toast("mv ${m.id}: $t") } }
                        }
                    }
                    demoTitle("SDMvItem (other)")
                    SDMvItem {
                        attr {
                            item = ctx.mv(50, MvStatus.success)
                            isMy = false
                        }
                        event { onTap { t -> ctx.toast("other mv: $t") } }
                    }

                    demoTitle("SDSongItem (create / other / liked / failed)")
                    listOf(
                        ctx.song(301, 3, "Create type") to "create",
                        ctx.song(302, 4, "Failed create") to "create",
                        ctx.song(303, 1, "Generating") to "create",
                        ctx.song(304, 3, "Other type", collect = true) to "other",
                        ctx.song(305, 3, "Liked type") to "liked",
                    ).forEach { (s, t) ->
                        SDSongItem {
                            attr {
                                song = s
                                type = t
                                marginBottom(16f)
                            }
                            event {
                                onDetail { ctx.toast("detail ${it.id}") }
                                addPlayList { ctx.toast("play ${it.id}") }
                                onTapMore { ctx.toast("more ${it.id}") }
                                onLike { ctx.toast("like ${it.id}") }
                                deleteSong { ctx.toast("delete ${it.id}") }
                            }
                        }
                    }

                    demoTitle("SDSongCard")
                    List {
                        attr {
                            height(190f)
                            flexDirectionRow()
                        }
                        (1..5).forEach { i ->
                            SDSongCard {
                                attr { song = ctx.song(400L + i, 3, "Card song $i") }
                                event {
                                    onDetail { ctx.toast("card detail ${it.id}") }
                                    addPlayList { ctx.toast("card play ${it.id}") }
                                }
                            }
                        }
                    }

                    demoTitle("SDAudioTaskCard")
                    val statuses = listOf(
                        AudioItemStatus.waiting,
                        AudioItemStatus.uploading,
                        AudioItemStatus.paused,
                        AudioItemStatus.failed,
                        AudioItemStatus.completed,
                        AudioItemStatus.deleting,
                    )
                    statuses.forEachIndexed { i, st ->
                        SDAudioTaskCard {
                            attr {
                                val p = ctx.audioProgress
                                item = AudioUploadItem(
                                    id = i + 1L,
                                    uploadId = "u$i",
                                    fileName = "audio_${st.name}.mp3",
                                    fileSize = 3_456_789,
                                    uploadedBytes = (3_456_789 * p).toLong(),
                                    status = st,
                                    duration = 201_000,
                                )
                                playingUploadId = ctx.playingUploadId
                                isPlaying = true
                                isFirstItem = i == 0
                                isLastItem = i == statuses.size - 1
                            }
                            event {
                                onTogglePlay { ctx.playingUploadId = if (ctx.playingUploadId == it.playbackKey) "" else it.playbackKey }
                                onSelect { ctx.toast("select ${it.fileName}") }
                                onPause { ctx.toast("pause $it") }
                                onResume { ctx.toast("resume $it") }
                                onRetry { ctx.toast("retry $it") }
                                onRemove { ctx.toast("remove ${it.fileName}") }
                            }
                        }
                    }

                    demoTitle("SDSongSkeleton")
                    SDSongSkeleton { attr { type = SDSongSkeletonAttr.TYPE_ITEM } }

                    demoTitle("Sheets / dialogs")
                    demoButton("DialogActionsSong") {
                        val s = ctx.song(500, 3, "Sheet song")
                        PlayerService.setSong(s, "dialog_more")
                        ctx.showDialogActionsSong(
                            SongActionsRequest(
                                item = s,
                                type = "my_song",
                                onEdit = { ctx.toast("edit") },
                                onLike = { ctx.toast("like $it") },
                                onShare = { ctx.toast("share") },
                                onPublish = { ctx.toast("publish $it") },
                                onDelete = { ctx.toast("delete") },
                                onDownload = { ctx.toast("download") },
                            ),
                        )
                    }
                    demoButton("DialogActionsMv") {
                        val m = ctx.mv(600, MvStatus.success)
                        ctx.showDialogActionsMv(
                            MvActionsRequest(
                                item = m,
                                type = "mv",
                                url = m.resultUrl ?: "",
                                onShare = { ctx.toast("share") },
                                onPublish = { ctx.toast("publish $it") },
                                onDelete = { ctx.toast("delete") },
                                onDownload = { ctx.toast("download") },
                            ),
                        )
                    }
                    demoButton("DialogEditSong") {
                        ctx.launch { ctx.toast("result = ${ctx.showDialogEditSong("Old title", ctx.cover, null, null)}") }
                    }
                    demoButton("DialogEditUser") {
                        ctx.launch { ctx.toast("result = ${ctx.showDialogEditUser("Nick", ctx.cover)}") }
                    }
                    demoButton("ReportTagOptions") { ctx.showReportTagOptions { ctx.toast("report: $it") } }
                    demoButton("MvPreviewUnlock (UI only)") { ctx.showMvPreviewUnlock(30) { ctx.toast("unlock") } }
                    demoButton("UsageStatement(download)") {
                        ctx.launch { ctx.toast("agree = ${ctx.showUsageStatement(UsageStatementType.DOWNLOAD)}") }
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.demoTitle(text: String) {
    Text {
        attr {
            margin(24f, 0f, 12f, 0f)
            text(text)
            fontSize(13f)
            fontWeightBold()
            color(SDColors.accentPurple)
        }
    }
}

private fun ViewContainer<*, *>.demoButton(text: String, onTap: () -> Unit) {
    View {
        attr {
            height(40f)
            marginBottom(8f)
            borderRadius(8f)
            backgroundColor(SDColors.white10)
            allCenter()
        }
        event { click { onTap() } }
        Text {
            attr {
                text(text)
                fontSize(14f)
                color(Color.WHITE)
            }
        }
    }
}
