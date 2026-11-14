import AVFoundation
import Foundation

/// 全局唯一的音频播放器（AVPlayer），跨页面存活。
///
/// - 所有播放控制都在主线程；`currentSnapshot()` 可在任意线程调用（同步的 `getState`）。
/// - 事件通过 `events` 向每个页面的 `SDAudioPlayerModule` 广播。
/// - 裁剪音源（clipStartMs / clipEndMs）对外的 position / duration / buffered 都是**相对裁剪起点**的值，
///   与 just_audio 的 ClippingAudioSource、Media3 的 ClippingConfiguration 一致。
/// - `playing` 表示「播放意图」（play 之后、pause / stop / 播完之前为 true），与 just_audio 的 `playing` 一致；
///   系统中断、拔耳机时原生不改它，只上报事件，由 Kuikly 侧决定暂停 / 恢复。
final class SDAudioPlayerManager: SDAudioOutputParticipant {

    static let shared = SDAudioPlayerManager()

    /// 各页面模块注册的常驻回调。
    let events = EventBroadcaster()

    // MARK: - 线程安全快照

    private struct Snapshot {
        var state = "idle"
        var playing = false
        var positionMs: Int64 = 0
        var bufferedMs: Int64 = 0
        var durationMs: Int64 = 0
    }

    private let snapshotLock = NSLock()
    private var snapshot = Snapshot()

    func currentSnapshot() -> [String: Any] {
        snapshotLock.lock()
        let s = snapshot
        snapshotLock.unlock()
        return [
            "state": s.state,
            "playing": s.playing,
            "positionMs": s.positionMs,
            "bufferedMs": s.bufferedMs,
            "durationMs": s.durationMs,
        ]
    }

    private func mutateSnapshot(_ block: (inout Snapshot) -> Void) {
        snapshotLock.lock()
        block(&snapshot)
        snapshotLock.unlock()
    }

    // MARK: - 主线程状态

    private struct Source {
        let url: URL
        let headers: [String: String]
        let clipStart: CMTime
        let clipEnd: CMTime?
    }

    private var player: AVPlayer?
    private var item: AVPlayerItem?
    private var timeObserver: Any?
    private var playerObservations: [NSKeyValueObservation] = []
    private var itemObservations: [NSKeyValueObservation] = []
    private var itemNotifications: [NSObjectProtocol] = []
    private var sessionNotifications: [NSObjectProtocol] = []

    private var source: Source?
    /// 每次换源 / 停止自增，用来丢弃过期的异步回调。
    private var generation = 0
    private var prepareStarted = false
    private var prepared = false
    private var completed = false
    private var playWhenReady = false
    private var loopOne = false
    private var volume: Float = 1
    private var speed: Float = 1
    private var pendingPrepare: SDOnce?

    private var lastState = "idle"
    private var lastPlaying = false

    private init() {
        let center = NotificationCenter.default
        sessionNotifications.append(center.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] note in
            self?.handleInterruption(note)
        })
        sessionNotifications.append(center.addObserver(
            forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main
        ) { [weak self] note in
            self?.handleRouteChange(note)
        })
        // `shared` 可能首次在 Kuikly 线程（同步的 getState）上创建；互斥表只在主线程读写。
        SD.onMain {
            SDMediaAudioFocus.shared.register(self)
        }
    }

    // MARK: - SDAudioOutputParticipant

    var sdOutputIsPlaying: Bool {
        return playWhenReady && item != nil && !completed
    }

    var sdOutputIsExclusiveAudible: Bool {
        return sdOutputIsPlaying && volume > 0
    }

    func sdOutputPauseForHandoff() {
        pause()
    }

    // MARK: - 对外接口（主线程）

    /// params: {uri, headers?, clipStartMs?, clipEndMs?, loopOne, autoPlay, preload?} → {ok, durationMs?, error?}
    func setSource(_ params: [String: Any], done: SDOnce) {
        guard let url = SDMedia.mediaURL(params.sdString("uri")) else {
            done.failure("invalid uri")
            return
        }
        // 上一个还没准备完的 setSource 以失败结束，保证每次调用都恰好回调一次。
        pendingPrepare?.failure("superseded")
        pendingPrepare = nil

        let startMs = max(0, params.sdOptInt64("clipStartMs") ?? 0)
        var clipEnd: CMTime?
        if let endMs = params.sdOptInt64("clipEndMs"), endMs > startMs {
            clipEnd = SDMedia.time(ms: endMs)
        }
        let newSource = Source(
            url: url,
            headers: params.sdStringDict("headers"),
            clipStart: SDMedia.time(ms: startMs),
            clipEnd: clipEnd
        )
        source = newSource
        loopOne = params.sdBool("loopOne", false)
        playWhenReady = params.sdBool("autoPlay", true)
        pendingPrepare = done
        loadSource(newSource)
        // preload=false（直播 AAC 等）：不等 readyToPlay，设置完音源就回调，缓冲交给播放阶段。
        if !params.sdBool("preload", true), let pending = pendingPrepare {
            pendingPrepare = nil
            pending.success()
        }
    }

    func play() {
        playWhenReady = true
        guard item != nil else {
            // stop 之后再 play：重新加载上一个音源。
            if let source = source {
                loadSource(source)
            } else {
                playWhenReady = false
                refreshState()
            }
            return
        }
        if completed {
            completed = false
            let gen = generation
            seekItem(to: source?.clipStart ?? .zero) { [weak self] in
                guard let self = self, gen == self.generation else { return }
                self.publishPosition()
                if self.playWhenReady {
                    self.startPlayback()
                }
                self.refreshState()
            }
        } else if prepared {
            startPlayback()
        }
        refreshState()
    }

    func pause() {
        playWhenReady = false
        player?.pause()
        refreshState()
    }

    /// 停止并释放当前音源的缓冲 / 解码资源；保留音源配置，之后 `play()` 会重新加载。
    func stop() {
        playWhenReady = false
        generation += 1
        pendingPrepare?.failure("stopped")
        pendingPrepare = nil
        detachItem()
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        prepareStarted = false
        prepared = false
        completed = false
        mutateSnapshot { s in
            s.positionMs = 0
            s.bufferedMs = 0
        }
        refreshState()
    }

    func releasePlayer() {
        stop()
        source = nil
        if let player = player, let token = timeObserver {
            player.removeTimeObserver(token)
        }
        timeObserver = nil
        for observation in playerObservations {
            observation.invalidate()
        }
        playerObservations = []
        player = nil
        mutateSnapshot { s in
            s.durationMs = 0
        }
    }

    func seek(positionMs: Int64) {
        guard let item = item, let source = source, prepared else {
            return
        }
        let startSeconds = CMTimeGetSeconds(source.clipStart)
        var target = startSeconds + Double(max(0, positionMs)) / 1000
        if let end = clipEndSeconds(of: item, source: source) {
            target = min(target, end)
        }
        completed = false
        let gen = generation
        seekItem(to: CMTime(seconds: target, preferredTimescale: 1000)) { [weak self] in
            guard let self = self, gen == self.generation else { return }
            self.publishPosition()
            self.refreshState()
        }
        refreshState()
    }

    func setVolume(_ value: Double) {
        volume = Float(min(1, max(0, value)))
        player?.volume = volume
    }

    func setSpeed(_ value: Double) {
        speed = Float(min(4, max(0.25, value)))
        if let player = player, player.rate != 0 {
            player.rate = speed
        }
    }

    func setLoopOne(_ on: Bool) {
        loopOne = on
    }

    // MARK: - 加载

    private func ensurePlayer() -> AVPlayer {
        if let player = player {
            return player
        }
        let created = AVPlayer()
        created.actionAtItemEnd = .pause
        created.volume = volume
        timeObserver = created.addPeriodicTimeObserver(
            forInterval: CMTime(value: 200, timescale: 1000), queue: .main
        ) { [weak self] _ in
            self?.publishPosition()
        }
        playerObservations.append(created.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            SD.onMain { self?.refreshState() }
        })
        player = created
        return created
    }

    private func loadSource(_ source: Source) {
        generation += 1
        let gen = generation
        detachItem()
        prepareStarted = false
        prepared = false
        completed = false

        var options: [String: Any] = [:]
        if SDMedia.isRemote(source.url) && !source.headers.isEmpty {
            options["AVURLAssetHTTPHeaderFieldsKey"] = source.headers
        }
        let asset = AVURLAsset(url: source.url, options: options.isEmpty ? nil : options)
        let newItem = AVPlayerItem(asset: asset)
        newItem.audioTimePitchAlgorithm = .timeDomain
        if let end = source.clipEnd {
            newItem.forwardPlaybackEndTime = end
        }
        item = newItem
        attachObservers(to: newItem, generation: gen)

        let player = ensurePlayer()
        player.pause()
        player.replaceCurrentItem(with: newItem)

        mutateSnapshot { s in
            s.positionMs = 0
            s.bufferedMs = 0
            s.durationMs = 0
        }
        refreshState()
    }

    private func attachObservers(to item: AVPlayerItem, generation gen: Int) {
        itemObservations.append(item.observe(\.status, options: [.new]) { [weak self] observed, _ in
            SD.onMain { self?.itemStatusChanged(observed, generation: gen) }
        })
        itemObservations.append(item.observe(\.isPlaybackBufferEmpty, options: [.new]) { [weak self] _, _ in
            SD.onMain { self?.refreshState() }
        })
        itemObservations.append(item.observe(\.isPlaybackLikelyToKeepUp, options: [.new]) { [weak self] _, _ in
            SD.onMain { self?.refreshState() }
        })
        itemObservations.append(item.observe(\.loadedTimeRanges, options: [.new]) { [weak self] _, _ in
            SD.onMain { self?.updateBuffered(generation: gen) }
        })
        itemObservations.append(item.observe(\.duration, options: [.new]) { [weak self] _, _ in
            SD.onMain { self?.durationChanged(generation: gen) }
        })

        let center = NotificationCenter.default
        itemNotifications.append(center.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main
        ) { [weak self] _ in
            self?.itemDidPlayToEnd(generation: gen)
        })
        itemNotifications.append(center.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main
        ) { [weak self] note in
            let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            self?.fail(message: error?.localizedDescription ?? "playback failed", generation: gen)
        })
    }

    private func detachItem() {
        for observation in itemObservations {
            observation.invalidate()
        }
        itemObservations = []
        for token in itemNotifications {
            NotificationCenter.default.removeObserver(token)
        }
        itemNotifications = []
        item = nil
    }

    private func itemStatusChanged(_ observed: AVPlayerItem, generation gen: Int) {
        guard gen == generation, observed === item else {
            return
        }
        switch observed.status {
        case .readyToPlay:
            if prepareStarted {
                return
            }
            prepareStarted = true
            let start = source?.clipStart ?? .zero
            if CMTimeCompare(start, .zero) > 0 {
                // 裁剪音源：先精确定位到起点，再对外宣布就绪。
                seekItem(to: start) { [weak self] in
                    self?.finishPrepare(generation: gen)
                }
            } else {
                finishPrepare(generation: gen)
            }
        case .failed:
            fail(message: observed.error?.localizedDescription ?? "load failed", generation: gen)
        default:
            break
        }
    }

    private func finishPrepare(generation gen: Int) {
        guard gen == generation, item != nil, !prepared else {
            return
        }
        prepared = true
        let durationMs = currentDurationMs()
        mutateSnapshot { s in
            s.durationMs = durationMs
        }
        emit(["event": "duration", "durationMs": durationMs])

        let done = pendingPrepare
        pendingPrepare = nil
        done?.success(["durationMs": durationMs])

        if playWhenReady {
            startPlayback()
        }
        refreshState()
    }

    private func fail(message: String, generation gen: Int) {
        guard gen == generation else {
            return
        }
        generation += 1
        playWhenReady = false
        detachItem()
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        prepareStarted = false
        prepared = false
        completed = false

        let done = pendingPrepare
        pendingPrepare = nil
        done?.failure(message)

        // 先上报状态回到 idle（playing=false），最后再发 error：Kuikly 侧把 error 映射成错误态，不能被随后的 idle 覆盖。
        refreshState()
        emit(["event": "error", "msg": message])
    }

    private func startPlayback(claimFocus: Bool = true) {
        guard let player = player, item != nil, prepared else {
            return
        }
        if claimFocus {
            // 应用内互斥：先让其它有声独占播放器（视频）停下，并把会话切到独占的 .playback。
            SDMediaAudioFocus.shared.claimExclusive(for: self)
        }
        player.volume = volume
        player.rate = speed
    }

    private func seekItem(to time: CMTime, completion: @escaping () -> Void) {
        guard let item = item else {
            completion()
            return
        }
        item.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero) { _ in
            SD.onMain(completion)
        }
    }

    // MARK: - 播放过程

    private func itemDidPlayToEnd(generation gen: Int) {
        guard gen == generation, item != nil else {
            return
        }
        if loopOne && playWhenReady {
            seekItem(to: source?.clipStart ?? .zero) { [weak self] in
                guard let self = self, gen == self.generation, self.playWhenReady else { return }
                self.publishPosition()
                self.startPlayback(claimFocus: false)
            }
            return
        }
        completed = true
        playWhenReady = false
        player?.pause()
        let durationMs = currentDurationMs()
        mutateSnapshot { s in
            if durationMs > 0 {
                s.positionMs = durationMs
            }
        }
        emitPosition()
        refreshState()
    }

    private func durationChanged(generation gen: Int) {
        guard gen == generation, prepared else {
            return
        }
        let durationMs = currentDurationMs()
        var changed = false
        mutateSnapshot { s in
            if s.durationMs != durationMs {
                s.durationMs = durationMs
                changed = true
            }
        }
        if changed {
            emit(["event": "duration", "durationMs": durationMs])
        }
    }

    private func updateBuffered(generation gen: Int) {
        guard gen == generation, let item = item, let source = source else {
            return
        }
        let buffered = bufferedMs(of: item, source: source)
        mutateSnapshot { s in
            s.bufferedMs = buffered
        }
    }

    /// 周期回调（约 200ms）以及 seek 完成后：刷新快照并上报 position 事件。
    private func publishPosition() {
        guard let item = item, let source = source, prepared, !completed else {
            return
        }
        let durationMs = currentDurationMs()
        let current = CMTimeGetSeconds(item.currentTime())
        let start = CMTimeGetSeconds(source.clipStart)
        var positionMs: Int64 = 0
        if current.isFinite && start.isFinite {
            positionMs = max(0, SDMedia.ms(seconds: current - start))
        }
        if durationMs > 0 {
            positionMs = min(positionMs, durationMs)
        }
        let buffered = bufferedMs(of: item, source: source)
        mutateSnapshot { s in
            s.positionMs = positionMs
            s.bufferedMs = buffered
        }
        emitPosition()
    }

    private func emitPosition() {
        snapshotLock.lock()
        let s = snapshot
        snapshotLock.unlock()
        emit([
            "event": "position",
            "positionMs": s.positionMs,
            "bufferedMs": s.bufferedMs,
            "durationMs": s.durationMs,
        ])
    }

    private func refreshState() {
        let state: String
        if item == nil {
            state = "idle"
        } else if completed {
            state = "completed"
        } else if !prepared {
            state = "loading"
        } else if playWhenReady, let player = player, player.timeControlStatus == .waitingToPlayAtSpecifiedRate {
            state = "buffering"
        } else if let item = item, item.isPlaybackBufferEmpty, !item.isPlaybackLikelyToKeepUp {
            state = "buffering"
        } else {
            state = "ready"
        }
        let playing = playWhenReady && item != nil && !completed
        if state == lastState && playing == lastPlaying {
            return
        }
        lastState = state
        lastPlaying = playing
        mutateSnapshot { s in
            s.state = state
            s.playing = playing
        }
        emit(["event": "state", "state": state, "playing": playing])
    }

    // MARK: - 时长 / 缓冲

    /// 可播放区间的终点（秒，绝对时间）；直播等不定长且没有裁剪终点时为 nil。
    private func clipEndSeconds(of item: AVPlayerItem, source: Source) -> Double? {
        var end: Double?
        let total = item.duration
        if total.isNumeric {
            let seconds = CMTimeGetSeconds(total)
            if seconds.isFinite {
                end = seconds
            }
        }
        if let clipEnd = source.clipEnd {
            let seconds = CMTimeGetSeconds(clipEnd)
            if seconds.isFinite {
                if let known = end {
                    end = min(known, seconds)
                } else {
                    end = seconds
                }
            }
        }
        return end
    }

    private func currentDurationMs() -> Int64 {
        guard let item = item, let source = source,
              let end = clipEndSeconds(of: item, source: source) else {
            return 0
        }
        let start = CMTimeGetSeconds(source.clipStart)
        guard start.isFinite else {
            return 0
        }
        return max(0, SDMedia.ms(seconds: end - start))
    }

    private func bufferedMs(of item: AVPlayerItem, source: Source) -> Int64 {
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
        var bufferedEnd = containing ?? furthest
        if let limit = clipEndSeconds(of: item, source: source) {
            bufferedEnd = min(bufferedEnd, limit)
        }
        let start = CMTimeGetSeconds(source.clipStart)
        guard start.isFinite else {
            return 0
        }
        return max(0, SDMedia.ms(seconds: bufferedEnd - start))
    }

    // MARK: - 会话事件（只上报，不自动处理）

    private func handleInterruption(_ note: Notification) {
        guard let info = note.userInfo,
              let rawType = (info[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.uintValue,
              let type = AVAudioSession.InterruptionType(rawValue: rawType) else {
            return
        }
        switch type {
        case .began:
            // App 被挂起导致的「补发」中断没有对应的 ended，不上报（否则 Kuikly 侧暂停后永远等不到恢复）。
            if (info["AVAudioSessionInterruptionWasSuspendedKey"] as? NSNumber)?.boolValue == true {
                return
            }
            if (info["AVAudioSessionInterruptionReasonKey"] as? NSNumber)?.uintValue == 1 {
                return
            }
            emit(["event": "interruption", "begin": true, "type": "pause"])
        case .ended:
            var shouldResume = false
            if let rawOptions = (info[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.uintValue {
                shouldResume = AVAudioSession.InterruptionOptions(rawValue: rawOptions).contains(.shouldResume)
            }
            // 与 audio_session 插件一致：系统允许恢复 → type=pause（Kuikly 侧据此自动续播），否则 unknown。
            emit(["event": "interruption", "begin": false, "type": shouldResume ? "pause" : "unknown"])
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
        if reason == .oldDeviceUnavailable {
            emit(["event": "becomingNoisy"])
        }
    }

    private func emit(_ dict: [String: Any]) {
        events.emit(dict)
    }
}
