/*
 * 媒体容器解析 / 写出用的字节工具。纯算法，不依赖任何系统 Kit，
 * 既在主线程用，也在 TaskPool 线程用（见 ../MediaTasks.ets）。
 *
 * - ByteSource：随机读（内存 / 文件两种实现，文件实现见 ../MediaFileIO.ets）
 * - ByteSink：顺序写
 * - Bin：大小端整数读写、FourCC、ASCII
 * - ByteBuilder：可增长的字节缓冲，用于拼 MP4 box
 */

/** 随机读数据源。 */
export interface ByteSource {
  /** 总字节数。 */
  size(): number;

  /** 读取 [offset, offset + length)；越界时抛 Error。返回的数组只读使用。 */
  read(offset: number, length: number): Uint8Array;
}

/** 顺序写目标。 */
export interface ByteSink {
  write(bytes: Uint8Array): void;

  /** 已写入的字节数。 */
  position(): number;
}

/** 进度 / 取消钩子：返回 false 表示任务已被取消，调用方应立刻中止。 */
export interface MediaProgress {
  report(fraction: number): boolean;
}

export class NoProgress implements MediaProgress {
  report(fraction: number): boolean {
    return true;
  }
}

export class MediaCancelledError extends Error {
  constructor() {
    super('cancelled');
  }
}

export class MemorySource implements ByteSource {
  private data: Uint8Array;

  constructor(data: Uint8Array) {
    this.data = data;
  }

  size(): number {
    return this.data.length;
  }

  read(offset: number, length: number): Uint8Array {
    if (offset < 0 || length < 0 || offset + length > this.data.length) {
      throw new Error(`read out of range: ${offset}+${length} > ${this.data.length}`);
    }
    return this.data.subarray(offset, offset + length);
  }
}

export class MemorySink implements ByteSink {
  private chunks: Array<Uint8Array> = [];
  private total: number = 0;

  write(bytes: Uint8Array): void {
    if (bytes.length === 0) {
      return;
    }
    // 调用方可能复用缓冲区，这里必须拷贝一份。
    const copy: Uint8Array = new Uint8Array(bytes.length);
    copy.set(bytes);
    this.chunks.push(copy);
    this.total += bytes.length;
  }

  position(): number {
    return this.total;
  }

  toBytes(): Uint8Array {
    const out: Uint8Array = new Uint8Array(this.total);
    let pos: number = 0;
    for (let i = 0; i < this.chunks.length; i++) {
      out.set(this.chunks[i], pos);
      pos += this.chunks[i].length;
    }
    return out;
  }
}

export class Bin {
  static u8(b: Uint8Array, o: number): number {
    return b[o];
  }

  static u16(b: Uint8Array, o: number): number {
    return (b[o] << 8) | b[o + 1];
  }

  static u24(b: Uint8Array, o: number): number {
    return (b[o] << 16) | (b[o + 1] << 8) | b[o + 2];
  }

  static u32(b: Uint8Array, o: number): number {
    return ((b[o] << 24) >>> 0) + ((b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3]);
  }

  static i32(b: Uint8Array, o: number): number {
    return (b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3];
  }

  /** 64 位无符号大端（用 double 表示，2^53 以内精确）。 */
  static u64(b: Uint8Array, o: number): number {
    return Bin.u32(b, o) * 4294967296 + Bin.u32(b, o + 4);
  }

  static u16le(b: Uint8Array, o: number): number {
    return b[o] | (b[o + 1] << 8);
  }

  static u32le(b: Uint8Array, o: number): number {
    return (b[o] | (b[o + 1] << 8) | (b[o + 2] << 16)) + ((b[o + 3] << 24) >>> 0);
  }

  static putU16(b: Uint8Array, o: number, v: number): void {
    b[o] = (v >>> 8) & 0xFF;
    b[o + 1] = v & 0xFF;
  }

  static putU32(b: Uint8Array, o: number, v: number): void {
    b[o] = (v >>> 24) & 0xFF;
    b[o + 1] = (v >>> 16) & 0xFF;
    b[o + 2] = (v >>> 8) & 0xFF;
    b[o + 3] = v & 0xFF;
  }

  static putU16le(b: Uint8Array, o: number, v: number): void {
    b[o] = v & 0xFF;
    b[o + 1] = (v >>> 8) & 0xFF;
  }

  static putU32le(b: Uint8Array, o: number, v: number): void {
    b[o] = v & 0xFF;
    b[o + 1] = (v >>> 8) & 0xFF;
    b[o + 2] = (v >>> 16) & 0xFF;
    b[o + 3] = (v >>> 24) & 0xFF;
  }

  /** 读 4 字节 ASCII（MP4 box 类型、RIFF chunk id）。 */
  static fourcc(b: Uint8Array, o: number): string {
    return String.fromCharCode(b[o], b[o + 1], b[o + 2], b[o + 3]);
  }

  static ascii(text: string): Uint8Array {
    const out: Uint8Array = new Uint8Array(text.length);
    for (let i = 0; i < text.length; i++) {
      out[i] = text.charCodeAt(i) & 0xFF;
    }
    return out;
  }

  /** b[o..] 是否以 ASCII 文本 text 开头（越界返回 false）。 */
  static matches(b: Uint8Array, o: number, text: string): boolean {
    if (o < 0 || o + text.length > b.length) {
      return false;
    }
    for (let i = 0; i < text.length; i++) {
      if (b[o + i] !== text.charCodeAt(i)) {
        return false;
      }
    }
    return true;
  }
}

/** 可增长的大端字节缓冲。 */
export class ByteBuilder {
  private buf: Uint8Array;
  private len: number = 0;

  constructor(capacity: number = 256) {
    this.buf = new Uint8Array(capacity < 16 ? 16 : capacity);
  }

  get length(): number {
    return this.len;
  }

  private ensure(extra: number): void {
    const need: number = this.len + extra;
    if (need <= this.buf.length) {
      return;
    }
    let cap: number = this.buf.length * 2;
    while (cap < need) {
      cap *= 2;
    }
    const next: Uint8Array = new Uint8Array(cap);
    next.set(this.buf.subarray(0, this.len));
    this.buf = next;
  }

  u8(v: number): ByteBuilder {
    this.ensure(1);
    this.buf[this.len] = v & 0xFF;
    this.len += 1;
    return this;
  }

  u16(v: number): ByteBuilder {
    this.ensure(2);
    Bin.putU16(this.buf, this.len, v);
    this.len += 2;
    return this;
  }

  u32(v: number): ByteBuilder {
    this.ensure(4);
    Bin.putU32(this.buf, this.len, v);
    this.len += 4;
    return this;
  }

  /** 有符号 32 位（按补码写出）。 */
  i32(v: number): ByteBuilder {
    return this.u32(v < 0 ? v + 4294967296 : v);
  }

  u64(v: number): ByteBuilder {
    const hi: number = Math.floor(v / 4294967296);
    const lo: number = v - hi * 4294967296;
    this.u32(hi);
    this.u32(lo);
    return this;
  }

  zeros(count: number): ByteBuilder {
    this.ensure(count);
    // 新分配的区域本来就是 0，但缓冲区可能被复用过，显式清零。
    this.buf.fill(0, this.len, this.len + count);
    this.len += count;
    return this;
  }

  bytes(data: Uint8Array): ByteBuilder {
    this.ensure(data.length);
    this.buf.set(data, this.len);
    this.len += data.length;
    return this;
  }

  text(value: string): ByteBuilder {
    return this.bytes(Bin.ascii(value));
  }

  /** 覆盖已写入位置的 32 位值（回填 box 大小 / chunk offset）。 */
  patchU32(offset: number, v: number): void {
    Bin.putU32(this.buf, offset, v);
  }

  toBytes(): Uint8Array {
    return this.buf.slice(0, this.len);
  }

  /**
   * 写一个完整 box：size(4) + type(4) + payload。
   */
  box(type: string, payload: Uint8Array): ByteBuilder {
    this.u32(8 + payload.length);
    this.text(type);
    this.bytes(payload);
    return this;
  }
}
