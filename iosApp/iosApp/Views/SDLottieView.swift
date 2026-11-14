import Lottie
import UIKit

/// Lottie 动画视图（契约见 shared 的 `SDNativeViews.kt`）。依赖 `lottie-ios` 4.x。
///
/// 属性：src（shared assets 里相对 `common/` 的路径，如 `lottie/music_loading.json`；或 http(s) URL；
///       也接受绝对路径 / file://）、loop（默认 1）、autoPlay（默认 1）、speed
/// 事件：sdComplete（非循环播放自然结束时）
/// 方法：play / pause / stop
@objc(SDLottieView)
final class SDLottieView: UIView, KuiklyRenderViewExportProtocol {

    private let animationView = LottieAnimationView(frame: .zero)

    private var src = ""
    private var loop = true
    private var autoPlay = true
    private var speed: CGFloat = 1
    private var onComplete: KuiklyRenderCallback?

    /// 每次换源自增，丢弃过期的异步加载结果。
    private var loadTicket = 0
    /// 播放意图：动画还没加载完时调用 play，加载完成后补播。
    private var wantsPlay = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        commonInit()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        commonInit()
    }

    private func commonInit() {
        animationView.frame = bounds
        animationView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        animationView.contentMode = .scaleAspectFit
        animationView.isUserInteractionEnabled = false
        animationView.backgroundColor = .clear
        // 进后台暂停、回前台从原位置继续。
        animationView.backgroundBehavior = .pauseAndRestore
        addSubview(animationView)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        animationView.frame = bounds
    }

    override func removeFromSuperview() {
        super.removeFromSuperview()
        loadTicket += 1
        wantsPlay = false
        animationView.stop()
    }

    // MARK: - KuiklyRenderViewExportProtocol

    @objc(hrv_setPropWithKey:propValue:)
    func hrv_setProp(withKey propKey: String, propValue: Any) {
        if css_setProp(withKey: propKey, value: propValue) {
            return
        }
        switch propKey {
        case "src":
            let value = SDMedia.string(propValue)
            if value != src {
                src = value
                scheduleLoad()
            }
        case "loop":
            loop = SDMedia.flag(propValue, fallback: true)
            animationView.loopMode = loop ? .loop : .playOnce
        case "autoPlay":
            let value = SDMedia.flag(propValue, fallback: true)
            let turnedOn = value && !autoPlay
            autoPlay = value
            if turnedOn && animationView.animation != nil && !animationView.isAnimationPlaying {
                play()
            }
        case "speed":
            let value = SDMedia.double(propValue) ?? 1
            speed = value.isFinite && value > 0 ? CGFloat(value) : 1
            animationView.animationSpeed = speed
        case "sdComplete":
            onComplete = SDMedia.eventCallback(propValue)
        default:
            break
        }
    }

    @objc(hrv_callWithMethod:params:callback:)
    func hrv_call(withMethod method: String, params: String?, callback: KuiklyRenderCallback?) {
        switch method {
        case "play":
            play()
        case "pause":
            wantsPlay = false
            animationView.pause()
        case "stop":
            wantsPlay = false
            animationView.stop()
        default:
            break
        }
    }

    // MARK: - 加载

    /// 属性逐个到达，延后到下一个主线程循环再加载，保证 loop / autoPlay / speed 已就位。
    private func scheduleLoad() {
        loadTicket += 1
        let ticket = loadTicket
        DispatchQueue.main.async { [weak self] in
            guard let self = self, ticket == self.loadTicket else { return }
            self.loadAnimation(ticket: ticket)
        }
    }

    private func loadAnimation(ticket: Int) {
        animationView.stop()
        animationView.animation = nil
        guard let url = SDMedia.mediaURL(src) else {
            return
        }
        if SDMedia.isRemote(url) {
            LottieAnimation.loadedFrom(url: url, closure: { [weak self] animation in
                SD.onMain {
                    self?.apply(animation, imageDirectory: nil, ticket: ticket)
                }
            })
            return
        }
        let path = url.path
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            // JSON 解码放到后台，避免大动画卡主线程。
            let animation = LottieAnimation.filepath(path)
            DispatchQueue.main.async {
                self?.apply(animation, imageDirectory: url.deletingLastPathComponent().path, ticket: ticket)
            }
        }
    }

    private func apply(_ animation: LottieAnimation?, imageDirectory: String?, ticket: Int) {
        guard ticket == loadTicket, let animation = animation else {
            return
        }
        if let imageDirectory = imageDirectory {
            // 动画里引用的外部图片（images/img_0.png）相对 JSON 所在目录查找。
            animationView.imageProvider = FilepathImageProvider(filepath: imageDirectory)
        }
        animationView.animation = animation
        animationView.loopMode = loop ? .loop : .playOnce
        animationView.animationSpeed = speed
        if autoPlay || wantsPlay {
            play()
        }
    }

    // MARK: - 播放

    private func play() {
        wantsPlay = true
        guard animationView.animation != nil else {
            return
        }
        animationView.loopMode = loop ? .loop : .playOnce
        animationView.animationSpeed = speed
        animationView.play { [weak self] finished in
            SD.onMain {
                guard let self = self, finished, !self.loop else { return }
                self.wantsPlay = false
                self.onComplete?([String: Any]())
            }
        }
    }
}
