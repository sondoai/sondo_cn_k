# Sondo UI Kit（`com.sondo.app.ui`）

通用 UI 组件目录。组件名 = `SD` + 参考工程（`sondo_cn/lib/shared/widgets`）里的 Dart 类名，参数名尽量与 Dart 一致，
方便照着 Dart 调用处逐行改写。全部是纯 UI：不依赖 models / services，数据和回调由调用方传入。

演示页：`nav.push("ui_kit_demo")`（`ui/demo/UiKitDemoPage.kt`，每个组件至少用了一次，可当用法范例查）。

## 0. 通用约定（先读这一节）

**组件怎么写。** 组件都是 `ComposeView` + 声明式构建函数：

```kotlin
SDButton {
    attr { text = "submit".tr; isLoading = ctx.submitting; marginTop(12f) }   // 属性：字段赋值；布局照常调方法
    event { onTap { ctx.submit() } }                                          // 事件：SDXxxEvent 上的方法
}
```

- **响应式**：表里标了「obs」的属性是响应式字段——在 `attr { }` 里用页面的 `observable` 给它赋值，页面状态变了组件自动更新。
  没标的属性只在构建时读一次（样式类常量）。
- **受控组件**：开关 / 勾选 / Tab / 分段 / 进度等都是「受控」的（同 Flutter）：组件不自己改值，只回调 `onChanged`，由页面改自己的字段。
- **布局属性作用在组件根节点上**：`flex(1f)`、`margin…`、`size(w, h)`、`absolutePosition(...)` 直接写在组件的 `attr { }` 里。
  例外已在表里注明（`SDButton` 的 `height()/width()` 作用于胶囊，`SDInput` 的 `height()` 作用于输入框）。
- **插槽**：需要传子内容的组件用 `content { }` / `items { }` / `header { }` 这类方法（写在 `attr`、`event` 旁边），或 `ViewBuilder` 类型的属性。
- **弹窗**：全部是 `BasePager` 的扩展函数，返回 `DialogHandle`（`handle.dismiss()` 关闭）。Dart 的三套机制
  （`SmartDialog.show` / `Get.dialog` / `Get.bottomSheet`）统一成这一套；`SmartDialog.dismiss()` / `Get.back()` → `handle.dismiss()`。
  需要结果的提供了 `suspend` 版本（在 `ctx.launch { }` 里调用）。
- **文案**：组件内部文案都走 `"key".tr`；需要自定义文案的参数由调用方传 `.tr` 后的字符串。

**Dart 小工具 → Kuikly 写法对照**

| Dart | Kuikly |
|---|---|
| `SizedBox(height: 12)` / `12.verticalSpace` | `VGap(12f)` |
| `SizedBox(width: 8)` / `8.horizontalSpace` | `HGap(8f)` |
| `Spacer()` / `Expanded(child: SizedBox())` | `Spacer()` |
| `widget.tap(() {…})` / `GestureDetector(onTap:)` | 在该节点 `event { click { … } }` |
| `.pdAll(8)` / `.pdSymmetric(h:, v:)` / `.pdOnly(...)` | 外层容器 `attr { padding(top, left, bottom, right) }`（注意顺序是 **上、左、下、右**） |
| `.expanded` / `Expanded` | `attr { flex(1f) }` |
| `.center` / `Center` | 父容器 `attr { allCenter() }` |
| `.visible(cond)` | `vif({ cond }) { … }`（要占位用 `attr { visibility(cond) }`） |
| `.position(t:, r:)` / `Positioned` | `attr { absolutePosition(top = , right = ) }` |
| `.safeArea` / `SafeArea` | `SDSafeArea { }`，或 `SafeTop()` / `SafeBottom()` |
| `[a, b].toRow()` / `Row` | `View { attr { flexDirectionRow(); alignItemsCenter() } … }` |
| `Stack` | 普通 `View`，子节点用 `absolutePosition` / `absolutePositionAllZero()` |
| `ScrollOverWidget(child: list)` | List / Scroller 的 `attr { scrollOver() }` |
| `KeepAliveWrapper` | `PageList { attr { keepItemAlive(true) } }` |
| `UnfocusWidget` | `SDUnfocus { }`，或 `SDPageScaffold { attr { unfocusOnTap = true } }` |
| `CommonUtils.hideKeyboard()` | 任意视图 / 页面上 `hideKeyboard()` |
| `SmartDialog.showToast(msg)` | `ctx.toast(msg)`（样式、1200ms 防抖已与参考工程一致） |
| `LoadingUtil.show(text:)` / `close()` | `ctx.showLoading(text)` / `ctx.hideLoading()`；`showLoadingDialog(text, clickMaskDismiss)` / `closeLoading()` |

**Kuikly 踩坑备忘（写页面时也适用）**

1. 构建辅助函数（`private fun ViewContainer<*, *>.xxx()`）必须是**顶层函数**。写成 Pager / ComposeView 的成员扩展，在嵌套闭包里调用会报
   `cannot be called in this context with an implicit receiver`（DSL 作用域限制）。成员函数要么接收 `container` 参数并用 `container.View { }` / `with(container) { }`，要么通过 `ctx.` 显式调用。
2. 声明式动画 `animate(anim, ctx.flag)`：`flag` 变化时，**同一个 attr 块里写在它前面的属性**变化带动画；正向、反向都会播。
   实际生效的是**上一次执行时注册的那个 Animation**（所以「进场用 A、退场用 B」要写成 `animate(if (state == 进场前) A else B, ctx.state)`）。
   需要「瞬间复位再重播」「播完回调」用命令式 `viewRef.view?.animateToAttr(anim, completion) { … }`。
3. `repeatForever(true)` 是从起点到终点反复**重播**，不是往返。往返动画用定时器翻转状态（见 `SDBreathing`）。
4. 属性「值没变就不下发」：`attr { text(ctx.x) }` 不能把输入框里用户改过的内容「再设回同一个值」。输入类组件用 `setText()`。
5. `RichText` 只保留最后一个 `attr { }` 块（多写会互相覆盖）；`Text` / `View` 可以写多个 attr 块（静态的和响应式的分开写很有用）。
6. 设了 `borderRadius` 的容器会裁剪子节点：需要「探出父容器」的元素要放到外面一层没有圆角的容器里。
7. 单行文字想拿到自然宽度（不被父宽度截断），要放进一个足够宽的绝对定位横向容器里再量（见 `SDMarqueeText`）。
8. `vforIndex` 的 index 在增删元素后不会刷新旧行；依赖 index 的样式（网格间距）在列表会增删时要注意。
9. 项目用的 Kuikly 是 **2.30.0**，`scratchpad/ref/KuiklyUI` 是更新的主干：个别 API 2.30 没有（`keyboardTypePhone/Decimal`、`TextArea.fontFamily`…），以编译结果为准。
10. Kuikly 的 `Scroller` / `List` 需要确定的高度（`flex(1f)` 或 `height()`）；放在「高度由内容决定」的弹层里时要给固定高度或 `maxHeight`。

---

## 1. 主题（`ui/SDTheme.kt`）

| Kotlin | 替代的 Dart | 说明 |
|---|---|---|
| `SDColors` | `CommonDialogColors` + 各处重复的颜色字面量 | `sheetBg #1C1C1C`、`barrier`(黑70%)、`mask50`、`primaryBtn #F3EFF7`、`primaryBtnText #0A0A0A`、`secondaryBtn`(白20%)、`brandPurple #7F58F7`、`accentPurple #9B64FF`、`tagPurple`、`progressPurple`、`linkPurple`、`gradientStart/End`、`errorRed`、`white100…white5` |
| `SDFont` | `FontFamily` | `poppins` / `anton` / `bangers` / `montserrat` |
| `SDWeight` | `FontWeight.w300…w900` | `TextAttr.weight(SDWeight?)` |
| `SDTextAlign` / `SDFit` | `TextAlign` / `BoxFit` | `START/CENTER/END`；`COVER/CONTAIN/FILL` |
| `TextAttr.textStyle(size, color, weight, height, maxLines, fontFamily, textAlign, letterSpacing)` | `TextStyle(...)` | 写在 `attr { }` 里；`height` 是行高**倍数** |
| `view.basePager` | `Get.find` 之类 | 组件里拿所在的 `BasePager` |

`core/constants/UIColor`（`UIColor.cFFF60` 等）照旧使用，`SDColors` 只是补充。

## 2. 基础组件（`ui/atoms/`）

| Kotlin | 替代的 Dart | 属性 / 事件 | 用法 |
|---|---|---|---|
| `SDText { }` | `TextWidget` | 就是原生 `Text`，预设 **白色 / 14 / 单行（超出裁剪）**；其余用 `TextAttr` 方法：`text() fontSize() color() fontWeight600() lines(n) lineHeight() textAlignCenter() textOverFlowTail() fontFamily()` | `SDText { attr {`<br>`  text(ctx.title); fontSize(16f); fontWeight600(); lines(2)`<br>`} }` |
| `SDText(text, color, size, fontWeight, height, maxLines, textAlign, fontFamily, letterSpacing, ellipsis) { }` | `TextWidget('…', size:, fontWeight:, …)` | 静态快捷写法（**参数只求值一次，不响应**）；尾部闭包可补 `attr { margin… }` / `event { click { } }` | `SDText("no_data".tr, size = 12f,`<br>`  color = UIColor.cFFF60, maxLines = 2)`<br>`{ attr { marginTop(8f) } }` |
| `SDLinkText(spans, size, color, height, weight, linkColor, linkWeight, linkUnderline, attrs)` + `SDLinkSpan(text, onTap?)` + `splitLinkSpans(source, labels, onTap)` | `Text.rich(TextSpan(children:[… recognizer …]))` | 带可点链接的富文本；`attrs` 用来给 RichText 补属性（对齐、边距） | `SDLinkText(listOf(SDLinkSpan("同意"),`<br>`  SDLinkSpan("《协议》") { ctx.openTerms() }),`<br>`  attrs = { textAlignCenter() })` |
| `SDImage { }` | `ImageWidget` | attr：`url`(obs，http / 绝对路径 / assets 相对路径) `fit`(obs, SDFit) `placeholder` `shimmer` `placeholderColor` `fadeIn` `tintColor`(obs) `blurRadius`(obs) `errorBuilder`；尺寸、圆角写在根上。event：`loadSuccess { }` `loadFailure { }` `click { }` | `SDImage { attr {`<br>`  size(60f, 60f); borderRadius(8f); url = ctx.cover`<br>`} }` |
| `SDAssetImage(asset, width, height = width, tint) { }` | `Image.asset(AssetsImage.x, width:, height:, color:)` | 原生 `Image`，已设 src / 尺寸 / contain | `SDAssetImage(AssetsImage.close, 26f) {`<br>`  event { click { handle.dismiss() } }`<br>`}` |
| `SDAvatar { }` | `AvatarWidget` / 导航栏头像 | attr：`avatar`(obs) `size`(obs, 60) `fallbackAsset` `placeholderColor` `borderWidth` `borderColor` | `SDAvatar { attr {`<br>`  avatar = ctx.user.avatar; size = 32f`<br>`} }` |
| `SDButton { }` | `ButtonWidget` | attr：`text`(obs) `fontSize`(obs,16) `weight`(obs) `textColor`(obs) `gradientColors`(obs，默认品牌渐变) `disabled`(obs) `disabledBg` `disabledTextColor` `isLoading`(obs) `withBottomPadding` `child: ViewBuilder?` `iconAsset` `borderWidth/borderColor`；**`padding(t,l,b,r)` = Dart padding（默认 20/20/34/20）**；**`height()` `width()` `radius()` 作用于胶囊**（默认 44 / 撑满 / 22）。event：`onTap { }`（`click { }` 等价）`onDisabledTap { }` | `SDButton {`<br>`  attr { text = "submit".tr; isLoading = ctx.busy }`<br>`  event { onTap { ctx.submit() } } }` |
| `SDGradientButton { }` | `GradientButton` | 同上；默认纯色 `#F3EFF7`、字 `#0A0A0A`、禁用底 ARGB(219,188,194,212) | `SDGradientButton {`<br>`  attr { padding(0f,0f,0f,0f); height(40f); text = "ok".tr }`<br>`  event { onTap { } } }` |
| `SDPrimaryButton { }` / `SDSecondaryButton { }` | 弹窗里的 `GradientButton(padding: zero, …)` 确定 / 取消 | 同上；**无外边距**；主：`#F3EFF7`/深字，次：白 20%/白字 | `SDSecondaryButton { attr { flex(1f); height(40f); text = "cancel".tr } }`<br>`HGap(20f)`<br>`SDPrimaryButton { attr { flex(1f); height(40f); text = "confirm".tr } }` |
| `SDIconButton { }` | `IconButton(icon: Image.asset(...))` / `Image.asset(...).tap()` | attr：`icon`(obs) `iconSize`(obs,24) `color`(obs) `hitSize`(48) `bgColor`(obs) `disabled`(obs)。event：`onTap { }` | `SDIconButton {`<br>`  attr { icon = AssetsImage.share; iconSize = 20f }`<br>`  event { onTap { ctx.share() } } }` |
| `SDTag { }` | 风格 / 分类 chip（`DialogEditSong`、`DialogShotTag` 等） | attr：`text`(obs) `selected`(obs) `tagHeight`(30) `radius`(10) `paddingH` `fontSize` `weight` `bgColor/textColor`(obs) `selectedBgColor/selectedTextColor`(obs) `iconAsset` `borderWidth/borderColor`。event：`onTap { }` | `SDTag {`<br>`  attr { text = item.name; selected = ctx.cur == item.id }`<br>`  event { onTap { ctx.cur = item.id } } }` |
| `SDBadge { }` | —（未读数 / 红点 / PRO 标） | attr：`count`(obs) `text`(obs) `dot`(obs) `max`(99) `bgColor`(obs) `textColor` `fontSize` `badgeHeight` `dotSize` | `SDBadge { attr {`<br>`  absolutePosition(top = -4f, right = -4f); count = ctx.unread`<br>`} }` |
| `VGap(h)` / `HGap(w)` / `Spacer(flex)` | `SizedBox` / `Spacer` | — | `VGap(12f)` |
| `SDDivider(color, thickness, vertical, length, indent)` | `Divider` / 1px 容器 | 默认横向 0.5、白 10% | `SDDivider(indent = 16f)` |
| `SafeTop(extra)` / `SafeBottom(extra, min)` / `SDSafeArea(top, bottom) { }` | `SafeArea` | 状态栏 / 底部安全区占位；`SDSafeArea` 默认 `flex(1f)` | `SDSafeArea(top = false) {`<br>`  …`<br>`}` |
| `SDDashedLine(horizontal, dashLength, dashGap, color, strokeWidth, totalLength)` | `DashedLine` | Canvas 绘制 | `SDDashedLine(color = UIColor.cFFF20)` |
| `SDSwitch { }` | `SwitchWidget` | attr：`value`(obs) `scale`(0.55) `alignment`(-1/0/1) `compact` `activeTrackColor`(obs,#7F58F7) `inactiveTrackColor`(obs,白20%) `thumbColor` `inactiveThumbColor` `disabled`(obs)。event：`onChanged { Boolean }`。默认保留 Dart 的 51×31 布局盒，`compact = true` 收缩到视觉大小 | `SDSwitch {`<br>`  attr { value = ctx.on }`<br>`  event { onChanged { ctx.on = it } } }` |
| `SDCheckbox { }` / `SDRadio { }` | —（协议勾选 / 单选） | attr：`checked`(obs) `boxSize`(18) `activeColor`(obs) `borderColor`(obs) `checkColor` `text`(obs) `label: ViewBuilder?` `disabled`(obs) `hitPadding`；`square()` 改方形。event：`onChanged { Boolean }` | `SDCheckbox {`<br>`  attr { checked = ctx.agreed; text = "…" }`<br>`  event { onChanged { ctx.agreed = it } } }` |
| `SDProgressBar { }` | 迷你播放器进度线 / 上传进度 | attr：`progress`(obs 0..1) `barHeight`(2) `trackColor`(obs) `color`(obs) `gradientColors` `radius` `animationS`(0.2) | `SDProgressBar { attr {`<br>`  flex(1f); progress = ctx.progress`<br>`} }` |
| `SDRingProgress { }` | `RingProgressPainter` | attr：`progress`(obs) `strokeWidth` `progressColor`(obs) `trackColor`(obs)；尺寸 `size(d, d)` | `SDRingProgress { attr {`<br>`  size(36f, 36f); strokeWidth = 3f; progress = ctx.p`<br>`} }` |
| `SDSeekBar { }` | `VideoProgressWidget`（可拖动进度条） | attr：`duration`(obs, ms) `position`(obs, ms) `buffered`(obs, ms) `touchHeight`(30) `paddingH` `trackHeight/activeTrackHeight`(3/4) `thumbRadius/activeThumbRadius`(0/7) `activeColor/inactiveColor/bufferedColor`(obs) `showTooltip` `alignBottom`(false，轨道在触摸区内居中，同 Dart) `seekThrottleMs`(80)。event：`onSeekStart { ms }` `onSeek { ms }`(节流) `onSeekEnd { ms }`。`view.dragging` 可读 | `SDSeekBar {`<br>`  attr { duration = ctx.dur; position = ctx.pos; buffered = ctx.buf }`<br>`  event { onSeekEnd { ctx.player.seekTo(it.toLong()) } } }` |
| `SDSlider { }` | `Slider`（取值滑块） | 同 `SDSeekBar`，预设常显滑块 / 轨道居中 / 无时间提示；`duration` = 最大值，`position` = 当前值 | `SDSlider {`<br>`  attr { duration = 1f; position = ctx.volume }`<br>`  event { onSeek { ctx.volume = it } } }` |
| `SDCoverLoadingBar { }` | `CoverLoadingBar` | attr：`active`(obs) | `SDCoverLoadingBar { attr { active = ctx.buffering } }` |
| `SDSeekBar { attr { loading = … } }` | — | `loading`(obs) `loadingColor`(obs)：在进度条**同一条轨道**里扫光表示加载 / 缓冲，宽高位置与进度完全一致；视频播放器的加载条与进度条都用它 | `SDSeekBar { attr { loading = !coverHidden; duration = d; position = p } }` |
| `formatDurationMs(ms)` | `_formatMs` | `mm:ss` / `hh:mm:ss` | `text(formatDurationMs(ctx.pos))` |
| `SDLoading { }` | `LoadingWidget` | attr：`size`(40) `type`(SDLoadingType.IMAGE/LOTTIE) `lottieAsset` `color` | `SDLoading { attr { size = 24f } }` |
| `SDMusicLoading(text, size)` | `LoadingUtil.musicLoading(text:)` | 80×80 music_loading 动画 + 文字 | `SDMusicLoading("loading".tr)` |
| `SDShimmer { }` | `Shimmer.fromColors(child: Container)` | attr：`baseColor`(obs) `highlightColor`(obs) `radius`(obs) `animated`(obs) `periodS`；尺寸写在根上 | `SDShimmer { attr { size(140f, 20f); radius = 2f } }` |
| `SDSongSkeleton { }` | `SongSkeleton` | attr：`type` = `SDSongSkeletonAttr.TYPE_CARD / TYPE_ITEM / TYPE_MORE` | `SDSongSkeleton { attr { type = SDSongSkeletonAttr.TYPE_ITEM } }` |
| `SDEmptyState { }` | 各列表页的空态（图 80 + 文案 + 胶囊按钮） | attr：`image`(obs) `imageSize` `text`(obs，默认 `no_data`) `textColor` `fontSize` `buttonText`(obs) `gap`；默认 `flex(1f)` 居中。event：`onButtonTap { }` | `SDEmptyState {`<br>`  attr { image = AssetsImage.libraryEmpty; buttonText = "start_creating".tr }`<br>`  event { onButtonTap { ctx.goCreate() } } }` |
| `SDErrorState(message, image, onRetry)` | 失败态（文案 + 重试） | 默认文案 `request_failed` | `SDErrorState(onRetry = { ctx.reload() })` |
| `SDLoadingState(text, lottie)` | 首屏加载态 | `flex(1f)` 居中的 loading | `SDLoadingState()` |
| `SDMarqueeText { }` | `MarqueeTextWidget` | attr：`text`(obs) `color`(obs) `size` `fontWeight` `fontFamily` `velocity`(50) `startAfterMs`(2000) `pauseAfterRoundMs`(2000) `blankSpace`(40)；宽度由调用方约束 | `SDMarqueeText { attr {`<br>`  flex(1f); text = ctx.song.title; fontWeight = SDWeight.W700`<br>`} }` |
| `SDFlipCounter { }` | `AnimatedFlipCounter`（NavPoints 积分） | attr：`value`(obs Int) `fontSize`(14) `weight` `fontFamily` `color`(obs) `digitWidth` `digitHeight` `durationS`(0.6) | `SDFlipCounter { attr { value = ctx.points } }` |
| `SDOfferCountdownDigits { }` | `OfferCountdownDigits` | attr：`remainingSeconds`(obs)；计时器由调用方维护 | `SDOfferCountdownDigits { attr {`<br>`  remainingSeconds = ctx.remain`<br>`} }` |
| `SDRollingDigit { }` | `AnimatedSwitcher` 单个数字位 | attr：`value`(obs String) `boxWidth` `boxHeight` `fontSize` `weight` `fontFamily` `color`(obs) `durationS` | 一般不直接用 |
| `SDExpandableText { }` | —（歌词 / 描述折叠） | attr：`text`(obs) `maxLines`(3) `fontSize` `color`(obs) `weight` `lineHeightDp` `expandText` `collapseText` `actionColor` `expanded`(obs)。event：`onToggle { Boolean }` | `SDExpandableText { attr {`<br>`  text = ctx.desc; maxLines = 2; expandText = "more".tr`<br>`} }` |
| `SDUploadImage { }` | `UploadImageWidget` | attr：`url`(obs) `progress`(obs 0..100，负数不显示) `status`(obs, `SDUploadImageStatus.NONE/UPLOADING/FAILED`) `fit` `radius`(10) `clearable` `retryable`；尺寸 `size(w,h)`。event：`onClear { }` `onEmptyTap { }` `onRetry { }` `onImageTap { }` | `SDUploadImage {`<br>`  attr { size(80f, 80f); url = ctx.url; status = ctx.st; progress = ctx.p }`<br>`  event { onEmptyTap { ctx.pick() }; onClear { ctx.clear() } } }` |
| `SDAudioVisualizer { }` | `AudioVisualizer` | attr：`status`(obs：`'pause'` 跳动柱 / `'loading'` / 其它 播放图标) `color` `barCount`(3) `size`(30) `imageSize`(22) `bg` | `SDAudioVisualizer { attr {`<br>`  status = ctx.playStatus(song)`<br>`} }` |

## 3. 输入（`ui/input/`）

| Kotlin | 替代的 Dart | 属性 / 事件 | 用法 |
|---|---|---|---|
| `SDInput { }` | `InputWidget` | attr：`text`(obs，初始值 + 之后父级改动会写进输入框) `placeholder`(obs，默认 `please_enter`) `enabled`(obs) **`disabled`**（⚠️ 与 Dart 同名同义：**true = 可编辑**，Dart 把它传给了 `TextField.enabled`；新代码用 `enabled`） `borderRadius`(属性) `fillColor`(obs) `borderColor`(obs) `fontSize` `color` `hintColor` `cursorColor` `maxLength`(100) `showCounter` `prefixIcon/suffixIcon: ViewBuilder?` `keyboardType` `returnKey` `autofocus` `textAlignCenter` `contentPaddingH`；`height(h)` = 输入框高度。event：`onChanged { String }` `onSubmitted { String }` `onFocus { }` `onBlur { }` `onKeyboardHeight { Float }`。方法（经 `ref`）：`text` `setText()` `focus()` `blur()` | `SDInput {`<br>`  attr { height(44f); borderRadius = 16f; fillColor = UIColor.cFFF10; maxLength = 30; text = ctx.name }`<br>`  event { onChanged { ctx.name = it } } }` |
| `SDTextArea { }` | 多行 `TextField` + 计数 | attr：`text`(obs) `placeholder`(obs) `enabled`(obs) `areaHeight`(120) `radius`(16) `fillColor`(obs) `borderColor`(obs) `fontSize` `lineHeightDp` `color` `hintColor` `cursorColor` `maxLength` `showCounter` `contentPadding` `autofocus`。event：`onChanged` `onFocus` `onBlur` `onKeyboardHeight`。方法：`setText()` `focus()` `blur()` | `SDTextArea {`<br>`  attr { areaHeight = 160f; maxLength = 500; text = ctx.desc }`<br>`  event { onChanged { ctx.desc = it } } }` |
| `SDCodeInput { }` | —（验证码格子） | attr：`length`(6) `boxWidth/boxHeight` `gap` `radius` `fillColor` `activeBorderColor` `fontSize` `color` `autofocus` `obscure`。event：`onChanged { String }` `onCompleted { String }`。方法：`clear()` `focus()` `blur()` | `SDCodeInput {`<br>`  attr { length = 6 }`<br>`  event { onCompleted { ctx.verify(it) } } }` |
| `SDUnfocus(onTap) { }` | `UnfocusWidget` | 点空白收起键盘的容器（默认 `flex(1f)`） | `SDUnfocus {`<br>`  …页面内容…`<br>`}` |
| `hideKeyboard()` | `CommonUtils.hideKeyboard()` | 任意视图 / 页面的扩展 | `ctx.hideKeyboard()` |
| `SDKeyboardAwareScroll { }` | `KeyboardAwareScrollView` | attr：`extraPadding`(16) `marginBottom`(滚动区外固定底栏的高度) `showIndicator` `bounces`。插槽：`content { }`。方法：`ensureVisible(view)` `clearActiveTarget()` `updateKeyboardHeight(h)` `scroller`。里面的 `SDInput` / `SDTextArea` 聚焦时自动滚到键盘上方 | `SDKeyboardAwareScroll {`<br>`  attr { flex(1f); marginBottom = 80f }`<br>`  content { SDInput { … }; SDTextArea { … } } }` |
| `keyboardAware()` | `KeyboardAwareField` | 原生 `Input` / `TextArea` 接入上面的容器：在其闭包里 `val aware = keyboardAware()`，把三个事件转给它 | `event { inputFocus { aware.onFocus() }`<br>`  inputBlur { aware.onBlur() }`<br>`  keyboardHeightChange { aware.onKeyboardHeight(it.height) } }` |
| `SDKeyboardTopInput { }` + `SDKeyboardTopInputController` | `KeyboardTopInputWrapper` + `KeyboardTopInputController` | attr：`controller` `isNavRoot` `navBarHeight` `doneWidget`。controller：`show(text, hintText, maxLines = 4, maxLength = 20, numberKeyboard, onChanged, onDone(text), onCancel(text))` `hide()` `isShowing`。没有 `TextEditingController`，最终文本经 `onDone` / `onCancel` 交回（两个回调里都写回目标即等价于 Dart 的同步回写） | `SDKeyboardTopInput { attr { controller = ctx.topInput } }  // 放在页面内容最后`<br>`ctx.topInput.show(text = shot.prompt, maxLength = 200,`<br>`  onDone = { shot.prompt = it }, onCancel = { shot.prompt = it })` |

键盘约定：宿主窗口**不随键盘调整大小**（键盘盖在页面上），由 `SDKeyboardAwareScroll` / 弹窗自己避让。

## 4. 导航（`ui/nav/`）

| Kotlin | 替代的 Dart | 属性 / 事件 | 用法 |
|---|---|---|---|
| `SDNavBack(color, onPressed)` | `NavBackWidget` | 48 热区、`back-left.png` 24；不传 `onPressed` 则 `nav.pop()` | `SDNavBack()` |
| `SDNavBar { }`（别名 `SDNavAppBar`） | `NavAppBarWidget` | attr：`title`(obs) `titleColor`(obs) `titleSize`(16) `titleLeft` `titleBuilder` `showBack`(obs) `backColor`(obs) `leading` `actions: ViewBuilder?` `actionsPaddingRight` `barHeight`(56) `withStatusBar`(true) `bgColor`(obs) `assetBgImage` `overlay`(悬浮在内容之上) `divider`。event：`onBack { }`（默认 pop）`onDoubleTapTitle { }` | `SDNavBar {`<br>`  attr { title = "settings".tr; actions = { SDIconButton { … } } }`<br>`}` |
| `SDPageScaffold { }` | `Scaffold(appBar:, body:, bottomNavigationBar:)` | attr：`title`(obs) `showNavBar` `actions` `safeTop` `safeBottom` `contentSafeBottom` `bgColor` `bgImage` `navOverlay` `unfocusOnTap` `bottomBarColor`。插槽：`navBar { }`(整体替换导航栏) `content { }` `bottomBar { }`(自动补底部安全区) `overlay { }`(铺满整页的最上层)。event：`onBack { }` | `SDPageScaffold {`<br>`  attr { title = "profile".tr }`<br>`  content { … }; bottomBar { SDButton { … } } }` |
| `SDTabBar { }` | `TabBar`（带指示器） | attr：`tabs: List<String>` `currentIndex`(obs) `isScrollable`(true) `tabHeight`(32) `tabMinWidth` `tabPaddingH`(16) `gap`(8) `paddingH` `labelColor/unselectedLabelColor`(obs) `labelSize` `unselectedLabelSize` `labelWeight` `unselectedLabelWeight` `fontFamily` `indicator`(`SDTabIndicator.NONE/UNDERLINE/PILL`) `indicatorColor`(obs) `indicatorRadius` `underlineWidth/Height` `tabBgColor`。event：`onChanged { index }` | `SDTabBar {`<br>`  attr { tabs = listOf("my".tr, "like".tr); currentIndex = ctx.tab }`<br>`  event { onChanged { ctx.tab = it } } }` |
| `SDSegmented { }` | `MusicModeSegment`（`TabBar(indicatorSize: tab)`） | attr：`segments` `currentIndex`(obs) `segmentHeight`(42) `containerColor`(obs) `selectedColor`(obs) `radius` `inset` `labelColor/unselectedLabelColor`(obs) `labelSize` `labelWeight` `unselectedLabelWeight` `fontFamily`。event：`onChanged { index }` | `SDSegmented {`<br>`  attr { segments = listOf("simple".tr, "custom".tr); currentIndex = ctx.mode }`<br>`  event { onChanged { ctx.mode = it } } }` |
| `SDBottomBar(row, safeBottom, paddingH, paddingV, bgColor, divider) { }` | 页面底部按钮区 | 独立使用时自带底部安全区；放进 `SDPageScaffold.bottomBar { }` 时传 `safeBottom = false` | `SDBottomBar {`<br>`  SDSecondaryButton { attr { flex(1f); text = "cancel".tr } }; HGap(12f)`<br>`  SDPrimaryButton { attr { flex(1f); text = "confirm".tr } } }` |

## 5. 列表（`ui/list/`）

| Kotlin | 替代的 Dart | 属性 / 事件 | 用法 |
|---|---|---|---|
| `PagingState<T>(scope, pageSize = 10, itemKey, after) { pageNo, pageSize -> ApiResult<PageResult<T>> }` | `RefreshMixin` / `RefreshState`(MultiRefresh) / `PagingController` | 响应式字段：`list` `loading` `refreshing` `loadingMore` `noMore` `error` `loadMoreFailed` `loaded` `total`；`pageNo` `hasMore` `isInitialLoading` `isEmpty` `isError` `isShowLoading`。方法：`refresh(onFinished?)` `loadMore(onFinished?)` `suspend refreshAwait()/loadMoreAwait()` `clearData()` `replaceFirst(pred, item)` `removeWhere(pred)` `prepend(item)`；`PagingState.ofList(scope, …) { → ApiResult<List<T>> }` | `private val paging = PagingState<SongModel>(this, itemKey = { it.id }) { p, s -> MusicApi.myList(p, s) }`<br>`override fun created() { super.created(); paging.refresh() }` |
| `SDRefreshList { }` | `SmartRefresher` + 全局 `RefreshConfiguration`（头部 `RefreshLoading`、`ClassicFooter`） | attr：`state: PagingState<*>?` `enableRefresh` `enableLoadMore` `columnCount`(>1 用瀑布流) `listWidth` `itemSpacing` `lineSpacing` `contentPaddingTop/H/Bottom` `showIndicator` `bounces` `preloadDistance` `emptyImage` `emptyText` `emptyButtonText` `emptyBuilder/loadingBuilder/errorBuilder` `placeholderHeight` `showNoMoreText` `footerIdleText/LoadingText/NoMoreText/FailedText` `footerExtraPadding` `firstContentLoadMaxIndex`。插槽：`header { }` `items { vfor… }`（接收者是 `ListView`）。event：`onScroll { ScrollParams }` `onScrollEnd` `onEmptyButtonTap`；手动模式（不给 state）：`onRefresh { }` `onLoadMore { }` + 方法 `endRefresh(noMore)` `endLoadMore(noMore, success)`。方法：`beginRefresh()` `scrollToTop()` `listView` | `SDRefreshList {`<br>`  attr { flex(1f); state = ctx.paging; emptyImage = AssetsImage.libraryEmpty }`<br>`  items { vfor({ ctx.paging.list }) { item -> SongRow { … } } } }` |
| `SDStaggeredGrid { }` | `MasonryGridView`（flutter_staggered_grid_view） | 同 `SDRefreshList`，预设 2 列、无刷新 / 加载；每个 vfor 项是一个格子，高度自适应 | `SDStaggeredGrid {`<br>`  attr { flex(1f); listWidth = ctx.screenWidth; itemSpacing = 8f; lineSpacing = 8f }`<br>`  items { vfor({ ctx.mvs }) { mv -> MvCard { … } } } }` |
| `SDGridSpec(columns, totalWidth, spacing, runSpacing)` + `attr.gridContainer(spec)` + `attr.gridCell(spec, index)` | `GridView.count` / `Wrap` | 等宽网格的尺寸与间距；`spec.itemWidth` | `View { attr { gridContainer(grid) }`<br>`  vforIndex({ ctx.tags }) { t, i, _ ->`<br>`    View { attr { gridCell(grid, i); height(grid.itemWidth) } … } } }` |
| `SDGrid(items, spec) { item, index -> }` | 静态 `GridView` | 构建时确定；数据会变用 `vbind` 包一层 | `SDGrid(list, SDGridSpec(3, w, 10f)) { item, _ ->`<br>`  attr { height(40f) }; SDText(item)`<br>`}` |
| `SDHorizontalList(height, paddingH, bounces) { }` | 横向 `ListView` | 闭包接收者是 `ListView`，里面写 `vfor` | `SDHorizontalList(height = 68f, paddingH = 20f) {`<br>`  vfor({ ctx.urls }) { u -> SDImage { attr { size(68f,68f); marginRight(10f); url = u } } }`<br>`}` |
| `SDSwiper { }` | `Swiper`（card_swiper）/ `CarouselSlider` | attr：`itemCount` `itemBuilder: (index) -> Unit` `pageWidth` `pageHeight` `initialIndex` `autoPlayIntervalMs`(obs，0 关闭) `scrollEnable`(obs) `showIndicator` `indicatorBottom` `dotSize` `dotGap` `activeDotWidth` `dotColor` `activeDotColor` `indicatorBuilder`。event：`onIndexChanged { index }`。方法：`scrollToPage(i, animated)` `next()` `currentIndex`。多于 1 页自动循环；数据变化用 `vbind` 重建 | `SDSwiper {`<br>`  attr { pageWidth = w; pageHeight = 180f; itemCount = n; autoPlayIntervalMs = 5000`<br>`    itemBuilder = { i -> SDImage { attr { flex(1f); url = banners[i].img } } } } }` |
| `attr.scrollOver(onlyAndroid = true)` | `ScrollOverWidget` | 关闭越界回弹（默认仅 Android） | `List { attr { flex(1f); scrollOver() } … }` |

## 6. 弹窗 / 弹层 / 提示（`ui/dialog/`，全部是 `BasePager` 扩展）

| Kotlin | 替代的 Dart | 签名要点 | 用法 |
|---|---|---|---|
| `showConfirmDialog(content, title?, confirmText?, cancelText?, isShowCancel = true, autoDismiss = true, contentCenter = false, onCancel?, onConfirm): DialogHandle` | `ConfirmDialog.show` | ⚠️ Dart 版点确定**不自动关闭**（调用处手写 `SmartDialog.dismiss()`）；这里默认先关再回调，照抄时删掉那句 dismiss；要保持打开传 `autoDismiss = false` | `ctx.showConfirmDialog("delete_tip".tr) {`<br>`  ctx.doDelete()`<br>`}` |
| `suspend confirm(content, title?, cancelText?, confirmText?): Boolean` | `ConfirmDialog.showChoice` | 确定 true；取消 / 遮罩 / 返回键 false | `ctx.launch {`<br>`  if (ctx.confirm("…")) ctx.doIt()`<br>`}` |
| `showAlertDialog(content, title?, confirmText?, onConfirm?)` | `ConfirmDialog.show(isShowCancel: false)` | 单按钮 | `ctx.showAlertDialog("…")` |
| `showCardDialog(title, cancelText?, confirmText?, widthFactor, radius, buttonGap, dismissOnMask, onDismiss?, onConfirm = { handle -> }) { body }` | `CustomDialog.show(contentType: custom)` / `_DialogShell` | 标题 + 自定义内容 + 两按钮；确定**不自动关闭** | `ctx.showCardDialog("tip".tr, onConfirm = { it.dismiss() }) {`<br>`  SDText("…")`<br>`}` |
| `showInputDialog(title, hintText, initialText, maxLength, cancelText?, confirmText?, numberKeyboard) { text, handle -> }` | `CustomDialog.show(contentType: input)` | 确定**不自动关闭**（校验 / 请求成功后 `handle.dismiss()`） | `ctx.showInputDialog("CDkey".tr, "please_enter_the_CDkey".tr) { text, h ->`<br>`  ctx.launch { if (ctx.exchange(text)) h.dismiss() }`<br>`}` |
| `showAppBottomSheet(isDismissible, enableDrag, maxHeight?, keyboardAware, showHandle, topRadius, maskColor, onDismiss?) { handle -> }` | `AppBottomSheet.show` | 圆角 24、拖拽条（可下拉关闭）、最大高 85%、底部安全区、遮罩黑 70%；`keyboardAware` = Dart `ignoreKeyboardInsets` | `ctx.showAppBottomSheet { handle ->`<br>`  SDSheetHeader("more".tr) { handle.dismiss() }`<br>`  … }` |
| `showPlainBottomSheet(isDismissible, padding = 16, topRadius = 16, maskColor, onDismiss?) { handle -> }` | 旧样式 `Get.bottomSheet(...)` / SmartDialog 底部对齐 | 圆角 16、内边距 16、无拖拽条 | `ctx.showPlainBottomSheet { h -> SDSheetHeader("lyric_tag".tr) { h.dismiss() }; … }` |
| `SDSheetHeader(title, titleSize, onClose?)` | 弹层标题行 | 左标题 16 bold + 右关闭 26 | 见上 |
| `showActionSheet(title, items: List<SDActionItem>, onDismiss?) { index -> }` / `suspend pickAction(title, items): Int?` | `DialogActionsMv` / `DialogActionsSong` 的行样式（通用部分） | `SDActionItem(text, icon, color, danger, loading, enabled)`；点击后先关闭再回调 | `ctx.showActionSheet("more".tr, listOf(`<br>`  SDActionItem("download".tr, AssetsImage.downloadMv1), SDActionItem("delete".tr, danger = true))`<br>`) { i -> … }` |
| `showOptionPicker(title, options, selectedIndex, maxListHeight) { index -> }` | 单选列表弹层 | 当前项高亮 | `ctx.showOptionPicker("ratio".tr, ratios, cur) { ctx.ratio = it }` |
| `showPopupMenu(anchorX, anchorY, items: List<SDPopupMenuItem>, selectedId, minWidth, selectedColor) { item -> }` | `HandleOptionWidget` / `HandleOptionItem` | 锚点用点击事件的页面坐标 | `event { click { p -> ctx.showPopupMenu(p.pageX, p.pageY,`<br>`  listOf(SDPopupMenuItem("Edit", "edit")), selectedId = cur) { … } } }` |
| `suspend showUsageStatement(type, barrierDismissible, onOpenPrivacy?, onOpenTerms?): Boolean` | `UsageStatement.show` | `UsageStatementType.UPLOAD/SHARE/DOWNLOAD` | `if (!ctx.showUsageStatement(UsageStatementType.UPLOAD)) return` |
| `suspend showAgree(type, storageKey?, displayUseExplain = true): Boolean` | `UserController.showAgree(key, type)` | 已同意（`StorageKey.isAgreeUpload/Share/Down`）或开关关闭直接 true；同意后落盘。`displayUseExplain` 由调用方从远程配置传入 | `if (!ctx.showAgree(UsageStatementType.UPLOAD, displayUseExplain = cfg.displayUseExplain)) return` |
| `suspend showAiWatermarkStatement(): Boolean` | `AiWatermarkDialogs.showStatement` | 同意并关闭 true | `if (ctx.showAiWatermarkStatement()) ctx.setWatermark(false)` |
| `suspend showPermissionRationale(permission, title?, content?, purpose?): Boolean` | `PermissionUtil.showPermissionRationale` | permission：`"camera" "photos" "photosAddOnly" "storage" "microphone" "notification"` | `if (!ctx.showPermissionRationale("camera")) return` |
| `showPermissionSettingsDialog(permission)` / `suspend ensurePermission(permission, purpose?): Boolean` | 永久拒绝引导 / 「说明 → 系统授权」通用流程 | `ensurePermission` 不含 Dart 里「相册说明已同意」的本地记忆，业务层有自己的权限工具时以它为准 | `if (!ctx.ensurePermission("microphone")) return` |
| `SDLinks.opener` / `SDLinks.open(pager, url, title)` | `UserController.openPrivacyPolicy/openTerms` 的打开方式 | 默认 `SDAppModule.openUrl`；账号模块可替换成应用内 WebView | `SDLinks.opener = { url, title -> … }` |
| `showLoadingDialog(text, clickMaskDismiss)` / `closeLoading()` / `isLoadingShowing()` | `LoadingUtil.show / close / isOpenLoading` | 与 `showLoading()/hideLoading()` 同一个浮层 | `ctx.showLoadingDialog("loading".tr); …; ctx.closeLoading()` |
| `showProgressDialog(message, progress, cancelable, onCancel?): SDProgressDialogHandle` | 带进度的 loading | handle：`message`(obs) `progress`(obs，负数不确定) `dismiss()` | `val d = ctx.showProgressDialog("loading".tr, 0f)`<br>`d.progress = 0.4f`<br>`d.dismiss()` |
| `showGenerationCompleted(title?, subtitle?, durationMs = 3000, onTap?): BannerHandle?` | `Toast.instance.showGenerationCompleted` + `GenerationCompletedToast` + `AnimatedBellIcon` | 顶部横幅（非模态）；相同内容在时长内去重 | `ctx.showGenerationCompleted { ctx.nav.push(Routes.musicDetail, args) }` |
| `showTopMessage(title, content, imageUrl, imagePath, isSingleLine, isWarning, isSuccess, onTap?)` / `showSuccessMessage(title)` / `showWarningMessage(title)` | `Toast.instance.showMessage / showSuccessMessage / showWarningMessage` | 渐变顶部横幅 | `ctx.showSuccessMessage("download_success".tr)` |
| `showImagePreview(images: List<ImagePreviewItem>, initialIndex, onDismiss?, onDownload?): DialogHandle?`（另有 `urls: List<String>` 重载） | `DialogImagePreview.show` + `ImagePreviewItem` | 传了 `onDownload` 才显示下载按钮（导出 / 存相册由业务层做）；双指 1–4×、双击 1↔2× | `ctx.showImagePreview(urls, index)` |
| `showVideoPreview(videos: List<VideoPreviewItem>, initialIndex, initialPositionMs, showDownloadButton, resolveSource?, onDownload?, onResult?): DialogHandle?` / `suspend previewVideos(...): VideoPreviewDismissResult?` | `DialogVideoPreview.show` + `VideoPreviewItem` + `VideoPreviewDismissResult` | `resolveSource(index, item)` 做合成 / 取缓存；播放闸门由调用方 acquire / release | `ctx.launch {`<br>`  val r = ctx.previewVideos(items, index)`<br>`  r?.let { ctx.resumeAt(it.index, it.positionMs) } }` |
| `showVideoInfoDialog(title, videoUrl, description, onTry?)` | `VideoInfoDialog.show` | 3:4 循环预览 + 说明 + 可选「试试」 | `ctx.showVideoInfoDialog(t.name, t.video, t.desc) { ctx.use(t) }` |
| `showAppHotDialog(onGet?)` | `DialogAppHot.show` | 不可取消的维护提示 | `ctx.showAppHotDialog()` |
| `suspend showScoreDialog(): Boolean` | `DialogScore`（UI 部分） | 只负责弹窗；「只弹一次」「远程开关」「调系统评分」「埋点」由业务层做 | `if (ctx.showScoreDialog()) app.requestReview()` |
| `showSheetImageOption(onRoleLibrary, onCamera, onAlbum, usageRecords, clickMaskDismiss) { url -> }` | `DialogSheetImageOption.show` | 回调前先关闭 | `ctx.showSheetImageOption({ … }, { … }, { … }, records) { url -> … }` |
| `showTagOptions(tags = LYRIC_STRUCTURE_TAGS) { tag -> }` | `DialogTagOptions` | 点 chip **不自动关闭**（同 Dart） | `val h = ctx.showTagOptions { tag -> ctx.insert(tag) }` |
| `showMvPreviewUnlock(needPoint) { }` / `SDPointsLabel(text, points, color)` | `MvPreviewUnlockDialog.show` / `MvPreviewPointsLabel` | CTA 先关闭再回调，只触发一次 | `ctx.showMvPreviewUnlock(20) { ctx.unlock() }` |

`BasePager` 自带的 `toast()` / `toastError()` / `showLoading()` / `showDialog()` / `showBottomSheet()` 仍可直接用（`ui/overlay`）；
`overlay.showBanner(durationMs) { handle -> … }` 可展示任意内容的顶部横幅。

## 7. 隐私同意（`ui/consent/`）

| Kotlin | 替代的 Dart | 属性 / 事件 | 用法 |
|---|---|---|---|
| `SDPrivacyConsent { }` | `PrivacyConsentPage`（`ConsentShell` 的界面） | attr：`isBusy`(obs) `showSplashBackground`。event：`onAgree { }`（点「同意并继续」）`onDisagree { }`（第二层「暂不使用」→ 退出应用）`onOpenLink { url, title -> }`。方法：`handleBack()`（返回键在两层间切换，根页面在 `onBackPressed()` 里调用，并让 `interceptBack()` 在 Gate 显示期间返回 true） | `SDPrivacyConsent {`<br>`  attr { isBusy = ctx.bootstrapping }`<br>`  event { onAgree { ctx.accept() }; onDisagree { ctx.exit() }; onOpenLink { u, _ -> ctx.open(u) } } }` |

同意版本的读写（`StorageKey.privacyConsentVersion` / `privacyConsentAt`）不在组件里，由根页面处理。

## 8. 动画（`ui/anim/`）

| Kotlin | 替代的 Dart | 属性 / 事件 | 用法 |
|---|---|---|---|
| `SDEnterAnim { }` / `SDFadeIn { }` / `SDScaleIn { }` / `SDSlideIn { }` | `flutter_animate` 的 `.fadeIn()/.scale()/.slide()`、弹窗 pop 动画 | attr：`fade` `scaleFrom` `slideX/slideY`(自身宽高的倍数) `durationS` `delayS` `curve`(SDCurve) `show`(obs，false 反向退场)。插槽：`content { }` | `SDScaleIn {`<br>`  attr { scaleFrom = 0.92f; durationS = 0.2f }`<br>`  content { … } }` |
| `SDBreathing { }` | 呼吸 / 脉冲 | attr：`minScale/maxScale` `minOpacity/maxOpacity` `periodS` `active`(obs)。插槽：`content { }` | `SDBreathing { attr { maxScale = 1.1f }; content { … } }` |
| `SDRotating { }` | 旋转 loading / 唱片 | attr：`periodS` `active`(obs)。插槽：`content { }` | `SDRotating { content { SDAssetImage(AssetsImage.loadingIcon, 24f) } }` |
| `SDTypingDots { }` | 输入中圆点 | attr：`dotSize` `gap` `color` `count` `stepMs` | `SDTypingDots { }` |
| `SDEqualizerBars { }` | 播放中跳动柱 | attr：`barCount` `barWidth` `gap` `maxHeight` `color` `playing`(obs) `intervalMs` | `SDEqualizerBars { attr { playing = ctx.isPlaying } }` |
| `SDRefreshLoading { }` | `RefreshLoading` | attr：`loadingWidth`(27) `loadingHeight`(21) `color` `active`(obs) | `SDRefreshLoading { }` |
| `SDAnimatedBell { }` | `AnimatedBellIcon` | attr：`size`(42) `bellSize`(24)。方法：`play()` | `SDAnimatedBell { }` |
| `SDFloatDelta { }` | `FloatItem` | attr：`delta` `fontSize`。event：`onFinished { }`（1.2s 后，从列表移除该项） | `vfor({ ctx.deltas }) { d -> SDFloatDelta {`<br>`  attr { delta = d }`<br>`  event { onFinished { ctx.deltas.remove(d) } } } }` |

## 9. 没有放进 UI Kit 的（依赖模型 / 服务，由业务层移植）

歌曲 / MV 列表项（`MusicItemWidget` `MvItemWidget` `SongItemWidget` `SongCard` `AudioTaskCard`）、`MusicSelectBottomSheet`、`PhotoSelectBottomSheet`、
引导（`guide_v3`）、`AudioCropper` / 波形、视频播放器模块（`video_player/**`、`VideoWidget`、`ListMvVideoPlayer`、`InlinePreviewVideoCard`）、
`NavPoints` / `NavRootAppBarWidget` / `NavPlayerWidget`（依赖 UserController / ConfigService / PlayerController）、
`DialogActionsSong` / `DialogActionsMv` / `DialogEditSong` / `DialogEditUser` / `DialogShotTag`（依赖模型与上传 / 审核服务；外壳可用 `showPlainBottomSheet` + `SDSheetHeader` + `SDActionItem` 行样式）、
`CreatorPhotoUploadSection`（依赖 `RoleModel`）、`ShotTagTextController`（依赖 `MvService` 的标签表）。
