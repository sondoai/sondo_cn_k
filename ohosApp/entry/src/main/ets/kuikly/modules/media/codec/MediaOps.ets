/*
 * 媒体处理的纯算法入口（不依赖系统 Kit，可在 TaskPool 线程里跑）：
 *
 *  - sniff()               按文件头判断容器：mp4 / mp3 / wav / adts / flac / ogg / unknown
 *  - remuxMp3WithXing()    MP3 重封装 + Xing 头
 *  - muxVideoWithAudio()   视频轨流拷贝 + 一条音频轨（AAC-in-MP4 流拷贝，或 MP3 帧直通）→ MP4，按 -shortest 截断
 *  - trimAudio()           MP3 / WAV / ADTS-AAC / MP4(M4A) 的无损裁剪
 *  - waveform()            WAV 精确峰值；MP3 为 global_gain 响度包络（近似值，approximate=true）
 *
 * 需要「解码 / 编码」才能完成的情况（音频不是 AAC-in-MP4 也不是 MP3、FLAC 裁剪、压缩格式的真实峰值）
 * 这里一律抛错，由 SDMediaProcessModule 决定走 AVTranscoder 还是回报 unsupported。
 */
import { Bin, ByteBuilder, ByteSink, ByteSource, MediaProgress, MemorySource } from './ByteIO';
import { Mp3Envelope, Mp3Format, Mp3FrameHeader, Mp3Scan } from './Mp3Format';
import { WavFormat, WavInfo, WavPeaks } from './WavFormat';
import { AdtsFormat } from './AdtsFormat';
import { Mp4Movie, Mp4Parser, Mp4Track } from './Mp4Format';
import { Mp4OutTrack, Mp4Writer } from './Mp4Writer';

/** 混流时音频既不是 AAC-in-MP4 也不能直通，需要先转码。kind 为 sniff() 的结果。 */
export class MediaNeedTranscodeError extends Error {
  kind: string;

  constructor(kind: string) {
    super(`need_transcode:${kind}`);
    this.kind = kind;
  }
}

export class MuxOptions {
  audioStartMs: number = 0;
  /** <= 0 表示用到音频结尾。 */
  audioDurationMs: number = 0;
  /** 音频是 MP3 时是否允许不转码、直接把 MP3 帧封进 MP4（esds objectType 0x6B）。 */
  allowMp3Passthrough: boolean = false;
}

export class MuxResult {
  durationMs: number = 0;
  /** 'aac' | 'mp3' */
  audioCodec: string = '';
  videoSamples: number = 0;
  audioSamples: number = 0;
  bytes: number = 0;
}

export class TrimResult {
  durationMs: number = 0;
  /** 输出容器：'mp3' | 'wav' | 'adts' | 'mp4' */
  format: string = '';
}

export class WaveformResult {
  durationMs: number = 0;
  peaks: Array<number> = [];
  /** true：不是 PCM 峰值，而是从码流参数估算的响度包络。 */
  approximate: boolean = false;
}

export class MediaOps {
  static readonly KIND_MP4: string = 'mp4';
  static readonly KIND_MP3: string = 'mp3';
  static readonly KIND_WAV: string = 'wav';
  static readonly KIND_ADTS: string = 'adts';
  static readonly KIND_FLAC: string = 'flac';
  static readonly KIND_OGG: string = 'ogg';
  static readonly KIND_UNKNOWN: string = 'unknown';

  /** 按内容（不看扩展名）判断容器类型。 */
  static sniff(src: ByteSource): string {
    const total: number = src.size();
    if (total < 12) {
      return MediaOps.KIND_UNKNOWN;
    }
    const head: Uint8Array = src.read(0, Math.min(16, total));
    if (WavFormat.isWav(head)) {
      return MediaOps.KIND_WAV;
    }
    if (Bin.matches(head, 0, 'fLaC')) {
      return MediaOps.KIND_FLAC;
    }
    if (Bin.matches(head, 0, 'OggS')) {
      return MediaOps.KIND_OGG;
    }
    if (Mp4Parser.looksLikeMp4(head)) {
      return MediaOps.KIND_MP4;
    }
    // MP3 / ADTS / FLAC 前面都可能有 ID3v2 标签。
    let start: number = 0;
    while (start + 10 <= total) {
      const tag: Uint8Array = src.read(start, 10);
      if (!Bin.matches(tag, 0, 'ID3')) {
        break;
      }
      let size: number = 10 + (((tag[6] & 0x7F) << 21) | ((tag[7] & 0x7F) << 14) | ((tag[8] & 0x7F) << 7) |
        (tag[9] & 0x7F));
      if ((tag[5] & 0x10) !== 0) {
        size += 10;
      }
      if (start + size > total) {
        break;
      }
      start += size;
    }
    const window: Uint8Array = src.read(start, Math.min(128 * 1024, total - start));
    if (Bin.matches(window, 0, 'fLaC')) {
      return MediaOps.KIND_FLAC;
    }
    if (AdtsFormat.isAdts(window)) {
      return MediaOps.KIND_ADTS;
    }
    if (Mp3Format.looksLikeMp3(window)) {
      return MediaOps.KIND_MP3;
    }
    return MediaOps.KIND_UNKNOWN;
  }

  private static readAll(src: ByteSource): Uint8Array {
    return src.read(0, src.size());
  }

  static remuxMp3WithXing(src: ByteSource, sink: ByteSink, progress: MediaProgress): Mp3Scan {
    return Mp3Format.writeWithXing(MediaOps.readAll(src), sink, progress);
  }

  // ---------------------------------------------------------------------------------------------
  // 混流
  // ---------------------------------------------------------------------------------------------

  /**
   * 视频轨原样拷贝 + 音频轨，输出时长 = min(视频时长, 音频可用时长)（对应 ffmpeg 的 -shortest）。
   * 音频从 audioStartMs 所在的采样开始，最多 audioDurationMs。
   */
  static muxVideoWithAudio(video: ByteSource, audio: ByteSource, options: MuxOptions, sink: ByteSink,
    progress: MediaProgress): MuxResult {
    const movie: Mp4Movie = Mp4Parser.parse(video);
    const v: Mp4Track | null = movie.firstTrack('vide');
    if (v === null) {
      throw new Error('mux: source has no video track');
    }
    const videoSeconds: number = v.mediaDuration / v.timescale;

    const result: MuxResult = new MuxResult();
    const kind: string = MediaOps.sniff(audio);
    let audioAvailable: number = 0; // 从起点到（受 audioDurationMs 限制的）终点的秒数
    let aacTrack: Mp4Track | null = null;
    let mp3Data: Uint8Array | null = null;
    let mp3Scan: Mp3Scan | null = null;
    let firstAudioSample: number = 0;

    if (kind === MediaOps.KIND_MP4) {
      const a: Mp4Track | null = Mp4Parser.parse(audio).firstTrack('soun');
      if (a === null) {
        throw new Error('mux: audio file has no audio track');
      }
      if (!a.isAac) {
        throw new MediaNeedTranscodeError(kind);
      }
      aacTrack = a;
      firstAudioSample = MediaOps.sampleAt(a.durations, options.audioStartMs * a.timescale / 1000);
      audioAvailable = MediaOps.sumFrom(a.durations, firstAudioSample) / a.timescale;
      result.audioCodec = 'aac';
    } else if (kind === MediaOps.KIND_MP3 && options.allowMp3Passthrough) {
      mp3Data = MediaOps.readAll(audio);
      mp3Scan = Mp3Format.scan(mp3Data);
      const frameSeconds: number = mp3Scan.header.samplesPerFrame / mp3Scan.header.sampleRate;
      firstAudioSample = Math.floor(Math.max(0, options.audioStartMs) / 1000 / frameSeconds);
      if (firstAudioSample > mp3Scan.frameCount) {
        firstAudioSample = mp3Scan.frameCount;
      }
      audioAvailable = (mp3Scan.frameCount - firstAudioSample) * frameSeconds;
      result.audioCodec = 'mp3';
    } else {
      throw new MediaNeedTranscodeError(kind);
    }
    if (options.audioDurationMs > 0 && options.audioDurationMs / 1000 < audioAvailable) {
      audioAvailable = options.audioDurationMs / 1000;
    }
    const outSeconds: number = Math.min(videoSeconds, audioAvailable);
    if (!(outSeconds > 0)) {
      throw new Error('mux: nothing to mux (audio start is beyond the end, or empty video)');
    }

    // 视频：解码顺序上起始时间 < outSeconds 的采样全部保留。
    const videoCount: number = MediaOps.countWithin(v.durations, 0, outSeconds * v.timescale);
    const videoOut: Mp4OutTrack = MediaOps.outTrackFrom(v, video, 0, videoCount);
    videoOut.alternateGroup = 0;
    videoOut.volume = 0;

    const audioOut: Mp4OutTrack = aacTrack !== null ?
      MediaOps.outTrackFrom(aacTrack, audio, firstAudioSample,
        MediaOps.countWithin(aacTrack.durations, firstAudioSample, outSeconds * aacTrack.timescale)) :
      MediaOps.outTrackFromMp3(mp3Data as Uint8Array, mp3Scan as Mp3Scan, firstAudioSample, outSeconds);
    audioOut.alternateGroup = 1;
    audioOut.volume = 0x0100;
    audioOut.tkhdFlags = 3;
    audioOut.widthFixed = 0;
    audioOut.heightFixed = 0;

    result.bytes = Mp4Writer.write([videoOut, audioOut], 'isom', sink, progress);
    result.videoSamples = videoOut.sampleCount;
    result.audioSamples = audioOut.sampleCount;
    const videoMs: number = videoOut.presentationDuration * 1000 / videoOut.timescale;
    const audioMs: number = audioOut.presentationDuration * 1000 / audioOut.timescale;
    result.durationMs = Math.round(Math.max(videoMs, audioMs));
    return result;
  }

  /** 起始时间 <= ticks 的最后一个采样下标（ticks 落在哪个采样里）。 */
  private static sampleAt(durations: Array<number>, ticks: number): number {
    if (ticks <= 0) {
      return 0;
    }
    let time: number = 0;
    for (let i = 0; i < durations.length; i++) {
      if (time + durations[i] > ticks) {
        return i;
      }
      time += durations[i];
    }
    return durations.length;
  }

  private static sumFrom(durations: Array<number>, from: number): number {
    let total: number = 0;
    for (let i = from; i < durations.length; i++) {
      total += durations[i];
    }
    return total;
  }

  /** 从 from 起，相对起始时间 < limitTicks 的采样个数（至少 1 个，只要还有采样）。 */
  private static countWithin(durations: Array<number>, from: number, limitTicks: number): number {
    let time: number = 0;
    let count: number = 0;
    // 容忍 1e-6 的浮点误差，避免「刚好等长」时多带一个采样。
    const limit: number = limitTicks - 0.000001;
    for (let i = from; i < durations.length; i++) {
      if (count > 0 && time >= limit) {
        break;
      }
      time += durations[i];
      count++;
    }
    return count;
  }

  /** 从已解析的 MP4 轨道截取 [from, from + count) 个采样，生成输出轨道。 */
  private static outTrackFrom(t: Mp4Track, source: ByteSource, from: number, count: number): Mp4OutTrack {
    if (count <= 0) {
      throw new Error('mp4: empty sample range');
    }
    const to: number = from + count;
    const out: Mp4OutTrack = new Mp4OutTrack();
    out.handler = t.handler;
    out.timescale = t.timescale;
    out.language = t.language;
    out.tkhdFlags = t.tkhdFlags | 0x01;
    out.layer = t.layer;
    out.alternateGroup = t.alternateGroup;
    out.volume = t.volume;
    out.matrix = t.matrix;
    out.widthFixed = t.widthFixed;
    out.heightFixed = t.heightFixed;
    out.hdlrBox = t.hdlrBox;
    out.mediaHeaderBox = t.mediaHeaderBox;
    out.stsdBox = t.stsdBox;
    out.source = source;
    out.sampleOffsets = t.offsets.slice(from, to);
    out.sampleSizes = t.sizes.slice(from, to);
    out.sampleDurations = t.durations.slice(from, to);
    const cts: Array<number> | null = t.ctsOffsets;
    if (cts !== null) {
      out.ctsOffsets = cts.slice(from, to);
      out.cttsVersion = t.cttsVersion;
    }
    const sync: Array<number> | null = t.syncSamples;
    if (sync !== null) {
      const kept: Array<number> = [];
      for (let i = 0; i < sync.length; i++) {
        if (sync[i] >= from && sync[i] < to) {
          kept.push(sync[i] - from);
        }
      }
      out.syncSamples = kept;
    }
    // 编辑列表：只有从第 0 个采样开始时才有意义（B 帧的 CTS 起点 / AAC 的 priming）。
    const mediaTime: number = t.firstEditMediaTime;
    if (from === 0 && mediaTime >= 0) {
      const kept: number = out.mediaDuration;
      out.editMediaTime = mediaTime;
      // 有 ctts 的轨道，media_time 抵消的是 CTS 偏移，呈现时长仍是全部采样时长；
      // 否则（音频 priming）呈现时长要减去被跳过的部分。
      let duration: number = cts !== null ? kept : kept - mediaTime;
      if (duration < 1) {
        duration = kept;
        out.editMediaTime = 0;
      }
      out.editDuration = duration;
    }
    return out;
  }

  /** 把 MP3 帧直接作为 MP4 采样（每帧一个采样，timescale = 采样率）。 */
  private static outTrackFromMp3(data: Uint8Array, scan: Mp3Scan, from: number, seconds: number): Mp4OutTrack {
    const h: Mp3FrameHeader = scan.header;
    let count: number = Math.ceil(seconds * h.sampleRate / h.samplesPerFrame - 0.000001);
    if (count < 1) {
      count = 1;
    }
    if (from + count > scan.frameCount) {
      count = scan.frameCount - from;
    }
    if (count <= 0) {
      throw new Error('mp3: empty frame range');
    }
    const out: Mp4OutTrack = new Mp4OutTrack();
    out.handler = 'soun';
    out.timescale = h.sampleRate;
    out.stsdBox = MediaOps.mp3SampleDescription(h);
    out.source = new MemorySource(data);
    out.sampleOffsets = scan.offsets.slice(from, from + count);
    out.sampleSizes = scan.sizes.slice(from, from + count);
    const durations: Array<number> = new Array<number>(count);
    for (let i = 0; i < count; i++) {
      durations[i] = h.samplesPerFrame;
    }
    out.sampleDurations = durations;
    return out;
  }

  /** MP3-in-MP4 的 stsd：mp4a sample entry + esds（objectTypeIndication 0x6B / 0x69，无 DecoderSpecificInfo）。 */
  private static mp3SampleDescription(h: Mp3FrameHeader): Uint8Array {
    const esds: ByteBuilder = new ByteBuilder(48);
    esds.u32(0);
    // ES_Descriptor
    esds.u8(0x03).u8(3 + 2 + 13 + 2 + 1);
    esds.u16(0).u8(0);
    // DecoderConfigDescriptor
    esds.u8(0x04).u8(13);
    esds.u8(h.isMpeg1 ? 0x6B : 0x69);
    esds.u8(0x15); // streamType = audio(5) << 2 | reserved 1
    esds.u8(0).u16(0); // bufferSizeDB
    esds.u32(h.bitrateKbps * 1000).u32(h.bitrateKbps * 1000);
    // SLConfigDescriptor
    esds.u8(0x06).u8(1).u8(0x02);

    const entry: ByteBuilder = new ByteBuilder(96);
    entry.zeros(6).u16(1); // reserved + data_reference_index
    entry.u16(0).u16(0).u32(0); // version / revision / vendor
    entry.u16(h.channels).u16(16).u16(0).u16(0);
    entry.u32(h.sampleRate * 65536);
    entry.box('esds', esds.toBytes());

    const stsd: ByteBuilder = new ByteBuilder(128);
    stsd.u32(0).u32(1);
    stsd.box('mp4a', entry.toBytes());
    return new ByteBuilder(160).box('stsd', stsd.toBytes()).toBytes();
  }

  // ---------------------------------------------------------------------------------------------
  // 裁剪
  // ---------------------------------------------------------------------------------------------

  /** 无损裁剪 [startMs, endMs)（endMs <= 0 表示到结尾）。输出容器与输入相同。 */
  static trimAudio(src: ByteSource, startMs: number, endMs: number, sink: ByteSink,
    progress: MediaProgress): TrimResult {
    const result: TrimResult = new TrimResult();
    const kind: string = MediaOps.sniff(src);
    if (endMs > 0 && endMs <= startMs) {
      throw new Error('trim: endMs must be greater than startMs');
    }
    if (kind === MediaOps.KIND_MP3) {
      result.durationMs = Mp3Format.writeTrimmed(MediaOps.readAll(src), startMs, endMs, sink, progress);
      result.format = 'mp3';
    } else if (kind === MediaOps.KIND_WAV) {
      const info: WavInfo = WavFormat.parse(src);
      result.durationMs = WavFormat.writeTrimmed(src, info, startMs, endMs, sink, progress);
      result.format = 'wav';
    } else if (kind === MediaOps.KIND_ADTS) {
      result.durationMs = AdtsFormat.writeTrimmed(MediaOps.readAll(src), startMs, endMs, sink, progress);
      result.format = 'adts';
    } else if (kind === MediaOps.KIND_MP4) {
      const a: Mp4Track | null = Mp4Parser.parse(src).firstTrack('soun');
      if (a === null) {
        throw new Error('trim: no audio track');
      }
      const from: number = MediaOps.sampleAt(a.durations, startMs * a.timescale / 1000);
      if (from >= a.sampleCount) {
        throw new Error('trim: startMs is beyond the end');
      }
      const limit: number = endMs > 0 ? (endMs - Math.max(0, startMs)) * a.timescale / 1000 :
        MediaOps.sumFrom(a.durations, from) + 1;
      const count: number = MediaOps.countWithin(a.durations, from, limit);
      const out: Mp4OutTrack = MediaOps.outTrackFrom(a, src, from, count);
      out.volume = 0x0100;
      Mp4Writer.write([out], 'M4A ', sink, progress);
      result.durationMs = Math.round(out.presentationDuration * 1000 / out.timescale);
      result.format = 'mp4';
    } else {
      throw new Error(`unsupported: cannot trim '${kind}' without a decoder`);
    }
    return result;
  }

  // ---------------------------------------------------------------------------------------------
  // 波形
  // ---------------------------------------------------------------------------------------------

  static waveform(src: ByteSource, samplesPerSecond: number, progress: MediaProgress): WaveformResult {
    let rate: number = Math.round(samplesPerSecond);
    if (!(rate >= 1)) {
      rate = 20;
    }
    if (rate > 200) {
      rate = 200;
    }
    const result: WaveformResult = new WaveformResult();
    const kind: string = MediaOps.sniff(src);
    if (kind === MediaOps.KIND_WAV) {
      const info: WavInfo = WavFormat.parse(src);
      const peaks: WavPeaks = WavFormat.peaks(src, info, rate, progress);
      result.durationMs = peaks.durationMs;
      result.peaks = peaks.peaks;
      result.approximate = false;
    } else if (kind === MediaOps.KIND_MP3) {
      const env: Mp3Envelope = Mp3Format.gainEnvelope(MediaOps.readAll(src), rate, progress);
      result.durationMs = env.durationMs;
      result.peaks = env.peaks;
      result.approximate = true;
    } else {
      throw new Error(`unsupported: no PCM decoder for '${kind}' in ArkTS`);
    }
    return result;
  }
}
