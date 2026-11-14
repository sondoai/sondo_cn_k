# 视频组件（`com.sondo.app.ui.video`）

参考工程 `lib/shared/widgets/video_player/**` + `ui_core/video_widget.dart` / `list_video_widget.dart` /
`inline_preview_video_card.dart` / `video_progress_widget.dart` / `video_playback_gate.dart` 的移植。
全部建立在原生视图 `SDVideo`（`nativeapi/SDNativeViews.kt`）+ `VideoCacheService` + `AppAudioOutputCoordinator` 之上。

演示页：`nav.push("video_demo")`（`ui/video/demo/VideoDemoPage.kt`）。

## 0. 先读：和 Flutter 的差别

- **解码器属于原生视图，不属于控制器。** `AppVideoController` 只是状态 + 命令；组件挂载 `SDVideo` 时才有解码器，
  卸载即释放（`deferInitUntilActive = true` 时不活跃 / 被门闩挡住就卸载）。再挂载时自动从上次位置（或 `seekOnReadyMs` / 续播位置）继续。
  所以 Dart 里的 `VideoPlayerControllerFactory.create/fromRaw/placeholder` + `initialize()` 统一改成
  `AppVideoController(this, url)` / `controller.setSource(url)`，不需要 `initialize`，也不用手动 `dispose` 旧解码器。
- **缓存**：`SDVideo` 默认走原生磁盘缓存（≤20MB 落盘，300 个 / 69 天）。邻页预热用 `VideoCacheService.prefetchInBackground(url)` 或 `AppVideoPrefetch`。
- **有声互斥**：控制器构造时就 `attach` 到 `AppAudioOutputCoordinator`，`play()` / 取消静音 / 调大音量前 `claim`，暂停 / 静音 / dispose 时 `release`。页面不用再管。
- **页面不可见 / 后台**：播放器组件自己监听 `pageDidAppear/Disappear`（Pager 事件）和 `Events.APP_LIFECYCLE`：
  不可见或后台时暂停，回来时只恢复之前在播的（`pauseOnPageHidden` / `pauseOnLifecycleChange` / `resumeAfterLifecycle` 可关）。
  只用控制器、不用组件的页面要自己在 `pageDidDisappear()` 里 `pause()`。
- **Dart `Duration`** → `Long` 毫秒；`VideoPlayerValue` 字段 → 控制器上的响应式属性。
- 设备 Surface 类型（华为 / 荣耀 SurfaceView）、系统音频焦点、耳机拔出、MediaCodec 串行释放（`VideoCodecLifecycle`）都在原生侧，不移植。

## 1. 控制器 `AppVideoController`（Dart `VideoPlayerController` + `AppVideoPlayerState` 命令式 API）

```kotlin
class MyPage : BasePager() {
    private val player = AppVideoController(this, url = "", looping = true, mixWithOthers = false)
    override fun pageWillDestroy() { player.dispose(); super.pageWillDestroy() }
    fun bind(url: String) = player.setSource(url, autoPlay = true)
}
// 渲染
SDAppVideoPlayer { attr { absolutePositionAllZero(); controller = ctx.player; displayMode = AppVideoDisplayMode.immersive; controlsMode = AppVideoControlsMode.none } }
// 页面里读状态（响应式）
Text { attr { text(formatVideoDuration(ctx.player.positionMs)) } }
```

| 成员 | 说明 |
|---|---|
| 构造 `AppVideoController(scope: PagerScope, url = "", looping = false, muted = false, volume = 1f, mixWithOthers = true, resumeKey = null, resumeStore = InMemoryAppVideoResumeStore, resumeSaveIntervalMs = 1000, keepScreenOn = true)` | `scope` = 页面 / ComposeView |
| 响应式只读：`url` `state`（`SDVideoState.*`）`positionMs` `durationMs` `bufferedMs` `videoWidth` `videoHeight` `firstFrameRendered` `errorDescription` `volume` `muted` `speed` `looping` `playIntent` `generation` `hasDecoder` | |
| 派生：`isInitialized` `isPlaying` `isBuffering` `isCompleted` `hasError` `isAudible` `aspectRatio` `progress`(0..1) `isDisposed` | Dart `value.isInitialized/isPlaying/isBuffering/isCompleted/hasError/aspectRatio` |
| `play()` `pause()` `togglePlayback()` `stop()` | 播完后 `play()` 从头开始 |
| `seekTo(ms)` `seekBy(deltaMs)` | 夹在 0..duration；未就绪先记下 |
| `setVolume(0..1)` `setMuted(b)` `toggleMute()` `setPlaybackSpeed(s)`（= `setSpeed`，0.25..4）`setLooping(b)` | |
| `setSource(raw, autoPlay = playIntent)` | 换源（保存旧源续播位置） |
| `retry()` | 重建解码器（保留位置） |
| `dispose()` | 页面销毁时调用 |
| `addListener(AppVideoControllerListener): () -> Unit` | 回调 `onStateChanged` `onProgress` `onFirstFrame` `onCompleted` `onError` `onReady`（替代 Dart `addListener(_onTick)`） |
| `pendingSeekMs` | 下次就绪时 seek 的位置 |

## 2. 播放器组件

| Kotlin | Dart | 说明 |
|---|---|---|
| `SDAppVideoPlayer { }` | `AppVideoPlayer` | 通用播放器（默认 inline、contain、完整控制条、不自动播放） |
| `SDFeedVideoPlayer { }` | `FeedVideoPlayer` | 预设 `feedDefaults()`：cover、immersive、无按钮、带进度、autoPlay、loop、无双击、`deferInitUntilActive` |
| `SDFeedBannerVideoPlayer { }` | `FeedVideoPlayer.banner` | 预设 `bannerDefaults()`：静音循环背景，无状态层，`pauseOnLifecycleChange = false` |
| `SDMvVideoPlayer { }` | `MvVideoPlayer` | 预设 `mvDefaults()`：contain、immersive、无按钮、带进度、loop、独占音频 |

**attr**（obs = 响应式，可在 attr 里用页面 observable 赋值）：

- 源：`controller: AppVideoController?`(obs，页面持有，组件不释放；切换时回调 `onControllerReleased`) / `url`(obs，组件自建控制器并负责释放)。两者都给时 controller 优先。
- 封面：`coverUrl`(obs) `cover: ViewBuilder?`(优先) `showCover` `coverFadeMs`(180) `coverRevealTimeoutMs`(3000) `coverRevealSettleMs`(200)。封面在**开始播放且首帧上屏**后再淡出。
- 布局：`displayMode`(`AppVideoDisplayMode.inline/immersive/thumbnail/background`) `aspectRatio`(0=视频比例，未知 16:9) `sizeByAspectRatio`(true：inline/thumbnail 按宽度算高度；自己给了高度时设 false) `fit`(obs, `SDFit`) `radius` `backgroundColor`。尺寸 / 定位直接写在 attr 上。
- 控制层：`controlsMode`(`full`=播放+时间+静音+倍速+全屏 / `minimal` / `none`) `labels` `showCenterPlayButton`(obs) `autoHideControls` `controlsHideDelayMs`(3000) `playbackSpeeds` `seekStepMs`(10000) `tapToTogglePlayback`(true) `enableDoubleTap`(true)。
- 状态层：`showLoading` `showStatusOverlays`(总开关) `showBufferingIndicator`。
- 进度条：`showProgress`(obs) `progressPadding: FloatArray(上,左,下,右)` `progressHeight`(3) `allowScrubbing` `showScrubTime`。
- 播放：`autoPlay` `looping`(obs) `muted`(obs) `volume`(obs) `seekOnReadyMs`(obs，-1=无) `resumeKey` `resumeStore` `mixWithOthers`(null=场景默认) `profile`。
- 系统：`keepScreenOn`(true，经 `AppVideoWakelock` 引用计数) `pauseOnLifecycleChange`(true) `resumeAfterLifecycle`(true) `pauseOnPageHidden`(true) `disposeExternalController`(false)。
- 重试：`maxInitializationRetries`(3) `retryBaseDelayMs`(500) `retryStrategy`（`AppVideoLinearRetryStrategy` / `AppVideoExponentialRetryStrategy`）。
- 活跃度：`isActive`(obs) `deferInitUntilActive` `forcePaused`(obs，暂停但保留帧) `playbackGate`（列表 / Banner 绑定 `AppVideoPlaybackGate.instance`） `bypassPlaybackGate`。
- 错误 / 叠加：`error: String?`(obs，业务错误) `errorBuilder: (ViewContainer.(message, retry) -> Unit)?` `overlayBuilder: (ViewContainer.(controller) -> Unit)?`。

**event**：`onTap { }`（业务接管单击）`onDoubleTap { }` `onFullscreen { }`（设置后显示全屏按钮）`onRetry { }` `onReady { controller -> }`
`onInitialized { aspectRatio, resolution, durationSeconds -> }` `onCompleted { controller -> }` `onControllerReleased { controller -> }`
`onError { message -> }` `onPlayingChanged { playing -> }` `onProgress { positionMs, durationMs -> }` `onSeekApplied { }` `onFirstFrame { }`。

**方法**（经 `ref`）：`controller`（当前控制器）`retry()`。

单击优先级：`onTap` > background 模式不响应 > `tapToTogglePlayback` 切换播放（并显示控制层，播放中 3 秒后隐藏）> 切换控制层显隐。双击：`onDoubleTap` 或快进 `seekStepMs`。

```kotlin
// 详情 / 独立播放
SDAppVideoPlayer { attr { url = ctx.videoUrl; coverUrl = ctx.cover; aspectRatio = 16f / 9f } }
// 首页 Banner（静音循环、只有当前可见页持有解码器）
SDFeedBannerVideoPlayer { attr { size(w, h); url = item.video; coverUrl = item.poster
    isActive = ctx.bannerIndex == i && ctx.tabActive; playbackGate = AppVideoPlaybackGate.instance } }
// 封面卡片，点击后播放
SDAppVideoPlayer { attr { size(w, w * 4f / 3f); sizeByAspectRatio = false; url = u; coverUrl = c
    displayMode = AppVideoDisplayMode.thumbnail; controlsMode = AppVideoControlsMode.none; showProgress = false } }
// 页面持有控制器的竖滑 MV（只给当前页挂播放器；其它页显示封面）
SDMvVideoPlayer { attr { absolutePositionAllZero(); controller = ctx.player; coverUrl = mv.cover }
    event { onTap { ctx.toggle() } } }
```

全屏 / 沉浸播放页进入时 `AppVideoPlaybackGate.instance.acquire()`，离开 `release()`，让绑定了门闩的 Banner / 列表释放解码器；
本页自己的播放器不要绑同一把门闩（或 `bypassPlaybackGate = true`）。

## 3. 旧组件（参数名照 Dart）

| Kotlin | Dart | attr / event |
|---|---|---|
| `SDVideoWidget { }` | `VideoWidget` | attr：`url`(obs) `controller`(obs) `coverUrl`(obs) `isLopper`(obs,true) `isFill`(obs,true=cover) `needLoading` `showStatusOverlays` `showCenterPlayButton`(obs) `showBottomControls` `volume: Float?`(obs，0=静音) `containerWidth/containerHeight` `bgColor` `isActive`(obs，同时=autoPlay) `deferInitUntilActive` `forcePaused`(obs) `playbackGate`(null=共享门闩) `bypassPlaybackGate` `radius` `enableDoubleTap`。event：`onInitialized { ratio, resolution, seconds -> }` `onPlayCompleted { }` `initCompleted { controller -> }` `onControllerReleased { }` `onError { }` `onTap { }`；扩展 `onProgress { pos, dur -> }` `onPlayingChanged { }`。方法：`controller`。固定 mixWithOthers = true |
| `SDListMvVideoPlayer { }` | `ListMvVideoPlayer` | attr：`mv: SquareModel`(obs) `index` `isCurrentPage`(obs) `initialPositionMs`(-1) `showProgress` `isPreloading`(obs) `isScrolling`(obs) `bypassPlaybackGate` `isLopper` `forcePaused`(obs) `showAiLabel`。event：`onControllerReady { index, controller -> }` `onDisposed { index -> }` `onProgress { index, pos, dur -> }` `onPausedChanged { index, isPaused -> }` `onInitialPositionApplied { }` `onPlayCompleted { }`。Dart 的 `pageId` 路由检查 = 播放器的页面可见性（自动）；页内弹层盖住时传 `forcePaused` |
| `SDInlinePreviewVideoCard { }` | `InlinePreviewVideoCard` | attr：`url`(obs) `coverUrl`(obs) `containerWidth` `containerHeight` `radius` `visibilityKey` `isVisible`(obs，见下) `onOpenFullscreen: suspend (positionMs: Long?) -> VideoPreviewDismissResult?`。滚动列表里由页面用 `inlineVisibleFraction(itemTop, itemH, viewportTop, viewportH) >= SDInlinePreviewVideoCardAttr.VISIBILITY_THRESHOLD`(0.85) 赋值 `isVisible` |
| `SDVideoProgressWidget { }` | `VideoProgressWidget` | 就是 UI Kit 的 `SDSeekBar`（ms 值，`onSeekStart/onSeek/onSeekEnd`） |
| `SDAppVideoProgressBar { }` | `AppVideoProgressBar` | attr：`controller`(obs) `allowScrubbing` `showScrubTime` `barHeight`(4) `hitTestHeight`(32) `playedColor` `bufferedColor` `backgroundColor` `bufferingColor` `paddingH`。event：`onScrubStart` `onScrubEnd` `onSeekTo { ms -> }`（替代默认 seek）。拖动时暂停、松手 seek 一次并恢复 |
| `SDVideoPlayerList { }` | `VideoPlayerList` | 整页竖滑视频列表（只有当前页持有解码器），见 §4 |
| `AppVideoPlaybackGate.instance` / `VideoPlaybackGate` | 同名 | `acquire()` `release()` `isBlocked` `addListener { count -> }: () -> Unit` |
| `AppVideoControllerPool(scope, maxControllers = 2)` | `AppVideoControllerPool` | `acquire(key, url): AppVideoController` `prefetch(key, url)` `release(key)` `evictIdle()` `dispose()`，见 §5 |
| `AppVideoSource.parse(raw)` / `isNetwork(raw)` | `AppVideoSource.parse` | 规整成 SDVideo 的 src |
| `AppVideoPlaybackPolicy(...)` | 同名 | 纯逻辑：`isEffectivelyActive` `canInitialize` `canPlay` `shouldReleaseOwnedDecoder` |
| `InMemoryAppVideoResumeStore` / `AppVideoResumeStore` / `AppVideoResumePosition` | 同名 | 续播位置（进程内） |
| `AppVideoPlaybackProfile.feed / mv / default` | `AppVideoPlaybackProfile` | 场景重试策略 + mixWithOthers |
| `formatVideoDuration(ms)` | `_formatDuration` | `m:ss` / `h:mm:ss` |
| `VideoIcon(kind, size, color)` | Material `Icons.play_arrow_rounded / pause / refresh / error_outline / volume_up / volume_off / speed / fullscreen` | Canvas 画的图标 |

`VideoPlayerControllerFactory` / `AppVideoControllerFactory` → 直接 `AppVideoController(this, url, mixWithOthers = …)`；
`createPlaceholder()` → `AppVideoController(this)`（空 url，只显示封面），URL 到了再 `setSource(url)`。

## 4. `SDVideoPlayerList`（Dart `VideoPlayerList`）

```kotlin
SDVideoPlayerList {
    attr { flex(1f); items = ctx.items /* List<VideoPlayerListItem> */; initialIndex = ctx.start
        isActive = ctx.listVisible; forcePaused = ctx.loginOpen; hasMore = ctx.hasMore
        overlayBuilder = { index, isCurrent -> MyInfoOverlay(ctx.items[index]) } }
    event { onPageChanged { ctx.current = it }; onEndReached { done -> ctx.loadMore(done) }; onDoubleTap { ctx.like(it) } }
}
```

attr：`items: List<VideoPlayerListItem>`(obs；`VideoPlayerListItem(id, url, coverUrl, aspectRatio, startPositionMs, resumeKey)`) `initialIndex`
`isActive`(obs) `forcePaused`(obs) `playbackGate` `bypassPlaybackGate` `fit` `looping` `muted` `mixWithOthers` `showProgress`
`tapToTogglePlayback` `allowScrubbing` `progressPadding` `preloadDistance`(1) `endReachedThreshold`(2) `hasMore`(obs) `emptyBuilder`
`errorBuilder: (ViewContainer.(index, message, retry) -> Unit)?` `overlayBuilder: (ViewContainer.(index, isCurrent) -> Unit)?`。
event：`onPageChanged { i }` `onEndReached { done -> }`（加载结束后调用 `done()`，之前不重复触发；调用方更新 items） `onReady { i, controller }`
`onProgress { i, pos, dur }` `onCompleted { i }` `onPlayingChanged { i, playing }` `onDoubleTap { i }`（不设则单击立即响应）。
方法：`scrollToIndex(i, animated)` `currentIndex`。

## 5. `AppVideoControllerPool` / 预热

按 key 复用控制器（当前页租约 +1，释放后暂停并可被 LRU 淘汰）；`prefetch` 只预热磁盘缓存（解码器由挂载的视图持有）。
`AppVideoPrefetch.neighbours(urls, index, distance = 1)` 预热前后邻居；`AppVideoPrefetch.prefetch(url)`。
