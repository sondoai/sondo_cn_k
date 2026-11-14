/*
 * ISO BMFF（MP4 / M4A / MOV）解析：只读 moov，把每条轨道的采样表（stts/ctts/stss/stsc/stsz/stco）
 * 展开成「每个采样的 文件偏移 / 长度 / 时长 / CTS 偏移 / 是否关键帧」，供 Mp4Writer 做流拷贝重封装。
 * 纯 ArkTS，不解码。不支持分片 MP4（moof）与压缩 moov（cmov）。
 */
import { Bin, ByteSource } from './ByteIO';

/** moov 最大允许读入内存的大小。 */
const MAX_MOOV_BYTES: number = 192 * 1024 * 1024;

export class Mp4BoxRef {
  type: string = '';
  /** box 起点（含头）。 */
  start: number = 0;
  /** payload 起点。 */
  body: number = 0;
  /** box 终点（不含）。 */
  end: number = 0;
}

export class Mp4Edit {
  /** 以 movie timescale 计。 */
  segmentDuration: number = 0;
  /** 以 media timescale 计；-1 表示空编辑。 */
  mediaTime: number = 0;
}

export class Mp4Track {
  trackId: number = 0;
  /** 'vide' | 'soun' | … */
  handler: string = '';
  timescale: number = 0;
  /** mdhd 里打包后的 ISO-639-2 语言码。 */
  language: number = 0x55C4;

  tkhdFlags: number = 3;
  layer: number = 0;
  alternateGroup: number = 0;
  volume: number = 0;
  matrix: Uint8Array = new Uint8Array(36);
  /** 16.16 定点原值。 */
  widthFixed: number = 0;
  heightFixed: number = 0;

  /** 以下为完整 box 字节（含 8 字节头），重封装时原样写回。 */
  hdlrBox: Uint8Array = new Uint8Array(0);
  mediaHeaderBox: Uint8Array = new Uint8Array(0);
  stsdBox: Uint8Array = new Uint8Array(0);

  /** 第一个 sample entry 的类型：'avc1' / 'hvc1' / 'hev1' / 'mp4a' … */
  sampleEntryType: string = '';
  /** mp4a 的 esds objectTypeIndication（0x40 = AAC，0x6B/0x69 = MP3）；取不到为 0。 */
  audioObjectType: number = 0;

  sizes: Array<number> = [];
  offsets: Array<number> = [];
  durations: Array<number> = [];
  /** 没有 ctts 时为 null。 */
  ctsOffsets: Array<number> | null = null;
  cttsVersion: number = 0;
  /** 关键帧下标（0 起，升序）；null 表示全部是关键帧。 */
  syncSamples: Array<number> | null = null;
  edits: Array<Mp4Edit> = [];

  get sampleCount(): number {
    return this.sizes.length;
  }

  /** 全部采样的时长之和（media timescale）。 */
  get mediaDuration(): number {
    let total: number = 0;
    for (let i = 0; i < this.durations.length; i++) {
      total += this.durations[i];
    }
    return total;
  }

  get durationMs(): number {
    return this.timescale > 0 ? Math.round(this.mediaDuration * 1000 / this.timescale) : 0;
  }

  get width(): number {
    return Math.floor(this.widthFixed / 65536);
  }

  get height(): number {
    return Math.floor(this.heightFixed / 65536);
  }

  /** 第一个非空编辑的 media_time；没有编辑列表返回 -1。 */
  get firstEditMediaTime(): number {
    for (let i = 0; i < this.edits.length; i++) {
      if (this.edits[i].mediaTime >= 0) {
        return this.edits[i].mediaTime;
      }
    }
    return -1;
  }

  /** 是否是 MP4 内的 AAC 轨。 */
  get isAac(): boolean {
    return this.handler === 'soun' && this.sampleEntryType === 'mp4a' &&
      (this.audioObjectType === 0x40 || this.audioObjectType === 0x66 || this.audioObjectType === 0x67 ||
        this.audioObjectType === 0x68);
  }
}

export class Mp4Movie {
  timescale: number = 1000;
  tracks: Array<Mp4Track> = [];

  firstTrack(handler: string): Mp4Track | null {
    for (let i = 0; i < this.tracks.length; i++) {
      if (this.tracks[i].handler === handler && this.tracks[i].sampleCount > 0) {
        return this.tracks[i];
      }
    }
    return null;
  }
}

export class Mp4Parser {
  /** 文件头是否像 ISO BMFF：第一个 box 是 ftyp / moov / mdat / free / wide / skip。 */
  static looksLikeMp4(head: Uint8Array): boolean {
    if (head.length < 12) {
      return false;
    }
    const type: string = Bin.fourcc(head, 4);
    return type === 'ftyp' || type === 'moov' || type === 'mdat' || type === 'free' || type === 'wide' ||
      type === 'skip';
  }

  /** 列出 b[from, to) 内的直接子 box；遇到非法长度即停止。 */
  static children(b: Uint8Array, from: number, to: number): Array<Mp4BoxRef> {
    const out: Array<Mp4BoxRef> = [];
    let pos: number = from;
    while (pos + 8 <= to) {
      let size: number = Bin.u32(b, pos);
      let header: number = 8;
      if (size === 1) {
        if (pos + 16 > to) {
          break;
        }
        size = Bin.u64(b, pos + 8);
        header = 16;
      } else if (size === 0) {
        size = to - pos;
      }
      if (size < header || pos + size > to) {
        break;
      }
      const ref: Mp4BoxRef = new Mp4BoxRef();
      ref.type = Bin.fourcc(b, pos + 4);
      ref.start = pos;
      ref.body = pos + header;
      ref.end = pos + size;
      out.push(ref);
      pos += size;
    }
    return out;
  }

  static find(boxes: Array<Mp4BoxRef>, type: string): Mp4BoxRef | null {
    for (let i = 0; i < boxes.length; i++) {
      if (boxes[i].type === type) {
        return boxes[i];
      }
    }
    return null;
  }

  static parse(src: ByteSource): Mp4Movie {
    const total: number = src.size();
    let pos: number = 0;
    let moov: Uint8Array | null = null;
    while (pos + 8 <= total) {
      const head: Uint8Array = src.read(pos, Math.min(16, total - pos));
      let size: number = Bin.u32(head, 0);
      const type: string = Bin.fourcc(head, 4);
      let header: number = 8;
      if (size === 1) {
        if (head.length < 16) {
          break;
        }
        size = Bin.u64(head, 8);
        header = 16;
      } else if (size === 0) {
        size = total - pos;
      }
      if (size < header || pos + size > total) {
        if (type === 'mdat' && moov !== null) {
          break; // 尾部 mdat 被截断：moov 已拿到，采样偏移越界时再报错
        }
        throw new Error(`mp4: bad box '${type}' at ${pos}`);
      }
      if (type === 'moof') {
        throw new Error('mp4: fragmented files are not supported');
      }
      if (type === 'moov') {
        if (size > MAX_MOOV_BYTES) {
          throw new Error('mp4: moov too large');
        }
        moov = src.read(pos + header, size - header).slice(0);
      }
      pos += size;
    }
    if (moov === null) {
      throw new Error('mp4: moov not found');
    }
    return Mp4Parser.parseMoov(moov, total);
  }

  private static parseMoov(b: Uint8Array, fileSize: number): Mp4Movie {
    const movie: Mp4Movie = new Mp4Movie();
    const boxes: Array<Mp4BoxRef> = Mp4Parser.children(b, 0, b.length);
    if (Mp4Parser.find(boxes, 'cmov') !== null) {
      throw new Error('mp4: compressed moov is not supported');
    }
    const mvhd: Mp4BoxRef | null = Mp4Parser.find(boxes, 'mvhd');
    if (mvhd !== null) {
      const version: number = b[mvhd.body];
      movie.timescale = Bin.u32(b, mvhd.body + (version === 1 ? 20 : 12));
    }
    if (movie.timescale <= 0) {
      movie.timescale = 1000;
    }
    for (let i = 0; i < boxes.length; i++) {
      if (boxes[i].type !== 'trak') {
        continue;
      }
      const track: Mp4Track | null = Mp4Parser.parseTrak(b, boxes[i], fileSize);
      if (track !== null) {
        movie.tracks.push(track);
      }
    }
    return movie;
  }

  private static parseTrak(b: Uint8Array, trak: Mp4BoxRef, fileSize: number): Mp4Track | null {
    const top: Array<Mp4BoxRef> = Mp4Parser.children(b, trak.body, trak.end);
    const tkhd: Mp4BoxRef | null = Mp4Parser.find(top, 'tkhd');
    const mdia: Mp4BoxRef | null = Mp4Parser.find(top, 'mdia');
    if (tkhd === null || mdia === null) {
      return null;
    }
    const mdiaBoxes: Array<Mp4BoxRef> = Mp4Parser.children(b, mdia.body, mdia.end);
    const mdhd: Mp4BoxRef | null = Mp4Parser.find(mdiaBoxes, 'mdhd');
    const hdlr: Mp4BoxRef | null = Mp4Parser.find(mdiaBoxes, 'hdlr');
    const minf: Mp4BoxRef | null = Mp4Parser.find(mdiaBoxes, 'minf');
    if (mdhd === null || hdlr === null || minf === null) {
      return null;
    }
    const minfBoxes: Array<Mp4BoxRef> = Mp4Parser.children(b, minf.body, minf.end);
    const stbl: Mp4BoxRef | null = Mp4Parser.find(minfBoxes, 'stbl');
    if (stbl === null) {
      return null;
    }
    const stblBoxes: Array<Mp4BoxRef> = Mp4Parser.children(b, stbl.body, stbl.end);
    const stsd: Mp4BoxRef | null = Mp4Parser.find(stblBoxes, 'stsd');
    const stts: Mp4BoxRef | null = Mp4Parser.find(stblBoxes, 'stts');
    const stsc: Mp4BoxRef | null = Mp4Parser.find(stblBoxes, 'stsc');
    let stsz: Mp4BoxRef | null = Mp4Parser.find(stblBoxes, 'stsz');
    const stz2: Mp4BoxRef | null = Mp4Parser.find(stblBoxes, 'stz2');
    const stco: Mp4BoxRef | null = Mp4Parser.find(stblBoxes, 'stco');
    const co64: Mp4BoxRef | null = Mp4Parser.find(stblBoxes, 'co64');
    if (stsd === null || stts === null || stsc === null || (stsz === null && stz2 === null) ||
      (stco === null && co64 === null)) {
      return null;
    }

    const t: Mp4Track = new Mp4Track();

    // ---- tkhd ----
    const tkVersion: number = b[tkhd.body];
    t.tkhdFlags = Bin.u24(b, tkhd.body + 1);
    const tk: number = tkhd.body + 4 + (tkVersion === 1 ? 16 : 8); // 跳过 creation / modification
    t.trackId = Bin.u32(b, tk);
    // track_ID(4) reserved(4) duration(4|8) reserved(8) layer(2) alt_group(2) volume(2) reserved(2) matrix(36) w(4) h(4)
    const afterDuration: number = tk + 8 + (tkVersion === 1 ? 8 : 4);
    t.layer = Bin.u16(b, afterDuration + 8);
    t.alternateGroup = Bin.u16(b, afterDuration + 10);
    t.volume = Bin.u16(b, afterDuration + 12);
    t.matrix = b.slice(afterDuration + 16, afterDuration + 52);
    t.widthFixed = Bin.u32(b, afterDuration + 52);
    t.heightFixed = Bin.u32(b, afterDuration + 56);

    // ---- mdhd ----
    const mdVersion: number = b[mdhd.body];
    const md: number = mdhd.body + 4 + (mdVersion === 1 ? 16 : 8);
    t.timescale = Bin.u32(b, md);
    t.language = Bin.u16(b, md + 4 + (mdVersion === 1 ? 8 : 4));
    if (t.timescale <= 0) {
      return null;
    }

    // ---- hdlr / media header / stsd ----
    t.handler = Bin.fourcc(b, hdlr.body + 8);
    t.hdlrBox = b.slice(hdlr.start, hdlr.end);
    for (let i = 0; i < minfBoxes.length; i++) {
      const type: string = minfBoxes[i].type;
      if (type === 'vmhd' || type === 'smhd' || type === 'nmhd' || type === 'hmhd' || type === 'sthd') {
        t.mediaHeaderBox = b.slice(minfBoxes[i].start, minfBoxes[i].end);
        break;
      }
    }
    t.stsdBox = b.slice(stsd.start, stsd.end);
    Mp4Parser.parseSampleEntry(b, stsd, t);

    // ---- edts ----
    const edts: Mp4BoxRef | null = Mp4Parser.find(top, 'edts');
    if (edts !== null) {
      const elst: Mp4BoxRef | null = Mp4Parser.find(Mp4Parser.children(b, edts.body, edts.end), 'elst');
      if (elst !== null) {
        const version: number = b[elst.body];
        const count: number = Bin.u32(b, elst.body + 4);
        const entrySize: number = version === 1 ? 20 : 12;
        let p: number = elst.body + 8;
        for (let i = 0; i < count && p + entrySize <= elst.end; i++) {
          const edit: Mp4Edit = new Mp4Edit();
          if (version === 1) {
            edit.segmentDuration = Bin.u64(b, p);
            const hi: number = Bin.i32(b, p + 8);
            edit.mediaTime = hi < 0 ? -1 : hi * 4294967296 + Bin.u32(b, p + 12);
          } else {
            edit.segmentDuration = Bin.u32(b, p);
            edit.mediaTime = Bin.i32(b, p + 4);
          }
          t.edits.push(edit);
          p += entrySize;
        }
      }
    }

    // ---- stsz / stz2 ----
    let sampleCount: number = 0;
    if (stsz !== null) {
      const uniform: number = Bin.u32(b, stsz.body + 4);
      sampleCount = Bin.u32(b, stsz.body + 8);
      if (uniform === 0 && stsz.body + 12 + sampleCount * 4 > stsz.end) {
        throw new Error('mp4: truncated stsz');
      }
      for (let i = 0; i < sampleCount; i++) {
        t.sizes.push(uniform !== 0 ? uniform : Bin.u32(b, stsz.body + 12 + i * 4));
      }
    } else if (stz2 !== null) {
      const fieldSize: number = b[stz2.body + 7];
      sampleCount = Bin.u32(b, stz2.body + 8);
      const p: number = stz2.body + 12;
      for (let i = 0; i < sampleCount; i++) {
        if (fieldSize === 16) {
          t.sizes.push(Bin.u16(b, p + i * 2));
        } else if (fieldSize === 8) {
          t.sizes.push(b[p + i]);
        } else if (fieldSize === 4) {
          const byte: number = b[p + (i >> 1)];
          t.sizes.push((i & 1) === 0 ? (byte >> 4) : (byte & 0x0F));
        } else {
          throw new Error('mp4: bad stz2 field size');
        }
      }
      stsz = stz2;
    }

    // ---- stts ----
    const sttsCount: number = Bin.u32(b, stts.body + 4);
    for (let i = 0; i < sttsCount && stts.body + 16 + i * 8 <= stts.end; i++) {
      const count: number = Bin.u32(b, stts.body + 8 + i * 8);
      const delta: number = Bin.u32(b, stts.body + 12 + i * 8);
      for (let k = 0; k < count && t.durations.length < sampleCount; k++) {
        t.durations.push(delta);
      }
    }
    while (t.durations.length < sampleCount) {
      t.durations.push(t.durations.length > 0 ? t.durations[t.durations.length - 1] : 0);
    }

    // ---- ctts ----
    const ctts: Mp4BoxRef | null = Mp4Parser.find(stblBoxes, 'ctts');
    if (ctts !== null) {
      t.cttsVersion = b[ctts.body];
      const cts: Array<number> = [];
      const count: number = Bin.u32(b, ctts.body + 4);
      for (let i = 0; i < count && ctts.body + 16 + i * 8 <= ctts.end; i++) {
        const n: number = Bin.u32(b, ctts.body + 8 + i * 8);
        // version 1 为有符号；version 0 理论上无符号，但不少封装器同样写入负值，统一按有符号读。
        const offset: number = Bin.i32(b, ctts.body + 12 + i * 8);
        for (let k = 0; k < n && cts.length < sampleCount; k++) {
          cts.push(offset);
        }
      }
      while (cts.length < sampleCount) {
        cts.push(0);
      }
      t.ctsOffsets = cts;
    }

    // ---- stss ----
    const stss: Mp4BoxRef | null = Mp4Parser.find(stblBoxes, 'stss');
    if (stss !== null) {
      const sync: Array<number> = [];
      const count: number = Bin.u32(b, stss.body + 4);
      for (let i = 0; i < count && stss.body + 12 + i * 4 <= stss.end; i++) {
        sync.push(Bin.u32(b, stss.body + 8 + i * 4) - 1);
      }
      t.syncSamples = sync;
    }

    // ---- stco / co64 + stsc → 每个采样的文件偏移 ----
    const chunkOffsets: Array<number> = [];
    if (stco !== null) {
      const count: number = Bin.u32(b, stco.body + 4);
      for (let i = 0; i < count && stco.body + 12 + i * 4 <= stco.end; i++) {
        chunkOffsets.push(Bin.u32(b, stco.body + 8 + i * 4));
      }
    } else if (co64 !== null) {
      const count: number = Bin.u32(b, co64.body + 4);
      for (let i = 0; i < count && co64.body + 16 + i * 8 <= co64.end; i++) {
        chunkOffsets.push(Bin.u64(b, co64.body + 8 + i * 8));
      }
    }
    const stscCount: number = Bin.u32(b, stsc.body + 4);
    let sample: number = 0;
    for (let i = 0; i < stscCount && stsc.body + 20 + i * 12 <= stsc.end; i++) {
      const firstChunk: number = Bin.u32(b, stsc.body + 8 + i * 12);
      const perChunk: number = Bin.u32(b, stsc.body + 12 + i * 12);
      let lastChunk: number = chunkOffsets.length;
      if (i + 1 < stscCount && stsc.body + 20 + (i + 1) * 12 <= stsc.end) {
        lastChunk = Bin.u32(b, stsc.body + 8 + (i + 1) * 12) - 1;
      }
      if (lastChunk > chunkOffsets.length) {
        lastChunk = chunkOffsets.length;
      }
      for (let chunk = firstChunk; chunk <= lastChunk && sample < sampleCount; chunk++) {
        let offset: number = chunkOffsets[chunk - 1];
        for (let k = 0; k < perChunk && sample < sampleCount; k++) {
          t.offsets.push(offset);
          offset += t.sizes[sample];
          sample++;
        }
      }
    }
    if (t.offsets.length !== sampleCount) {
      throw new Error('mp4: sample tables are inconsistent');
    }
    if (sampleCount > 0) {
      const last: number = sampleCount - 1;
      if (t.offsets[last] + t.sizes[last] > fileSize) {
        throw new Error('mp4: sample data is truncated');
      }
    }
    return t;
  }

  /** 解析 stsd 第一个 sample entry：类型，以及 mp4a 的 esds objectTypeIndication。 */
  private static parseSampleEntry(b: Uint8Array, stsd: Mp4BoxRef, t: Mp4Track): void {
    const entries: Array<Mp4BoxRef> = Mp4Parser.children(b, stsd.body + 8, stsd.end);
    if (entries.length === 0) {
      return;
    }
    const entry: Mp4BoxRef = entries[0];
    t.sampleEntryType = entry.type;
    if (t.handler !== 'soun' || entry.type !== 'mp4a') {
      return;
    }
    // AudioSampleEntry：reserved(6) data_ref_index(2) version(2) revision(2) vendor(4)
    //                  channelcount(2) samplesize(2) pre_defined(2) reserved(2) samplerate(4)
    const soundVersion: number = Bin.u16(b, entry.body + 8);
    let childStart: number = entry.body + 28;
    if (soundVersion === 1) {
      childStart += 16; // QuickTime sound description v1
    } else if (soundVersion === 2) {
      childStart += 36; // QuickTime sound description v2
    }
    let esds: Mp4BoxRef | null = Mp4Parser.find(Mp4Parser.children(b, childStart, entry.end), 'esds');
    if (esds === null) {
      // QuickTime 把 esds 包在 wave 里。
      const wave: Mp4BoxRef | null = Mp4Parser.find(Mp4Parser.children(b, childStart, entry.end), 'wave');
      if (wave !== null) {
        esds = Mp4Parser.find(Mp4Parser.children(b, wave.body, wave.end), 'esds');
      }
    }
    if (esds === null) {
      return;
    }
    let p: number = esds.body + 4;
    if (p >= esds.end || b[p] !== 0x03) {
      return;
    }
    p = Mp4Parser.skipDescriptorHeader(b, p, esds.end);
    if (p + 3 > esds.end) {
      return;
    }
    const flags: number = b[p + 2];
    p += 3;
    if ((flags & 0x80) !== 0) {
      p += 2; // dependsOn_ES_ID
    }
    if ((flags & 0x40) !== 0 && p < esds.end) {
      p += 1 + b[p]; // URL
    }
    if ((flags & 0x20) !== 0) {
      p += 2; // OCR_ES_Id
    }
    if (p >= esds.end || b[p] !== 0x04) {
      return;
    }
    p = Mp4Parser.skipDescriptorHeader(b, p, esds.end);
    if (p < esds.end) {
      t.audioObjectType = b[p];
    }
  }

  /** 跳过 MPEG-4 descriptor 的 tag + 变长 length，返回内容起点。 */
  private static skipDescriptorHeader(b: Uint8Array, p: number, end: number): number {
    let pos: number = p + 1;
    for (let i = 0; i < 4 && pos < end; i++) {
      const byte: number = b[pos];
      pos++;
      if ((byte & 0x80) === 0) {
        break;
      }
    }
    return pos;
  }
}
