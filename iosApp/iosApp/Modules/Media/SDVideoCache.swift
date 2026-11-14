import CryptoKit
import Foundation

/// 视频磁盘缓存（`SDVideoView` 与 `SDVideoCacheModule` 共用）。
///
/// 策略与参考工程的 `sondo_video_player_cache` 一致：
/// - 只缓存 ≤ 20MB 的资源：先发 `Range: bytes=0-0`（5 秒超时）从 `Content-Range` 读总大小，读不到就不缓存；
/// - 最多 300 个对象，69 天未使用即过期（以文件修改时间作为「最近使用时间」，命中时刷新）；
/// - 只保存**完整下载**的文件：下载到系统临时文件，校验后一次性移动进缓存目录，
///   所以缓存目录里出现的文件一定可以直接交给 AVPlayer 当本地文件播放。
final class SDVideoCache: NSObject, URLSessionDataDelegate {

    static let shared = SDVideoCache()

    static let maxFileBytes: Int64 = 20 * 1024 * 1024
    static let maxObjects = 300
    static let maxAge: TimeInterval = 69 * 24 * 3600

    typealias Completion = (_ file: URL?, _ error: String?) -> Void

    private final class Job {
        let key: String
        let url: URL
        let destination: URL
        var completions: [Completion] = []
        var task: URLSessionTask?
        var cancelled = false

        init(key: String, url: URL, destination: URL) {
            self.key = key
            self.url = url
            self.destination = destination
        }
    }

    private let directory: URL
    /// 串行队列：保护 jobs / probeJobs，并承担目录整理。
    private let queue = DispatchQueue(label: "com.sondo.media.videocache")
    private var jobs: [String: Job] = [:]
    private var probeJobs: [Int: Job] = [:]
    /// 本次运行中已确认不缓存的地址（超过 20MB / 服务端不给大小）：不再重复探测。
    private var rejected: [String: String] = [:]

    /// 探测大小用：需要在收到响应头时立刻取消，所以走 delegate；回调派发到 `queue`。
    private lazy var probeSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 5
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        let operationQueue = OperationQueue()
        operationQueue.maxConcurrentOperationCount = 1
        operationQueue.underlyingQueue = queue
        return URLSession(configuration: config, delegate: self, delegateQueue: operationQueue)
    }()

    private let downloadSession: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.urlCache = nil
        config.httpMaximumConnectionsPerHost = 3
        return URLSession(configuration: config)
    }()

    private override init() {
        let fm = FileManager.default
        let base = fm.urls(for: .cachesDirectory, in: .userDomainMask).first ?? fm.temporaryDirectory
        directory = base.appendingPathComponent("sondo_video_player_cache", isDirectory: true)
        super.init()
        try? fm.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
        queue.async { [weak self] in
            self?.trim()
        }
    }

    // MARK: - 查询

    /// 已完整缓存且未过期则返回本地文件（任意线程，只做一次 stat）。
    func cachedFile(for urlString: String) -> URL? {
        let file = destination(for: urlString)
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: file.path),
              let size = (attrs[.size] as? NSNumber)?.int64Value, size > 0 else {
            return nil
        }
        if let modified = attrs[.modificationDate] as? Date,
           Date().timeIntervalSince(modified) > SDVideoCache.maxAge {
            queue.async {
                try? FileManager.default.removeItem(at: file)
            }
            return nil
        }
        queue.async {
            try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: file.path)
        }
        return file
    }

    // MARK: - 预取

    /// 预取到磁盘。`completion` 在后台线程回调；同一地址并发调用会合并成一次下载。
    func prefetch(_ urlString: String, completion: Completion?) {
        guard let url = SDMedia.remoteURL(urlString), SDMedia.isRemote(url) else {
            completion?(nil, "invalid url")
            return
        }
        queue.async {
            if let cached = self.cachedFile(for: urlString) {
                completion?(cached, nil)
                return
            }
            let key = SDVideoCache.key(for: urlString)
            if let reason = self.rejected[key] {
                completion?(nil, reason)
                return
            }
            if let running = self.jobs[key] {
                if let completion = completion {
                    running.completions.append(completion)
                }
                return
            }
            let job = Job(key: key, url: url, destination: self.destination(for: urlString))
            if let completion = completion {
                job.completions.append(completion)
            }
            self.jobs[key] = job
            self.startProbe(job)
        }
    }

    func cancelPrefetch(_ urlString: String) {
        queue.async {
            guard let job = self.jobs[SDVideoCache.key(for: urlString)] else {
                return
            }
            job.cancelled = true
            job.task?.cancel()
        }
    }

    /// 清空缓存（进行中的预取一并取消）。`completion` 在后台线程回调。
    func clear(completion: @escaping (Bool) -> Void) {
        queue.async {
            for job in self.jobs.values {
                job.cancelled = true
                job.task?.cancel()
            }
            self.rejected.removeAll()
            let fm = FileManager.default
            var ok = true
            if let files = try? fm.contentsOfDirectory(at: self.directory, includingPropertiesForKeys: nil, options: []) {
                for file in files {
                    do {
                        try fm.removeItem(at: file)
                    } catch {
                        ok = false
                    }
                }
            }
            completion(ok)
        }
    }

    // MARK: - 内部：探测 + 下载（均在 queue 上）

    private func startProbe(_ job: Job) {
        var request = URLRequest(url: job.url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 5)
        request.setValue("bytes=0-0", forHTTPHeaderField: "Range")
        let task = probeSession.dataTask(with: request)
        probeJobs[task.taskIdentifier] = job
        job.task = task
        task.resume()
    }

    func urlSession(_ session: URLSession,
                    dataTask: URLSessionDataTask,
                    didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        // 只需要响应头：不管服务端是否支持 Range，都不再接收正文。
        completionHandler(.cancel)
        guard let job = probeJobs.removeValue(forKey: dataTask.taskIdentifier) else {
            return
        }
        job.task = nil
        if job.cancelled {
            finish(job, file: nil, error: "cancelled")
            return
        }
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            finish(job, file: nil, error: "probe failed")
            return
        }
        guard let total = SDVideoCache.totalLength(of: http) else {
            rejected[job.key] = "unknown size"
            finish(job, file: nil, error: "unknown size")
            return
        }
        if total > SDVideoCache.maxFileBytes {
            rejected[job.key] = "too large"
            finish(job, file: nil, error: "too large")
            return
        }
        startDownload(job)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        // 没等到响应头就结束了（超时 / 断网 / 被取消）。收到响应头的任务已经从 probeJobs 里移除。
        guard let job = probeJobs.removeValue(forKey: task.taskIdentifier) else {
            return
        }
        job.task = nil
        finish(job, file: nil, error: job.cancelled ? "cancelled" : (error?.localizedDescription ?? "probe failed"))
    }

    private func startDownload(_ job: Job) {
        let destination = job.destination
        let task = downloadSession.downloadTask(with: job.url) { [weak self] temp, response, error in
            // 临时文件在这个回调返回后会被系统删除，必须在这里同步移动。
            var saved: URL?
            var message: String?
            if let temp = temp, let http = response as? HTTPURLResponse, http.statusCode == 200 {
                let size = SDMedia.fileSize(temp)
                if size <= 0 {
                    message = "empty file"
                } else if size > SDVideoCache.maxFileBytes {
                    message = "too large"
                } else {
                    let fm = FileManager.default
                    try? fm.createDirectory(at: destination.deletingLastPathComponent(),
                                            withIntermediateDirectories: true, attributes: nil)
                    try? fm.removeItem(at: destination)
                    do {
                        try fm.moveItem(at: temp, to: destination)
                        saved = destination
                    } catch {
                        message = error.localizedDescription
                    }
                }
            } else if let error = error {
                message = error.localizedDescription
            } else if let http = response as? HTTPURLResponse {
                message = "http \(http.statusCode)"
            } else {
                message = "download failed"
            }
            guard let self = self else {
                return
            }
            self.queue.async {
                job.task = nil
                if job.cancelled {
                    // 取消后即使文件恰好下完也保留在缓存里（内容是完整的），只是按取消回调。
                    self.finish(job, file: nil, error: "cancelled")
                } else {
                    self.finish(job, file: saved, error: saved == nil ? (message ?? "download failed") : nil)
                }
                if saved != nil {
                    self.trim()
                }
            }
        }
        job.task = task
        task.resume()
    }

    private func finish(_ job: Job, file: URL?, error: String?) {
        if jobs[job.key] === job {
            jobs[job.key] = nil
        }
        let completions = job.completions
        job.completions = []
        for completion in completions {
            completion(file, error)
        }
    }

    /// 删除过期文件；超过数量上限时从最久未使用的开始删。
    private func trim() {
        let fm = FileManager.default
        let keys: [URLResourceKey] = [.contentModificationDateKey, .isRegularFileKey]
        guard let files = try? fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: keys, options: []) else {
            return
        }
        let now = Date()
        var alive: [(url: URL, date: Date)] = []
        for file in files {
            let values = try? file.resourceValues(forKeys: Set(keys))
            if values?.isRegularFile == false {
                continue
            }
            let date = values?.contentModificationDate ?? now
            if now.timeIntervalSince(date) > SDVideoCache.maxAge {
                try? fm.removeItem(at: file)
            } else {
                alive.append((url: file, date: date))
            }
        }
        if alive.count > SDVideoCache.maxObjects {
            alive.sort { $0.date < $1.date }
            for entry in alive.prefix(alive.count - SDVideoCache.maxObjects) {
                try? fm.removeItem(at: entry.url)
            }
        }
    }

    // MARK: - 工具

    private static func key(for urlString: String) -> String {
        let digest = SHA256.hash(data: Data(urlString.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private func destination(for urlString: String) -> URL {
        var ext = "mp4"
        if let url = URL(string: urlString) {
            let candidate = url.pathExtension.lowercased()
            let alphanumerics = CharacterSet.alphanumerics
            if !candidate.isEmpty, candidate.count <= 5,
               candidate.unicodeScalars.allSatisfy({ alphanumerics.contains($0) }) {
                ext = candidate
            }
        }
        return directory.appendingPathComponent(SDVideoCache.key(for: urlString) + "." + ext)
    }

    /// 资源总大小：206 读 `Content-Range: bytes 0-0/12345`；服务端忽略 Range 返回 200 时读 Content-Length。
    private static func totalLength(of http: HTTPURLResponse) -> Int64? {
        if http.statusCode == 206 {
            guard let range = http.value(forHTTPHeaderField: "Content-Range"),
                  let slash = range.lastIndex(of: "/") else {
                return nil
            }
            let totalText = range[range.index(after: slash)...].trimmingCharacters(in: .whitespaces)
            guard let total = Int64(totalText), total > 0 else {
                return nil
            }
            return total
        }
        if http.statusCode == 200 {
            let length = http.expectedContentLength
            return length > 0 ? length : nil
        }
        return nil
    }
}
