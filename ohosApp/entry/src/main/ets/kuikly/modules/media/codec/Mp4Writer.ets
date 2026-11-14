/*
 * MP4 写出（流拷贝重封装）：把若干条「已选好采样范围」的轨道写成一个非分片 MP4。
 * 布局：ftyp + moov + mdat（moov 在前，可边下边播）；各轨道按约 0.5 秒一个 chunk 交错存放。
 * 纯 ArkTS，不解码、不转码——采样数据从源文件原样拷贝。
 */
import { Bin, ByteBuilder, ByteSink, ByteSource, MediaCancelledError, MediaProgress } from './ByteIO';

const MOVIE_TIMESCALE: number = 1000;
const CHUNK_SECONDS: number = 0.5;
const COPY_BLOCK: number = 2 * 1024 * 1024;

/** 一条待写出的轨道。所有数组等长，下标即输出文件里的采样序号。 */
export class Mp4OutTrack {
  /** 'vide' | 'soun' */
  handler: string = 'vide';
  timescale: number = 1000;
  language: number = 0x55C4;

  tkhdFlags: number = 3;
  layer: number = 0;
  alternateGroup: number = 0;
  /** 8.8 定点音量（音频 0x0100，视频 0）。 */
  volume: number = 0;
  /** 36 字节变换矩阵；长度不为 36 时写单位矩阵。 */
  matrix: Uint8Array = new Uint8Array(0);
  widthFixed: number = 0;
  heightFixed: number = 0;

  /** 完整 box；长度为 0 时写默认值。 */
  hdlrBox: Uint8Array = new Uint8Array(0);
  mediaHeaderBox: Uint8Array = new Uint8Array(0);
  /** 完整 stsd box（必填）。 */
  stsdBox: Uint8Array = new Uint8Array(0);

  source: ByteSource | null = null;
  sampleOffsets: Array<number> = [];
  sampleSizes: Array<number> = [];
  sampleDurations: Array<number> = [];
  ctsOffsets: Array<number> | null = null;
  cttsVersion: number = 0;
  /** 关键帧下标（升序）；null 表示全部是关键帧（不写 stss）。 */
  syncSamples: Array<number> | null = null;

  /** 单条编辑的 media_time（media timescale）；-1 表示不写 edts。 */
  editMediaTime: number = -1;
  /** 编辑段时长（media timescale）；editMediaTime >= 0 时有效。 */
  editDuration: number = 0;

  get sampleCount(): number {
    return this.sampleSizes.length;
  }

  get mediaDuration(): number {
    let total: number = 0;
    for (let i = 0; i < this.sampleDurations.length; i++) {
      total += this.sampleDurations[i];
    }
    return total;
  }

  /** 呈现时长（media timescale）。 */
  get presentationDuration(): number {
    return this.editMediaTime >= 0 ? this.editDuration : this.mediaDuration;
  }
}

class OutChunk {
  track: number = 0;
  firstSample: number = 0;
  sampleCount: number = 0;
  bytes: number = 0;
}

export class Mp4Writer {
  /**
   * @param majorBrand 'isom'（视频）或 'M4A '（纯音频）
   * @returns 写出的总字节数
   */
  static write(tracks: Array<Mp4OutTrack>, majorBrand: string, sink: ByteSink, progress: MediaProgress): number {
    if (tracks.length === 0) {
      throw new Error('mp4: no tracks to write');
    }
    for (let i = 0; i < tracks.length; i++) {
      const t: Mp4OutTrack = tracks[i];
      if (t.sampleCount === 0 || t.source === null || t.stsdBox.length === 0 || t.timescale <= 0 ||
        t.sampleOffsets.length !== t.sampleCount || t.sampleDurations.length !== t.sampleCount) {
        throw new Error(`mp4: track ${i} is not writable`);
      }
    }

    const chunks: Array<OutChunk> = Mp4Writer.planChunks(tracks);
    let dataBytes: number = 0;
    for (let i = 0; i < chunks.length; i++) {
      dataBytes += chunks[i].bytes;
    }

    const ftyp: Uint8Array = Mp4Writer.buildFtyp(majorBrand, tracks);
    const use64: boolean = dataBytes + 64 * 1024 * 1024 > 0xFFFFFFFF;
    // 第一遍：偏移全 0，只为得到 moov 的长度（长度与偏移取值无关）。
    const zeroOffsets: Array<number> = new Array<number>(chunks.length);
    for (let i = 0; i < chunks.length; i++) {
      zeroOffsets[i] = 0;
    }
    const draft: Uint8Array = Mp4Writer.buildMoov(tracks, chunks, zeroOffsets, use64);
    const mdatHeader: number = use64 ? 16 : 8;
    let offset: number = ftyp.length + draft.length + mdatHeader;
    const chunkOffsets: Array<number> = new Array<number>(chunks.length);
    for (let i = 0; i < chunks.length; i++) {
      chunkOffsets[i] = offset;
      offset += chunks[i].bytes;
    }
    const moov: Uint8Array = Mp4Writer.buildMoov(tracks, chunks, chunkOffsets, use64);
    if (moov.length !== draft.length) {
      throw new Error('mp4: moov size changed between passes');
    }

    sink.write(ftyp);
    sink.write(moov);
    const head: ByteBuilder = new ByteBuilder(16);
    if (use64) {
      head.u32(1).text('mdat').u64(dataBytes + 16);
    } else {
      head.u32(dataBytes + 8).text('mdat');
    }
    sink.write(head.toBytes());

    let copied: number = 0;
    for (let i = 0; i < chunks.length; i++) {
      const chunk: OutChunk = chunks[i];
      const t: Mp4OutTrack = tracks[chunk.track];
      const source: ByteSource = t.source as ByteSource;
      // 源文件里连续的采样合并成一次读。
      let runStart: number = t.sampleOffsets[chunk.firstSample];
      let runLength: number = 0;
      for (let k = 0; k < chunk.sampleCount; k++) {
        const index: number = chunk.firstSample + k;
        const off: number = t.sampleOffsets[index];
        const size: number = t.sampleSizes[index];
        if (off !== runStart + runLength || runLength + size > COPY_BLOCK) {
          if (runLength > 0) {
            sink.write(source.read(runStart, runLength));
            copied += runLength;
          }
          runStart = off;
          runLength = 0;
        }
        runLength += size;
      }
      if (runLength > 0) {
        sink.write(source.read(runStart, runLength));
        copied += runLength;
      }
      if ((i & 0x0F) === 0 && !progress.report(dataBytes > 0 ? copied / dataBytes : 1)) {
        throw new MediaCancelledError();
      }
    }
    progress.report(1);
    return sink.position();
  }

  /** 按时间交错排 chunk：每次取「下一个采样开始时间最早」的轨道，连续写约 CHUNK_SECONDS 的采样。 */
  private static planChunks(tracks: Array<Mp4OutTrack>): Array<OutChunk> {
    const chunks: Array<OutChunk> = [];
    const cursor: Array<number> = [];
    const time: Array<number> = []; // 各轨道游标处的解码时间（media timescale）
    for (let i = 0; i < tracks.length; i++) {
      cursor.push(0);
      time.push(0);
    }
    while (true) {
      let pick: number = -1;
      let pickSeconds: number = 0;
      for (let i = 0; i < tracks.length; i++) {
        if (cursor[i] >= tracks[i].sampleCount) {
          continue;
        }
        const seconds: number = time[i] / tracks[i].timescale;
        if (pick < 0 || seconds < pickSeconds) {
          pick = i;
          pickSeconds = seconds;
        }
      }
      if (pick < 0) {
        break;
      }
      const t: Mp4OutTrack = tracks[pick];
      const chunk: OutChunk = new OutChunk();
      chunk.track = pick;
      chunk.firstSample = cursor[pick];
      const limit: number = time[pick] + CHUNK_SECONDS * t.timescale;
      while (cursor[pick] < t.sampleCount && (chunk.sampleCount === 0 || time[pick] < limit)) {
        chunk.bytes += t.sampleSizes[cursor[pick]];
        time[pick] += t.sampleDurations[cursor[pick]];
        cursor[pick] += 1;
        chunk.sampleCount += 1;
      }
      chunks.push(chunk);
    }
    return chunks;
  }

  private static buildFtyp(majorBrand: string, tracks: Array<Mp4OutTrack>): Uint8Array {
    const body: ByteBuilder = new ByteBuilder(32);
    body.text(majorBrand).u32(0x200);
    body.text(majorBrand).text('isom').text('iso2').text('mp41');
    for (let i = 0; i < tracks.length; i++) {
      if (tracks[i].handler === 'vide' && tracks[i].stsdBox.length >= 24 &&
        Bin.fourcc(tracks[i].stsdBox, 20) === 'avc1') {
        body.text('avc1');
        break;
      }
    }
    return new ByteBuilder(48).box('ftyp', body.toBytes()).toBytes();
  }

  private static toMovieTime(value: number, timescale: number): number {
    return Math.round(value * MOVIE_TIMESCALE / timescale);
  }

  private static identityMatrix(): Uint8Array {
    const m: ByteBuilder = new ByteBuilder(36);
    m.u32(0x00010000).u32(0).u32(0).u32(0).u32(0x00010000).u32(0).u32(0).u32(0).u32(0x40000000);
    return m.toBytes();
  }

  private static buildMoov(tracks: Array<Mp4OutTrack>, chunks: Array<OutChunk>, chunkOffsets: Array<number>,
    use64: boolean): Uint8Array {
    let movieDuration: number = 0;
    for (let i = 0; i < tracks.length; i++) {
      const d: number = Mp4Writer.toMovieTime(tracks[i].presentationDuration, tracks[i].timescale);
      if (d > movieDuration) {
        movieDuration = d;
      }
    }
    const moov: ByteBuilder = new ByteBuilder(64 * 1024);

    const mvhd: ByteBuilder = new ByteBuilder(128);
    mvhd.u32(0).u32(0).u32(0).u32(MOVIE_TIMESCALE).u32(movieDuration);
    mvhd.u32(0x00010000).u16(0x0100).zeros(10);
    mvhd.bytes(Mp4Writer.identityMatrix()).zeros(24).u32(tracks.length + 1);
    moov.box('mvhd', mvhd.toBytes());

    for (let i = 0; i < tracks.length; i++) {
      moov.box('trak', Mp4Writer.buildTrak(tracks[i], i, chunks, chunkOffsets, use64));
    }
    return new ByteBuilder(moov.length + 8).box('moov', moov.toBytes()).toBytes();
  }

  private static buildTrak(t: Mp4OutTrack, index: number, chunks: Array<OutChunk>, chunkOffsets: Array<number>,
    use64: boolean): Uint8Array {
    const trak: ByteBuilder = new ByteBuilder(16 * 1024);
    const movieDuration: number = Mp4Writer.toMovieTime(t.presentationDuration, t.timescale);

    const tkhd: ByteBuilder = new ByteBuilder(96);
    tkhd.u32(t.tkhdFlags & 0x00FFFFFF).u32(0).u32(0).u32(index + 1).u32(0).u32(movieDuration).zeros(8);
    tkhd.u16(t.layer).u16(t.alternateGroup).u16(t.volume).u16(0);
    tkhd.bytes(t.matrix.length === 36 ? t.matrix : Mp4Writer.identityMatrix());
    tkhd.u32(t.widthFixed).u32(t.heightFixed);
    trak.box('tkhd', tkhd.toBytes());

    if (t.editMediaTime >= 0) {
      const elst: ByteBuilder = new ByteBuilder(32);
      elst.u32(0).u32(1).u32(movieDuration).u32(t.editMediaTime).u32(0x00010000);
      trak.box('edts', new ByteBuilder(40).box('elst', elst.toBytes()).toBytes());
    }

    const mdia: ByteBuilder = new ByteBuilder(16 * 1024);
    const mdhd: ByteBuilder = new ByteBuilder(32);
    mdhd.u32(0).u32(0).u32(0).u32(t.timescale).u32(t.mediaDuration).u16(t.language).u16(0);
    mdia.box('mdhd', mdhd.toBytes());
    if (t.hdlrBox.length > 0) {
      mdia.bytes(t.hdlrBox);
    } else {
      const hdlr: ByteBuilder = new ByteBuilder(48);
      hdlr.u32(0).u32(0).text(t.handler).zeros(12);
      hdlr.text(t.handler === 'soun' ? 'SoundHandler' : 'VideoHandler').u8(0);
      mdia.box('hdlr', hdlr.toBytes());
    }

    const minf: ByteBuilder = new ByteBuilder(16 * 1024);
    if (t.mediaHeaderBox.length > 0) {
      minf.bytes(t.mediaHeaderBox);
    } else if (t.handler === 'soun') {
      minf.box('smhd', new ByteBuilder(8).u32(0).u16(0).u16(0).toBytes());
    } else {
      minf.box('vmhd', new ByteBuilder(12).u32(1).u16(0).zeros(6).toBytes());
    }
    // 数据就在本文件内：dref 里放一条 flags=1 的 'url ' 项。
    const url: Uint8Array = new ByteBuilder(12).box('url ', new ByteBuilder(4).u32(1).toBytes()).toBytes();
    const dref: Uint8Array = new ByteBuilder(28).box('dref', new ByteBuilder(20).u32(0).u32(1).bytes(url).toBytes())
      .toBytes();
    minf.box('dinf', dref);
    minf.box('stbl', Mp4Writer.buildStbl(t, index, chunks, chunkOffsets, use64));

    mdia.box('minf', minf.toBytes());
    trak.box('mdia', mdia.toBytes());
    return trak.toBytes();
  }

  private static buildStbl(t: Mp4OutTrack, index: number, chunks: Array<OutChunk>, chunkOffsets: Array<number>,
    use64: boolean): Uint8Array {
    const count: number = t.sampleCount;
    const stbl: ByteBuilder = new ByteBuilder(count * 12 + 1024);
    stbl.bytes(t.stsdBox);

    // stts：相同时长游程压缩。
    const stts: ByteBuilder = new ByteBuilder(1024);
    let runs: number = 0;
    stts.u32(0).u32(0);
    let i: number = 0;
    while (i < count) {
      const delta: number = t.sampleDurations[i];
      let n: number = 1;
      while (i + n < count && t.sampleDurations[i + n] === delta) {
        n++;
      }
      stts.u32(n).u32(delta);
      runs++;
      i += n;
    }
    stts.patchU32(4, runs);
    stbl.box('stts', stts.toBytes());

    const cts: Array<number> | null = t.ctsOffsets;
    if (cts !== null && cts.length === count) {
      let negative: boolean = false;
      for (let k = 0; k < count; k++) {
        if (cts[k] < 0) {
          negative = true;
          break;
        }
      }
      const ctts: ByteBuilder = new ByteBuilder(1024);
      const version: number = negative || t.cttsVersion === 1 ? 1 : 0;
      ctts.u32(version << 24).u32(0);
      runs = 0;
      i = 0;
      while (i < count) {
        const offset: number = cts[i];
        let n: number = 1;
        while (i + n < count && cts[i + n] === offset) {
          n++;
        }
        ctts.u32(n).i32(offset);
        runs++;
        i += n;
      }
      ctts.patchU32(4, runs);
      stbl.box('ctts', ctts.toBytes());
    }

    const sync: Array<number> | null = t.syncSamples;
    if (sync !== null) {
      const stss: ByteBuilder = new ByteBuilder(sync.length * 4 + 16);
      stss.u32(0).u32(sync.length);
      for (let k = 0; k < sync.length; k++) {
        stss.u32(sync[k] + 1);
      }
      stbl.box('stss', stss.toBytes());
    }

    // stsc + stco：只看属于本轨道的 chunk。
    const stsc: ByteBuilder = new ByteBuilder(1024);
    const stco: ByteBuilder = new ByteBuilder(1024);
    stsc.u32(0).u32(0);
    stco.u32(0).u32(0);
    let chunkNumber: number = 0;
    let stscRuns: number = 0;
    let lastPerChunk: number = -1;
    for (let k = 0; k < chunks.length; k++) {
      if (chunks[k].track !== index) {
        continue;
      }
      chunkNumber++;
      if (chunks[k].sampleCount !== lastPerChunk) {
        stsc.u32(chunkNumber).u32(chunks[k].sampleCount).u32(1);
        lastPerChunk = chunks[k].sampleCount;
        stscRuns++;
      }
      if (use64) {
        stco.u64(chunkOffsets[k]);
      } else {
        stco.u32(chunkOffsets[k]);
      }
    }
    stsc.patchU32(4, stscRuns);
    stco.patchU32(4, chunkNumber);
    stbl.box('stsc', stsc.toBytes());

    const stsz: ByteBuilder = new ByteBuilder(count * 4 + 16);
    stsz.u32(0).u32(0).u32(count);
    for (let k = 0; k < count; k++) {
      stsz.u32(t.sampleSizes[k]);
    }
    stbl.box('stsz', stsz.toBytes());
    stbl.box(use64 ? 'co64' : 'stco', stco.toBytes());
    return stbl.toBytes();
  }
}
