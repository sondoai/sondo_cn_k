import Foundation

/// 纯 Swift 的 MP3 重封装：对应参考工程的
/// `ffmpeg -i src -map 0:a:0 -map_metadata 0 -c:a copy -write_xing 1 dest`。
///
/// 做的事情：
/// 1. 原样保留文件头的 ID3v2 标签和文件尾的 ID3v1 / APEv2 标签；
/// 2. 逐帧解析 MPEG Layer III 音频帧（不解码），丢掉帧间垃圾数据和被截断的尾帧；
/// 3. 丢掉原有的 Xing / Info / VBRI 帧；
/// 4. 在最前面写一个新的 Xing（VBR）/ Info（CBR）帧：帧数、字节数、100 项 TOC，
///    让播放器 / 服务端不用扫全文件就能得到准确时长并支持拖动。
///
/// 任何一步解析失败都返回错误（不产生输出文件），调用方保留原文件。
/// 限制：只支持 Layer III；不支持 free-format 码率；不写 LAME 扩展（编码延迟 / 回放增益信息不保留）。
enum SDMp3XingRemuxer {

    /// 成功返回 nil，失败返回原因。可在任意后台线程调用。
    static func remux(source: URL, destination: URL) -> String? {
        let data: Data
        do {
            data = try Data(contentsOf: source, options: .mappedIfSafe)
        } catch {
            return "read failed: \(error.localizedDescription)"
        }
        if data.count < 64 {
            return "not an mp3 file"
        }
        let fm = FileManager.default
        let temp = destination.deletingLastPathComponent()
            .appendingPathComponent(".xing_" + UUID().uuidString + ".tmp")
        try? fm.createDirectory(at: destination.deletingLastPathComponent(),
                                withIntermediateDirectories: true, attributes: nil)

        let failure: String? = data.withUnsafeBytes { (bytes: UnsafeRawBufferPointer) -> String? in
            guard let plan = makePlan(bytes) else {
                return "mp3 frame parse failed"
            }
            return write(plan: plan, bytes: bytes, to: temp)
        }
        if let failure = failure {
            try? fm.removeItem(at: temp)
            return failure
        }
        do {
            if fm.fileExists(atPath: destination.path) {
                try fm.removeItem(at: destination)
            }
            try fm.moveItem(at: temp, to: destination)
        } catch {
            try? fm.removeItem(at: temp)
            return "write failed: \(error.localizedDescription)"
        }
        return nil
    }

    // MARK: - 帧头

    private struct FrameHeader {
        let raw: UInt32
        /// 3 = MPEG1，2 = MPEG2，0 = MPEG2.5
        let version: UInt32
        let bitrateIndex: Int
        let bitrateKbps: Int
        let sampleRate: Int
        let isMono: Bool
        let hasCRC: Bool
        let frameSize: Int

        var isMpeg1: Bool {
            return version == 3
        }

        /// Layer III 的 side info 长度；Xing 标签紧跟其后。
        var sideInfoSize: Int {
            if isMpeg1 {
                return isMono ? 17 : 32
            }
            return isMono ? 9 : 17
        }
    }

    private static let bitratesMpeg1 = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320]
    private static let bitratesMpeg2 = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160]

    private static func sampleRate(version: UInt32, index: Int) -> Int? {
        let base: [Int]
        switch version {
        case 3:
            base = [44100, 48000, 32000]
        case 2:
            base = [22050, 24000, 16000]
        case 0:
            base = [11025, 12000, 8000]
        default:
            return nil
        }
        return index >= 0 && index < base.count ? base[index] : nil
    }

    private static func frameSize(version: UInt32, bitrateKbps: Int, sampleRate: Int, padding: Int) -> Int {
        // Layer III：MPEG1 每帧 1152 采样，MPEG2 / 2.5 每帧 576 采样。
        let coefficient = version == 3 ? 144_000 : 72_000
        return coefficient * bitrateKbps / sampleRate + padding
    }

    private static func parseHeader(_ bytes: UnsafeRawBufferPointer, at offset: Int) -> FrameHeader? {
        guard offset >= 0, offset + 4 <= bytes.count else {
            return nil
        }
        let b0 = UInt32(bytes[offset])
        let b1 = UInt32(bytes[offset + 1])
        if b0 != 0xFF || (b1 & 0xE0) != 0xE0 {
            return nil
        }
        let raw = (b0 << 24) | (b1 << 16) | (UInt32(bytes[offset + 2]) << 8) | UInt32(bytes[offset + 3])
        let version = (raw >> 19) & 0x3
        let layer = (raw >> 17) & 0x3
        let bitrateIndex = Int((raw >> 12) & 0xF)
        let sampleRateIndex = Int((raw >> 10) & 0x3)
        let padding = Int((raw >> 9) & 0x1)
        let channelMode = (raw >> 6) & 0x3
        let emphasis = raw & 0x3
        // version 1 保留；layer 01 = Layer III；码率 0 = free format、15 = 非法；emphasis 2 保留。
        guard version != 1, layer == 1, bitrateIndex >= 1, bitrateIndex <= 14, emphasis != 2,
              let rate = sampleRate(version: version, index: sampleRateIndex) else {
            return nil
        }
        let kbps = version == 3 ? bitratesMpeg1[bitrateIndex] : bitratesMpeg2[bitrateIndex]
        let size = frameSize(version: version, bitrateKbps: kbps, sampleRate: rate, padding: padding)
        guard size > 4 else {
            return nil
        }
        return FrameHeader(
            raw: raw,
            version: version,
            bitrateIndex: bitrateIndex,
            bitrateKbps: kbps,
            sampleRate: rate,
            isMono: channelMode == 3,
            hasCRC: (raw >> 16) & 0x1 == 0,
            frameSize: size
        )
    }

    private static func compatible(_ a: FrameHeader, _ b: FrameHeader) -> Bool {
        return a.version == b.version && a.sampleRate == b.sampleRate
    }

    private static func matches(_ bytes: UnsafeRawBufferPointer, at offset: Int, limit: Int, _ tag: [UInt8]) -> Bool {
        guard offset >= 0, offset + tag.count <= limit else {
            return false
        }
        for (index, value) in tag.enumerated() where bytes[offset + index] != value {
            return false
        }
        return true
    }

    /// 帧里是否带 Xing / Info / VBRI 标签（这种帧不含音频，要丢掉重写）。
    private static func isVbrTagFrame(_ bytes: UnsafeRawBufferPointer, at offset: Int, header: FrameHeader) -> Bool {
        let end = offset + header.frameSize
        let xing: [UInt8] = [0x58, 0x69, 0x6E, 0x67] // "Xing"
        let info: [UInt8] = [0x49, 0x6E, 0x66, 0x6F] // "Info"
        let vbri: [UInt8] = [0x56, 0x42, 0x52, 0x49] // "VBRI"
        var candidates = [offset + 4 + header.sideInfoSize]
        if header.hasCRC {
            candidates.append(offset + 4 + 2 + header.sideInfoSize)
        }
        for candidate in candidates {
            if matches(bytes, at: candidate, limit: end, xing) || matches(bytes, at: candidate, limit: end, info) {
                return true
            }
        }
        return matches(bytes, at: offset + 4 + 32, limit: end, vbri)
    }

    // MARK: - 解析

    private struct Plan {
        /// 文件头原样保留的部分（ID3v2）：[0, leadingEnd)
        let leadingEnd: Int
        /// 文件尾原样保留的部分（ID3v1 / APEv2）：[trailingStart, count)
        let trailingStart: Int
        /// 连续的音频帧区间（已去掉旧 Xing 帧和垃圾数据）
        let runs: [Range<Int>]
        let xingFrame: [UInt8]
    }

    private static func makePlan(_ bytes: UnsafeRawBufferPointer) -> Plan? {
        let count = bytes.count

        // 1. 文件头的 ID3v2（可能不止一个）
        var audioStart = 0
        while audioStart + 10 <= count,
              bytes[audioStart] == 0x49, bytes[audioStart + 1] == 0x44, bytes[audioStart + 2] == 0x33 {
            let s0 = Int(bytes[audioStart + 6])
            let s1 = Int(bytes[audioStart + 7])
            let s2 = Int(bytes[audioStart + 8])
            let s3 = Int(bytes[audioStart + 9])
            if s0 >= 0x80 || s1 >= 0x80 || s2 >= 0x80 || s3 >= 0x80 {
                return nil
            }
            var total = 10 + ((s0 << 21) | (s1 << 14) | (s2 << 7) | s3)
            if bytes[audioStart + 5] & 0x10 != 0 {
                total += 10 // footer
            }
            if audioStart + total > count {
                return nil
            }
            audioStart += total
        }

        // 2. 文件尾的 ID3v1（128 字节，"TAG"）和 APEv2（32 字节 footer，"APETAGEX"）
        var audioEnd = count
        if audioEnd - audioStart >= 128, matches(bytes, at: audioEnd - 128, limit: count, [0x54, 0x41, 0x47]) {
            audioEnd -= 128
        }
        let apeMagic: [UInt8] = [0x41, 0x50, 0x45, 0x54, 0x41, 0x47, 0x45, 0x58]
        if audioEnd - audioStart >= 32, matches(bytes, at: audioEnd - 32, limit: count, apeMagic) {
            let footer = audioEnd - 32
            let tagSize = Int(littleEndian32(bytes, at: footer + 12))
            let flags = littleEndian32(bytes, at: footer + 20)
            // size 字段含 footer、不含 header；flags 最高位表示另有 32 字节 header。
            let total = tagSize + (flags & 0x8000_0000 != 0 ? 32 : 0)
            if total >= 32 && total <= audioEnd - audioStart {
                audioEnd -= total
            }
        }

        // 3. 找到第一个可信的帧：自己合法，且下一帧（如果有）也合法并且参数一致。
        let scanLimit = min(audioEnd, audioStart + 512 * 1024)
        var position = audioStart
        var reference: FrameHeader?
        while position + 4 <= scanLimit {
            if bytes[position] == 0xFF, let header = parseHeader(bytes, at: position),
               position + header.frameSize <= audioEnd {
                let next = position + header.frameSize
                if next + 4 > audioEnd {
                    reference = header
                    break
                }
                if let following = parseHeader(bytes, at: next), compatible(header, following) {
                    reference = header
                    break
                }
            }
            position += 1
        }
        guard let first = reference else {
            return nil
        }

        // 4. 逐帧走完整个音频区
        var runs: [Range<Int>] = []
        var frameOffsets: [Int] = []
        frameOffsets.reserveCapacity(16_384)
        var audioBytes = 0
        var firstAudio: FrameHeader?
        var constantBitrate = true
        var isFirstFrame = true
        var resyncing = false

        while position + 4 <= audioEnd {
            guard bytes[position] == 0xFF, let header = parseHeader(bytes, at: position), compatible(header, first) else {
                position += 1
                resyncing = true
                continue
            }
            let end = position + header.frameSize
            if end > audioEnd {
                // 被截断的尾帧：也可能是垃圾数据里的假同步字，继续往后找。
                position += 1
                resyncing = true
                continue
            }
            if resyncing && end + 4 <= audioEnd {
                // 垃圾数据之后重新同步：要求紧跟的下一帧也合法，避免把垃圾里的 0xFFEx 当成帧头。
                guard let following = parseHeader(bytes, at: end), compatible(header, following) else {
                    position += 1
                    continue
                }
            }
            resyncing = false
            if isFirstFrame {
                isFirstFrame = false
                if isVbrTagFrame(bytes, at: position, header: header) {
                    position = end
                    continue
                }
            }
            if let known = firstAudio {
                if known.bitrateKbps != header.bitrateKbps {
                    constantBitrate = false
                }
            } else {
                firstAudio = header
            }
            frameOffsets.append(audioBytes)
            audioBytes += header.frameSize
            if let last = runs.last, last.upperBound == position {
                runs[runs.count - 1] = last.lowerBound..<end
            } else {
                runs.append(position..<end)
            }
            position = end
        }

        guard let template = firstAudio, frameOffsets.count >= 8 else {
            return nil
        }
        guard let xing = makeXingFrame(template: template,
                                       constantBitrate: constantBitrate,
                                       frameOffsets: frameOffsets,
                                       audioBytes: audioBytes) else {
            return nil
        }
        return Plan(leadingEnd: audioStart, trailingStart: audioEnd, runs: runs, xingFrame: xing)
    }

    private static func littleEndian32(_ bytes: UnsafeRawBufferPointer, at offset: Int) -> UInt32 {
        guard offset >= 0, offset + 4 <= bytes.count else {
            return 0
        }
        return UInt32(bytes[offset])
            | (UInt32(bytes[offset + 1]) << 8)
            | (UInt32(bytes[offset + 2]) << 16)
            | (UInt32(bytes[offset + 3]) << 24)
    }

    // MARK: - Xing 帧

    private static func makeXingFrame(template: FrameHeader,
                                      constantBitrate: Bool,
                                      frameOffsets: [Int],
                                      audioBytes: Int) -> [UInt8]? {
        let frameCount = frameOffsets.count
        let sideInfo = template.sideInfoSize
        // 帧头 4 + side info + "Xing" 4 + flags 4 + 帧数 4 + 字节数 4 + TOC 100
        let needed = 4 + sideInfo + 4 + 4 + 4 + 4 + 100

        // 选码率：优先沿用音频帧的码率（CBR 文件保持帧长一致），放不下再从小到大找第一个放得下的。
        var chosenIndex: Int?
        var chosenSize = 0
        let table = template.isMpeg1 ? bitratesMpeg1 : bitratesMpeg2
        for index in [template.bitrateIndex] + Array(1...14) {
            let size = frameSize(version: template.version, bitrateKbps: table[index],
                                 sampleRate: template.sampleRate, padding: 0)
            if size >= needed {
                chosenIndex = index
                chosenSize = size
                break
            }
        }
        guard let bitrateIndex = chosenIndex else {
            return nil
        }

        let totalBytes = chosenSize + audioBytes
        guard frameCount <= Int(UInt32.max), totalBytes <= Int(UInt32.max), totalBytes > 0 else {
            return nil
        }

        var frame = [UInt8](repeating: 0, count: chosenSize)

        // 帧头：沿用音频帧的版本 / 层 / 采样率 / 声道模式；无 CRC、无 padding、码率换成选中的。
        var header = template.raw
        header |= 0x0001_0000                          // protection bit = 1（没有 CRC）
        header &= ~UInt32(0x0000_F000)
        header |= UInt32(bitrateIndex) << 12
        header &= ~UInt32(0x0000_0200)                 // padding = 0
        header &= ~UInt32(0x0000_0100)                 // private = 0
        header &= ~UInt32(0x0000_0003)                 // emphasis = none
        putBigEndian32(&frame, at: 0, header)

        var offset = 4 + sideInfo                      // side info 全 0：解码出来是一帧静音
        let tag: [UInt8] = constantBitrate ? [0x49, 0x6E, 0x66, 0x6F] : [0x58, 0x69, 0x6E, 0x67]
        for (index, value) in tag.enumerated() {
            frame[offset + index] = value
        }
        offset += 4
        putBigEndian32(&frame, at: offset, 0x0000_0007) // frames | bytes | TOC
        offset += 4
        putBigEndian32(&frame, at: offset, UInt32(frameCount))
        offset += 4
        putBigEndian32(&frame, at: offset, UInt32(totalBytes))
        offset += 4
        // TOC[i]：播放到 i% 时长时所在的文件位置（占整个音频流的 1/256 份数）。
        for percent in 0..<100 {
            let frameIndex = min(frameCount - 1, percent * frameCount / 100)
            let bytePosition = chosenSize + frameOffsets[frameIndex]
            frame[offset + percent] = UInt8(min(255, bytePosition * 256 / totalBytes))
        }
        return frame
    }

    private static func putBigEndian32(_ buffer: inout [UInt8], at offset: Int, _ value: UInt32) {
        buffer[offset] = UInt8((value >> 24) & 0xFF)
        buffer[offset + 1] = UInt8((value >> 16) & 0xFF)
        buffer[offset + 2] = UInt8((value >> 8) & 0xFF)
        buffer[offset + 3] = UInt8(value & 0xFF)
    }

    // MARK: - 写文件

    private static func write(plan: Plan, bytes: UnsafeRawBufferPointer, to url: URL) -> String? {
        guard let base = bytes.baseAddress else {
            return "empty file"
        }
        let fm = FileManager.default
        guard fm.createFile(atPath: url.path, contents: nil, attributes: nil),
              let handle = try? FileHandle(forWritingTo: url) else {
            return "cannot create output file"
        }
        do {
            try writeRange(0..<plan.leadingEnd, base: base, to: handle)
            try handle.write(contentsOf: Data(plan.xingFrame))
            for run in plan.runs {
                try writeRange(run, base: base, to: handle)
            }
            try writeRange(plan.trailingStart..<bytes.count, base: base, to: handle)
            try handle.close()
        } catch {
            try? handle.close()
            return "write failed: \(error.localizedDescription)"
        }
        return nil
    }

    /// 分块写入（不整段拷贝到内存）。
    private static func writeRange(_ range: Range<Int>, base: UnsafeRawPointer, to handle: FileHandle) throws {
        let chunk = 4 * 1024 * 1024
        var position = range.lowerBound
        while position < range.upperBound {
            let length = min(chunk, range.upperBound - position)
            let pointer = UnsafeMutableRawPointer(mutating: base.advanced(by: position))
            // 数据来自只读内存映射，这里只读不写；不接管内存。
            let data = Data(bytesNoCopy: pointer, count: length, deallocator: .none)
            try handle.write(contentsOf: data)
            position += length
        }
    }
}
