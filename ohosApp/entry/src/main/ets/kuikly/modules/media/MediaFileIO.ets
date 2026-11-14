/*
 * codec/ 里纯算法用的 ByteSource / ByteSink 的文件实现（@kit.CoreFileKit）。
 * 主线程和 TaskPool 线程都可以用；用完必须 close()。
 */
import { fileIo as fs, ReadOptions } from '@kit.CoreFileKit';
import { ByteSink, ByteSource } from './codec/ByteIO';

export class FileSource implements ByteSource {
  private file: fs.File;
  private total: number = 0;
  private closed: boolean = false;

  /** @param pathOrUri 沙箱路径或 file:// URI */
  constructor(pathOrUri: string) {
    this.file = fs.openSync(pathOrUri, fs.OpenMode.READ_ONLY);
    try {
      this.total = fs.statSync(this.file.fd).size;
    } catch (e) {
      fs.closeSync(this.file);
      throw new Error(`stat failed: ${pathOrUri}`);
    }
  }

  size(): number {
    return this.total;
  }

  read(offset: number, length: number): Uint8Array {
    if (offset < 0 || length < 0 || offset + length > this.total) {
      throw new Error(`read out of range: ${offset}+${length} > ${this.total}`);
    }
    const buffer: ArrayBuffer = new ArrayBuffer(length);
    const out: Uint8Array = new Uint8Array(buffer);
    if (length === 0) {
      return out;
    }
    const first: ReadOptions = { offset: offset, length: length };
    let done: number = fs.readSync(this.file.fd, buffer, first);
    // readSync 可能少读（大块读取时），补齐剩余部分。
    while (done < length) {
      const rest: ArrayBuffer = new ArrayBuffer(length - done);
      const options: ReadOptions = { offset: offset + done, length: length - done };
      const n: number = fs.readSync(this.file.fd, rest, options);
      if (n <= 0) {
        throw new Error('unexpected end of file');
      }
      out.set(new Uint8Array(rest, 0, n), done);
      done += n;
    }
    return out;
  }

  close(): void {
    if (this.closed) {
      return;
    }
    this.closed = true;
    try {
      fs.closeSync(this.file);
    } catch (e) {
      // 忽略
    }
  }
}

export class FileSink implements ByteSink {
  private file: fs.File;
  private written: number = 0;
  private closed: boolean = false;
  readonly path: string;

  /** 以「创建 + 截断」方式打开 path。 */
  constructor(path: string) {
    this.path = path;
    this.file = fs.openSync(path, fs.OpenMode.WRITE_ONLY | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
  }

  write(bytes: Uint8Array): void {
    const length: number = bytes.byteLength;
    if (length === 0) {
      return;
    }
    // writeSync 只能从 ArrayBuffer 的起点写，子视图需要先拷贝成独立缓冲。
    let buffer: ArrayBuffer = bytes.buffer as ArrayBuffer;
    if (bytes.byteOffset !== 0 || length !== buffer.byteLength) {
      buffer = bytes.slice().buffer as ArrayBuffer;
    }
    let done: number = fs.writeSync(this.file.fd, buffer);
    while (done < length) {
      const rest: ArrayBuffer = buffer.slice(done);
      const n: number = fs.writeSync(this.file.fd, rest);
      if (n <= 0) {
        throw new Error('write failed (disk full?)');
      }
      done += n;
    }
    this.written += length;
  }

  position(): number {
    return this.written;
  }

  close(): void {
    if (this.closed) {
      return;
    }
    this.closed = true;
    try {
      fs.closeSync(this.file);
    } catch (e) {
      // 忽略
    }
  }

  /** 关闭并删除（任务失败 / 取消时用）。 */
  discard(): void {
    this.close();
    try {
      fs.unlinkSync(this.path);
    } catch (e) {
      // 忽略
    }
  }
}
