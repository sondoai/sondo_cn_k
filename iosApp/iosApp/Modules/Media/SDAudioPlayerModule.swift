import Foundation

/// 全局音频播放器的 Kuikly 模块（契约见 shared 的 `SDMediaModules.kt`）。
///
/// 模块实例每个页面一份，真正的播放器是进程内单例 `SDAudioPlayerManager`；
/// 每个页面通过 `addEventListener` 注册的常驻回调都会收到广播，页面销毁（模块释放）时自动注销。
@objc(SDAudioPlayerModule)
@objcMembers
final class SDAudioPlayerModule: KRBaseModule {

    deinit {
        SDAudioPlayerManager.shared.events.remove(ownerId: ObjectIdentifier(self))
    }

    /// params: {uri, headers?, clipStartMs?, clipEndMs?, loopOne, autoPlay} → {ok, durationMs?, error?}
    func setSource(_ args: NSDictionary) {
        let params = SD.params(args)
        let done = SD.once(args)
        SD.onMain {
            SDAudioPlayerManager.shared.setSource(params, done: done)
        }
    }

    func play(_ args: NSDictionary) {
        SD.onMain {
            SDAudioPlayerManager.shared.play()
        }
    }

    func pause(_ args: NSDictionary) {
        SD.onMain {
            SDAudioPlayerManager.shared.pause()
        }
    }

    func stop(_ args: NSDictionary) {
        SD.onMain {
            SDAudioPlayerManager.shared.stop()
        }
    }

    /// params: {positionMs}
    func seek(_ args: NSDictionary) {
        let positionMs = SD.params(args).sdInt64("positionMs", 0)
        SD.onMain {
            SDAudioPlayerManager.shared.seek(positionMs: positionMs)
        }
    }

    /// params: {volume: 0..1}
    func setVolume(_ args: NSDictionary) {
        let volume = SD.params(args).sdDouble("volume", 1)
        SD.onMain {
            SDAudioPlayerManager.shared.setVolume(volume)
        }
    }

    /// params: {speed}
    func setSpeed(_ args: NSDictionary) {
        let speed = SD.params(args).sdDouble("speed", 1)
        SD.onMain {
            SDAudioPlayerManager.shared.setSpeed(speed)
        }
    }

    /// params: {on}
    func setLoopOne(_ args: NSDictionary) {
        let on = SD.params(args).sdBool("on", false)
        SD.onMain {
            SDAudioPlayerManager.shared.setLoopOne(on)
        }
    }

    /// sync（Kuikly 线程）→ JSON 字符串 {playing, positionMs, bufferedMs, durationMs, state}
    func getState(_ args: NSDictionary) -> NSString {
        return SD.jsonResult(SDAudioPlayerManager.shared.currentSnapshot())
    }

    /// params: {active} → {ok}
    func setSessionActive(_ args: NSDictionary) {
        let active = SD.params(args).sdBool("active", true)
        let done = SD.once(args)
        SD.onMain {
            if SDMediaAudioFocus.shared.setSessionActive(active) {
                done.success()
            } else {
                done.failure("audio session error")
            }
        }
    }

    /// Kuikly 侧方法名是 `release`；Swift 里不能直接叫 release，用显式 selector 导出。
    @objc(release:)
    func releasePlayer(_ args: NSDictionary) {
        SD.onMain {
            SDAudioPlayerManager.shared.releasePlayer()
        }
    }

    /// 常驻回调：{event: "state" | "position" | "duration" | "error" | "interruption" | "becomingNoisy", ...}
    func addEventListener(_ args: NSDictionary) {
        guard let callback = SD.callback(args) else {
            return
        }
        SDAudioPlayerManager.shared.events.add(owner: self, callback: callback)
    }
}
