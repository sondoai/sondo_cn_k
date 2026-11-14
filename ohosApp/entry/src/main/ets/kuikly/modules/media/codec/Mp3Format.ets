/*
 * MP3（MPEG-1/2/2.5 Layer III）帧级处理，纯 ArkTS，不解码音频：
 *
 *  - scan()            跳过 ID3v2，逐帧解析帧头，识别并丢弃已有的 Xing / Info / VBRI 信息帧
 *  - writeWithXing()   重新写出：ID3v2（原样保留）+ 新 Xing/Info 帧（帧数、字节数、100 项 TOC）+ 音频帧
 *                      等价于 `ffmpeg -c:a copy -write_xing 1`，用于修正 VBR 文件的时长 / 拖动
 *  - writeTrimmed()    按帧裁剪 [startMs, endMs)，同样带 Xing 头（无损，精度一帧 ≈ 26ms）
 *  - gainEnvelope()    不解码的响度包络近似（见方法注释），供波形图降级使用
 *
 * 任何解析失败都抛 Error，调用方据此返回 ok=false（保留源文件）。
 */
import { Bin, ByteSink, MediaCancelledError, MediaProgress } from './ByteIO';

const BITRATES_V1_L1: Array<number> = [0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448];
const BITRATES_V1_L2: Array<number> = [0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384];
const BITRATES_V1_L3: Array<number> = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320];
const BITRATES_V2_L1: Array<number> = [0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256];
const BITRATES_V2_L23: Array<number> = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160];
const SAMPLE_RATES_V1: Array<number> = [44100, 48000, 32000];
const SAMPLE_RATES_V2: Array<number> = [22050, 24000, 16000];
const SAMPLE_RATES_V25: Array<number> = [11025, 12000, 8000];

/** Xing 信息体长度：tag(4) + flags(4) + frames(4) + bytes(4) + TOC(100)。 */
const XING_BODY_SIZE: number = 116;
const XING_FLAGS: number = 0x07;

export class Mp3FrameHeader {
  /** MPEG 版本位：3 = MPEG-1，2 = MPEG-2，0 = MPEG-2.5。 */
  versionBits: number = 3;
  /** 实际 layer：1 / 2 / 3。 */
  layer: number = 3;
  /** 帧头后是否跟 16 位 CRC。 */
  hasCrc: boolean = false;
  bitrateIndex: number = 0;
  bitrateKbps: number = 0;
  sampleRateIndex: number = 0;
  sampleRate: number = 0;
  padding: number = 0;
  /** 0 立体声 / 1 联合立体声 / 2 双声道 / 3 单声道。 */
  channelMode: number = 0;
  frameSize: number = 0;
  samplesPerFrame: number = 0;
  /** Layer III side info 字节数。 */
  sideInfoSize: number = 0;
  /** 帧头第 2、4 字节原值（生成 Xing 帧时复用）。 */
  byte1: number = 0;
  byte3: number = 0;

  get channels(): number {
    return this.channelMode === 3 ? 1 : 2;
  }

  get isMpeg1(): boolean {
    return this.versionBits === 3;
  }

  /** 与另一帧是否属于同一条流（版本、layer、采样率一致）。 */
  sameStream(other: Mp3FrameHeader): boolean {
    return this.versionBits === other.versionBits && this.layer === other.layer &&
      this.sampleRate === other.sampleRate;
  }
}

export class Mp3Scan {
  /** 文件头部 ID3v2 标签总长度（可能有多个，全部计入）。 */
  id3v2Size: number = 0;
  /** 第一帧音频帧的帧头。 */
  header: Mp3FrameHeader = new Mp3FrameHeader();
  /** 每个音频帧在文件中的偏移 / 长度（不含被丢弃的信息帧）。 */
  offsets: Array<number> = [];
  sizes: Array<number> = [];
  /** 是否丢弃了原有的 Xing / Info / VBRI 帧。 */
  droppedInfoFrame: boolean = false;
  /** 各帧码率是否不一致。 */
  vbr: boolean = false;
  /** 音频帧总字节数。 */
  audioBytes: number = 0;
  /** 文件尾 ID3v1 标签偏移，没有则为 -1。 */
  id3v1Offset: number = -1;

  get frameCount(): number {
    return this.offsets.length;
  }

  get durationMs(): number {
    if (this.header.sampleRate <= 0) {
      return 0;
    }
    return Math.round(this.frameCount * this.header.samplesPerFrame * 1000 / this.header.sampleRate);
  }
}

export class Mp3Envelope {
  durationMs: number = 0;
  peaks: Array<number> = [];
}

export class Mp3Format {
  /** 解析 o 处的 4 字节帧头；不是合法帧头返回 null。 */
  static parseHeader(b: Uint8Array, o: number): Mp3FrameHeader | null {
    if (o < 0 || o + 4 > b.length) {
      return null;
    }
    const b0: number = b[o];
    const b1: number = b[o + 1];
    const b2: number = b[o + 2];
    const b3: number = b[o + 3];
    if (b0 !== 0xFF || (b1 & 0xE0) !== 0xE0) {
      return null;
    }
    const versionBits: number = (b1 >> 3) & 0x03;
    const layerBits: number = (b1 >> 1) & 0x03;
    const bitrateIndex: number = (b2 >> 4) & 0x0F;
    const sampleRateIndex: number = (b2 >> 2) & 0x03;
    if (versionBits === 1 || layerBits === 0 || bitrateIndex === 0 || bitrateIndex === 15 || sampleRateIndex === 3) {
      return null;
    }
    const h: Mp3FrameHeader = new Mp3FrameHeader();
    h.versionBits = versionBits;
    h.layer = 4 - layerBits;
    h.hasCrc = (b1 & 0x01) === 0;
    h.bitrateIndex = bitrateIndex;
    h.sampleRateIndex = sampleRateIndex;
    h.padding = (b2 >> 1) & 0x01;
    h.channelMode = (b3 >> 6) & 0x03;
    h.byte1 = b1;
    h.byte3 = b3;
    const mpeg1: boolean = versionBits === 3;
    if (mpeg1) {
      h.sampleRate = SAMPLE_RATES_V1[sampleRateIndex];
    } else if (versionBits === 2) {
      h.sampleRate = SAMPLE_RATES_V2[sampleRateIndex];
    } else {
      h.sampleRate = SAMPLE_RATES_V25[sampleRateIndex];
    }
    h.bitrateKbps = Mp3Format.bitrateKbps(mpeg1, h.layer, bitrateIndex);
    h.frameSize = Mp3Format.frameSize(mpeg1, h.layer, h.bitrateKbps, h.sampleRate, h.padding);
    if (h.layer === 1) {
      h.samplesPerFrame = 384;
    } else if (h.layer === 2) {
      h.samplesPerFrame = 1152;
    } else {
      h.samplesPerFrame = mpeg1 ? 1152 : 576;
    }
    if (mpeg1) {
      h.sideInfoSize = h.channelMode === 3 ? 17 : 32;
    } else {
      h.sideInfoSize = h.channelMode === 3 ? 9 : 17;
    }
    if (h.frameSize < 24) {
      return null;
    }
    return h;
  }

  private static bitrateKbps(mpeg1: boolean, layer: number, index: number): number {
    if (mpeg1) {
      if (layer === 1) {
        return BITRATES_V1_L1[index];
      }
      return layer === 2 ? BITRATES_V1_L2[index] : BITRATES_V1_L3[index];
    }
    return layer === 1 ? BITRATES_V2_L1[index] : BITRATES_V2_L23[index];
  }

  private static frameSize(mpeg1: boolean, layer: number, kbps: number, sampleRate: number, padding: number): number {
    if (layer === 1) {
      return (Math.floor(12000 * kbps / sampleRate) + padding) * 4;
    }
    if (layer === 2 || mpeg1) {
      return Math.floor(144000 * kbps / sampleRate) + padding;
    }
    return Math.floor(72000 * kbps / sampleRate) + padding;
  }

  /** 文件头部所有 ID3v2 标签的总长度。 */
  private static id3v2Size(b: Uint8Array): number {
    let pos: number = 0;
    while (pos + 10 <= b.length && Bin.matches(b, pos, 'ID3')) {
      const flags: number = b[pos + 5];
      const s0: number = b[pos + 6];
      const s1: number = b[pos + 7];
      const s2: number = b[pos + 8];
      const s3: number = b[pos + 9];
      if (((s0 | s1 | s2 | s3) & 0x80) !== 0) {
        // 长度不是 syncsafe 整数，标签已损坏，停止跳过。
        break;
      }
      let size: number = 10 + ((s0 << 21) | (s1 << 14) | (s2 << 7) | s3);
      if ((flags & 0x10) !== 0) {
        size += 10; // footer
      }
      if (pos + size > b.length) {
        break;
      }
      pos += size;
    }
    return pos;
  }

  /**
   * 从 from 起找第一个可信的帧头：自身合法，且下一帧位置也是同一条流的合法帧头
   * （或恰好到达音频区末尾）。找不到返回 -1。
   */
  private static findSync(b: Uint8Array, from: number, end: number, ref: Mp3FrameHeader | null): number {
    for (let pos = from; pos + 4 <= end; pos++) {
      if (b[pos] !== 0xFF) {
        continue;
      }
      const h: Mp3FrameHeader | null = Mp3Format.parseHeader(b, pos);
      if (h === null || (ref !== null && !h.sameStream(ref))) {
        continue;
      }
      const next: number = pos + h.frameSize;
      if (next === end) {
        return pos;
      }
      if (next + 4 > end) {
        continue;
      }
      const h2: Mp3FrameHeader | null = Mp3Format.parseHeader(b, next);
      if (h2 !== null && h2.sameStream(h)) {
        return pos;
      }
    }
    return -1;
  }

  /** 数据开头（已跳过 ID3v2 的一段窗口）是否能同步到 Layer III 帧。 */
  static looksLikeMp3(window: Uint8Array): boolean {
    const pos: number = Mp3Format.findSync(window, 0, window.length, null);
    if (pos < 0) {
      return false;
    }
    const h: Mp3FrameHeader | null = Mp3Format.parseHeader(window, pos);
    return h !== null && h.layer === 3;
  }

  /** 第一帧是否是 Xing / Info / VBRI 信息帧（不含音频）。 */
  private static isInfoFrame(b: Uint8Array, offset: number, h: Mp3FrameHeader): boolean {
    if (h.layer !== 3) {
      return false;
    }
    const base: number = offset + 4 + h.sideInfoSize;
    if (base + 4 <= offset + h.frameSize && (Bin.matches(b, base, 'Xing') || Bin.matches(b, base, 'Info'))) {
      return true;
    }
    // 带 CRC 的帧，side info 之前多 2 字节。
    if (h.hasCrc && base + 6 <= offset + h.frameSize &&
      (Bin.matches(b, base + 2, 'Xing') || Bin.matches(b, base + 2, 'Info'))) {
      return true;
    }
    // Fraunhofer VBRI：固定在帧头后 32 字节。
    return offset + 40 <= offset + h.frameSize && Bin.matches(b, offset + 36, 'VBRI');
  }

  /**
   * 扫描整份 MP3 数据。
   * 失败（不是 MP3 / 不是 Layer III / 一帧都没有）时抛 Error。
   */
  static scan(b: Uint8Array): Mp3Scan {
    const scan: Mp3Scan = new Mp3Scan();
    scan.id3v2Size = Mp3Format.id3v2Size(b);

    let end: number = b.length;
    if (end - scan.id3v2Size >= 128 && Bin.matches(b, end - 128, 'TAG')) {
      scan.id3v1Offset = end - 128;
      end = scan.id3v1Offset;
    }

    let pos: number = Mp3Format.findSync(b, scan.id3v2Size, end, null);
    if (pos < 0) {
      throw new Error('mp3: no frame sync found');
    }
    const first: Mp3FrameHeader | null = Mp3Format.parseHeader(b, pos);
    if (first === null) {
      throw new Error('mp3: bad first frame');
    }
    if (first.layer !== 3) {
      throw new Error(`mp3: layer ${first.layer} is not supported`);
    }
    if (Mp3Format.isInfoFrame(b, pos, first)) {
      scan.droppedInfoFrame = true;
      pos += first.frameSize;
    }

    let firstAudio: Mp3FrameHeader | null = null;
    let lastBitrate: number = -1;
    while (pos + 4 <= end) {
      let h: Mp3FrameHeader | null = Mp3Format.parseHeader(b, pos);
      if (h === null || !h.sameStream(first)) {
        // 帧间有垃圾数据 / 尾部标签（APE、Lyrics3…）：向后重新同步，找不到就结束。
        const resync: number = Mp3Format.findSync(b, pos + 1, end, first);
        if (resync < 0) {
          break;
        }
        pos = resync;
        h = Mp3Format.parseHeader(b, pos);
        if (h === null) {
          break;
        }
      }
      if (pos + h.frameSize > end) {
        break; // 最后一帧被截断，丢弃
      }
      if (firstAudio === null) {
        firstAudio = h;
      }
      if (lastBitrate >= 0 && lastBitrate !== h.bitrateKbps) {
        scan.vbr = true;
      }
      lastBitrate = h.bitrateKbps;
      scan.offsets.push(pos);
      scan.sizes.push(h.frameSize);
      scan.audioBytes += h.frameSize;
      pos += h.frameSize;
    }
    if (firstAudio === null) {
      throw new Error('mp3: no audio frames');
    }
    scan.header = firstAudio;
    return scan;
  }

  /**
   * 生成 Xing（VBR）/ Info（CBR）信息帧。
   * @param frameSizes 将要写出的音频帧长度序列
   */
  static buildXingFrame(header: Mp3FrameHeader, frameSizes: Array<number>, vbr: boolean): Uint8Array {
    const count: number = frameSizes.length;
    if (count <= 0) {
      throw new Error('mp3: nothing to write');
    }
    const required: number = 4 + header.sideInfoSize + XING_BODY_SIZE;
    const mpeg1: boolean = header.isMpeg1;
    let bitrateIndex: number = -1;
    let frameSize: number = 0;
    for (let i = 1; i <= 14; i++) {
      const kbps: number = mpeg1 ? BITRATES_V1_L3[i] : BITRATES_V2_L23[i];
      const size: number = Mp3Format.frameSize(mpeg1, 3, kbps, header.sampleRate, 0);
      if (size >= required) {
        bitrateIndex = i;
        frameSize = size;
        break;
      }
    }
    if (bitrateIndex < 0) {
      throw new Error('mp3: cannot fit Xing header');
    }

    const frame: Uint8Array = new Uint8Array(frameSize);
    frame[0] = 0xFF;
    frame[1] = header.byte1 | 0x01; // 不带 CRC
    frame[2] = (bitrateIndex << 4) | (header.sampleRateIndex << 2);
    frame[3] = header.byte3;

    let audioBytes: number = 0;
    for (let i = 0; i < count; i++) {
      audioBytes += frameSizes[i];
    }
    const totalBytes: number = frameSize + audioBytes;

    let p: number = 4 + header.sideInfoSize;
    frame.set(Bin.ascii(vbr ? 'Xing' : 'Info'), p);
    p += 4;
    Bin.putU32(frame, p, XING_FLAGS);
    p += 4;
    Bin.putU32(frame, p, count);
    p += 4;
    Bin.putU32(frame, p, totalBytes > 0xFFFFFFFF ? 0xFFFFFFFF : totalBytes);
    p += 4;

    // TOC[i]：播放到 i% 时长处对应的文件位置（以 1/256 为单位）。
    let frameIndex: number = 0;
    let bytePos: number = frameSize;
    for (let i = 0; i < 100; i++) {
      const target: number = Math.floor(i * count / 100);
      while (frameIndex < target) {
        bytePos += frameSizes[frameIndex];
        frameIndex++;
      }
      const value: number = Math.floor(bytePos * 256 / totalBytes);
      frame[p + i] = value > 255 ? 255 : value;
    }
    return frame;
  }

  private static writeFrames(b: Uint8Array, scan: Mp3Scan, from: number, to: number, sink: ByteSink,
    progress: MediaProgress): void {
    const sizes: Array<number> = scan.sizes.slice(from, to);
    const vbr: boolean = Mp3Format.rangeIsVbr(b, scan, from, to);

    if (scan.id3v2Size > 0) {
      sink.write(b.subarray(0, scan.id3v2Size));
    }
    sink.write(Mp3Format.buildXingFrame(scan.header, sizes, vbr));

    // 连续的帧合并成一段写出，减少 write 次数。
    const total: number = to - from;
    let runStart: number = scan.offsets[from];
    let runEnd: number = runStart;
    let lastReport: number = 0;
    for (let i = from; i < to; i++) {
      const off: number = scan.offsets[i];
      if (off !== runEnd || runEnd - runStart >= 4 * 1024 * 1024) {
        sink.write(b.subarray(runStart, runEnd));
        runStart = off;
        if (i - lastReport >= 4096) {
          lastReport = i;
          if (!progress.report((i - from) / total)) {
            throw new MediaCancelledError();
          }
        }
      }
      runEnd = off + scan.sizes[i];
    }
    if (runEnd > runStart) {
      sink.write(b.subarray(runStart, runEnd));
    }
    if (scan.id3v1Offset >= 0) {
      sink.write(b.subarray(scan.id3v1Offset, scan.id3v1Offset + 128));
    }
    progress.report(1);
  }

  private static rangeIsVbr(b: Uint8Array, scan: Mp3Scan, from: number, to: number): boolean {
    if (!scan.vbr) {
      return false;
    }
    // 取帧头第 3 字节的高 4 位（码率索引）比较。
    const firstIndex: number = (b[scan.offsets[from] + 2] >> 4) & 0x0F;
    for (let i = from + 1; i < to; i++) {
      if (((b[scan.offsets[i] + 2] >> 4) & 0x0F) !== firstIndex) {
        return true;
      }
    }
    return false;
  }

  /** 重封装并写入 Xing 头。返回 scan 结果（时长、帧数等）。 */
  static writeWithXing(b: Uint8Array, sink: ByteSink, progress: MediaProgress): Mp3Scan {
    const scan: Mp3Scan = Mp3Format.scan(b);
    Mp3Format.writeFrames(b, scan, 0, scan.frameCount, sink, progress);
    return scan;
  }

  /**
   * 按帧裁剪 [startMs, endMs)（endMs <= 0 表示到结尾）。返回实际写出的时长（毫秒）。
   * 注意：Layer III 的 bit reservoir 使得裁剪点后的第一帧可能引用前一帧的数据，
   * 解码器会把该帧静音处理，这是所有无损 MP3 切割工具的共同行为。
   */
  static writeTrimmed(b: Uint8Array, startMs: number, endMs: number, sink: ByteSink,
    progress: MediaProgress): number {
    const scan: Mp3Scan = Mp3Format.scan(b);
    const frameMs: number = scan.header.samplesPerFrame * 1000 / scan.header.sampleRate;
    let from: number = Math.floor((startMs < 0 ? 0 : startMs) / frameMs);
    let to: number = endMs > 0 ? Math.ceil(endMs / frameMs) : scan.frameCount;
    if (to > scan.frameCount) {
      to = scan.frameCount;
    }
    if (from >= to) {
      throw new Error('mp3: empty trim range');
    }
    if (from < 0) {
      from = 0;
    }
    Mp3Format.writeFrames(b, scan, from, to, sink, progress);
    return Math.round((to - from) * frameMs);
  }

  /** 从 base 字节起的第 bitPos 位开始读 count 位（大端位序）。 */
  private static bits(b: Uint8Array, base: number, bitPos: number, count: number): number {
    let value: number = 0;
    for (let i = 0; i < count; i++) {
      const pos: number = bitPos + i;
      const byte: number = b[base + (pos >> 3)];
      value = (value << 1) | ((byte >> (7 - (pos & 7))) & 0x01);
    }
    return value;
  }

  /**
   * 一帧里各 granule / 声道 global_gain 的最大值；该帧没有任何频谱数据（静音帧）返回 -1。
   */
  private static frameGain(b: Uint8Array, offset: number, h: Mp3FrameHeader): number {
    const base: number = offset + 4 + (h.hasCrc ? 2 : 0);
    if (base + h.sideInfoSize > offset + h.frameSize) {
      return -1;
    }
    const channels: number = h.channels;
    // MPEG-1：main_data_begin(9) + private_bits(5/3) + scfsi(4×声道)，2 个 granule，每个声道 59 位；
    // MPEG-2/2.5：main_data_begin(8) + private_bits(1/2)，1 个 granule，每个声道 63 位。
    // 每个 granule-声道块的开头都是 part2_3_length(12) big_values(9) global_gain(8)。
    let bitPos: number = 8 + (channels === 1 ? 1 : 2);
    let granules: number = 1;
    let granuleBits: number = 63;
    if (h.isMpeg1) {
      bitPos = 9 + (channels === 1 ? 5 : 3) + 4 * channels;
      granules = 2;
      granuleBits = 59;
    }
    let best: number = -1;
    for (let g = 0; g < granules; g++) {
      for (let c = 0; c < channels; c++) {
        const part23: number = Mp3Format.bits(b, base, bitPos, 12);
        const bigValues: number = Mp3Format.bits(b, base, bitPos + 12, 9);
        const gain: number = Mp3Format.bits(b, base, bitPos + 21, 8);
        if (part23 > 0 && bigValues > 0 && gain > best) {
          best = gain;
        }
        bitPos += granuleBits;
      }
    }
    return best;
  }

  /**
   * 不解码的响度包络（近似值，不是 PCM 峰值）。
   *
   * Layer III 的每个 granule 都带 8 位 global_gain：反量化时频谱值乘以 2^((global_gain-210)/4)，
   * 即 global_gain 每 +4 对应约 +6dB。编码器在固定码率预算下量化后的整数分布大致稳定，
   * 所以 global_gain 的相对变化能较好地跟随响度变化。这里取每个时间片内的最大 global_gain，
   * 以全曲第 98 百分位为 0dB 参考，换算成 0..1 的相对幅度；没有频谱数据的帧记为 0。
   * 该结果只适合画波形轮廓，调用方必须把它标记为近似值。
   */
  static gainEnvelope(b: Uint8Array, samplesPerSecond: number, progress: MediaProgress): Mp3Envelope {
    const scan: Mp3Scan = Mp3Format.scan(b);
    const env: Mp3Envelope = new Mp3Envelope();
    env.durationMs = scan.durationMs;
    const count: number = scan.frameCount;
    const frameSec: number = scan.header.samplesPerFrame / scan.header.sampleRate;
    const bucketCount: number = Math.max(1, Math.ceil(env.durationMs * samplesPerSecond / 1000));

    const bucketGain: Array<number> = new Array<number>(bucketCount);
    for (let i = 0; i < bucketCount; i++) {
      bucketGain[i] = -2; // -2：该时间片没有帧；-1：静音
    }
    const histogram: Array<number> = new Array<number>(256);
    for (let i = 0; i < 256; i++) {
      histogram[i] = 0;
    }
    let audible: number = 0;
    for (let i = 0; i < count; i++) {
      const offset: number = scan.offsets[i];
      const h: Mp3FrameHeader | null = Mp3Format.parseHeader(b, offset);
      if (h === null) {
        continue;
      }
      const gain: number = Mp3Format.frameGain(b, offset, h);
      if (gain >= 0) {
        histogram[gain] += 1;
        audible++;
      }
      // 一帧可能跨多个时间片（时间片比帧短时）。
      let first: number = Math.floor(i * frameSec * samplesPerSecond);
      let last: number = Math.floor(((i + 1) * frameSec * samplesPerSecond) - 0.000001);
      if (first >= bucketCount) {
        first = bucketCount - 1;
      }
      if (last >= bucketCount) {
        last = bucketCount - 1;
      }
      for (let k = first; k <= last; k++) {
        if (gain > bucketGain[k]) {
          bucketGain[k] = gain;
        }
      }
      if ((i & 0x3FFF) === 0 && !progress.report(i / count)) {
        throw new MediaCancelledError();
      }
    }

    // 第 98 百分位作为参考电平，避免个别异常帧把整条曲线压扁。
    let reference: number = 255;
    if (audible > 0) {
      const threshold: number = audible * 0.02;
      let seen: number = 0;
      for (let g = 255; g >= 0; g--) {
        seen += histogram[g];
        if (seen >= threshold && histogram[g] > 0) {
          reference = g;
          break;
        }
      }
    }

    let previous: number = 0;
    for (let i = 0; i < bucketCount; i++) {
      const gain: number = bucketGain[i];
      let value: number = 0;
      if (gain === -2) {
        value = previous;
      } else if (gain < 0 || audible === 0) {
        value = 0;
      } else {
        value = Math.pow(2, (gain - reference) / 4);
        if (value > 1) {
          value = 1;
        }
      }
      previous = value;
      env.peaks.push(Math.round(value * 1000) / 1000);
    }
    progress.report(1);
    return env;
  }
}
