import Foundation

/// 视频缓存模块（契约见 shared 的 `SDMediaModules.kt`；缓存实现见 `SDVideoCache`）。
@objc(SDVideoCacheModule)
@objcMembers
final class SDVideoCacheModule: KRBaseModule {

    /// params: {url} → {ok, localPath?}；超过 20MB / 探测不到大小 / 下载失败 / 被取消时 ok=false。
    func prefetch(_ args: NSDictionary) {
        let url = SD.params(args).sdString("url")
        let done = SD.once(args)
        if url.isEmpty {
            done.failure("empty url")
            return
        }
        if let local = SDMedia.fileURL(url) {
            // 本来就是本地文件：无需缓存。
            if FileManager.default.fileExists(atPath: local.path) {
                done.success(["localPath": local.path])
            } else {
                done.failure("file not found")
            }
            return
        }
        SDVideoCache.shared.prefetch(url) { file, error in
            if let file = file {
                done.success(["localPath": file.path])
            } else {
                done.failure(error ?? "prefetch failed")
            }
        }
    }

    /// params: {url} → {ok: true, localPath?}（未缓存时没有 localPath）
    func resolve(_ args: NSDictionary) {
        let url = SD.params(args).sdString("url")
        let done = SD.once(args)
        if url.isEmpty {
            done.failure("empty url")
            return
        }
        SD.onIO {
            if let local = SDMedia.fileURL(url) {
                if FileManager.default.fileExists(atPath: local.path) {
                    done.success(["localPath": local.path])
                } else {
                    done.success()
                }
                return
            }
            if let file = SDVideoCache.shared.cachedFile(for: url) {
                done.success(["localPath": file.path])
            } else {
                done.success()
            }
        }
    }

    /// params: {url}
    func cancelPrefetch(_ args: NSDictionary) {
        let url = SD.params(args).sdString("url")
        if url.isEmpty {
            return
        }
        SDVideoCache.shared.cancelPrefetch(url)
    }

    /// → {ok}
    func clear(_ args: NSDictionary) {
        let done = SD.once(args)
        SDVideoCache.shared.clear { ok in
            if ok {
                done.success()
            } else {
                done.failure("clear failed")
            }
        }
    }
}
