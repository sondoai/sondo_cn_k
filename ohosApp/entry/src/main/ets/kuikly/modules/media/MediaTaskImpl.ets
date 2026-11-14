/*
 * 媒体处理任务的实现体。只在 TaskPool 线程里执行（由 MediaTasks.ets 的 @Concurrent 函数调用）：
 * 负责文件 IO、进度上报（taskpool.Task.sendData）、响应取消（taskpool.Task.isCanceled），
 * 具体算法在 codec/MediaOps.ets。
 *
 * 所有方法都返回 JSON 字符串 {ok, ...} / {ok:false, error, ...}，不抛异常：
 * TaskPool 的返回值需要跨线程序列化，字符串最省事也最稳。
 * 输出先写到 <dest>.part，成功后改名，失败 / 取消时删除，保证 dest 不会出现半截文件。
 */
import { fileIo as fs } from '@kit.CoreFileKit';
import { taskpool } from '@kit.ArkTS';
import { MediaCancelledError, MediaProgress } from './codec/ByteIO';
import { MediaNeedTranscodeError, MediaOps, MuxOptions, MuxResult, TrimResult, WaveformResult } from './codec/MediaOps';
import { Mp3Scan } from './codec/Mp3Format';
import { WavFormat, WavInfo } from './codec/WavFormat';
import { Mp4Movie, Mp4Parser, Mp4Track } from './codec/Mp4Format';
import { FileSink, FileSource } from './MediaFileIO';

/** 整文件读入内存的上限（MP3 / ADTS 的帧级处理需要整份数据）。 */
const MAX_IN_MEMORY_BYTES: number = 400 * 1024 * 1024;

class TaskProgress implements MediaProgress {
  private last: number = -1;

  report(fraction: number): boolean {
    if (taskpool.Task.isCanceled()) {
      return false;
    }
    let value: number = fraction;
    if (!(value >= 0)) {
      value = 0;
    }
    if (value > 1) {
      value = 1;
    }
    if (value - this.last >= 0.01 || (value >= 1 && this.last < 1)) {
      this.last = value;
      try {
        taskpool.Task.sendData(value);
      } catch (e) {
        // 宿主线程没有注册 onReceiveData：进度没人要，忽略。
      }
    }
    return true;
  }
}

export class MediaTaskImpl {
  private static ok(extra: Record<string, Object>): string {
    extra['ok'] = true;
    return JSON.stringify(extra);
  }

  private static fail(e: Object | null | undefined, extra?: Record<string, Object>): string {
    const out: Record<string, Object> = extra === undefined ? {} : extra;
    out['ok'] = false;
    if (e instanceof MediaCancelledError) {
      out['error'] = 'cancelled';
      out['cancelled'] = true;
    } else if (e instanceof Error) {
      out['error'] = (e as Error).message;
    } else {
      out['error'] = e === null || e === undefined ? 'unknown error' : `${e}`;
    }
    return JSON.stringify(out);
  }

  private static partPath(dest: string): string {
    return `${dest}.part`;
  }

  private static ensureParent(path: string): void {
    const index: number = path.lastIndexOf('/');
    if (index <= 0) {
      return;
    }
    const dir: string = path.substring(0, index);
    if (!fs.accessSync(dir)) {
      fs.mkdirSync(dir, true);
    }
  }

  /** 把 .part 改名为正式文件（目标已存在则先删除）。 */
  private static commit(sink: FileSink, dest: string): void {
    sink.close();
    if (fs.accessSync(dest)) {
      fs.unlinkSync(dest);
    }
    fs.renameSync(sink.path, dest);
  }

  private static checkInMemory(source: FileSource): void {
    if (source.size() > MAX_IN_MEMORY_BYTES) {
      throw new Error('file too large for in-memory processing');
    }
  }

  /** MP3 → 带 Xing 头的 MP3。→ {ok, path, durationMs, frames, vbr} */
  static remuxMp3(src: string, dest: string): string {
    let source: FileSource | null = null;
    let sink: FileSink | null = null;
    try {
      source = new FileSource(src);
      MediaTaskImpl.checkInMemory(source);
      MediaTaskImpl.ensureParent(dest);
      sink = new FileSink(MediaTaskImpl.partPath(dest));
      const scan: Mp3Scan = MediaOps.remuxMp3WithXing(source, sink, new TaskProgress());
      MediaTaskImpl.commit(sink, dest);
      sink = null;
      const out: Record<string, Object> = {};
      out['path'] = dest;
      out['durationMs'] = scan.durationMs;
      out['frames'] = scan.frameCount;
      out['vbr'] = scan.vbr;
      return MediaTaskImpl.ok(out);
    } catch (e) {
      return MediaTaskImpl.fail(e as Object);
    } finally {
      if (source !== null) {
        source.close();
      }
      if (sink !== null) {
        sink.discard();
      }
    }
  }

  /** 无损裁剪。→ {ok, path, durationMs, format} */
  static trimAudio(src: string, dest: string, startMs: number, endMs: number): string {
    let source: FileSource | null = null;
    let sink: FileSink | null = null;
    try {
      source = new FileSource(src);
      const kind: string = MediaOps.sniff(source);
      if (kind === MediaOps.KIND_MP3 || kind === MediaOps.KIND_ADTS) {
        MediaTaskImpl.checkInMemory(source);
      }
      MediaTaskImpl.ensureParent(dest);
      sink = new FileSink(MediaTaskImpl.partPath(dest));
      const result: TrimResult = MediaOps.trimAudio(source, startMs, endMs, sink, new TaskProgress());
      MediaTaskImpl.commit(sink, dest);
      sink = null;
      const out: Record<string, Object> = {};
      out['path'] = dest;
      out['durationMs'] = result.durationMs;
      out['format'] = result.format;
      return MediaTaskImpl.ok(out);
    } catch (e) {
      return MediaTaskImpl.fail(e as Object);
    } finally {
      if (source !== null) {
        source.close();
      }
      if (sink !== null) {
        sink.discard();
      }
    }
  }

  /**
   * 视频流拷贝 + 音频轨混流。
   * → {ok, path, durationMs, audioCodec} | {ok:false, needTranscode:true, kind} | {ok:false, error}
   */
  static mux(videoPath: string, audioPath: string, audioStartMs: number, audioDurationMs: number, dest: string,
    allowMp3Passthrough: boolean): string {
    let video: FileSource | null = null;
    let audio: FileSource | null = null;
    let sink: FileSink | null = null;
    try {
      video = new FileSource(videoPath);
      audio = new FileSource(audioPath);
      const options: MuxOptions = new MuxOptions();
      options.audioStartMs = audioStartMs;
      options.audioDurationMs = audioDurationMs;
      options.allowMp3Passthrough = allowMp3Passthrough;
      MediaTaskImpl.ensureParent(dest);
      sink = new FileSink(MediaTaskImpl.partPath(dest));
      const result: MuxResult = MediaOps.muxVideoWithAudio(video, audio, options, sink, new TaskProgress());
      MediaTaskImpl.commit(sink, dest);
      sink = null;
      const out: Record<string, Object> = {};
      out['path'] = dest;
      out['durationMs'] = result.durationMs;
      out['audioCodec'] = result.audioCodec;
      out['size'] = result.bytes;
      return MediaTaskImpl.ok(out);
    } catch (e) {
      if (e instanceof MediaNeedTranscodeError) {
        const extra: Record<string, Object> = {};
        extra['needTranscode'] = true;
        extra['kind'] = (e as MediaNeedTranscodeError).kind;
        return MediaTaskImpl.fail(e as Object, extra);
      }
      return MediaTaskImpl.fail(e as Object);
    } finally {
      if (video !== null) {
        video.close();
      }
      if (audio !== null) {
        audio.close();
      }
      if (sink !== null) {
        sink.discard();
      }
    }
  }

  /** 波形峰值。→ {ok, durationMs, peaks:[…], approximate} */
  static waveform(audioPath: string, samplesPerSecond: number): string {
    let source: FileSource | null = null;
    try {
      source = new FileSource(audioPath);
      if (MediaOps.sniff(source) === MediaOps.KIND_MP3) {
        MediaTaskImpl.checkInMemory(source);
      }
      const result: WaveformResult = MediaOps.waveform(source, samplesPerSecond, new TaskProgress());
      const out: Record<string, Object> = {};
      out['durationMs'] = result.durationMs;
      out['peaks'] = result.peaks;
      out['approximate'] = result.approximate;
      return MediaTaskImpl.ok(out);
    } catch (e) {
      return MediaTaskImpl.fail(e as Object);
    } finally {
      if (source !== null) {
        source.close();
      }
    }
  }

  /**
   * 不依赖系统解封装器的探测（AVMetadataExtractor 失败时的兜底，也用于拿 MP4 的帧率）。
   * → {ok, kind, durationMs, hasAudio, hasVideo, width?, height?, fps?}
   */
  static inspect(path: string): string {
    let source: FileSource | null = null;
    try {
      source = new FileSource(path);
      const kind: string = MediaOps.sniff(source);
      const out: Record<string, Object> = {};
      out['kind'] = kind;
      out['size'] = source.size();
      if (kind === MediaOps.KIND_MP4) {
        const movie: Mp4Movie = Mp4Parser.parse(source);
        const v: Mp4Track | null = movie.firstTrack('vide');
        const a: Mp4Track | null = movie.firstTrack('soun');
        let durationMs: number = 0;
        if (v !== null) {
          durationMs = v.durationMs;
          out['width'] = v.width;
          out['height'] = v.height;
          if (v.durationMs > 0) {
            out['fps'] = Math.round(v.sampleCount * 100000 / v.durationMs) / 100;
          }
        }
        if (a !== null && a.durationMs > durationMs) {
          durationMs = a.durationMs;
        }
        out['durationMs'] = durationMs;
        out['hasVideo'] = v !== null;
        out['hasAudio'] = a !== null;
      } else if (kind === MediaOps.KIND_WAV) {
        const info: WavInfo = WavFormat.parse(source);
        out['durationMs'] = info.durationMs;
        out['hasVideo'] = false;
        out['hasAudio'] = true;
      } else {
        throw new Error(`unsupported: cannot inspect '${kind}'`);
      }
      return MediaTaskImpl.ok(out);
    } catch (e) {
      return MediaTaskImpl.fail(e as Object);
    } finally {
      if (source !== null) {
        source.close();
      }
    }
  }
}
