import AVFoundation
import CoreMedia
import Foundation
import UIKit

/// 媒体处理（契约见 shared 的 `SDMediaModules.kt`）。不依赖 ffmpeg，全部用系统媒体框架：
///
/// - probe：AVURLAsset 读时长 / 轨道信息；
/// - remuxMp3WithXing：纯 Swift 的 MP3 帧解析 + Xing 头重写（`SDMp3XingRemuxer`）；
/// - muxVideoWithAudio：AVMutableComposition 拼出「视频 + 指定区间的音频」，
///   先用 AVAssetReader / AVAssetWriter 做「视频流原样拷贝 + 音频转 AAC」，
///   失败时退回 AVAssetExportSession 整体重编码；
/// - extractWaveform：AVAssetReader 解成 16bit PCM，按时间片取最大振幅；
/// - trimAudio：AVAssetExportSession 导出 m4a（AAC）；
/// - videoFrame：AVAssetImageGenerator 截帧并存 JPEG。
///
/// 长任务（mux / waveform / trim）经常驻回调上报：
/// `{event: "progress", taskId, progress}` …… `{event: "done", taskId, ok, path?, error?}`（done 恰好一次）。
@objc(SDMediaProcessModule)
@objcMembers
final class SDMediaProcessModule: KRBaseModule {

    private static let workQueue = DispatchQueue(
        label: "com.sondo.media.process", qos: .userInitiated, attributes: .concurrent
    )

    // MARK: - probe

    /// params: {path} → {ok, durationMs, hasVideo, hasAudio, width?, height?, fps?, format?}
    func probe(_ args: NSDictionary) {
        let path = SD.params(args).sdString("path")
        let done = SD.once(args)
        SDMediaProcessModule.workQueue.async {
            guard let url = SDMedia.mediaURL(path), let info = SDMediaProbe.info(of: url) else {
                done.failure("probe failed")
                return
            }
            done.success(info.dict)
        }
    }

    // MARK: - remuxMp3WithXing

    /// params: {src, dest} → {ok, path}；失败时不产生 dest，调用方保留原文件。
    func remuxMp3WithXing(_ args: NSDictionary) {
        let params = SD.params(args)
        let done = SD.once(args)
        guard let source = SDMedia.fileURL(params.sdString("src")),
              let destination = SDMedia.fileURL(params.sdString("dest")) else {
            done.failure("invalid path")
            return
        }
        SDMediaProcessModule.workQueue.async {
            if let failure = SDMp3XingRemuxer.remux(source: source, destination: destination) {
                done.failure(failure)
            } else {
                done.success(["path": destination.path])
            }
        }
    }

    // MARK: - muxVideoWithAudio

    /// params: {videoPath, audioPath, audioStartMs?, audioDurationMs?, dest, taskId}
    func muxVideoWithAudio(_ args: NSDictionary) {
        let params = SD.params(args)
        let reporter = SDMediaTaskReporter(taskId: params.sdString("taskId"), callback: SD.callback(args))
        guard let videoURL = SDMedia.fileURL(params.sdString("videoPath")),
              let audioURL = SDMedia.fileURL(params.sdString("audioPath")),
              let destination = SDMedia.fileURL(params.sdString("dest")) else {
            reporter.fail("invalid path")
            return
        }
        let request = SDVideoAudioMuxer.Request(
            videoURL: videoURL,
            audioURL: audioURL,
            audioStartMs: max(0, params.sdInt64("audioStartMs", 0)),
            audioDurationMs: params.sdOptInt64("audioDurationMs"),
            destination: destination
        )
        let task = SDMediaTasks.register(reporter.taskId)
        SDMediaProcessModule.workQueue.async {
            SDVideoAudioMuxer.run(request, task: task, reporter: reporter)
        }
    }

    // MARK: - extractWaveform

    /// params: {audioPath, samplesPerSecond(8–40), taskId}
    /// done: {event: "done", ok, durationMs, peaks: [0..1]}
    func extractWaveform(_ args: NSDictionary) {
        let params = SD.params(args)
        let reporter = SDMediaTaskReporter(taskId: params.sdString("taskId"), callback: SD.callback(args))
        guard let audioURL = SDMedia.fileURL(params.sdString("audioPath")) else {
            reporter.fail("invalid path")
            return
        }
        let samplesPerSecond = min(200, max(1, params.sdInt("samplesPerSecond", 20)))
        let task = SDMediaTasks.register(reporter.taskId)
        SDMediaProcessModule.workQueue.async {
            SDWaveformExtractor.run(audioURL: audioURL, samplesPerSecond: samplesPerSecond, task: task, reporter: reporter)
        }
    }

    // MARK: - trimAudio

    /// params: {src, dest, startMs, endMs, taskId}
    /// 输出固定是 m4a（AAC）：dest 的扩展名不是 m4a 时会被替换，实际路径以 done 事件里的 path 为准。
    func trimAudio(_ args: NSDictionary) {
        let params = SD.params(args)
        let reporter = SDMediaTaskReporter(taskId: params.sdString("taskId"), callback: SD.callback(args))
        guard let source = SDMedia.fileURL(params.sdString("src")),
              var destination = SDMedia.fileURL(params.sdString("dest")) else {
            reporter.fail("invalid path")
            return
        }
        if destination.pathExtension.lowercased() != "m4a" {
            destination = destination.deletingPathExtension().appendingPathExtension("m4a")
        }
        let startMs = max(0, params.sdInt64("startMs", 0))
        let endMs = params.sdInt64("endMs", 0)
        let output = destination
        let task = SDMediaTasks.register(reporter.taskId)
        SDMediaProcessModule.workQueue.async {
            guard FileManager.default.fileExists(atPath: source.path) else {
                SDMediaTasks.finish(task)
                reporter.fail("file not found")
                return
            }
            let asset = AVURLAsset(url: source, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
            let total = asset.duration
            guard total.isNumeric, CMTimeGetSeconds(total) > 0 else {
                SDMediaTasks.finish(task)
                reporter.fail("unreadable audio")
                return
            }
            let start = CMTimeMinimum(SDMedia.time(ms: startMs), total)
            var end = total
            if endMs > startMs {
                end = CMTimeMinimum(SDMedia.time(ms: endMs), total)
            }
            guard CMTimeCompare(end, start) > 0 else {
                SDMediaTasks.finish(task)
                reporter.fail("empty range")
                return
            }
            guard let session = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
                SDMediaTasks.finish(task)
                reporter.fail("export unavailable")
                return
            }
            session.timeRange = CMTimeRange(start: start, end: end)
            SDMediaExport.run(session, fileType: .m4a, destination: output, task: task, progress: { value in
                reporter.progress(value)
            }, completion: { failure in
                SDMediaTasks.finish(task)
                if let failure = failure {
                    reporter.fail(failure)
                } else {
                    reporter.progress(1)
                    reporter.succeed(["path": output.path])
                }
            })
        }
    }

    // MARK: - videoFrame

    /// params: {videoPath, positionMs, dest} → {ok, path, width, height}
    func videoFrame(_ args: NSDictionary) {
        let params = SD.params(args)
        let done = SD.once(args)
        let positionMs = max(0, params.sdInt64("positionMs", 0))
        guard let videoURL = SDMedia.mediaURL(params.sdString("videoPath")) else {
            done.failure("invalid path")
            return
        }
        let destination = SDMedia.fileURL(params.sdString("dest")) ?? SDMedia.tempFileURL(prefix: "frame", ext: "jpg")
        SDMediaProcessModule.workQueue.async {
            let asset = AVURLAsset(url: videoURL)
            let generator = AVAssetImageGenerator(asset: asset)
            generator.appliesPreferredTrackTransform = true
            generator.requestedTimeToleranceBefore = .zero
            generator.requestedTimeToleranceAfter = .zero

            var time = SDMedia.time(ms: positionMs)
            let total = asset.duration
            if total.isNumeric, CMTimeCompare(time, total) >= 0 {
                // 超出时长：取最后一帧附近。
                time = CMTimeMaximum(.zero, CMTimeSubtract(total, CMTime(value: 100, timescale: 1000)))
            }
            var image: CGImage?
            do {
                image = try generator.copyCGImage(at: time, actualTime: nil)
            } catch {
                // 精确定位失败（个别编码的最后几帧）：放宽容差再取一次。
                generator.requestedTimeToleranceBefore = .positiveInfinity
                generator.requestedTimeToleranceAfter = .positiveInfinity
                image = try? generator.copyCGImage(at: time, actualTime: nil)
            }
            guard let frame = image,
                  let output = SDImageCodec.writeJPEG(frame, quality: 90, to: destination) else {
                done.failure("frame capture failed")
                return
            }
            done.success(["path": output.path, "width": output.width, "height": output.height])
        }
    }

    // MARK: - cancel

    /// params: {taskId}
    func cancel(_ args: NSDictionary) {
        let taskId = SD.params(args).sdString("taskId")
        if taskId.isEmpty {
            return
        }
        SDMediaTasks.cancel(taskId)
    }
}

// MARK: - 任务登记 / 取消

/// 一个可取消的长任务。线程安全。
final class SDMediaTask {
    let id: String
    private let lock = NSLock()
    private var cancelled = false
    private var cancelHandler: (() -> Void)?

    init(id: String) {
        self.id = id
    }

    var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return cancelled
    }

    /// 注册取消动作（如 `exportSession.cancelExport()`）；若任务已被取消则立即执行。
    func onCancel(_ handler: @escaping () -> Void) {
        lock.lock()
        if cancelled {
            lock.unlock()
            handler()
            return
        }
        cancelHandler = handler
        lock.unlock()
    }

    func cancel() {
        lock.lock()
        if cancelled {
            lock.unlock()
            return
        }
        cancelled = true
        let handler = cancelHandler
        cancelHandler = nil
        lock.unlock()
        handler?()
    }
}

enum SDMediaTasks {
    private static let lock = NSLock()
    private static var tasks: [String: SDMediaTask] = [:]

    static func register(_ id: String) -> SDMediaTask {
        let task = SDMediaTask(id: id)
        if id.isEmpty {
            return task
        }
        lock.lock()
        tasks[id] = task
        lock.unlock()
        return task
    }

    static func finish(_ task: SDMediaTask) {
        lock.lock()
        if tasks[task.id] === task {
            tasks[task.id] = nil
        }
        lock.unlock()
    }

    static func cancel(_ id: String) {
        lock.lock()
        let task = tasks[id]
        lock.unlock()
        task?.cancel()
    }
}

/// 长任务的事件上报：progress 限频，done 恰好一次。线程安全。
final class SDMediaTaskReporter {
    let taskId: String
    private let callback: SDCallback?
    private let lock = NSLock()
    private var finished = false
    private var lastProgress = -1.0

    init(taskId: String, callback: SDCallback?) {
        self.taskId = taskId
        self.callback = callback
    }

    func progress(_ value: Double) {
        guard value.isFinite else {
            return
        }
        let clamped = min(1, max(0, value))
        lock.lock()
        if finished || clamped - lastProgress < 0.01 {
            lock.unlock()
            return
        }
        lastProgress = clamped
        lock.unlock()
        callback?(["event": "progress", "taskId": taskId, "progress": clamped])
    }

    func succeed(_ extra: [String: Any]) {
        var result = extra
        result["ok"] = true
        finish(result)
    }

    func fail(_ error: String) {
        finish(["ok": false, "error": error])
    }

    private func finish(_ payload: [String: Any]) {
        lock.lock()
        if finished {
            lock.unlock()
            return
        }
        finished = true
        lock.unlock()
        var result = payload
        result["event"] = "done"
        result["taskId"] = taskId
        callback?(result)
    }
}

// MARK: - probe

enum SDMediaProbe {

    struct Info {
        let durationMs: Int64
        let hasVideo: Bool
        let hasAudio: Bool
        let width: Int
        let height: Int
        let fps: Double
        let format: String

        var dict: [String: Any] {
            var result: [String: Any] = [
                "durationMs": durationMs,
                "hasVideo": hasVideo,
                "hasAudio": hasAudio,
                "format": format,
            ]
            if hasVideo {
                result["width"] = width
                result["height"] = height
                result["fps"] = fps
            }
            return result
        }
    }

    /// 同步读取媒体信息（会阻塞，必须在后台线程调用）。文件不存在 / 不是可识别的音视频时返回 nil。
    static func info(of url: URL) -> Info? {
        if url.isFileURL && !FileManager.default.fileExists(atPath: url.path) {
            return nil
        }
        // 本地文件要求精确时长：没有 Xing 头的 VBR MP3 否则只能得到估算值。
        let options: [String: Any]? = url.isFileURL ? [AVURLAssetPreferPreciseDurationAndTimingKey: true] : nil
        let asset = AVURLAsset(url: url, options: options)
        let videoTracks = asset.tracks(withMediaType: .video)
        let audioTracks = asset.tracks(withMediaType: .audio)
        if videoTracks.isEmpty && audioTracks.isEmpty {
            return nil
        }
        var width = 0
        var height = 0
        var fps = 0.0
        if let track = videoTracks.first {
            let size = track.naturalSize.applying(track.preferredTransform)
            width = Int(abs(size.width).rounded())
            height = Int(abs(size.height).rounded())
            let rate = Double(track.nominalFrameRate)
            fps = rate.isFinite ? (rate * 100).rounded() / 100 : 0
        }
        return Info(
            durationMs: SDMedia.ms(asset.duration),
            hasVideo: !videoTracks.isEmpty,
            hasAudio: !audioTracks.isEmpty,
            width: width,
            height: height,
            fps: fps,
            format: url.pathExtension.lowercased()
        )
    }

    /// 轨道的第一个格式描述。
    static func formatDescription(of track: AVAssetTrack) -> CMFormatDescription? {
        guard let first = track.formatDescriptions.first else {
            return nil
        }
        let object = first as CFTypeRef
        guard CFGetTypeID(object) == CMFormatDescriptionGetTypeID() else {
            return nil
        }
        // CoreFoundation 类型没有条件转换，类型已在上面校验过。
        return (object as! CMFormatDescription)
    }
}

// MARK: - AVAssetExportSession 封装

enum SDMediaExport {

    /// 运行导出并轮询进度；`completion(nil)` 表示成功，否则是失败原因（取消为 "cancelled"）。
    /// 先导出到临时文件，成功后再移动到 destination。
    static func run(_ session: AVAssetExportSession,
                    fileType: AVFileType,
                    destination: URL,
                    task: SDMediaTask,
                    progress: ((Double) -> Void)?,
                    completion: @escaping (String?) -> Void) {
        // 导出会话要求输出文件的扩展名与容器类型一致。
        let temp = SDMedia.tempFileURL(prefix: "export", ext: fileType == .m4a ? "m4a" : "mp4")
        session.outputURL = temp
        session.outputFileType = fileType
        session.shouldOptimizeForNetworkUse = true

        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now() + 0.2, repeating: 0.2)
        timer.setEventHandler {
            progress?(Double(session.progress))
        }
        timer.resume()

        task.onCancel {
            session.cancelExport()
        }
        session.exportAsynchronously {
            timer.cancel()
            let fm = FileManager.default
            switch session.status {
            case .completed:
                do {
                    SDMedia.prepareDestination(destination)
                    try fm.moveItem(at: temp, to: destination)
                    completion(nil)
                } catch {
                    try? fm.removeItem(at: temp)
                    completion(error.localizedDescription)
                }
            case .cancelled:
                try? fm.removeItem(at: temp)
                completion("cancelled")
            default:
                try? fm.removeItem(at: temp)
                completion(task.isCancelled ? "cancelled" : (session.error?.localizedDescription ?? "export failed"))
            }
        }
    }
}

// MARK: - 视频 + 音频混流

/// 对应参考工程的
/// `ffmpeg -i video [-ss a -t d] -i audio -c:v copy -c:a aac -map 0:v:0 -map 1:a:0 -shortest dest`。
enum SDVideoAudioMuxer {

    struct Request {
        let videoURL: URL
        let audioURL: URL
        let audioStartMs: Int64
        let audioDurationMs: Int64?
        let destination: URL
    }

    private struct Prepared {
        let composition: AVMutableComposition
        let videoTrack: AVCompositionTrack
        let audioTrack: AVCompositionTrack
        let sourceVideoTrack: AVAssetTrack
        let sourceAudioTrack: AVAssetTrack
        let duration: CMTime
    }

    /// 在后台线程调用；内部会阻塞到任务结束。
    static func run(_ request: Request, task: SDMediaTask, reporter: SDMediaTaskReporter) {
        defer {
            SDMediaTasks.finish(task)
        }
        let fm = FileManager.default
        guard fm.fileExists(atPath: request.videoURL.path), fm.fileExists(atPath: request.audioURL.path) else {
            reporter.fail("file not found")
            return
        }
        let prepared: Prepared
        switch prepare(request) {
        case .success(let value):
            prepared = value
        case .failure(let failure):
            reporter.fail(failure.message)
            return
        }

        // 方案 A：视频流原样拷贝 + 音频转 AAC。
        var failure = remux(prepared, destination: request.destination, task: task, reporter: reporter)
        if failure != nil && !task.isCancelled {
            // 方案 B：个别视频流无法直接写入 mp4（或读写器出错）时，退回整体重编码。
            failure = export(prepared, destination: request.destination, task: task, reporter: reporter)
        }
        if task.isCancelled {
            try? fm.removeItem(at: request.destination)
            reporter.fail("cancelled")
            return
        }
        if let failure = failure {
            try? fm.removeItem(at: request.destination)
            reporter.fail(failure)
            return
        }
        // 与参考工程一致：输出小于 1KB 视为失败。
        guard SDMedia.fileSize(request.destination) > 1024 else {
            try? fm.removeItem(at: request.destination)
            reporter.fail("output too small")
            return
        }
        reporter.progress(1)
        reporter.succeed(["path": request.destination.path])
    }

    private struct PrepareError: Error {
        let message: String
    }

    private static func prepare(_ request: Request) -> Result<Prepared, PrepareError> {
        let options: [String: Any] = [AVURLAssetPreferPreciseDurationAndTimingKey: true]
        let videoAsset = AVURLAsset(url: request.videoURL, options: options)
        let audioAsset = AVURLAsset(url: request.audioURL, options: options)
        guard let videoTrack = videoAsset.tracks(withMediaType: .video).first else {
            return .failure(PrepareError(message: "no video track"))
        }
        guard let audioTrack = audioAsset.tracks(withMediaType: .audio).first else {
            return .failure(PrepareError(message: "no audio track"))
        }
        let videoRange = videoTrack.timeRange
        let audioRange = audioTrack.timeRange
        guard videoRange.duration.isNumeric, audioRange.duration.isNumeric,
              CMTimeGetSeconds(videoRange.duration) > 0, CMTimeGetSeconds(audioRange.duration) > 0 else {
            return .failure(PrepareError(message: "invalid duration"))
        }

        // 音频取 [audioStart, audioStart + audioDuration)，不超过音频本身的长度。
        let audioStart = CMTimeAdd(audioRange.start, SDMedia.time(ms: request.audioStartMs))
        let audioEnd = CMTimeRangeGetEnd(audioRange)
        guard CMTimeCompare(audioStart, audioEnd) < 0 else {
            return .failure(PrepareError(message: "audio start out of range"))
        }
        var audioAvailable = CMTimeSubtract(audioEnd, audioStart)
        if let limitMs = request.audioDurationMs, limitMs > 0 {
            audioAvailable = CMTimeMinimum(audioAvailable, SDMedia.time(ms: limitMs))
        }
        // -shortest：以较短的一路为准。
        let duration = CMTimeMinimum(videoRange.duration, audioAvailable)
        guard CMTimeGetSeconds(duration) > 0 else {
            return .failure(PrepareError(message: "empty output"))
        }

        let composition = AVMutableComposition()
        guard let compositionVideo = composition.addMutableTrack(
                withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid),
              let compositionAudio = composition.addMutableTrack(
                withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            return .failure(PrepareError(message: "composition failed"))
        }
        do {
            try compositionVideo.insertTimeRange(
                CMTimeRange(start: videoRange.start, duration: duration), of: videoTrack, at: .zero)
            try compositionAudio.insertTimeRange(
                CMTimeRange(start: audioStart, duration: duration), of: audioTrack, at: .zero)
        } catch {
            return .failure(PrepareError(message: error.localizedDescription))
        }
        compositionVideo.preferredTransform = videoTrack.preferredTransform

        return .success(Prepared(
            composition: composition,
            videoTrack: compositionVideo,
            audioTrack: compositionAudio,
            sourceVideoTrack: videoTrack,
            sourceAudioTrack: audioTrack,
            duration: duration
        ))
    }

    // MARK: 方案 A：读写器（视频直通 + 音频 AAC）

    /// 成功返回 nil；失败 / 取消返回原因。
    private static func remux(_ prepared: Prepared,
                              destination: URL,
                              task: SDMediaTask,
                              reporter: SDMediaTaskReporter) -> String? {
        guard let videoFormat = SDMediaProbe.formatDescription(of: prepared.sourceVideoTrack) else {
            return "no video format"
        }
        SDMedia.prepareDestination(destination)

        let reader: AVAssetReader
        let writer: AVAssetWriter
        do {
            reader = try AVAssetReader(asset: prepared.composition)
            writer = try AVAssetWriter(outputURL: destination, fileType: .mp4)
        } catch {
            return error.localizedDescription
        }
        writer.shouldOptimizeForNetworkUse = true

        // 视频：outputSettings = nil → 压缩样本原样读出、原样写入。
        let videoOutput = AVAssetReaderTrackOutput(track: prepared.videoTrack, outputSettings: nil)
        videoOutput.alwaysCopiesSampleData = false
        // 音频：解成 16bit 交错 PCM，再由写入端编码成 AAC。
        let pcmSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
        ]
        let audioOutput = AVAssetReaderTrackOutput(track: prepared.audioTrack, outputSettings: pcmSettings)
        audioOutput.alwaysCopiesSampleData = false
        guard reader.canAdd(videoOutput), reader.canAdd(audioOutput) else {
            return "reader setup failed"
        }
        reader.add(videoOutput)
        reader.add(audioOutput)

        guard let aacSettings = aacSettings(for: prepared.sourceAudioTrack, writer: writer) else {
            return "aac settings unavailable"
        }
        let videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: nil, sourceFormatHint: videoFormat)
        videoInput.expectsMediaDataInRealTime = false
        videoInput.transform = prepared.sourceVideoTrack.preferredTransform
        let audioInput = AVAssetWriterInput(mediaType: .audio, outputSettings: aacSettings)
        audioInput.expectsMediaDataInRealTime = false
        guard writer.canAdd(videoInput), writer.canAdd(audioInput) else {
            return "writer setup failed"
        }
        writer.add(videoInput)
        writer.add(audioInput)

        guard reader.startReading() else {
            return reader.error?.localizedDescription ?? "reader start failed"
        }
        guard writer.startWriting() else {
            reader.cancelReading()
            return writer.error?.localizedDescription ?? "writer start failed"
        }
        writer.startSession(atSourceTime: .zero)

        let totalSeconds = CMTimeGetSeconds(prepared.duration)
        let queue = DispatchQueue(label: "com.sondo.media.mux")
        let state = PumpState(pending: 2)

        // 两路都在同一个串行队列上按写入端的节奏喂数据（与 Apple 的 AVReaderWriter 示例一致）。
        func pump(_ input: AVAssetWriterInput, from output: AVAssetReaderOutput, reportsProgress: Bool) {
            var finished = false
            input.requestMediaDataWhenReady(on: queue) {
                // aborted 只在这个串行队列上读写。
                if finished || state.aborted {
                    return
                }
                while input.isReadyForMoreMediaData {
                    var shouldFinish = false
                    if task.isCancelled || reader.status != .reading || writer.status != .writing {
                        shouldFinish = true
                    } else if let sample = output.copyNextSampleBuffer() {
                        if reportsProgress && totalSeconds > 0 {
                            let seconds = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
                            // 留 5% 给收尾（finishWriting）。
                            reporter.progress(seconds / totalSeconds * 0.95)
                        }
                        if !input.append(sample) {
                            shouldFinish = true
                        }
                    } else {
                        shouldFinish = true
                    }
                    if shouldFinish {
                        finished = true
                        input.markAsFinished()
                        state.finishOne()
                        return
                    }
                }
            }
        }
        pump(videoInput, from: videoOutput, reportsProgress: true)
        pump(audioInput, from: audioOutput, reportsProgress: false)

        // 等两路都喂完。写入端一旦出错可能不再回调，所以轮询状态而不是无限等待。
        while state.pending > 0 {
            if task.isCancelled || reader.status == .failed || writer.status == .failed {
                break
            }
            Thread.sleep(forTimeInterval: 0.02)
        }

        let cancelled = task.isCancelled
        if cancelled || reader.status == .failed || writer.status == .failed || state.pending > 0 {
            let message: String
            if cancelled {
                message = "cancelled"
            } else if reader.status == .failed {
                message = reader.error?.localizedDescription ?? "read failed"
            } else {
                message = writer.error?.localizedDescription ?? "write failed"
            }
            // 在喂数据的队列上收尾：等正在执行的回调返回，并阻止之后的回调再动读写器。
            queue.sync {
                state.aborted = true
                reader.cancelReading()
                writer.cancelWriting()
            }
            return message
        }

        let semaphore = DispatchSemaphore(value: 0)
        writer.finishWriting {
            semaphore.signal()
        }
        semaphore.wait()
        if writer.status != .completed {
            return writer.error?.localizedDescription ?? "finish failed"
        }
        return nil
    }

    /// 读写两路的收尾计数。`pending` 跨线程读写（加锁）；`aborted` 只在喂数据的串行队列上访问。
    private final class PumpState {
        private let lock = NSLock()
        private var remaining: Int
        var aborted = false

        init(pending: Int) {
            remaining = pending
        }

        var pending: Int {
            lock.lock()
            defer { lock.unlock() }
            return remaining
        }

        func finishOne() {
            lock.lock()
            remaining -= 1
            lock.unlock()
        }
    }

    /// AAC 编码参数：尽量沿用音源的采样率 / 声道数；写入端不接受时逐步退回最通用的配置。
    private static func aacSettings(for track: AVAssetTrack, writer: AVAssetWriter) -> [String: Any]? {
        var sampleRate = 44_100.0
        var channels = 2
        if let format = SDMediaProbe.formatDescription(of: track),
           let description = CMAudioFormatDescriptionGetStreamBasicDescription(format)?.pointee {
            let supported: [Double] = [8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000]
            if supported.contains(description.mSampleRate) {
                sampleRate = description.mSampleRate
            } else if description.mSampleRate > 48_000 {
                sampleRate = 48_000
            }
            channels = description.mChannelsPerFrame == 1 ? 1 : 2
        }
        let perChannel: Int
        if sampleRate >= 44_100 {
            perChannel = 64_000
        } else if sampleRate >= 32_000 {
            perChannel = 48_000
        } else {
            perChannel = 32_000
        }
        let preferred: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: channels,
            AVEncoderBitRateKey: perChannel * channels,
        ]
        let withoutBitrate: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: channels,
        ]
        let generic: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 44_100.0,
            AVNumberOfChannelsKey: 2,
        ]
        for candidate in [preferred, withoutBitrate, generic] where writer.canApply(outputSettings: candidate, forMediaType: .audio) {
            return candidate
        }
        return nil
    }

    // MARK: 方案 B：导出会话（整体重编码）

    private static func export(_ prepared: Prepared,
                               destination: URL,
                               task: SDMediaTask,
                               reporter: SDMediaTaskReporter) -> String? {
        guard let session = AVAssetExportSession(asset: prepared.composition,
                                                 presetName: AVAssetExportPresetHighestQuality) else {
            return "export unavailable"
        }
        var failure: String?
        let semaphore = DispatchSemaphore(value: 0)
        SDMediaExport.run(session, fileType: .mp4, destination: destination, task: task, progress: { value in
            reporter.progress(value * 0.95)
        }, completion: { result in
            failure = result
            semaphore.signal()
        })
        semaphore.wait()
        return failure
    }
}

// MARK: - 波形

enum SDWaveformExtractor {

    /// 在后台线程调用；阻塞到结束。每个时间片（1 / samplesPerSecond 秒）取所有声道的最大绝对振幅，归一化到 0..1。
    static func run(audioURL: URL, samplesPerSecond: Int, task: SDMediaTask, reporter: SDMediaTaskReporter) {
        defer {
            SDMediaTasks.finish(task)
        }
        guard FileManager.default.fileExists(atPath: audioURL.path) else {
            reporter.fail("file not found")
            return
        }
        let asset = AVURLAsset(url: audioURL, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
        guard let track = asset.tracks(withMediaType: .audio).first else {
            reporter.fail("no audio track")
            return
        }
        let reader: AVAssetReader
        do {
            reader = try AVAssetReader(asset: asset)
        } catch {
            reporter.fail(error.localizedDescription)
            return
        }
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
        ]
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: settings)
        output.alwaysCopiesSampleData = false
        guard reader.canAdd(output) else {
            reporter.fail("reader setup failed")
            return
        }
        reader.add(output)
        guard reader.startReading() else {
            reporter.fail(reader.error?.localizedDescription ?? "reader start failed")
            return
        }

        let totalSeconds = CMTimeGetSeconds(asset.duration)
        var peaks: [Double] = []
        var scratch: [Int16] = []
        var sampleRate = 0.0
        var channels = 0
        var frameIndex = 0
        var sliceIndex = 0
        var slicePeak: Int32 = 0
        var sliceHasData = false

        while reader.status == .reading {
            if task.isCancelled {
                reader.cancelReading()
                reporter.fail("cancelled")
                return
            }
            guard let sample = output.copyNextSampleBuffer() else {
                break
            }
            if let format = CMSampleBufferGetFormatDescription(sample),
               let description = CMAudioFormatDescriptionGetStreamBasicDescription(format)?.pointee {
                sampleRate = description.mSampleRate
                channels = Int(description.mChannelsPerFrame)
            }
            guard sampleRate > 0, channels > 0, let block = CMSampleBufferGetDataBuffer(sample) else {
                continue
            }
            let sampleCount = CMBlockBufferGetDataLength(block) / MemoryLayout<Int16>.size
            if sampleCount == 0 {
                continue
            }
            if scratch.count < sampleCount {
                scratch = [Int16](repeating: 0, count: sampleCount)
            }
            let status = scratch.withUnsafeMutableBytes { raw -> OSStatus in
                guard let base = raw.baseAddress else {
                    return -1
                }
                return CMBlockBufferCopyDataBytes(
                    block, atOffset: 0, dataLength: sampleCount * MemoryLayout<Int16>.size, destination: base)
            }
            if status != kCMBlockBufferNoErr {
                continue
            }

            let framesPerSlice = sampleRate / Double(samplesPerSecond)
            let frames = sampleCount / channels
            scratch.withUnsafeBufferPointer { samples in
                var cursor = 0
                var boundary = Double(sliceIndex + 1) * framesPerSlice
                for _ in 0..<frames {
                    for _ in 0..<channels {
                        let value = Int32(samples[cursor])
                        cursor += 1
                        let magnitude = value < 0 ? -value : value
                        if magnitude > slicePeak {
                            slicePeak = magnitude
                        }
                    }
                    sliceHasData = true
                    frameIndex += 1
                    if Double(frameIndex) >= boundary {
                        peaks.append(normalized(slicePeak))
                        slicePeak = 0
                        sliceHasData = false
                        sliceIndex += 1
                        boundary = Double(sliceIndex + 1) * framesPerSlice
                    }
                }
            }
            if totalSeconds.isFinite && totalSeconds > 0 {
                reporter.progress(Double(frameIndex) / (totalSeconds * sampleRate))
            }
        }

        if reader.status == .failed {
            reporter.fail(reader.error?.localizedDescription ?? "decode failed")
            return
        }
        if task.isCancelled {
            reporter.fail("cancelled")
            return
        }
        if sliceHasData {
            peaks.append(normalized(slicePeak))
        }
        guard sampleRate > 0, !peaks.isEmpty else {
            reporter.fail("no audio data")
            return
        }
        reporter.progress(1)
        reporter.succeed([
            "durationMs": SDMedia.ms(seconds: Double(frameIndex) / sampleRate),
            "peaks": peaks,
        ])
    }

    /// 0..32768 → 0..1，保留 3 位小数（减小回传 JSON 的体积）。
    private static func normalized(_ peak: Int32) -> Double {
        let value = min(1, Double(peak) / 32_768)
        return (value * 1000).rounded() / 1000
    }
}
