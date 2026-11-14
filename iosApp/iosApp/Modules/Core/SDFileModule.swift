import Foundation
import UIKit

/// 文件操作（路径均为绝对路径，兼容 `file://` 前缀）。契约见 shared/.../nativeapi/SDAppModule.kt（SDFileModule）。
/// 全部异步：模块方法在主线程进入，实际 IO 在后台队列执行，回调恰好一次。
@objc(SDFileModule)
@objcMembers
final class SDFileModule: KRBaseModule {

    /// 文件 IO 队列（并发；各操作互不依赖）。
    private static let ioQueue = DispatchQueue(label: "com.sondo.file.io", qos: .userInitiated, attributes: .concurrent)

    // MARK: - 模块方法

    /// → {ok, exists}
    func exists(_ args: NSDictionary) {
        let path = SDFileModule.path(args)
        let done = SD.once(args)
        SDFileModule.ioQueue.async {
            done.resolve(SD.success(["exists": !path.isEmpty && FileManager.default.fileExists(atPath: path)]))
        }
    }

    /// → {ok, size}；不存在为 -1
    func size(_ args: NSDictionary) {
        let path = SDFileModule.path(args)
        let done = SD.once(args)
        SDFileModule.ioQueue.async {
            done.resolve(SD.success(["size": SDFileModule.fileSize(path)]))
        }
    }

    /// 删除文件或目录；本来就不存在也算成功。→ {ok}
    func delete(_ args: NSDictionary) {
        let path = SDFileModule.path(args)
        let done = SD.once(args)
        SDFileModule.ioQueue.async {
            guard !path.isEmpty else {
                done.failure("empty path")
                return
            }
            let manager = FileManager.default
            guard manager.fileExists(atPath: path) else {
                done.success()
                return
            }
            do {
                try manager.removeItem(atPath: path)
                done.success()
            } catch {
                done.failure(error.localizedDescription)
            }
        }
    }

    /// params: {path, dest} → {ok}（目标已存在则覆盖）
    func copy(_ args: NSDictionary) {
        let params = SD.params(args)
        let source = SDPath.normalize(params.sdString("path"))
        let dest = SDPath.normalize(params.sdString("dest"))
        let done = SD.once(args)
        SDFileModule.ioQueue.async {
            done.resolve(SDFileModule.transfer(from: source, to: dest, move: false))
        }
    }

    /// params: {path, dest} → {ok}（目标已存在则覆盖）
    func rename(_ args: NSDictionary) {
        let params = SD.params(args)
        let source = SDPath.normalize(params.sdString("path"))
        let dest = SDPath.normalize(params.sdString("dest"))
        let done = SD.once(args)
        SDFileModule.ioQueue.async {
            done.resolve(SDFileModule.transfer(from: source, to: dest, move: true))
        }
    }

    /// → {ok, text}
    func readText(_ args: NSDictionary) {
        let path = SDFileModule.path(args)
        let done = SD.once(args)
        SDFileModule.ioQueue.async {
            guard let data = FileManager.default.contents(atPath: path) else {
                done.failure("file not readable")
                return
            }
            done.success(["text": String(decoding: data, as: UTF8.self)])
        }
    }

    /// params: {path, text} → {ok}
    func writeText(_ args: NSDictionary) {
        let params = SD.params(args)
        let path = SDPath.normalize(params.sdString("path"))
        let text = params.sdString("text")
        let done = SD.once(args)
        SDFileModule.ioQueue.async {
            guard !path.isEmpty else {
                done.failure("empty path")
                return
            }
            SDPath.ensureParentDirectory(of: path)
            do {
                try Data(text.utf8).write(to: URL(fileURLWithPath: path), options: .atomic)
                done.success()
            } catch {
                done.failure(error.localizedDescription)
            }
        }
    }

    /// 读文件头 length 字节。params: {path, length} → {ok, base64}
    func readHead(_ args: NSDictionary) {
        let params = SD.params(args)
        let path = SDPath.normalize(params.sdString("path"))
        let length = max(0, min(params.sdInt("length", 0), 1024 * 1024))
        let done = SD.once(args)
        SDFileModule.ioQueue.async {
            guard length > 0, let handle = FileHandle(forReadingAtPath: path) else {
                done.failure("file not readable", extra: ["base64": ""])
                return
            }
            defer { try? handle.close() }
            let data = (try? handle.read(upToCount: length)) ?? Data()
            done.success(["base64": data.base64EncodedString()])
        }
    }

    /// → {ok, md5}（十六进制小写）
    func md5File(_ args: NSDictionary) {
        let path = SDFileModule.path(args)
        let done = SD.once(args)
        SDFileModule.ioQueue.async {
            if let md5 = SDHash.md5Hex(fileAtPath: path) {
                done.success(["md5": md5])
            } else {
                done.failure("file not readable", extra: ["md5": ""])
            }
        }
    }

    /// 把 shared assets（相对 `common/` 的路径）拷到本地文件。params: {asset, destPath} → {ok}
    func copyAsset(_ args: NSDictionary) {
        let params = SD.params(args)
        let asset = params.sdString("asset")
        let dest = SDPath.normalize(params.sdString("destPath"))
        let done = SD.once(args)
        SDFileModule.ioQueue.async {
            guard let source = SDFileModule.assetPath(asset) else {
                done.failure("asset not found: \(asset)")
                return
            }
            done.resolve(SDFileModule.transfer(from: source, to: dest, move: false))
        }
    }

    /// 缓存占用。→ {ok, bytes}
    func cacheSize(_ args: NSDictionary) {
        let done = SD.once(args)
        SDFileModule.ioQueue.async {
            var total: Int64 = 0
            for directory in SDFileModule.cacheRoots() {
                for item in SDFileModule.clearableItems(in: directory) {
                    total += SDFileModule.diskUsage(of: item)
                }
            }
            done.success(["bytes": total])
        }
    }

    /// 清空图片 / 视频 / 临时文件缓存。→ {ok}
    func clearCache(_ args: NSDictionary) {
        let done = SD.once(args)
        // 内存里的图片缓存一并清掉（磁盘部分随 Library/Caches 一起删除）。
        SD.onMain {
            SDKuiklyAdapters.clearLocalImageMemoryCache()
        }
        URLCache.shared.removeAllCachedResponses()
        SDFileModule.ioQueue.async {
            let manager = FileManager.default
            for directory in SDFileModule.cacheRoots() {
                for item in SDFileModule.clearableItems(in: directory) {
                    try? manager.removeItem(atPath: item)
                }
            }
            done.success()
        }
    }

    /// 列目录。→ {ok, files: [{name, path, size, isDir, modifiedMs}]}
    func list(_ args: NSDictionary) {
        let path = SDFileModule.path(args)
        let done = SD.once(args)
        SDFileModule.ioQueue.async {
            let manager = FileManager.default
            guard let names = try? manager.contentsOfDirectory(atPath: path) else {
                done.failure("directory not readable", extra: ["files": [Any]()])
                return
            }
            var files: [[String: Any]] = []
            for name in names.sorted() {
                let full = (path as NSString).appendingPathComponent(name)
                let attributes = (try? manager.attributesOfItem(atPath: full)) ?? [:]
                let isDirectory = (attributes[.type] as? FileAttributeType) == .typeDirectory
                let size = (attributes[.size] as? NSNumber)?.int64Value ?? 0
                let modified = (attributes[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
                files.append([
                    "name": name,
                    "path": full,
                    "size": isDirectory ? 0 : size,
                    "isDir": isDirectory,
                    "modifiedMs": Int64((modified * 1000).rounded()),
                ])
            }
            done.success(["files": files])
        }
    }

    // MARK: - helpers

    /// 取入参里的 `path` 并规范化。
    private static func path(_ args: NSDictionary) -> String {
        return SDPath.normalize(SD.params(args).sdString("path"))
    }

    private static func fileSize(_ path: String) -> Int64 {
        guard !path.isEmpty,
              let attributes = try? FileManager.default.attributesOfItem(atPath: path),
              let size = attributes[.size] as? NSNumber else {
            return -1
        }
        return size.int64Value
    }

    /// 拷贝 / 移动，目标已存在则先删除；自动创建目标目录。
    private static func transfer(from source: String, to dest: String, move: Bool) -> [String: Any] {
        guard !source.isEmpty, !dest.isEmpty else {
            return SD.failure("empty path")
        }
        let manager = FileManager.default
        guard manager.fileExists(atPath: source) else {
            return SD.failure("source not found")
        }
        if source == dest {
            return SD.success()
        }
        SDPath.ensureParentDirectory(of: dest)
        do {
            if manager.fileExists(atPath: dest) {
                try manager.removeItem(atPath: dest)
            }
            if move {
                try manager.moveItem(atPath: source, toPath: dest)
            } else {
                try manager.copyItem(atPath: source, toPath: dest)
            }
            return SD.success()
        } catch {
            return SD.failure(error.localizedDescription)
        }
    }

    /// shared assets 的包内路径。asset 相对 `common/`（也兼容已带 `common/` 或 `assets://common/` 前缀的写法）。
    private static func assetPath(_ asset: String) -> String? {
        var relative = asset.trimmingCharacters(in: .whitespacesAndNewlines)
        if relative.hasPrefix("assets://") {
            relative = String(relative.dropFirst("assets://".count))
        }
        while relative.hasPrefix("/") {
            relative.removeFirst()
        }
        guard !relative.isEmpty else { return nil }
        var candidates: [String] = []
        if relative.hasPrefix("common/") {
            candidates.append(relative)
        } else {
            candidates.append("common/" + relative)
            candidates.append(relative)
        }
        for candidate in candidates {
            if let url = SDKuiklyAdapters.assetURL(relativePath: candidate) {
                return url.path
            }
        }
        return nil
    }

    /// 可清理的缓存根目录：`Library/Caches` 与 `tmp`。
    private static func cacheRoots() -> [String] {
        return [SDPath.cachesDirectory, SDPath.systemTempDirectory]
    }

    /// 目录下可以安全清理的条目（跳过系统自己维护的目录）。
    private static func clearableItems(in directory: String) -> [String] {
        guard let names = try? FileManager.default.contentsOfDirectory(atPath: directory) else { return [] }
        return names.compactMap { name in
            if name == "Snapshots" || name.hasPrefix("com.apple.") || name.hasPrefix(".") {
                return nil
            }
            return (directory as NSString).appendingPathComponent(name)
        }
    }

    /// 文件或目录的磁盘占用（字节）。
    private static func diskUsage(of path: String) -> Int64 {
        let manager = FileManager.default
        var isDirectory: ObjCBool = false
        guard manager.fileExists(atPath: path, isDirectory: &isDirectory) else { return 0 }
        if !isDirectory.boolValue {
            return max(0, fileSize(path))
        }
        var total: Int64 = 0
        let url = URL(fileURLWithPath: path)
        let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey]
        guard let enumerator = manager.enumerator(at: url, includingPropertiesForKeys: keys, options: [], errorHandler: nil) else {
            return 0
        }
        for case let fileURL as URL in enumerator {
            guard let values = try? fileURL.resourceValues(forKeys: Set(keys)),
                  values.isRegularFile == true,
                  let size = values.fileSize else {
                continue
            }
            total += Int64(size)
        }
        return total
    }
}
