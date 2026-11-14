import AVFoundation
import Foundation

/// 参与「应用内有声输出互斥」的播放器：全局音频播放器（`SDAudioPlayerManager`）和每个 `SDVideoView`。
protocol SDAudioOutputParticipant: AnyObject {
    /// 当前是否在播放（含静音播放）。
    var sdOutputIsPlaying: Bool { get }
    /// 当前是否在播放、有声、并且要求独占输出。
    var sdOutputIsExclusiveAudible: Bool { get }
    /// 被另一个有声输出抢占：暂停，不自动恢复。
    func sdOutputPauseForHandoff()
}

/// AVAudioSession 配置 + 应用内音频 / 视频互斥（仅主线程使用）。
///
/// 规则与参考工程一致（`audio_output_coordinator.dart` + `app_video_audio.dart`）：
/// - 有声且独占的播放器（音频播放器；`mixWithOthers = 0` 且未静音的视频）开始播放前，
///   先暂停其它正在出声的独占播放器，再把会话设为 `.playback`（不混音）并激活；
/// - 静音视频或 `mixWithOthers = 1` 的视频只播画面：不暂停任何人；仅当没有独占播放器在用会话时，
///   才把会话设为 `.playback + .mixWithOthers`，避免打断其它 App 的音乐。
final class SDMediaAudioFocus {

    static let shared = SDMediaAudioFocus()

    private let participants = NSHashTable<AnyObject>.weakObjects()

    /// Kuikly 侧通过 `SDAudioPlayerModule.setSessionActive(true)` 申请了音频焦点。
    private(set) var audioModuleHoldsSession = false

    private init() {}

    func register(_ participant: SDAudioOutputParticipant) {
        participants.add(participant)
    }

    func unregister(_ participant: SDAudioOutputParticipant) {
        participants.remove(participant)
    }

    private func others(except owner: SDAudioOutputParticipant?) -> [SDAudioOutputParticipant] {
        var result: [SDAudioOutputParticipant] = []
        for object in participants.allObjects {
            guard let participant = object as? SDAudioOutputParticipant else {
                continue
            }
            if let owner = owner, participant === owner {
                continue
            }
            result.append(participant)
        }
        return result
    }

    /// 有声独占播放器即将开始播放。
    func claimExclusive(for owner: SDAudioOutputParticipant) {
        for other in others(except: owner) where other.sdOutputIsExclusiveAudible {
            other.sdOutputPauseForHandoff()
        }
        configureCategory(mixWithOthers: false)
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    /// 混播（或静音）播放器即将开始播放。
    func prepareMixing(for owner: SDAudioOutputParticipant) {
        if audioModuleHoldsSession {
            return
        }
        for other in others(except: owner) where other.sdOutputIsExclusiveAudible {
            return
        }
        configureCategory(mixWithOthers: true)
    }

    /// `SDAudioPlayerModule.setSessionActive`。
    func setSessionActive(_ active: Bool) -> Bool {
        let session = AVAudioSession.sharedInstance()
        if active {
            audioModuleHoldsSession = true
            let configured = configureCategory(mixWithOthers: false)
            do {
                try session.setActive(true)
                return configured
            } catch {
                return false
            }
        }
        audioModuleHoldsSession = false
        // 还有播放器在出画面 / 出声时不能关会话：系统会强行停掉它们（AVAudioSessionErrorCodeIsBusy）。
        for participant in others(except: nil) where participant.sdOutputIsPlaying {
            return true
        }
        do {
            try session.setActive(false, options: .notifyOthersOnDeactivation)
            return true
        } catch {
            return false
        }
    }

    @discardableResult
    private func configureCategory(mixWithOthers: Bool) -> Bool {
        let session = AVAudioSession.sharedInstance()
        let options: AVAudioSession.CategoryOptions = mixWithOthers ? [.mixWithOthers] : []
        if session.category == .playback && session.categoryOptions == options {
            return true
        }
        do {
            try session.setCategory(.playback, mode: .default, options: options)
            return true
        } catch {
            return false
        }
    }
}
