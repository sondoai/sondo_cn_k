/*
 * WAV（RIFF PCM）解析：精确波形峰值、无损裁剪。纯 ArkTS。
 * 支持 8/16/24/32 位整型 PCM 与 32/64 位浮点 PCM（含 WAVE_FORMAT_EXTENSIBLE）。
 */
import { Bin, ByteBuilder, ByteSink, ByteSource, MediaCancelledError, MediaProgress } from './ByteIO';

const BLOCK_BYTES: number = 512 * 1024;

export class WavInfo {
  /** 1 = 整型 PCM，3 = IEEE 浮点。 */
  format: number = 1;
  channels: number = 0;
  sampleRate: number = 0;
  blockAlign: number = 0;
  bitsPerSample: number = 0;
  /** 原始 fmt chunk 内容（裁剪时原样写回）。 */
  fmtChunk: Uint8Array = new Uint8Array(0);
  dataOffset: number = 0;
  dataSize: number = 0;

  get frameCount(): number {
    return this.blockAlign > 0 ? Math.floor(this.dataSize / this.blockAlign) : 0;
  }

  get durationMs(): number {
    return this.sampleRate > 0 ? Math.round(this.frameCount * 1000 / this.sampleRate) : 0;
  }
}

export class WavPeaks {
  durationMs: number = 0;
  peaks: Array<number> = [];
}

export class WavFormat {
  static isWav(head: Uint8Array): boolean {
    return head.length >= 12 && Bin.matches(head, 0, 'RIFF') && Bin.matches(head, 8, 'WAVE');
  }

  static parse(src: ByteSource): WavInfo {
    const total: number = src.size();
    if (total < 12 || !WavFormat.isWav(src.read(0, 12))) {
      throw new Error('wav: not a RIFF/WAVE file');
    }
    const info: WavInfo = new WavInfo();
    let haveFmt: boolean = false;
    let haveData: boolean = false;
    let pos: number = 12;
    while (pos + 8 <= total) {
      const head: Uint8Array = src.read(pos, 8);
      const id: string = Bin.fourcc(head, 0);
      let size: number = Bin.u32le(head, 4);
      const body: number = pos + 8;
      if (id === 'fmt ') {
        if (size < 16 || body + size > total) {
          throw new Error('wav: bad fmt chunk');
        }
        const fmt: Uint8Array = src.read(body, size);
        info.fmtChunk = fmt.slice(0, size);
        info.format = Bin.u16le(fmt, 0);
        info.channels = Bin.u16le(fmt, 2);
        info.sampleRate = Bin.u32le(fmt, 4);
        info.blockAlign = Bin.u16le(fmt, 12);
        info.bitsPerSample = Bin.u16le(fmt, 14);
        if (info.format === 0xFFFE && size >= 26) {
          info.format = Bin.u16le(fmt, 24); // SubFormat GUID 的前两个字节
        }
        haveFmt = true;
      } else if (id === 'data') {
        if (body + size > total) {
          size = total - body; // 录音被截断 / 流式写入未回填长度
        }
        info.dataOffset = body;
        info.dataSize = size;
        haveData = true;
        break;
      }
      pos = body + size + (size & 1);
    }
    if (!haveFmt || !haveData) {
      throw new Error('wav: missing fmt or data chunk');
    }
    if (info.format !== 1 && info.format !== 3) {
      throw new Error(`wav: unsupported format tag ${info.format}`);
    }
    const bytesPerSample: number = info.bitsPerSample / 8;
    const validInt: boolean = info.format === 1 && (bytesPerSample === 1 || bytesPerSample === 2 ||
      bytesPerSample === 3 || bytesPerSample === 4);
    const validFloat: boolean = info.format === 3 && (bytesPerSample === 4 || bytesPerSample === 8);
    if (info.channels <= 0 || info.sampleRate <= 0 || (!validInt && !validFloat) ||
      info.blockAlign !== bytesPerSample * info.channels) {
      throw new Error('wav: unsupported sample layout');
    }
    return info;
  }

  /** 每个时间片（1/samplesPerSecond 秒）内所有声道的最大绝对幅度，归一化到 0..1。 */
  static peaks(src: ByteSource, info: WavInfo, samplesPerSecond: number, progress: MediaProgress): WavPeaks {
    const out: WavPeaks = new WavPeaks();
    out.durationMs = info.durationMs;
    const frames: number = info.frameCount;
    const bucketCount: number = Math.max(1, Math.ceil(frames * samplesPerSecond / info.sampleRate));
    const peaks: Array<number> = new Array<number>(bucketCount);
    for (let i = 0; i < bucketCount; i++) {
      peaks[i] = 0;
    }
    const bytesPerSample: number = info.bitsPerSample / 8;
    const framesPerBlock: number = Math.max(1, Math.floor(BLOCK_BYTES / info.blockAlign));
    let frame: number = 0;
    while (frame < frames) {
      const count: number = Math.min(framesPerBlock, frames - frame);
      const block: Uint8Array = src.read(info.dataOffset + frame * info.blockAlign, count * info.blockAlign);
      const view: DataView = new DataView(block.buffer, block.byteOffset, block.byteLength);
      for (let i = 0; i < count; i++) {
        let peak: number = 0;
        const base: number = i * info.blockAlign;
        for (let c = 0; c < info.channels; c++) {
          const o: number = base + c * bytesPerSample;
          let v: number = 0;
          if (info.format === 3) {
            v = bytesPerSample === 4 ? view.getFloat32(o, true) : view.getFloat64(o, true);
          } else if (bytesPerSample === 2) {
            v = view.getInt16(o, true) / 32768;
          } else if (bytesPerSample === 1) {
            v = (block[o] - 128) / 128;
          } else if (bytesPerSample === 3) {
            v = ((block[o] | (block[o + 1] << 8) | (block[o + 2] << 16)) << 8 >> 8) / 8388608;
          } else {
            v = view.getInt32(o, true) / 2147483648;
          }
          if (v < 0) {
            v = -v;
          }
          if (v > peak) {
            peak = v;
          }
        }
        let bucket: number = Math.floor((frame + i) * samplesPerSecond / info.sampleRate);
        if (bucket >= bucketCount) {
          bucket = bucketCount - 1;
        }
        if (peak > peaks[bucket]) {
          peaks[bucket] = peak;
        }
      }
      frame += count;
      if (!progress.report(frame / frames)) {
        throw new MediaCancelledError();
      }
    }
    for (let i = 0; i < bucketCount; i++) {
      const v: number = peaks[i] > 1 ? 1 : peaks[i];
      out.peaks.push(Math.round(v * 1000) / 1000);
    }
    return out;
  }

  /** 裁剪 [startMs, endMs)（endMs <= 0 表示到结尾），写出标准 RIFF 头 + 原 fmt chunk + 数据。返回写出的时长。 */
  static writeTrimmed(src: ByteSource, info: WavInfo, startMs: number, endMs: number, sink: ByteSink,
    progress: MediaProgress): number {
    const frames: number = info.frameCount;
    let from: number = Math.floor((startMs < 0 ? 0 : startMs) * info.sampleRate / 1000);
    let to: number = endMs > 0 ? Math.ceil(endMs * info.sampleRate / 1000) : frames;
    if (to > frames) {
      to = frames;
    }
    if (from > to) {
      from = to;
    }
    if (from >= to) {
      throw new Error('wav: empty trim range');
    }
    const dataBytes: number = (to - from) * info.blockAlign;
    const fmtSize: number = info.fmtChunk.length;
    const header: ByteBuilder = new ByteBuilder(64 + fmtSize);
    const riffSize: Uint8Array = new Uint8Array(4);
    Bin.putU32le(riffSize, 0, 4 + 8 + fmtSize + (fmtSize & 1) + 8 + dataBytes + (dataBytes & 1));
    const fmtLen: Uint8Array = new Uint8Array(4);
    Bin.putU32le(fmtLen, 0, fmtSize);
    const dataLen: Uint8Array = new Uint8Array(4);
    Bin.putU32le(dataLen, 0, dataBytes);
    header.text('RIFF').bytes(riffSize).text('WAVE').text('fmt ').bytes(fmtLen).bytes(info.fmtChunk);
    if ((fmtSize & 1) === 1) {
      header.u8(0);
    }
    header.text('data').bytes(dataLen);
    sink.write(header.toBytes());

    let done: number = 0;
    const start: number = info.dataOffset + from * info.blockAlign;
    while (done < dataBytes) {
      const n: number = Math.min(BLOCK_BYTES, dataBytes - done);
      sink.write(src.read(start + done, n));
      done += n;
      if (!progress.report(done / dataBytes)) {
        throw new MediaCancelledError();
      }
    }
    if ((dataBytes & 1) === 1) {
      sink.write(new Uint8Array(1));
    }
    return Math.round((to - from) * 1000 / info.sampleRate);
  }
}
