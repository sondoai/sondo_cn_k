/*
 * ADTS AAC（.aac 裸流）帧级裁剪。纯 ArkTS，不解码。
 * 每个 ADTS 帧自带 7/9 字节头，帧之间互不依赖，按帧边界截取即可得到合法文件。
 */
import { Bin, ByteSink, MediaCancelledError, MediaProgress } from './ByteIO';

const ADTS_SAMPLE_RATES: Array<number> =
  [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350];

export class AdtsScan {
  sampleRate: number = 0;
  offsets: Array<number> = [];
  sizes: Array<number> = [];
  /** 每帧包含的采样数（1024 × raw_data_block 数），整条流取第一帧的值。 */
  samplesPerFrame: number = 1024;

  get durationMs(): number {
    return this.sampleRate > 0 ? Math.round(this.offsets.length * this.samplesPerFrame * 1000 / this.sampleRate) : 0;
  }
}

export class AdtsFormat {
  /** o 处是否是 ADTS 帧头；是则返回帧长度，否则返回 -1。 */
  private static frameLength(b: Uint8Array, o: number): number {
    if (o + 7 > b.length) {
      return -1;
    }
    // syncword 12 位全 1，layer 必须为 0。
    if (b[o] !== 0xFF || (b[o + 1] & 0xF6) !== 0xF0) {
      return -1;
    }
    const rateIndex: number = (b[o + 2] >> 2) & 0x0F;
    if (rateIndex >= ADTS_SAMPLE_RATES.length) {
      return -1;
    }
    const length: number = ((b[o + 3] & 0x03) << 11) | (b[o + 4] << 3) | (b[o + 5] >> 5);
    return length >= 7 ? length : -1;
  }

  private static skipId3(b: Uint8Array): number {
    if (b.length >= 10 && Bin.matches(b, 0, 'ID3')) {
      const size: number = 10 + (((b[6] & 0x7F) << 21) | ((b[7] & 0x7F) << 14) | ((b[8] & 0x7F) << 7) | (b[9] & 0x7F));
      return size <= b.length ? size : 0;
    }
    return 0;
  }

  /** 头部（跳过 ID3v2 后）是否像 ADTS：连续两帧同步字都成立。 */
  static isAdts(b: Uint8Array): boolean {
    const start: number = AdtsFormat.skipId3(b);
    const len: number = AdtsFormat.frameLength(b, start);
    if (len < 0) {
      return false;
    }
    const next: number = start + len;
    return next === b.length || AdtsFormat.frameLength(b, next) > 0;
  }

  static scan(b: Uint8Array): AdtsScan {
    const scan: AdtsScan = new AdtsScan();
    let pos: number = AdtsFormat.skipId3(b);
    let first: boolean = true;
    while (pos + 7 <= b.length) {
      const len: number = AdtsFormat.frameLength(b, pos);
      if (len < 0 || pos + len > b.length) {
        break;
      }
      if (first) {
        scan.sampleRate = ADTS_SAMPLE_RATES[(b[pos + 2] >> 2) & 0x0F];
        scan.samplesPerFrame = 1024 * ((b[pos + 6] & 0x03) + 1);
        first = false;
      }
      scan.offsets.push(pos);
      scan.sizes.push(len);
      pos += len;
    }
    if (scan.offsets.length === 0 || scan.sampleRate <= 0) {
      throw new Error('aac: no ADTS frames');
    }
    return scan;
  }

  /** 裁剪 [startMs, endMs)（endMs <= 0 表示到结尾）。返回写出的时长（毫秒）。 */
  static writeTrimmed(b: Uint8Array, startMs: number, endMs: number, sink: ByteSink,
    progress: MediaProgress): number {
    const scan: AdtsScan = AdtsFormat.scan(b);
    const frameMs: number = scan.samplesPerFrame * 1000 / scan.sampleRate;
    const count: number = scan.offsets.length;
    const from: number = Math.floor((startMs < 0 ? 0 : startMs) / frameMs);
    let to: number = endMs > 0 ? Math.ceil(endMs / frameMs) : count;
    if (to > count) {
      to = count;
    }
    if (from >= to) {
      throw new Error('aac: empty trim range');
    }
    // ADTS 帧在文件里是连续的，整段一次写出（分块以便上报进度 / 响应取消）。
    const begin: number = scan.offsets[from];
    const end: number = scan.offsets[to - 1] + scan.sizes[to - 1];
    let pos: number = begin;
    while (pos < end) {
      const n: number = Math.min(1024 * 1024, end - pos);
      sink.write(b.subarray(pos, pos + n));
      pos += n;
      if (!progress.report((pos - begin) / (end - begin))) {
        throw new MediaCancelledError();
      }
    }
    return Math.round((to - from) * frameMs);
  }
}
