import Foundation
import CryptoKit

/// Core 模块（Http / App / File / Crypto）共用的小工具。

enum SDURLUtil {

    /// 字符串 → URL。含中文、空格等非法字符时自动做百分号转义（已有的 %XX 保持不变）。
    static func make(_ text: String) -> URL? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if let url = URL(string: trimmed), url.scheme != nil {
            return url
        }
        var allowed = CharacterSet.urlQueryAllowed
        allowed.insert(charactersIn: "#%[]")
        guard let escaped = trimmed.addingPercentEncoding(withAllowedCharacters: allowed),
              let url = URL(string: escaped), url.scheme != nil else {
            return nil
        }
        return url
    }
}

enum SDPath {

    /// Kuikly 侧传来的本地路径 → 文件系统绝对路径（兼容 `file://` 前缀与百分号转义）。
    static func normalize(_ path: String) -> String {
        let trimmed = path.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("file://") else { return trimmed }
        if let url = URL(string: trimmed), url.isFileURL, !url.path.isEmpty {
            return url.path
        }
        let raw = String(trimmed.dropFirst("file://".count))
        return raw.removingPercentEncoding ?? raw
    }

    static func fileURL(_ path: String) -> URL {
        return URL(fileURLWithPath: normalize(path))
    }

    /// `Library/Caches`
    static var cachesDirectory: String {
        return NSSearchPathForDirectoriesInDomains(.cachesDirectory, .userDomainMask, true).first
            ?? (NSHomeDirectory() as NSString).appendingPathComponent("Library/Caches")
    }

    /// `Documents`
    static var documentsDirectory: String {
        return NSSearchPathForDirectoriesInDomains(.documentDirectory, .userDomainMask, true).first
            ?? (NSHomeDirectory() as NSString).appendingPathComponent("Documents")
    }

    /// `tmp`（末尾不带斜杠）
    static var systemTempDirectory: String {
        let path = NSTemporaryDirectory()
        return path.hasSuffix("/") ? String(path.dropLast()) : path
    }

    /// 确保文件所在目录存在。
    static func ensureParentDirectory(of path: String) {
        let parent = (path as NSString).deletingLastPathComponent
        guard !parent.isEmpty else { return }
        try? FileManager.default.createDirectory(atPath: parent, withIntermediateDirectories: true, attributes: nil)
    }
}

enum SDHash {

    static func hex<S: Sequence>(_ bytes: S) -> String where S.Element == UInt8 {
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    static func md5Hex(_ data: Data) -> String {
        return hex(Insecure.MD5.hash(data: data))
    }

    static func md5Hex(_ text: String) -> String {
        return md5Hex(Data(text.utf8))
    }

    static func sha256Hex(_ data: Data) -> String {
        return hex(SHA256.hash(data: data))
    }

    /// 流式计算文件 MD5（大文件不整体读入内存）；文件不可读返回 nil。
    static func md5Hex(fileAtPath path: String) -> String? {
        guard let handle = FileHandle(forReadingAtPath: path) else { return nil }
        defer { try? handle.close() }
        var hasher = Insecure.MD5()
        while true {
            let chunk: Data? = autoreleasepool {
                try? handle.read(upToCount: 1024 * 1024)
            }
            guard let data = chunk, !data.isEmpty else {
                break
            }
            hasher.update(data: data)
        }
        return hex(hasher.finalize())
    }
}
