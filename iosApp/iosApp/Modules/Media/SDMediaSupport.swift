import AVFoundation
import Foundation
import UIKit

/// 媒体模块 / 原生视图共用的小工具（路径解析、时间换算、视图属性取值）。
/// 通用的入参解析、回调包装见 `App/SDModuleSupport.swift`（`SD` / `SDOnce` / `EventBroadcaster`）。
enum SDMedia {

    // MARK: - URL / 路径

    /// 把 Kuikly 侧传来的媒体地址转成 URL：
    /// - `http(s)://…` 远程地址；
    /// - `file://…` 或 `/abs/path` 本地文件；
    /// - `assets://common/x.mp4`、`guide/3.mp4` 这类 shared assets 相对路径（相对 `common/`）。
    static func mediaURL(_ raw: String) -> URL? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty {
            return nil
        }
        let lower = text.lowercased()
        if lower.hasPrefix("http://") || lower.hasPrefix("https://") {
            return remoteURL(text)
        }
        if lower.hasPrefix("file://") {
            if let url = URL(string: text), url.isFileURL {
                return url
            }
            return URL(fileURLWithPath: String(text.dropFirst("file://".count)))
        }
        if text.hasPrefix("/") {
            return URL(fileURLWithPath: text)
        }
        if lower.hasPrefix("assets://") {
            return assetURL(String(text.dropFirst("assets://".count)))
        }
        return assetURL(text)
    }

    /// 本地文件地址（`file://…` 或绝对路径）；其它形式返回 nil。
    static func fileURL(_ raw: String) -> URL? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty {
            return nil
        }
        if text.lowercased().hasPrefix("file://") {
            if let url = URL(string: text), url.isFileURL {
                return url
            }
            return URL(fileURLWithPath: String(text.dropFirst("file://".count)))
        }
        if text.hasPrefix("/") {
            return URL(fileURLWithPath: text)
        }
        return nil
    }

    static func isRemote(_ url: URL) -> Bool {
        let scheme = (url.scheme ?? "").lowercased()
        return scheme == "http" || scheme == "https"
    }

    /// 远程地址；含中文 / 空格等未转义字符时先做一次百分号编码。
    static func remoteURL(_ text: String) -> URL? {
        if let url = URL(string: text) {
            return url
        }
        let allowed = CharacterSet.urlQueryAllowed.union(CharacterSet(charactersIn: "%#"))
        if let encoded = text.addingPercentEncoding(withAllowedCharacters: allowed) {
            return URL(string: encoded)
        }
        return nil
    }

    /// shared 模块的 assets（`shared/src/commonMain/assets/**`）在包内的位置。
    ///
    /// `shared.podspec` 的 `resources = ['src/commonMain/assets/**']` 会把 `common/` 整个目录拷进 App 包根目录
    /// （Kuikly 自己的 `assets://common/x.png` 也是按 `mainBundle/common/x.png` 找的）。
    /// 实际定位统一走宿主的 `SDKuiklyAdapters.assetURL(relativePath:)`，与图片资源保持同一套规则。
    /// `relative` 可以带或不带 `common/` 前缀（契约里 Lottie 的 src 是相对 `common/` 的路径）。
    static func assetURL(_ relative: String) -> URL? {
        var rel = relative
        while rel.hasPrefix("/") {
            rel.removeFirst()
        }
        if rel.isEmpty {
            return nil
        }
        if !rel.hasPrefix("common/"), let url = SDKuiklyAdapters.assetURL(relativePath: "common/" + rel) {
            return url
        }
        return SDKuiklyAdapters.assetURL(relativePath: rel)
    }

    // MARK: - 文件

    static func fileSize(_ url: URL) -> Int64 {
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
              let size = attrs[.size] as? NSNumber else {
            return 0
        }
        return size.int64Value
    }

    /// App 临时目录下的一个新文件地址（目录保证存在，文件本身不创建）。
    static func tempFileURL(prefix: String, ext: String) -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sd_media", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: nil)
        let name = prefix + "_" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        return ext.isEmpty ? dir.appendingPathComponent(name) : dir.appendingPathComponent(name + "." + ext)
    }

    /// 确保目标文件的上级目录存在，并删掉同名旧文件。
    static func prepareDestination(_ url: URL) {
        let fm = FileManager.default
        try? fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true, attributes: nil)
        if fm.fileExists(atPath: url.path) {
            try? fm.removeItem(at: url)
        }
    }

    // MARK: - 时间

    static func time(ms: Int64) -> CMTime {
        return CMTime(value: CMTimeValue(ms), timescale: 1000)
    }

    /// CMTime → 毫秒；无效 / 不定长返回 0。
    static func ms(_ time: CMTime) -> Int64 {
        guard time.isNumeric else {
            return 0
        }
        let seconds = CMTimeGetSeconds(time)
        guard seconds.isFinite else {
            return 0
        }
        return Int64((seconds * 1000).rounded())
    }

    static func ms(seconds: Double) -> Int64 {
        guard seconds.isFinite else {
            return 0
        }
        return Int64((seconds * 1000).rounded())
    }

    // MARK: - 原生视图属性取值（hrv_setPropWithKey 的 propValue）

    static func double(_ value: Any) -> Double? {
        if let number = value as? NSNumber {
            return number.doubleValue
        }
        if let text = value as? String {
            return Double(text)
        }
        return nil
    }

    static func flag(_ value: Any, fallback: Bool) -> Bool {
        if let number = value as? NSNumber {
            return number.boolValue
        }
        if let text = value as? String {
            let lower = text.lowercased()
            if lower == "1" || lower == "true" {
                return true
            }
            if lower == "0" || lower == "false" || lower.isEmpty {
                return false
            }
        }
        return fallback
    }

    static func string(_ value: Any) -> String {
        if let text = value as? String {
            return text
        }
        if let number = value as? NSNumber {
            return number.stringValue
        }
        return ""
    }

    /// 事件属性：Kuikly 把事件回调作为 propValue（ObjC block）传进来。
    /// Swift 里 `as? KuiklyRenderCallback` 对 ObjC block 不成立，统一交给 ObjC 侧的 `SDKuiklyArgs` 取出。
    static func eventCallback(_ value: Any) -> KuiklyRenderCallback? {
        let object = value as AnyObject
        guard let blockClass = NSClassFromString("NSBlock"), object.isKind(of: blockClass) else {
            return nil
        }
        let args = NSDictionary(object: object, forKey: KR_CALLBACK_KEY as NSString)
        return SDKuiklyArgs.callback(args)
    }

    /// 解析视图方法的 JSON 入参（`hrv_callWithMethod` 的 params）。
    static func methodParams(_ params: String?) -> [String: Any] {
        guard let text = params, !text.isEmpty else {
            return [:]
        }
        return SD.jsonObject(text) ?? [:]
    }
}
