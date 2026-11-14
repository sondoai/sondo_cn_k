import Foundation

// =====================================================================================
// SDHttpModule：通用 HTTP（任意方法 / 请求体 / 超时、取消、文件上传下载、资源大小探测）。
// 契约见 shared/.../nativeapi/SDHttpModule.kt。
//
// 约定：
// - request：只要拿到 HTTP 响应（任意状态码）就是 ok=true；传输层失败 ok=false，
//   errorType ∈ "timeout" | "network" | "cancel" | "other"；
// - 响应头同时给出「原始大小写」和「全小写」两份 key，ETag 额外保证有 "ETag"；
// - 不带 Cookie、不走本地缓存（与参考工程 Dio 的默认行为一致）；
// - 页面关闭不会取消进行中的请求（与 Dio 一致），需要取消请显式调用 cancel(id)。
// =====================================================================================

@objc(SDHttpModule)
@objcMembers
final class SDHttpModule: KRBaseModule {

    /// params: {id, method, url, headers:{}, body?, timeoutMs}
    /// → {ok, status, headers:{}, body, error, errorType}
    func request(_ args: NSDictionary) {
        let params = SD.params(args)
        let done = SD.once(args)
        guard let url = SDURLUtil.make(params.sdString("url")) else {
            done.resolve(SDHttpEngine.failure(message: "invalid url", type: "other"))
            return
        }
        SDHttpEngine.shared.request(
            id: params.sdString("id"),
            method: params.sdString("method", "GET"),
            url: url,
            headers: params.sdStringDict("headers"),
            body: params.sdOptString("body"),
            timeoutMs: params.sdInt("timeoutMs", 0)
        ) { result in
            done.resolve(result)
        }
    }

    /// 取消进行中的请求 / 上传 / 下载。params: {id}
    func cancel(_ args: NSDictionary) {
        let id = SD.params(args).sdString("id")
        guard !id.isEmpty else { return }
        SDHttpEngine.shared.cancel(id: id)
    }

    /// 上传本地文件（常驻回调）。
    /// params: {id, method, url, headers, filePath, contentType?, multipart?, fieldName?, fileName?,
    ///          formFields?, offset?, length?, timeoutMs}
    /// 事件: {event:"progress", sent, total} … {event:"done", ok, status, headers, body, error, errorType}
    func upload(_ args: NSDictionary) {
        let params = SD.params(args)
        let callback = SD.callback(args)
        let emit: ([String: Any]) -> Void = { event in
            callback?(event)
        }
        guard let url = SDURLUtil.make(params.sdString("url")) else {
            emit(SDHttpEngine.doneEvent(SDHttpEngine.failure(message: "invalid url", type: "other")))
            return
        }
        let options = SDHttpEngine.UploadOptions(
            id: params.sdString("id"),
            method: params.sdString("method", "PUT"),
            url: url,
            headers: params.sdStringDict("headers"),
            filePath: SDPath.normalize(params.sdString("filePath")),
            contentType: params.sdOptString("contentType"),
            multipart: params.sdBool("multipart", false),
            fieldName: params.sdString("fieldName", "file"),
            fileName: params.sdOptString("fileName"),
            formFields: params.sdStringDict("formFields"),
            offset: params.sdOptInt64("offset"),
            length: params.sdOptInt64("length"),
            timeoutMs: params.sdInt("timeoutMs", 0)
        )
        SDHttpEngine.shared.upload(options, onEvent: emit)
    }

    /// 下载到本地文件（常驻回调）。
    /// params: {id, url, headers?, savePath?, fileName?, timeoutMs}
    /// 事件: {event:"progress", received, total} … {event:"done", ok, path, status, error, errorType}
    func download(_ args: NSDictionary) {
        let params = SD.params(args)
        let callback = SD.callback(args)
        let emit: ([String: Any]) -> Void = { event in
            callback?(event)
        }
        guard let url = SDURLUtil.make(params.sdString("url")) else {
            var result = SDHttpEngine.failure(message: "invalid url", type: "other")
            result["path"] = ""
            emit(SDHttpEngine.doneEvent(result))
            return
        }
        let options = SDHttpEngine.DownloadOptions(
            id: params.sdString("id"),
            url: url,
            headers: params.sdStringDict("headers"),
            savePath: SDPath.normalize(params.sdString("savePath")),
            fileName: params.sdString("fileName"),
            timeoutMs: params.sdInt("timeoutMs", 0)
        )
        SDHttpEngine.shared.download(options, onEvent: emit)
    }

    /// GET `Range: bytes=0-0` 探测资源大小（5s 超时）。params: {url} → {ok, length}（未知为 -1）
    func probeContentLength(_ args: NSDictionary) {
        let done = SD.once(args)
        guard let url = SDURLUtil.make(SD.params(args).sdString("url")) else {
            done.resolve(SD.failure("invalid url", extra: ["length": -1]))
            return
        }
        SDHttpEngine.shared.probeContentLength(url: url) { result in
            done.resolve(result)
        }
    }
}

// =====================================================================================
// SDHttpEngine：进程内唯一的 URLSession 与任务表。
// =====================================================================================

final class SDHttpEngine: NSObject, URLSessionDataDelegate {

    static let shared = SDHttpEngine()

    struct UploadOptions {
        let id: String
        let method: String
        let url: URL
        let headers: [String: String]
        let filePath: String
        let contentType: String?
        let multipart: Bool
        let fieldName: String
        let fileName: String?
        let formFields: [String: String]
        let offset: Int64?
        let length: Int64?
        let timeoutMs: Int
    }

    struct DownloadOptions {
        let id: String
        let url: URL
        let headers: [String: String]
        let savePath: String
        let fileName: String
        let timeoutMs: Int
    }

    private enum Kind {
        case request
        case upload
        case download
        case probe
    }

    /// 一次请求 / 上传 / 下载的运行状态。除 `cancelled` / `finished` / `task`（受 engine.lock 保护）外，
    /// 其余字段只在 URLSession 的串行代理队列上读写。
    private final class Job {
        let id: String
        let kind: Kind
        var task: URLSessionTask?
        var cancelled = false
        var finished = false

        var response: HTTPURLResponse?
        var body = Data()

        /// 最终结果（恰好调用一次）。
        var onFinish: (([String: Any]) -> Void)?
        /// 进度（已传输, 总量；总量未知为 -1）。
        var onProgress: ((Int64, Int64) -> Void)?
        var lastProgressTime: CFAbsoluteTime = 0

        // 上传：临时请求体文件（multipart / 大分片），结束后删除
        var temporaryBodyPath: String?

        // 下载
        var destinationPath = ""
        var temporaryPath = ""
        var fileHandle: FileHandle?
        var received: Int64 = 0
        var expected: Int64 = -1
        var localError: String?

        // 探测
        var probeFinished = false
        var probeLength: Int64 = -1

        init(id: String, kind: Kind) {
            self.id = id
            self.kind = kind
        }
    }

    private static let defaultTimeout: TimeInterval = 60
    /// 分片小于该值时直接读进内存上传，否则先切到临时文件（避免大分片占用内存）。
    private static let inMemoryRangeLimit: Int64 = 8 * 1024 * 1024
    private static let copyChunkSize = 1024 * 1024
    private static let progressInterval: CFAbsoluteTime = 0.1

    private let lock = NSLock()
    private var jobsByTask: [Int: Job] = [:]
    private var jobsById: [String: Job] = [:]

    private let delegateQueue: OperationQueue = {
        let queue = OperationQueue()
        queue.name = "com.sondo.http.delegate"
        queue.maxConcurrentOperationCount = 1
        return queue
    }()

    /// 上传前的准备工作（拼 multipart、切分片）放到后台做。
    private let prepareQueue = DispatchQueue(label: "com.sondo.http.prepare", qos: .userInitiated, attributes: .concurrent)

    /// 在 init 里创建（需要把 self 作为 delegate），之后只读。
    private var session: URLSession!

    private override init() {
        super.init()
        let configuration = URLSessionConfiguration.default
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.httpCookieAcceptPolicy = .never
        configuration.httpMaximumConnectionsPerHost = 8
        configuration.timeoutIntervalForRequest = SDHttpEngine.defaultTimeout
        // 整体时长不设上限（大文件上传 / 下载），空闲超时由每个请求的 timeoutInterval 控制。
        configuration.timeoutIntervalForResource = 24 * 60 * 60
        session = URLSession(configuration: configuration, delegate: self, delegateQueue: delegateQueue)
    }

    // MARK: - 结果构造

    static func failure(message: String, type: String, status: Int = 0) -> [String: Any] {
        return [
            "ok": false,
            "status": status,
            "headers": [String: String](),
            "body": "",
            "error": message,
            "errorType": type,
        ]
    }

    static func doneEvent(_ result: [String: Any]) -> [String: Any] {
        var event = result
        event["event"] = "done"
        return event
    }

    private static func failure(error: Error) -> [String: Any] {
        return failure(message: error.localizedDescription, type: errorType(error))
    }

    private static func success(response: HTTPURLResponse, body: Data) -> [String: Any] {
        return [
            "ok": true,
            "status": response.statusCode,
            "headers": headers(of: response),
            "body": text(from: body),
            "error": "",
        ]
    }

    private static func text(from data: Data) -> String {
        if data.isEmpty {
            return ""
        }
        return String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
    }

    /// 响应头：原始 key + 全小写 key；ETag 额外保证有 "ETag"（iOS 会把它规范化成 "Etag"）。
    private static func headers(of response: HTTPURLResponse) -> [String: String] {
        var result: [String: String] = [:]
        for (key, value) in response.allHeaderFields {
            let name = (key as? String) ?? String(describing: key)
            let text = (value as? String) ?? String(describing: value)
            result[name] = text
            result[name.lowercased()] = text
        }
        if let etag = result["etag"] {
            result["ETag"] = etag
        }
        return result
    }

    private static func errorType(_ error: Error) -> String {
        let nsError = error as NSError
        guard nsError.domain == NSURLErrorDomain else { return "other" }
        switch nsError.code {
        case NSURLErrorTimedOut:
            return "timeout"
        case NSURLErrorCancelled:
            return "cancel"
        case NSURLErrorNotConnectedToInternet,
             NSURLErrorNetworkConnectionLost,
             NSURLErrorCannotFindHost,
             NSURLErrorCannotConnectToHost,
             NSURLErrorDNSLookupFailed,
             NSURLErrorInternationalRoamingOff,
             NSURLErrorCallIsActive,
             NSURLErrorDataNotAllowed,
             NSURLErrorSecureConnectionFailed,
             NSURLErrorCannotLoadFromNetwork:
            return "network"
        default:
            return "other"
        }
    }

    // MARK: - 请求构造

    private func makeRequest(method: String, url: URL, headers: [String: String], timeoutMs: Int) -> URLRequest {
        let timeout = timeoutMs > 0 ? TimeInterval(timeoutMs) / 1000 : SDHttpEngine.defaultTimeout
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        let verb = method.trimmingCharacters(in: .whitespaces).uppercased()
        request.httpMethod = verb.isEmpty ? "GET" : verb
        request.httpShouldHandleCookies = false
        for (name, value) in headers {
            // Content-Length 由 URLSession 按实际请求体计算，避免与手填的值不一致。
            if name.caseInsensitiveCompare("Content-Length") == .orderedSame {
                continue
            }
            request.setValue(value, forHTTPHeaderField: name)
        }
        return request
    }

    private static func hasHeader(_ name: String, in headers: [String: String]) -> Bool {
        return headers.keys.contains { $0.caseInsensitiveCompare(name) == .orderedSame }
    }

    // MARK: - 任务表

    private func register(_ job: Job) {
        guard !job.id.isEmpty else { return }
        lock.lock()
        jobsById[job.id] = job
        lock.unlock()
    }

    /// 绑定并启动任务；如果在准备阶段已被取消则直接以 cancel 结束。
    private func start(_ job: Job, task: URLSessionTask) {
        lock.lock()
        if job.cancelled {
            lock.unlock()
            // 没有 resume 过的 task 也要 cancel，否则会一直被 session 持有。
            task.cancel()
            finish(job, SDHttpEngine.failure(message: "cancelled", type: "cancel"))
            return
        }
        job.task = task
        jobsByTask[task.taskIdentifier] = job
        lock.unlock()
        task.resume()
    }

    private func job(for task: URLSessionTask) -> Job? {
        lock.lock()
        defer { lock.unlock() }
        return jobsByTask[task.taskIdentifier]
    }

    /// 结束一个任务：从任务表移除、清理临时文件、回调最终结果（恰好一次）。
    private func finish(_ job: Job, _ result: [String: Any]) {
        lock.lock()
        if job.finished {
            lock.unlock()
            return
        }
        job.finished = true
        if let task = job.task {
            jobsByTask[task.taskIdentifier] = nil
        }
        if !job.id.isEmpty, jobsById[job.id] === job {
            jobsById[job.id] = nil
        }
        let onFinish = job.onFinish
        job.onFinish = nil
        job.onProgress = nil
        lock.unlock()

        if let handle = job.fileHandle {
            try? handle.close()
            job.fileHandle = nil
        }
        if let path = job.temporaryBodyPath {
            try? FileManager.default.removeItem(atPath: path)
            job.temporaryBodyPath = nil
        }
        onFinish?(result)
    }

    private func reportProgress(_ job: Job, _ transferred: Int64, _ total: Int64) {
        let now = CFAbsoluteTimeGetCurrent()
        let isLast = total > 0 && transferred >= total
        if !isLast && now - job.lastProgressTime < SDHttpEngine.progressInterval {
            return
        }
        job.lastProgressTime = now
        job.onProgress?(transferred, total)
    }

    // MARK: - request

    func request(id: String,
                 method: String,
                 url: URL,
                 headers: [String: String],
                 body: String?,
                 timeoutMs: Int,
                 completion: @escaping ([String: Any]) -> Void) {
        var request = makeRequest(method: method, url: url, headers: headers, timeoutMs: timeoutMs)
        let verb = request.httpMethod ?? "GET"
        if let body = body, verb != "GET", verb != "HEAD" {
            request.httpBody = Data(body.utf8)
        }
        let job = Job(id: id, kind: .request)
        job.onFinish = completion
        register(job)
        start(job, task: session.dataTask(with: request))
    }

    // MARK: - cancel

    func cancel(id: String) {
        lock.lock()
        let job = jobsById[id]
        job?.cancelled = true
        let task = job?.task
        lock.unlock()
        task?.cancel()
    }

    // MARK: - probe

    func probeContentLength(url: URL, completion: @escaping ([String: Any]) -> Void) {
        var request = makeRequest(method: "GET", url: url, headers: ["Range": "bytes=0-0"], timeoutMs: 5000)
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        let job = Job(id: "", kind: .probe)
        job.onFinish = completion
        start(job, task: session.dataTask(with: request))
    }

    /// 从 `Content-Range: bytes 0-0/12345` 或 200 响应的 Content-Length 得到资源总大小；未知为 -1。
    private static func totalLength(from response: HTTPURLResponse?) -> Int64 {
        guard let response = response, (200...299).contains(response.statusCode) else { return -1 }
        if let range = response.value(forHTTPHeaderField: "Content-Range"),
           let slash = range.lastIndex(of: "/") {
            let totalText = range[range.index(after: slash)...].trimmingCharacters(in: .whitespaces)
            if let total = Int64(totalText), total >= 0 {
                return total
            }
            return -1
        }
        if response.statusCode == 200, response.expectedContentLength >= 0 {
            return response.expectedContentLength
        }
        return -1
    }

    // MARK: - upload

    func upload(_ options: UploadOptions, onEvent: @escaping ([String: Any]) -> Void) {
        let job = Job(id: options.id, kind: .upload)
        job.onProgress = { sent, total in
            onEvent(["event": "progress", "sent": sent, "total": total])
        }
        job.onFinish = { result in
            onEvent(SDHttpEngine.doneEvent(result))
        }
        register(job)

        prepareQueue.async { [weak self] in
            guard let self = self else { return }
            self.prepareAndStartUpload(job, options)
        }
    }

    private func prepareAndStartUpload(_ job: Job, _ options: UploadOptions) {
        let manager = FileManager.default
        guard !options.filePath.isEmpty,
              let attributes = try? manager.attributesOfItem(atPath: options.filePath),
              let fileSize = (attributes[.size] as? NSNumber)?.int64Value else {
            finish(job, SDHttpEngine.failure(message: "file not found", type: "other"))
            return
        }

        // 文件区间（分片上传）
        let offset = max(0, options.offset ?? 0)
        let length = options.length ?? (fileSize - offset)
        guard offset <= fileSize, length >= 0, offset + length <= fileSize else {
            finish(job, SDHttpEngine.failure(message: "invalid file range", type: "other"))
            return
        }
        let isWholeFile = offset == 0 && length == fileSize

        var request = makeRequest(method: options.method, url: options.url, headers: options.headers, timeoutMs: options.timeoutMs)
        let fileURL = URL(fileURLWithPath: options.filePath)

        if options.multipart {
            // multipart/form-data：请求体整体写入临时文件，由 URLSession 按文件大小给出准确的 Content-Length。
            let boundary = "----SondoFormBoundary" + UUID().uuidString.replacingOccurrences(of: "-", with: "")
            guard let bodyPath = SDHttpEngine.writeMultipartBody(
                boundary: boundary,
                options: options,
                offset: offset,
                length: length
            ) else {
                finish(job, SDHttpEngine.failure(message: "failed to build multipart body", type: "other"))
                return
            }
            job.temporaryBodyPath = bodyPath
            request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
            start(job, task: session.uploadTask(with: request, fromFile: URL(fileURLWithPath: bodyPath)))
            return
        }

        if !SDHttpEngine.hasHeader("Content-Type", in: options.headers) {
            let contentType = (options.contentType?.isEmpty == false) ? (options.contentType ?? "") : "application/octet-stream"
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }

        if isWholeFile {
            // 整文件：直接从文件上传，Content-Length = 文件大小，不走 chunked。
            start(job, task: session.uploadTask(with: request, fromFile: fileURL))
            return
        }

        if length <= SDHttpEngine.inMemoryRangeLimit {
            guard let data = SDHttpEngine.readRange(of: options.filePath, offset: offset, length: length) else {
                finish(job, SDHttpEngine.failure(message: "failed to read file range", type: "other"))
                return
            }
            start(job, task: session.uploadTask(with: request, from: data))
            return
        }

        // 大分片：先切到临时文件再上传
        guard let slicePath = SDHttpEngine.temporaryBodyPath(),
              manager.createFile(atPath: slicePath, contents: nil, attributes: nil),
              let output = FileHandle(forWritingAtPath: slicePath) else {
            finish(job, SDHttpEngine.failure(message: "failed to create temp file", type: "other"))
            return
        }
        let copied = SDHttpEngine.copyRange(of: options.filePath, offset: offset, length: length, to: output)
        try? output.close()
        guard copied else {
            try? manager.removeItem(atPath: slicePath)
            finish(job, SDHttpEngine.failure(message: "failed to read file range", type: "other"))
            return
        }
        job.temporaryBodyPath = slicePath
        start(job, task: session.uploadTask(with: request, fromFile: URL(fileURLWithPath: slicePath)))
    }

    private static func temporaryBodyPath() -> String? {
        let directory = (SDPath.systemTempDirectory as NSString).appendingPathComponent("sd_upload")
        do {
            try FileManager.default.createDirectory(atPath: directory, withIntermediateDirectories: true, attributes: nil)
        } catch {
            return nil
        }
        return (directory as NSString).appendingPathComponent(UUID().uuidString + ".body")
    }

    private static func readRange(of path: String, offset: Int64, length: Int64) -> Data? {
        guard let handle = FileHandle(forReadingAtPath: path) else { return nil }
        defer { try? handle.close() }
        do {
            try handle.seek(toOffset: UInt64(offset))
            if length == 0 {
                return Data()
            }
            guard let data = try handle.read(upToCount: Int(length)), Int64(data.count) == length else {
                return nil
            }
            return data
        } catch {
            return nil
        }
    }

    /// 把源文件的 [offset, offset+length) 追加写入 output。
    private static func copyRange(of path: String, offset: Int64, length: Int64, to output: FileHandle) -> Bool {
        guard let input = FileHandle(forReadingAtPath: path) else { return false }
        defer { try? input.close() }
        do {
            try input.seek(toOffset: UInt64(offset))
            var remaining = length
            while remaining > 0 {
                let want = Int(min(Int64(copyChunkSize), remaining))
                let chunk: Data? = try autoreleasepool {
                    try input.read(upToCount: want)
                }
                guard let data = chunk, !data.isEmpty else {
                    return false
                }
                try output.write(contentsOf: data)
                remaining -= Int64(data.count)
            }
            return true
        } catch {
            return false
        }
    }

    /// 生成 multipart/form-data 请求体文件：先表单字段，后文件（与 Dio FormData 的顺序一致）。
    private static func writeMultipartBody(boundary: String,
                                           options: UploadOptions,
                                           offset: Int64,
                                           length: Int64) -> String? {
        guard let bodyPath = temporaryBodyPath(),
              FileManager.default.createFile(atPath: bodyPath, contents: nil, attributes: nil),
              let output = FileHandle(forWritingAtPath: bodyPath) else {
            return nil
        }
        var succeeded = false
        defer {
            try? output.close()
            if !succeeded {
                try? FileManager.default.removeItem(atPath: bodyPath)
            }
        }

        let lineBreak = "\r\n"
        do {
            var head = ""
            for name in options.formFields.keys.sorted() {
                let value = options.formFields[name] ?? ""
                head += "--\(boundary)\(lineBreak)"
                head += "Content-Disposition: form-data; name=\"\(escapeQuotes(name))\"\(lineBreak)\(lineBreak)"
                head += "\(value)\(lineBreak)"
            }
            let fileName: String
            if let name = options.fileName, !name.isEmpty {
                fileName = name
            } else {
                fileName = (options.filePath as NSString).lastPathComponent
            }
            let contentType = (options.contentType?.isEmpty == false) ? (options.contentType ?? "") : "application/octet-stream"
            head += "--\(boundary)\(lineBreak)"
            head += "Content-Disposition: form-data; name=\"\(escapeQuotes(options.fieldName))\"; filename=\"\(escapeQuotes(fileName))\"\(lineBreak)"
            head += "Content-Type: \(contentType)\(lineBreak)\(lineBreak)"
            try output.write(contentsOf: Data(head.utf8))

            guard copyRange(of: options.filePath, offset: offset, length: length, to: output) else {
                return nil
            }

            let tail = "\(lineBreak)--\(boundary)--\(lineBreak)"
            try output.write(contentsOf: Data(tail.utf8))
            succeeded = true
            return bodyPath
        } catch {
            return nil
        }
    }

    private static func escapeQuotes(_ text: String) -> String {
        return text
            .replacingOccurrences(of: "\"", with: "%22")
            .replacingOccurrences(of: "\r", with: "%0D")
            .replacingOccurrences(of: "\n", with: "%0A")
    }

    // MARK: - download

    func download(_ options: DownloadOptions, onEvent: @escaping ([String: Any]) -> Void) {
        let destination = SDHttpEngine.destinationPath(for: options)
        let job = Job(id: options.id, kind: .download)
        job.destinationPath = destination
        job.temporaryPath = destination + ".part-" + String(UUID().uuidString.prefix(8))
        job.onProgress = { received, total in
            onEvent(["event": "progress", "received": received, "total": total])
        }
        job.onFinish = { result in
            onEvent(SDHttpEngine.doneEvent(result))
        }
        register(job)

        let request = makeRequest(method: "GET", url: options.url, headers: options.headers, timeoutMs: options.timeoutMs)
        start(job, task: session.dataTask(with: request))
    }

    /// 保存位置：savePath 为空 → `Library/Caches/sd_downloads/<fileName 或 md5(url).ext>`；
    /// savePath 是目录（以 / 结尾或已存在的目录）→ 目录下的 fileName；否则 savePath 本身就是目标文件。
    private static func destinationPath(for options: DownloadOptions) -> String {
        var name = options.fileName
        if name.isEmpty {
            let ext = options.url.pathExtension
            name = SDHash.md5Hex(options.url.absoluteString) + (ext.isEmpty ? "" : "." + ext)
        }
        if options.savePath.isEmpty {
            let directory = (SDPath.cachesDirectory as NSString).appendingPathComponent("sd_downloads")
            return (directory as NSString).appendingPathComponent(name)
        }
        var isDirectory: ObjCBool = false
        let exists = FileManager.default.fileExists(atPath: options.savePath, isDirectory: &isDirectory)
        if options.savePath.hasSuffix("/") || (exists && isDirectory.boolValue) {
            return (options.savePath as NSString).appendingPathComponent(name)
        }
        return options.savePath
    }

    private static func downloadResult(ok: Bool, path: String, status: Int, error: String, errorType: String) -> [String: Any] {
        var result: [String: Any] = [
            "ok": ok,
            "path": path,
            "status": status,
            "error": error,
        ]
        if !ok {
            result["errorType"] = errorType
        }
        return result
    }

    private func completeDownload(_ job: Job, error: Error?) {
        let manager = FileManager.default
        if let handle = job.fileHandle {
            try? handle.close()
            job.fileHandle = nil
        }
        let status = job.response?.statusCode ?? 0

        func fail(_ message: String, _ type: String) {
            try? manager.removeItem(atPath: job.temporaryPath)
            finish(job, SDHttpEngine.downloadResult(ok: false, path: "", status: status, error: message, errorType: type))
        }

        if let localError = job.localError {
            fail(localError, "other")
            return
        }
        if let error = error {
            fail(error.localizedDescription, SDHttpEngine.errorType(error))
            return
        }
        guard (200...299).contains(status) else {
            fail("HTTP \(status)", "other")
            return
        }
        // 空响应体：didReceive data 没被调用过，临时文件也没建，这里补一个空文件。
        if !manager.fileExists(atPath: job.temporaryPath) {
            SDPath.ensureParentDirectory(of: job.temporaryPath)
            manager.createFile(atPath: job.temporaryPath, contents: nil, attributes: nil)
        }
        do {
            SDPath.ensureParentDirectory(of: job.destinationPath)
            if manager.fileExists(atPath: job.destinationPath) {
                try manager.removeItem(atPath: job.destinationPath)
            }
            try manager.moveItem(atPath: job.temporaryPath, toPath: job.destinationPath)
        } catch {
            fail(error.localizedDescription, "other")
            return
        }
        job.onProgress?(job.received, max(job.expected, job.received))
        finish(job, SDHttpEngine.downloadResult(ok: true, path: job.destinationPath, status: status, error: "", errorType: ""))
    }

    // MARK: - URLSessionDataDelegate

    func urlSession(_ session: URLSession,
                    dataTask: URLSessionDataTask,
                    didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let job = job(for: dataTask) else {
            completionHandler(.cancel)
            return
        }
        job.response = response as? HTTPURLResponse
        switch job.kind {
        case .probe:
            // 只需要响应头，立刻取消，避免服务器不支持 Range 时把整个文件下回来。
            job.probeLength = SDHttpEngine.totalLength(from: job.response)
            job.probeFinished = true
            completionHandler(.cancel)
        case .download:
            let status = job.response?.statusCode ?? 0
            if (200...299).contains(status) {
                job.expected = response.expectedContentLength
                SDPath.ensureParentDirectory(of: job.temporaryPath)
                let manager = FileManager.default
                if manager.fileExists(atPath: job.temporaryPath) {
                    try? manager.removeItem(atPath: job.temporaryPath)
                }
                if manager.createFile(atPath: job.temporaryPath, contents: nil, attributes: nil),
                   let handle = FileHandle(forWritingAtPath: job.temporaryPath) {
                    job.fileHandle = handle
                } else {
                    job.localError = "cannot create file"
                    completionHandler(.cancel)
                    return
                }
            }
            completionHandler(.allow)
        case .request, .upload:
            completionHandler(.allow)
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard let job = job(for: dataTask) else { return }
        switch job.kind {
        case .request, .upload:
            job.body.append(data)
        case .download:
            guard let handle = job.fileHandle else {
                // 非 2xx 的错误响应体不落盘。
                return
            }
            do {
                try handle.write(contentsOf: data)
                job.received += Int64(data.count)
                reportProgress(job, job.received, job.expected)
            } catch {
                job.localError = error.localizedDescription
                dataTask.cancel()
            }
        case .probe:
            break
        }
    }

    func urlSession(_ session: URLSession,
                    task: URLSessionTask,
                    didSendBodyData bytesSent: Int64,
                    totalBytesSent: Int64,
                    totalBytesExpectedToSend: Int64) {
        guard let job = job(for: task), job.kind == .upload else { return }
        reportProgress(job, totalBytesSent, totalBytesExpectedToSend)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let job = job(for: task) else { return }
        switch job.kind {
        case .request, .upload:
            if let error = error {
                finish(job, SDHttpEngine.failure(error: error))
            } else if let response = job.response {
                finish(job, SDHttpEngine.success(response: response, body: job.body))
            } else {
                finish(job, SDHttpEngine.failure(message: "no http response", type: "other"))
            }
        case .download:
            completeDownload(job, error: error)
        case .probe:
            if job.probeFinished {
                finish(job, ["ok": job.probeLength >= 0, "length": job.probeLength])
            } else {
                let message = error?.localizedDescription ?? "no http response"
                finish(job, ["ok": false, "length": -1, "error": message])
            }
        }
    }
}
