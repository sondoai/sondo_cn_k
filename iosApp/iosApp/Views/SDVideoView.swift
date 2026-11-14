import AVFoundation
import UIKit

/// 原生视频播放视图（契约见 shared 的 `SDNativeViews.kt`）。类名必须等于 Kuikly 侧的 viewName。
///
/// 属性：src / autoPlay / loop / muted / volume / speed / fit / mixWithOthers / cache / progressIntervalMs
/// 事件：sdState / sdProgress / sdFirstFrame
/// 方法：play / pause / stop / seekTo({positionMs}) / release
///
/// 生命周期：
/// - 属性在同一批 UI 任务里逐个到达，所以换源延后到下一个主线程循环再加载（保证 cache / autoPlay 等已就位）；
/// - 离开 window（页面被盖住）、App 失去活跃、音频会话被打断时只暂停，条件恢复后按「播放意图」自动续播；
/// - 被 Kuikly 移除（removeFromSuperview）或释放时拆掉播放器和全部观察者。
///
/// 磁盘缓存：`cache=1` 的 http(s) 地址先查 `SDVideoCache`，命中则直接播本地完整文件；
/// 未命中则边播网络流、边在后台把整个文件预取进缓存（仅 ≤ 20MB 的资源）。
@objc(SDVideoView)
final class SDVideoView: UIView, KuiklyRenderViewExportProtocol, SDAudioOutputParticipant {

    override class var layerClass: AnyClass {
        return AVPlayerLayer.self
    }

    private var playerLayer: AVPlayerLayer? {
        return layer as? AVPlayerLayer
    }

    // MARK: - 属性

    private var src = ""
    private var autoPlay = false
    private var loop = false
    private var muted = false
    private var volume: Float = 1
    private var speed: Float = 1
    private var fit = "contain"
    private var mixWithOthers = false
    private var useCache = true
    private var progressIntervalMs = 250

    // MARK: - 事件回调

    private var onState: KuiklyRenderCallback?
    private var onProgress: KuiklyRenderCallback?
    private var onFirstFrame: KuiklyRenderCallback?

    // MARK: - 播放状态（仅主线程）

    private var player: AVPlayer?
    private var item: AVPlayerItem?
    private var timeObserver: Any?
    private var observations: [NSKeyValueObservation] = []
    private var layerObservation: NSKeyValueObservation?
    private var itemNotifications: [NSObjectProtocol] = []
    private var systemNotifications: [NSObjectProtocol] = []

    /// 每次加载 / 拆除自增，用来丢弃过期的异步回调。
    private var generation = 0
    /// 延后加载的票据：只有最后一次 scheduleReload 生效；同步加载时作废还没执行的那次。
    private var reloadTicket = 0
    /// 播放意图：play / autoPlay 之后为 true，pause / 播完 / 被抢占后为 false。
    private var wantsPlay = false
    private var prepared = false
    private var completed = false
    private var hasStarted = false
    private var firstFrameSent = false
    private var failure: String?
    private var pendingSeekMs: Int64?
    private var videoSize = CGSize.zero
    private var lastStateKey = ""

    private var appInactive = false
    private var interrupted = false
    private var removedFromSuperview = false

    /// 暂时不能播（不改变播放意图）：不在 window 上 / App 不活跃 / 音频会话被打断。
    private var isSuspended: Bool {
        return window == nil || appInactive || interrupted
    }

    private var isExclusiveAudio: Bool {
        return !mixWithOthers && !muted && volume > 0
    }

    // MARK: - 初始化

    override init(frame: CGRect) {
        super.init(frame: frame)
        commonInit()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        commonInit()
    }

    private func commonInit() {
        playerLayer?.videoGravity = .resizeAspect
        appInactive = UIApplication.shared.applicationState != .active

        layerObservation = playerLayer?.observe(\.isReadyForDisplay, options: [.new]) { [weak self] _, _ in
            SD.onMain { self?.readyForDisplayChanged() }
        }

        let center = NotificationCenter.default
        systemNotifications.append(center.addObserver(
            forName: UIApplication.willResignActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in
            guard let self = self else { return }
            self.appInactive = true
            self.applyPlayback()
        })
        systemNotifications.append(center.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in
            guard let self = self else { return }
            self.appInactive = false
            self.interrupted = false
            self.applyPlayback()
        })
        systemNotifications.append(center.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] note in
            self?.handleInterruption(note)
        })
        systemNotifications.append(center.addObserver(
            forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main
        ) { [weak self] note in
            self?.handleRouteChange(note)
        })
        SDMediaAudioFocus.shared.register(self)
    }

    deinit {
        teardownPlayer()
        layerObservation?.invalidate()
        for token in systemNotifications {
            NotificationCenter.default.removeObserver(token)
        }
    }

    // MARK: - KuiklyRenderViewExportProtocol

    @objc(hrv_setPropWithKey:propValue:)
    func hrv_setProp(withKey propKey: String, propValue: Any) {
        // 通用属性（frame / 背景 / 圆角 / 点击事件 …）交给 Kuikly 的 UIView+CSS 处理。
        if css_setProp(withKey: propKey, value: propValue) {
            return
        }
        switch propKey {
        case "src":
            let value = SDMedia.string(propValue)
            if value != src {
                src = value
                pendingSeekMs = nil
                scheduleReload()
            }
        case "autoPlay":
            let value = SDMedia.flag(propValue, fallback: false)
            let turnedOn = value && !autoPlay
            autoPlay = value
            // 已就绪但还没播过时再打开 autoPlay：视为开始播放。
            if turnedOn && prepared && !hasStarted && !wantsPlay && !completed {
                wantsPlay = true
                applyPlayback()
            }
        case "loop":
            loop = SDMedia.flag(propValue, fallback: false)
            player?.actionAtItemEnd = loop ? .none : .pause
        case "muted":
            muted = SDMedia.flag(propValue, fallback: false)
            player?.isMuted = muted
            audibilityChanged()
        case "volume":
            volume = Float(min(1, max(0, SDMedia.double(propValue) ?? 1)))
            player?.volume = volume
            audibilityChanged()
        case "speed":
            speed = Float(min(4, max(0.25, SDMedia.double(propValue) ?? 1)))
            if let player = player, player.rate != 0 {
                player.rate = speed
            }
        case "fit":
            let value = SDMedia.string(propValue)
            fit = value.isEmpty ? "contain" : value
            applyFit()
        case "mixWithOthers":
            mixWithOthers = SDMedia.flag(propValue, fallback: false)
            audibilityChanged()
        case "cache":
            useCache = SDMedia.flag(propValue, fallback: true)
        case "progressIntervalMs":
            let raw = SDMedia.double(propValue) ?? 250
            let value = raw.isFinite ? Int(min(60_000, max(50, raw))) : 250
            if value != progressIntervalMs {
                progressIntervalMs = value
                installTimeObserver()
            }
        case "sdState":
            onState = SDMedia.eventCallback(propValue)
            // 监听晚于状态变化注册时补发一次当前状态。
            lastStateKey = ""
            if onState != nil && (item != nil || failure != nil) {
                emitState()
            }
        case "sdProgress":
            onProgress = SDMedia.eventCallback(propValue)
        case "sdFirstFrame":
            onFirstFrame = SDMedia.eventCallback(propValue)
        default:
            break
        }
    }

    @objc(hrv_callWithMethod:params:callback:)
    func hrv_call(withMethod method: String, params: String?, callback: KuiklyRenderCallback?) {
        switch method {
        case "play":
            interrupted = false
            play()
        case "pause":
            wantsPlay = false
            applyPlayback()
        case "stop", "release":
            stopAndUnload()
        case "seekTo":
            seek(toMs: SDMedia.methodParams(params).sdInt64("positionMs", 0))
        default:
            break
        }
    }

    // MARK: - 视图生命周期

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil && removedFromSuperview {
            // 被移除后又挂回来（正常不会发生）：按属性重新加载。
            removedFromSuperview = false
            if player == nil && !src.isEmpty {
                scheduleReload()
            }
            return
        }
        applyPlayback()
    }

    override func removeFromSuperview() {
        super.removeFromSuperview()
        // Kuikly 移除节点（列表滑出、vif 变 false、页面关闭）：立即释放播放器，不等 dealloc。
        removedFromSuperview = true
        wantsPlay = false
        teardownPlayer()
    }

    // MARK: - SDAudioOutputParticipant

    var sdOutputIsPlaying: Bool {
        return player != nil && prepared && wantsPlay && !completed && !isSuspended
    }

    var sdOutputIsExclusiveAudible: Bool {
        return sdOutputIsPlaying && isExclusiveAudio
    }

    func sdOutputPauseForHandoff() {
        wantsPlay = false
        applyPlayback()
    }

    // MARK: - 加载

    private func scheduleReload() {
        reloadTicket += 1
        let ticket = reloadTicket
        DispatchQueue.main.async { [weak self] in
            guard let self = self, ticket == self.reloadTicket else { return }
            self.reload(playWhenReady: self.autoPlay)
        }
    }

    private func reload(playWhenReady: Bool) {
        // 加载前收到的 seekTo（续播位置）要带到新的播放器上。
        let seekAfterLoad = pendingSeekMs
        teardownPlayer()
        pendingSeekMs = seekAfterLoad
        failure = nil
        hasStarted = false
        firstFrameSent = false
        videoSize = .zero
        wantsPlay = false

        if removedFromSuperview || src.isEmpty {
            emitState()
            return
        }
        guard let url = SDMedia.mediaURL(src) else {
            failure = "invalid src"
            emitState()
            return
        }
        wantsPlay = playWhenReady

        var playURL = url
        if SDMedia.isRemote(url) && useCache {
            if let cached = SDVideoCache.shared.cachedFile(for: src) {
                playURL = cached
            } else {
                // 未命中：照常播网络流，同时后台把完整文件拉进缓存，下次直接播本地。
                SDVideoCache.shared.prefetch(src, completion: nil)
            }
        }
        startPlayer(with: playURL)
    }

    private func startPlayer(with url: URL) {
        generation += 1
        let gen = generation

        let newItem = AVPlayerItem(asset: AVURLAsset(url: url))
        newItem.audioTimePitchAlgorithm = .timeDomain
        let newPlayer = AVPlayer(playerItem: newItem)
        newPlayer.actionAtItemEnd = loop ? .none : .pause
        newPlayer.isMuted = muted
        newPlayer.volume = volume
        item = newItem
        player = newPlayer
        playerLayer?.player = newPlayer
        applyFit()

        observations.append(newItem.observe(\.status, options: [.new]) { [weak self] observed, _ in
            SD.onMain { self?.itemStatusChanged(observed, generation: gen) }
        })
        observations.append(newItem.observe(\.presentationSize, options: [.new]) { [weak self] _, _ in
            SD.onMain { self?.mediaInfoChanged(generation: gen) }
        })
        observations.append(newItem.observe(\.duration, options: [.new]) { [weak self] _, _ in
            SD.onMain { self?.mediaInfoChanged(generation: gen) }
        })
        observations.append(newPlayer.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            SD.onMain { self?.timeControlStatusChanged(generation: gen) }
        })

        let center = NotificationCenter.default
        itemNotifications.append(center.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime, object: newItem, queue: .main
        ) { [weak self] _ in
            self?.itemDidPlayToEnd(generation: gen)
        })
        itemNotifications.append(center.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime, object: newItem, queue: .main
        ) { [weak self] note in
            let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            self?.fail(error?.localizedDescription ?? "playback failed", generation: gen)
        })

        installTimeObserver()
        emitState()
    }

    /// 拆掉播放器和它身上的全部观察者（可重复调用）。
    private func teardownPlayer() {
        generation += 1
        for observation in observations {
            observation.invalidate()
        }
        observations = []
        for token in itemNotifications {
            NotificationCenter.default.removeObserver(token)
        }
        itemNotifications = []
        if let player = player, let token = timeObserver {
            player.removeTimeObserver(token)
        }
        timeObserver = nil
        player?.pause()
        playerLayer?.player = nil
        player?.replaceCurrentItem(with: nil)
        player = nil
        item = nil
        prepared = false
        completed = false
        pendingSeekMs = nil
    }

    private func installTimeObserver() {
        guard let player = player else {
            return
        }
        if let token = timeObserver {
            player.removeTimeObserver(token)
            timeObserver = nil
        }
        let interval = CMTime(value: CMTimeValue(progressIntervalMs), timescale: 1000)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] _ in
            self?.emitProgress()
        }
    }

    private func applyFit() {
        switch fit {
        case "cover":
            playerLayer?.videoGravity = .resizeAspectFill
        case "fill":
            playerLayer?.videoGravity = .resize
        default:
            playerLayer?.videoGravity = .resizeAspect
        }
    }

    // MARK: - 播放控制

    private func play() {
        guard player != nil, item != nil else {
            // stop / release / 出错之后再 play：重新加载并播放。
            if !src.isEmpty && !removedFromSuperview {
                reloadTicket += 1
                reload(playWhenReady: true)
            }
            return
        }
        wantsPlay = true
        if completed {
            completed = false
            let gen = generation
            player?.seek(to: .zero, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
                SD.onMain {
                    guard let self = self, gen == self.generation else { return }
                    self.emitProgress()
                    self.applyPlayback()
                }
            }
            emitState()
            return
        }
        applyPlayback()
    }

    private func stopAndUnload() {
        reloadTicket += 1
        wantsPlay = false
        failure = nil
        teardownPlayer()
        emitState()
    }

    private func seek(toMs positionMs: Int64) {
        guard let player = player, let item = item, prepared else {
            // 还没就绪（或还没开始加载）：记下来，就绪后先定位再播。
            if self.item != nil || (!src.isEmpty && failure == nil) {
                pendingSeekMs = max(0, positionMs)
            }
            return
        }
        var target = Double(max(0, positionMs)) / 1000
        let duration = CMTimeGetSeconds(item.duration)
        if item.duration.isNumeric, duration.isFinite {
            target = min(target, duration)
        }
        completed = false
        let gen = generation
        player.seek(to: CMTime(seconds: target, preferredTimescale: 1000),
                    toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            SD.onMain {
                guard let self = self, gen == self.generation else { return }
                self.emitProgress()
                self.applyPlayback()
            }
        }
        emitState()
    }

    /// 根据「播放意图 + 是否被挂起」驱动 AVPlayer，并上报状态。所有触发点最终都走这里。
    private func applyPlayback() {
        guard let player = player, prepared, !completed else {
            // 还没有播放器（属性未到齐 / 已 stop）时不重复上报 idle。
            if self.player != nil {
                emitState()
            }
            return
        }
        if wantsPlay && !isSuspended {
            if player.rate == 0 {
                claimAudioOutput()
                player.rate = speed
            }
            hasStarted = true
        } else if player.rate != 0 {
            player.pause()
        }
        emitState()
    }

    /// 音频 / 视频互斥：有声独占的视频先让其它有声播放器（全局音频播放器、其它独占视频）停下并激活会话；
    /// 静音或混播的视频不打扰任何人。
    private func claimAudioOutput() {
        if isExclusiveAudio {
            SDMediaAudioFocus.shared.claimExclusive(for: self)
        } else {
            SDMediaAudioFocus.shared.prepareMixing(for: self)
        }
    }

    /// muted / volume / mixWithOthers 在播放过程中变化：从无声变成有声独占时补一次抢占。
    private func audibilityChanged() {
        if sdOutputIsPlaying && isExclusiveAudio {
            SDMediaAudioFocus.shared.claimExclusive(for: self)
        }
    }

    // MARK: - 播放器回调

    private func itemStatusChanged(_ observed: AVPlayerItem, generation gen: Int) {
        guard gen == generation, observed === item else {
            return
        }
        switch observed.status {
        case .readyToPlay:
            if prepared {
                return
            }
            prepared = true
            refreshVideoSize()
            emitState(forced: "ready")
            if let pending = pendingSeekMs {
                pendingSeekMs = nil
                seek(toMs: pending)
            } else {
                applyPlayback()
            }
        case .failed:
            fail(observed.error?.localizedDescription ?? "load failed", generation: gen)
        default:
            break
        }
    }

    private func mediaInfoChanged(generation gen: Int) {
        guard gen == generation, prepared else {
            return
        }
        refreshVideoSize()
        emitState()
    }

    private func timeControlStatusChanged(generation gen: Int) {
        guard gen == generation, let player = player else {
            return
        }
        emitState()
        guard player.timeControlStatus == .paused, wantsPlay, prepared, !completed, !isSuspended else {
            return
        }
        // 我们想播、系统却把播放器停了（拔耳机、会话被别人抢走等）。稍等片刻确认不是中断通知的
        // 前奏、也不是播到结尾，再把意图改成暂停，避免界面一直显示「播放中 / 缓冲中」。
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { [weak self] in
            guard let self = self, gen == self.generation, let player = self.player else { return }
            guard player.timeControlStatus == .paused, self.wantsPlay, self.prepared,
                  !self.completed, !self.isSuspended else {
                return
            }
            self.wantsPlay = false
            self.emitState()
        }
    }

    private func itemDidPlayToEnd(generation gen: Int) {
        guard gen == generation, let player = player else {
            return
        }
        if loop {
            player.seek(to: .zero, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
                SD.onMain {
                    guard let self = self, gen == self.generation else { return }
                    self.applyPlayback()
                }
            }
            return
        }
        completed = true
        wantsPlay = false
        player.pause()
        emitProgress(atEnd: true)
        emitState()
    }

    private func fail(_ message: String, generation gen: Int) {
        guard gen == generation else {
            return
        }
        wantsPlay = false
        teardownPlayer()
        failure = message
        emitState()
    }

    private func readyForDisplayChanged() {
        guard let layer = playerLayer, layer.isReadyForDisplay, item != nil, !firstFrameSent else {
            return
        }
        firstFrameSent = true
        refreshVideoSize()
        emitState()
        onFirstFrame?([String: Any]())
    }

    // MARK: - 系统事件

    private func handleInterruption(_ note: Notification) {
        guard let info = note.userInfo,
              let rawType = (info[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.uintValue,
              let type = AVAudioSession.InterruptionType(rawValue: rawType) else {
            return
        }
        switch type {
        case .began:
            // App 被挂起导致的补发中断没有对应的 ended，忽略。
            if (info["AVAudioSessionInterruptionWasSuspendedKey"] as? NSNumber)?.boolValue == true {
                return
            }
            if (info["AVAudioSessionInterruptionReasonKey"] as? NSNumber)?.uintValue == 1 {
                return
            }
            interrupted = true
            applyPlayback()
        case .ended:
            guard interrupted else {
                return
            }
            interrupted = false
            var shouldResume = false
            if let rawOptions = (info[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.uintValue {
                shouldResume = AVAudioSession.InterruptionOptions(rawValue: rawOptions).contains(.shouldResume)
            }
            // 与参考工程一致：只有系统允许恢复时才自动续播中断前在播的视频。
            if !shouldResume {
                wantsPlay = false
            }
            applyPlayback()
        @unknown default:
            break
        }
    }

    private func handleRouteChange(_ note: Notification) {
        guard let info = note.userInfo,
              let rawReason = (info[AVAudioSessionRouteChangeReasonKey] as? NSNumber)?.uintValue,
              let reason = AVAudioSession.RouteChangeReason(rawValue: rawReason) else {
            return
        }
        // 拔出耳机：正在出声的视频暂停，不自动恢复。
        if reason == .oldDeviceUnavailable && wantsPlay && !muted && volume > 0 {
            wantsPlay = false
            applyPlayback()
        }
    }

    // MARK: - 事件上报

    private func currentState() -> String {
        if failure != nil {
            return "error"
        }
        guard let player = player, item != nil else {
            return "idle"
        }
        if completed {
            return "completed"
        }
        if !prepared {
            return "loading"
        }
        if wantsPlay && !isSuspended {
            return player.timeControlStatus == .playing ? "playing" : "buffering"
        }
        return hasStarted ? "paused" : "ready"
    }

    private func emitState(forced: String? = nil) {
        let state = forced ?? currentState()
        let durationMs = currentDurationMs()
        let width = Int(videoSize.width.rounded())
        let height = Int(videoSize.height.rounded())
        let key = "\(state)|\(durationMs)|\(width)|\(height)|\(failure ?? "")"
        if key == lastStateKey {
            return
        }
        lastStateKey = key
        guard let onState = onState else {
            return
        }
        var dict: [String: Any] = [
            "state": state,
            "durationMs": durationMs,
            "width": width,
            "height": height,
        ]
        if let failure = failure {
            dict["error"] = failure
        }
        onState(dict)
    }

    private func emitProgress(atEnd: Bool = false) {
        guard let onProgress = onProgress, let item = item, prepared else {
            return
        }
        let durationMs = currentDurationMs()
        var positionMs = max(0, SDMedia.ms(item.currentTime()))
        if atEnd && durationMs > 0 {
            positionMs = durationMs
        } else if durationMs > 0 {
            positionMs = min(positionMs, durationMs)
        }
        let dict: [String: Any] = [
            "positionMs": positionMs,
            "durationMs": durationMs,
            "bufferedMs": bufferedMs(of: item, durationMs: durationMs),
        ]
        onProgress(dict)
    }

    private func currentDurationMs() -> Int64 {
        guard let item = item, prepared else {
            return 0
        }
        return SDMedia.ms(item.duration)
    }

    private func bufferedMs(of item: AVPlayerItem, durationMs: Int64) -> Int64 {
        let current = CMTimeGetSeconds(item.currentTime())
        var containing: Double?
        var furthest = 0.0
        for value in item.loadedTimeRanges {
            let range = value.timeRangeValue
            let start = CMTimeGetSeconds(range.start)
            let end = CMTimeGetSeconds(CMTimeRangeGetEnd(range))
            guard start.isFinite, end.isFinite else {
                continue
            }
            furthest = max(furthest, end)
            if current.isFinite, current >= start - 0.5, current <= end + 0.5 {
                containing = max(containing ?? 0, end)
            }
        }
        var result = max(0, SDMedia.ms(seconds: containing ?? furthest))
        if durationMs > 0 {
            result = min(result, durationMs)
        }
        return result
    }

    private func refreshVideoSize() {
        guard let item = item else {
            return
        }
        let size = item.presentationSize
        if size.width > 0 && size.height > 0 {
            videoSize = size
        }
    }
}
