# 作品组件（`com.sondo.app.ui.works`）

歌曲 / MV 列表项、导航栏积分 / 头像 / 迷你播放器、作品操作面板，以及 service 层 `WorksUi` / `AccountHooks` 钩子的实现。
组件名 = `SD` + Dart 类名（去掉 `Widget` 后缀）。演示页：`nav.push("works_demo")`（`ui/works/demo/WorksDemoPage.kt`）。

## 0. 约定

- 列表项的模型属性是**响应式**的（`item` / `song`）：父级在 `vfor` 里替换列表元素即可刷新行；也可在 `attr { item = ctx.xxx }` 里读 observable。
- **收藏 / 分享 / 播放数不要替换列表元素**（替换会让 `vfor` 重建整行、封面重新加载）：服务层广播 `MUSIC_LIST_CHANGED` / `MV_LIST_CHANGED`
  （like / share / play）后，页面级 `WorksState` 按 id 记下覆盖值，列表项在 attr 里读 `songStats(song)` / `mvStats(mv)` / `videoStats(v)`，
  只重跑图标和数字的 attr。页面把条目交给服务或带去别的页面时用 `latestSong` / `latestMv` / `latestVideo` 合上覆盖值。
  收藏是乐观更新（`MusicService` / `MvService`：先广播、再请求、失败回滚，无 loading）。两态图标用 `SDToggleIcon`（两张图只切透明度，不闪）。
- 标准操作走服务：列表项**不注册 `onTap`** 时自动调用 `MusicService.onTap(type, item, list)` / `MvService.onTap(type, item, list)`（与 Dart 调用处一致）；
  注册了 `event { onTap { type -> … } }` 则完全由调用方处理（需要时自己再调服务）。
- 播放指示、分享 / 下载中转圈、登录态、积分、优惠倒计时来自页面级镜像 `WorksState.of(pager)`（每页一份，订阅
  `Events.PLAYER_STATE / MUSIC_BUSY_CHANGED / MV_BUSY_CHANGED / USER_CHANGED / POINTS_CHANGED / OFFER_COUNTDOWN / CONFIG_CHANGED`），
  组件自动使用，页面无需做任何事；页面自己想读也可以：`WorksState.of(ctx).isPlaying`、`playIcon(songId)`。
- 启动时 `WorksUiInstaller.install()`（slice MAIN 调用，幂等）。

## 1. 列表项 / 卡片

| Kotlin | Dart | 属性 / 事件 | 用法 |
|---|---|---|---|
| `SDMusicItem { }` | `MusicItemWidget` | attr：`item: SongModel`(obs) `isMy`(obs, true) `list: List<SongModel>`。event：`onTap { MusicItemType -> }`（不注册 = `MusicService.onTap(type, item, list)`） | `vfor({ ctx.songs }) { s -> View { SDMusicItem { attr { item = s; isMy = true; list = ctx.songs; marginBottom(20f) } } } }` |
| `SDMvItem { }` | `MvItemWidget` | attr：`item: MvModel`(obs) `isMy`(obs, true) `list: List<MvModel>`。event：`onTap { MvItemType -> }`（不注册 = `MvService.onTap(type, item, list)`）。含试用 MV、试看解锁、合成失败、待确认等全部状态 | `SDMvItem { attr { item = mv; isMy = true; list = ctx.mvs } }` |
| `SDSongItem { }` | `SongItemWidget` | attr：`song: SongModel`(obs) `type`("create" / "other" / "liked" / 其它)。event：`onDetail { song }` `addPlayList { song }` `onTapMore { }` `onLike { }` `deleteSong { }` `shareVideo { }`（同 Dart：分享按钮直接 `MusicService.onTap(share)`）。「create」胶囊 → `PlayerService.createSong` + 埋点 `create_similar` | `SDSongItem { attr { song = s; type = "other" }`<br>`  event { onDetail { … }; addPlayList { … }; onLike { … } } }` |
| `SDSongCard { }` | `SongCardWidget` | attr：`song`(obs)。event：`onDetail { }`（点卡片）`addPlayList { }`（点播放指示）。宽 140 + 左右 6 | `SDSongCard { attr { song = s }; event { onDetail { … }; addPlayList { … } } }` |
| `SDAudioTaskCard { }` | `AudioTaskCard` | attr：`item: AudioUploadItem`(obs) `playingUploadId`(obs) `index` `isFirstItem/isLastItem`(obs) `isLoadingAudio`(obs) `isPlaying`(obs)。event：`onSelect { item }` `onPause { uploadId }` `onResume { }` `onRetry { }` `onRemove { item }` `onTogglePlay { item }` | `SDAudioTaskCard { attr { item = it; playingUploadId = ctx.playingId; isFirstItem = i == 0 } … }` |
| `SDSongSkeleton { }` | `SongSkeleton` | 在 UI Kit（`ui/atoms`） | |

## 2. 导航

| Kotlin | Dart | 属性 / 事件 | 用法 |
|---|---|---|---|
| `SDNavPoints { }` | `NavPoints` | attr：`isOnlyPoints`。自带：召回倒计时 > 折扣倒计时 > 积分（翻牌动画）/「会员」；积分增减浮动（`POINTS_CHANGED.delta`）；点击流程（登录 → `RecallFlow.openProductFromHome` → 折扣页 → 积分包 / 订阅页） | `SDNavPoints()` |
| `SDNavRootAppBar { }` | `NavRootAppBarWidget` | attr：`title`(obs) `titleWidget: ViewBuilder?` `headerReveal: Float?`(obs) `headerImmersive: Boolean?`(obs) `withStatusBar`(true)。event：`onTapTitle { }` `onDoubleTapTitle { }` `onTapHelp { }`（注册才显示帮助图标）。高 56（+状态栏） | `SDNavRootAppBar { attr { title = "create".tr } }` |
| `SDNavPlayer { }` | `NavPlayerWidget` | 无属性；无当前歌曲时不占空间。点封面 / 标题 → 详情（仅生成成功），上一首 / 播放暂停 / 下一首 | `SDNavPlayer()` |

## 3. 弹层（`BasePager` 扩展；平时由服务经 `WorksUi` 钩子调用，页面很少直接用）

| Kotlin | Dart | 签名 |
|---|---|---|
| `showDialogActionsSong(request: SongActionsRequest): DialogHandle?` | `DialogActionsSong` | 展示 `PlayerService.song`，随 `PLAYER_SONG_CHANGED` / `MUSIC_BUSY_CHANGED` 刷新；分享 / 下载完成自动关闭 |
| `showDialogActionsMv(request: MvActionsRequest): DialogHandle?` | `DialogActionsMv` | 展示 `MvService.currentMv`；发布行受 `enableWorkPublish`、分享行受 `enableMvDanceShare` 控制 |
| `suspend showDialogEditSong(title, cover, musicCategoryId, musicCategory): JSONObject?` | `DialogEditSong` | 返回 `{title, image, categoryId, categoryName}`，取消 null |
| `suspend showDialogEditUser(title, cover): JSONObject?` | `DialogEditUser` | 返回 `{title, image}`，取消 null |
| `showReportTagOptions(onSelect: (String) -> Unit): DialogHandle` | `ReportTagOptions` | 选中原因后回调文案并关闭（音乐详情页也可直接调用） |
| `WorksPreviewUnlock.show(pager, mvId, needPoint, onPaid?)` / `suspend unlock(...)` | `MvPreviewUnlockFlow.show / unlock` | 弹解锁窗 → 登录 → 积分够扣费（继续生成接口，同一 MV 防重入），不够按会员态去积分包 / 订阅页；扣费成功刷新余额后回调 `onPaid` |

用法：`ctx.launch { val r = ctx.showDialogEditUser(user.nickname, user.avatar) ?: return@launch; UserStore.updateUserInfo(r.optString("title"), r.optString("image")) }`

## 4. 钩子安装（`WorksUiInstaller.install()`）

`WorksUi.songActionsSheet / editSongSheet / mvActionsSheet / reportOptionsSheet / previewUnlock`（用当前页面弹 `WorksPreviewUnlock.show`）、
`AccountHooks.showUsageStatement`（→ UI Kit `showUsageStatement`）、`SDLinks.opener`（应用内浏览器打开协议链接，与 `UserStore.openTerms` 一致）。

## 4b. 页面级镜像 `WorksState`

`WorksState.of(pager)`：`currentSongId hasCurrentSong currentTitle currentCover isPlaying processingState progress`（`progress` 需先 `watchPosition()`）、
`musicSharing musicDownloading mvSharing mvDownloading`、`isLogin isSubscribed avatar points`、`recallRemainingSeconds recallTimerText remainingSeconds timerText discountActive`、
`actionSong currentMv`，方法 `playIcon(songId, bufferingAsLoading)`。全部是响应式字段。
互动数据：`songStats(song)` `mvStats(mv)` `videoStats(v)`（按 id 的响应式读取）、`latestSong / latestMv / latestVideo`（合上覆盖值），换账号时自动清空。

---

# 选择弹窗 / 裁剪 / 引导 / 上传组件（slice UIB-WORKS-B）

包：`ui.works.select` / `ui.works.cropper` / `ui.works.guide` / `ui.works.upload`。演示页：`nav.push("select_demo")`（`ui/works/select/demo/SelectDemoPage.kt`）。

## 5. 选曲弹窗（`ui.works.select`，Dart `common_dialog/music_select/**`）

| Kotlin | Dart | 签名 / 说明 |
|---|---|---|
| `enum MusicSelectTab { my, upload, trending }` | `MusicSelectTab` | 下标 = `StorageKey.musicSelectLastTab` 存的值 |
| `sealed class MusicSelectResult { Song(song) / Upload(item: AudioUploadItem) / TryNow }` | `show()` 的 dynamic 结果（`SongModel` / `AudioUploadItem` / `1`） | 取消 = `null` |
| `BasePager.showMusicSelect(type: String, initialTab: MusicSelectTab? = null, onSelected: (MusicSelectResult?) -> Unit): DialogHandle` | `MusicSelectBottomSheet.show(type:, initialTab:)` | 屏高 60% 弹层；未登录固定热门且无 Tab 栏；已登录 `initialTab ?: 上次 Tab ?: trending`；打开时暂停主播放器，关闭时停试听；同一时刻只有一个（再次调用直接回调 null） |
| `suspend BasePager.musicSelect(type, initialTab): MusicSelectResult?` | 同上 `await` | 在 `ctx.launch { }` 里用 |

```kotlin
ctx.launch {
    when (val r = ctx.musicSelect("mv")) {
        is MusicSelectResult.Song -> ctx.onSongPicked(r.song)          // 选中 / 秒传
        is MusicSelectResult.Upload -> ctx.trackUpload(r.item.uploadId) // 跟 Events.UPLOAD_TASKS_CHANGED
        MusicSelectResult.TryNow -> ctx.nav.push(Routes.musicCreate)
        null -> {}
    }
}
```

## 6. 选图弹窗（`ui.works.select`，Dart `common_dialog/photo_select/**`）

| Kotlin | Dart | 签名 / 说明 |
|---|---|---|
| `enum PhotoSelectSectionType { recommend, usageRecord }` / `enum PhotoSelectAction { camera, album, itemSelected, roleLibrary }` / `enum PhotoSelectItemType { usageRecord, recommend }` | 同名 | |
| `data class PhotoSelectItem(type, imageUrl, role: RoleModel?)` + `PhotoSelectItem.fromRole(role)` / `fromRecommend(url)` | `PhotoSelectItem` | |
| `data class PhotoSelectResult(action, imageSource: String?, selectedItem)` | `PhotoSelectResult` | `imageSource` = `CommonUtils.SOURCE_CAMERA/SOURCE_GALLERY`（直接传给 `CommonUtils.pickImage`） |
| `BasePager.showPhotoSelect(sectionType, items, tip: String? = null, title: String? = null, onSelected: (PhotoSelectResult?) -> Unit): DialogHandle` | `PhotoSelectBottomSheet.show(...)` | 与 Dart 一样**只回报选择**：选图 / 权限 / 上传检测（`CommonUtils.pickImage` + `FileUploadApi.uploadImageWithCheck`）由调用方做 |
| `suspend BasePager.photoSelect(sectionType, items, tip, title): PhotoSelectResult?` | 同上 | 回调在弹层完全关闭后才到（可立即再开登录 / 系统相册） |

```kotlin
val items = MvService.roleList.filter { it.roleImg.startsWith("http") }.map { PhotoSelectItem.fromRole(it) } +
    recommendUrls.map { PhotoSelectItem.fromRecommend(it) }
val r = ctx.photoSelect(PhotoSelectSectionType.usageRecord, items, tip = "upload_singer_photos_tip".tr, title = "upload_singer_photos".tr)
when (r?.action) {
    PhotoSelectAction.camera, PhotoSelectAction.album -> ctx.pickImage(r.imageSource!!)
    PhotoSelectAction.itemSelected -> r.selectedItem!!.let { if (it.type == PhotoSelectItemType.recommend) ctx.selectPreset(it.imageUrl) else ctx.selectRole(it.role!!) }
    PhotoSelectAction.roleLibrary -> ctx.openRoleLibrary()
    null -> {}
}
```

## 7. 音频裁剪 + 波形（`ui.works.cropper`，Dart `ui_core/audio_cropper.dart` / `audio_waveform.dart`）

| Kotlin | Dart | 属性 / 事件 |
|---|---|---|
| `SDAudioCropper { }` | `AudioCropper` | attr：`url` `fileName` `durationMs: Long?` `defaultSelectionSeconds`(0) `initialSelectionScope`("mm:ss,mm:ss") `musicCoverUrl` `isCropGuideActive`(obs) `isPauseChooseSong`(obs，上升沿暂停试听)。event：`onChange { start, end, startMs, endMs -> }`（加载完成及每次拖动）`onClear { }` `onDurationLoaded { }`。view（经 `ref`）：`isEligibleForCropGuide` `buildCropGuideHandlesConfig(onDragEnd)` `waveformRef`（引导锚点）。最短 30 s；内部用 `AppAudioPreviewCoordinator` 循环试听选区；**`url` 只在构建时读一次，换歌用 `vbind({ song.id to song.musicAudioUrl }) { SDAudioCropper { … } }`**（= Dart `ValueKey`） |
| `SDAudioWaveform { }` | `AudioWaveform` | attr：`audioPath`(obs，本地文件) `durationMs` `waveColor`(#797A7A) `strokeWidth`(2) `pixelsPerStep`(4)；尺寸写在根上。数据来自 `SDMediaProcessModule.extractWaveform`，失败 / unsupported 时画确定性的「歌曲形」占位波形 |
| `BasePager.showAudioCropper(url, fileName, musicCoverUrl?, durationMs?, defaultSelectionSeconds, initialSelectionScope, title, confirmText?, onResult: (AudioCropResult?) -> Unit): DialogHandle` / `suspend audioCrop(...)` | —（弹层封装，参考工程没有） | `AudioCropResult(startMs, endMs, start, end)`，`scope` = `"start,end"` |

```kotlin
vbind({ ctx.song?.id to ctx.song?.musicAudioUrl }) {
    val s = ctx.song ?: return@vbind
    SDAudioCropper {
        attr { url = s.musicAudioUrl ?: ""; fileName = s.musicTitle ?: ""; musicCoverUrl = s.musicCoverUrl
               defaultSelectionSeconds = ctx.defaultSec; initialSelectionScope = ctx.scope; isPauseChooseSong = !ctx.isPageVisibleObs }
        event { onChange { st, en, sMs, eMs -> ctx.scope = "$st,$en"; ctx.subDuration = ((eMs - sMs) / 1000).toInt() }
                onClear { ctx.clearSong() } }
    }
}
```

## 8. 引导浮层（`ui.works.guide`，Dart `guide_v3/**`；v1 / v2 无引用未移植）

| Kotlin | Dart | 说明 |
|---|---|---|
| `GuideStep(id, target: () -> AbstractBaseView<*, *>?, title, description, direction = bottom, margin, focusPadding = 8, focusRadius = 14, allowTapOverlayNext = true, customContentBuilder: (ViewContainer<*,*>.(onClose) -> Unit)? = null)` | `GuideStep`（`targetKey: GlobalKey` → `target`：返回目标视图，如 `{ ctx.editRef.view }`） | |
| `GuideTipDirection { top, bottom, left, right }` | 同名 | |
| `GuideStepController(steps)`：`currentIndex isShowing total isFirstStep isLastStep currentStep`、`show next prev jumpTo finish close reset` | 同名 | |
| `GuideManager.show(pager, controller, onFinish?, onClose?, onStepChanged?, initialIndex = 0, cropHandles: GuideCropHandlesConfig? = null)`、`isShowing` `next()` `prev()` `close()`(触发 onClose) `hide()`(静默) `repaint()` | `GuideManager` | 遮罩黑 88% 挖孔；点高亮区总是下一步，点遮罩仅 `allowTapOverlayNext` 时；返回键 = close。「已展示」标记由调用方在 onFinish / onClose 里写（同 Dart） |
| `GuideCropHandlesConfig(getStartPercent, getEndPercent, onStartDragUpdate, onEndDragUpdate, onSelectionDragUpdate?, onDragEnd?, handleWidth = 14, trackHeight = 45, …)` | 同名 | 由 `SDAudioCropperView.buildCropGuideHandlesConfig` 生成 |

```kotlin
// 目标：View { ref { ctx.editRef = it } … }
val c = GuideStepController(listOf(GuideStep("playback_edit", { ctx.editRef.view }, "", "mv_playback_edit_guide_title".tr,
    direction = GuideTipDirection.bottom, allowTapOverlayNext = false)))
GuideManager.show(ctx, c, onFinish = { Sp.setBool(StorageKey.isMvPlaybackEditGuideShown, true); ctx.openEditor() },
    onClose = { Sp.setBool(StorageKey.isMvPlaybackEditGuideShown, true); ctx.resume() })
// 离页：override fun pageWillDestroy() { if (GuideManager.isShowing) GuideManager.hide(); super.pageWillDestroy() }
```

## 9. 上传 / 标签（`ui.works.upload`）

| Kotlin | Dart | 属性 / 事件 |
|---|---|---|
| `SDCreatorPhotoUploadSection { }` | `CreatorPhotoUploadSection` | attr（均 obs）：`imageUrl` `imageFile: String?`(本地路径) `role: RoleModel` `isImageLoading` `isImageFailed` `isHasSensitive` `errorMessage` `imageProgress`(0~100) `hasError`；常量：`showSectionTitle`(true) `sectionTitleKey`("upload") `emptyLabelKey`("upload") `cardFill`。event：`onUploadTap { }` `onClearImage { }` `onRetryUpload { }` |
| `BasePager.showDialogShotTag(onSelect: (String) -> Unit): DialogHandle` | `Get.bottomSheet(DialogShotTag(onSelect:, tagList: mvService.mvTagList, tagMap: mvService.mvTagMap))` | 标签表直接读 `MvService`（`MV_DICT_CHANGED{kind:"tags"}` 刷新）；点卡片回调 `remark ?: value` 并关闭 |
| `ShotTagTextController.insertTag(text, tag, cursor = -1): Pair<String, Int>` / `adjustForTagDeletion(old, new): Pair<String, Int>?` / `highlight(text): List<Pair<String, Boolean>>` / `tagColor` | `_insertShotTag`（edit_video / retry_shots）+ `ShotTagTextController` | 返回 (新文本, 光标)；Kuikly 输入框不能着色，`highlight` 给 RichText 用 |

```kotlin
ctx.showDialogShotTag { tag -> val (t, _) = ShotTagTextController.insertTag(ctx.desc, tag); ctx.descInput.setText(t); ctx.desc = t }
```
