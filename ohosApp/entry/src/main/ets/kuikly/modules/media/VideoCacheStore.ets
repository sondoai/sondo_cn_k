/*
 * 视频磁盘缓存（进程内单例），SDVideoCacheModule 与 SDVideoView 共用。
 * 规则与 Flutter 参考工程的 sondo_video_player_cache 一致：
 *   - 只缓存 ≤ 20MB 的资源：先发 `Range: bytes=0-0` 探测，读 Content-Range 里的总长度（5 秒超时）；
 *     探测不到大小的资源不缓存；
 *   - 最多 300 个对象、69 天过期（按文件 mtime；命中时刷新 mtime，淘汰时删最久未用的）。
 * 目录：<cacheDir>/sondo_video_player_cache/<key>.<ext>，下载中的文件带 .part 后缀，完成后改名。
 *
 * 下载用 @kit.NetworkKit 的 http.requestInStream（流式落盘，不占内存），最多 2 个并发，其余排队。
 */
import { http } from '@kit.NetworkKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { SD, SDLog } from '../../support/SDModuleSupport';
import { MediaSupport } from './MediaSupport';

const TAG: string = 'VideoCacheStore';
const CACHE_DIR: string = 'sondo_video_player_cache';
const PART_SUFFIX: string = '.part';
const MAX_BYTES: number = 20 * 1024 * 1024;
const MAX_OBJECTS: number = 300;
const MAX_AGE_MS: number = 69 * 24 * 60 * 60 * 1000;
const PROBE_TIMEOUT_MS: number = 5000;
const MAX_CONCURRENT: number = 2;
/** .part 文件超过这个时间仍未完成，视为上次进程遗留的垃圾。 */
const STALE_PART_MS: number = 6 * 60 * 60 * 1000;

class CacheEntry {
  path: string = '';
  mtimeMs: number = 0;
}

class DownloadJob {
  url: string;
  finalPath: string;
  partPath: string;
  request: http.HttpRequest | null = null;
  /** 中止正在进行的流式下载（关闭文件、销毁请求）；不在下载阶段时为 null。 */
  abort: (() => void) | null = null;
  cancelled: boolean = false;
  settled: boolean = false;
  started: boolean = false;
  resolvers: Array<(path: string) => void> = [];
  rejecters: Array<(error: Error) => void> = [];

  constructor(url: string, finalPath: string) {
    this.url = url;
    this.finalPath = finalPath;
    this.partPath = `${finalPath}${PART_SUFFIX}`;
  }
}

export class VideoCacheStore {
  private static instance: VideoCacheStore | null = null;

  static get(): VideoCacheStore {
    if (VideoCacheStore.instance === null) {
      VideoCacheStore.instance = new VideoCacheStore();
    }
    return VideoCacheStore.instance;
  }

  private jobs: Map<string, DownloadJob> = new Map();
  private waiting: Array<DownloadJob> = [];
  private active: number = 0;
  private trimmedOnce: boolean = false;
  /** 探测结果为「太大 / 不可缓存」的 URL，本次进程内不再重复探测。 */
  private rejected: Set<string> = new Set();

  private constructor() {
  }

  // ---------------------------------------------------------------------------------------------
  // 路径
  // ---------------------------------------------------------------------------------------------

  private dir(): string {
    return MediaSupport.cacheSubDir(CACHE_DIR);
  }

  /** 缓存 key：sondo_video_<长度>_<两个 32 位 FNV-1a 哈希>。只用于本地文件名。 */
  private static keyFor(url: string): string {
    let h1: number = 0x811C9DC5;
    let h2: number = 0x01000193;
    for (let i = 0; i < url.length; i++) {
      const c: number = url.charCodeAt(i);
      h1 = Math.imul(h1 ^ (c & 0xFF), 0x01000193) >>> 0;
      h1 = Math.imul(h1 ^ (c >>> 8), 0x01000193) >>> 0;
      h2 = Math.imul((h2 ^ c) + i, 0x85EBCA6B) >>> 0;
      h2 = (h2 ^ (h2 >>> 13)) >>> 0;
    }
    return `sondo_video_${url.length}_${VideoCacheStore.hex8(h1)}${VideoCacheStore.hex8(h2)}`;
  }

  private static hex8(value: number): string {
    const text: string = (value >>> 0).toString(16);
    return '00000000'.substring(text.length) + text;
  }

  private pathFor(url: string): string {
    let ext: string = MediaSupport.extensionOf(url);
    if (ext.length === 0) {
      ext = 'mp4';
    }
    return `${this.dir()}/${VideoCacheStore.keyFor(url)}.${ext}`;
  }

  // ---------------------------------------------------------------------------------------------
  // 查询
  // ---------------------------------------------------------------------------------------------

  /** 已完整缓存且未过期则返回本地路径（并刷新最近使用时间），否则返回空串。 */
  resolve(url: string): string {
    if (!MediaSupport.isHttp(url)) {
      return '';
    }
    try {
      this.trimOnce();
      const path: string = this.pathFor(url);
      if (!fs.accessSync(path)) {
        return '';
      }
      const stat: fs.Stat = fs.statSync(path);
      if (stat.size <= 0 || Date.now() - stat.mtime * 1000 > MAX_AGE_MS) {
        MediaSupport.remove(path);
        return '';
      }
      try {
        fs.utimes(path, Date.now());
      } catch (e) {
        // 刷新 mtime 失败不影响命中
      }
      return path;
    } catch (e) {
      SDLog.w(TAG, `resolve failed: ${SD.errMsg(e as Object)}`);
      return '';
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 预取
  // ---------------------------------------------------------------------------------------------

  /**
   * 预取到磁盘缓存。已缓存直接 resolve；同一个 URL 的并发调用共享一次下载。
   * reject 的 message：not_http / too_large / unknown_size / probe_failed / cancelled / http_<code> / 其它错误文本。
   */
  prefetch(url: string): Promise<string> {
    if (!MediaSupport.isHttp(url)) {
      return Promise.reject(new Error('not_http'));
    }
    const cached: string = this.resolve(url);
    if (cached.length > 0) {
      return Promise.resolve(cached);
    }
    if (this.rejected.has(url)) {
      return Promise.reject(new Error('too_large'));
    }
    let job: DownloadJob | undefined = this.jobs.get(url);
    if (job === undefined) {
      let finalPath: string = '';
      try {
        finalPath = this.pathFor(url);
      } catch (e) {
        return Promise.reject(new Error(SD.errMsg(e as Object)));
      }
      job = new DownloadJob(url, finalPath);
      this.jobs.set(url, job);
      this.waiting.push(job);
    }
    const target: DownloadJob = job;
    const promise: Promise<string> = new Promise<string>((resolve, reject) => {
      target.resolvers.push(resolve);
      target.rejecters.push(reject);
    });
    this.pump();
    return promise;
  }

  /** 取消某个 URL 的预取（没有在下载则什么也不做）。 */
  cancel(url: string): void {
    const job: DownloadJob | undefined = this.jobs.get(url);
    if (job === undefined || job.settled) {
      return;
    }
    job.cancelled = true;
    if (!job.started) {
      const index: number = this.waiting.indexOf(job);
      if (index >= 0) {
        this.waiting.splice(index, 1);
      }
      this.settle(job, '', new Error('cancelled'));
      return;
    }
    const abort: (() => void) | null = job.abort;
    const request: http.HttpRequest | null = job.request;
    if (abort !== null) {
      abort();
    } else if (request !== null) {
      try {
        request.destroy();
      } catch (e) {
        SDLog.w(TAG, `destroy failed: ${SD.errMsg(e as Object)}`);
      }
    }
    this.finishActive(job, '', new Error('cancelled'));
  }

  /** 清空缓存并取消所有下载。 */
  clear(): boolean {
    const urls: Array<string> = [];
    this.jobs.forEach((job: DownloadJob, url: string) => {
      urls.push(url);
    });
    for (let i = 0; i < urls.length; i++) {
      this.cancel(urls[i]);
    }
    this.rejected.clear();
    try {
      const dir: string = this.dir();
      const names: Array<string> = fs.listFileSync(dir);
      for (let i = 0; i < names.length; i++) {
        MediaSupport.remove(`${dir}/${names[i]}`);
      }
      return true;
    } catch (e) {
      SDLog.e(TAG, `clear failed: ${SD.errMsg(e as Object)}`);
      return false;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 下载调度
  // ---------------------------------------------------------------------------------------------

  private pump(): void {
    while (this.active < MAX_CONCURRENT && this.waiting.length > 0) {
      const job: DownloadJob | undefined = this.waiting.shift();
      if (job === undefined || job.settled) {
        continue;
      }
      job.started = true;
      this.active += 1;
      this.run(job).then((path: string) => {
        this.finishActive(job, path, null);
      }).catch((e: BusinessError) => {
        this.finishActive(job, '', new Error(job.cancelled ? 'cancelled' : SD.errMsg(e)));
      });
    }
  }

  /** 一个已开始的任务结束（成功 / 失败 / 取消）；只生效一次。 */
  private finishActive(job: DownloadJob, path: string, error: Error | null): void {
    if (job.settled) {
      return;
    }
    this.active = Math.max(0, this.active - 1);
    if (error !== null) {
      MediaSupport.remove(job.partPath);
    }
    this.settle(job, path, error);
    this.pump();
  }

  private settle(job: DownloadJob, path: string, error: Error | null): void {
    if (job.settled) {
      return;
    }
    job.settled = true;
    job.request = null;
    if (this.jobs.get(job.url) === job) {
      this.jobs.delete(job.url);
    }
    const resolvers: Array<(path: string) => void> = job.resolvers;
    const rejecters: Array<(error: Error) => void> = job.rejecters;
    job.resolvers = [];
    job.rejecters = [];
    if (error === null) {
      for (let i = 0; i < resolvers.length; i++) {
        resolvers[i](path);
      }
    } else {
      for (let i = 0; i < rejecters.length; i++) {
        rejecters[i](error);
      }
    }
  }

  private async run(job: DownloadJob): Promise<string> {
    const total: number = await this.probeSize(job);
    if (job.cancelled) {
      throw new Error('cancelled');
    }
    if (total < 0) {
      throw new Error('probe_failed'); // 网络错误：不记入黑名单，下次还可以再试
    }
    if (total === 0) {
      this.rejected.add(job.url);
      throw new Error('unknown_size');
    }
    if (total > MAX_BYTES) {
      this.rejected.add(job.url);
      throw new Error('too_large');
    }
    await this.download(job, total);
    if (job.cancelled) {
      throw new Error('cancelled');
    }
    if (fs.accessSync(job.finalPath)) {
      fs.unlinkSync(job.finalPath);
    }
    fs.renameSync(job.partPath, job.finalPath);
    this.trim();
    return job.finalPath;
  }

  /** `Range: bytes=0-0` 探测资源总大小。服务器没给出大小返回 0；请求本身失败返回 -1。 */
  private async probeSize(job: DownloadJob): Promise<number> {
    const request: http.HttpRequest = http.createHttp();
    job.request = request;
    try {
      const header: Record<string, string> = {};
      header['Range'] = 'bytes=0-0';
      const options: http.HttpRequestOptions = {
        method: http.RequestMethod.GET,
        header: header,
        connectTimeout: PROBE_TIMEOUT_MS,
        readTimeout: PROBE_TIMEOUT_MS,
        expectDataType: http.HttpDataType.ARRAY_BUFFER,
        // 服务器不支持 Range 时会返回整份文件：限制响应体，超限即失败（视为不可缓存）。
        maxLimit: 256 * 1024
      };
      const response: http.HttpResponse = await request.request(job.url, options);
      const code: number = response.responseCode as number;
      const headers: Record<string, string> = VideoCacheStore.lowerCaseHeaders(response.header);
      if (code === 206) {
        // Content-Range: bytes 0-0/12345
        const range: string | undefined = headers['content-range'];
        if (range !== undefined) {
          const slash: number = range.lastIndexOf('/');
          if (slash >= 0) {
            const total: number = Number(range.substring(slash + 1).trim());
            if (Number.isFinite(total) && total > 0) {
              return total;
            }
          }
        }
        return 0;
      }
      if (code === 200) {
        const length: string | undefined = headers['content-length'];
        if (length !== undefined) {
          const total: number = Number(length.trim());
          if (Number.isFinite(total) && total > 0) {
            return total;
          }
        }
      }
      return 0;
    } catch (e) {
      SDLog.d(TAG, `probe failed: ${SD.errMsg(e as Object)}`);
      return -1;
    } finally {
      job.request = null;
      try {
        request.destroy();
      } catch (e) {
        // 忽略
      }
    }
  }

  private static lowerCaseHeaders(header: Object | null | undefined): Record<string, string> {
    const out: Record<string, string> = {};
    if (header === null || header === undefined || typeof header !== 'object') {
      return out;
    }
    const map: Record<string, Object> = header as Record<string, Object>;
    const keys: Array<string> = Object.keys(map);
    for (let i = 0; i < keys.length; i++) {
      const value: Object | null | undefined = map[keys[i]] as Object | null | undefined;
      if (value === null || value === undefined) {
        continue;
      }
      out[keys[i].toLowerCase()] = Array.isArray(value) ? `${(value as Array<Object>)[0]}` : `${value}`;
    }
    return out;
  }

  /** 流式下载到 .part；字节数与探测到的总大小不一致视为失败。 */
  private download(job: DownloadJob, expected: number): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      let file: fs.File | null = null;
      let received: number = 0;
      let ended: boolean = false;
      let code: number = -1;
      let settled: boolean = false;
      const request: http.HttpRequest = http.createHttp();
      job.request = request;

      const finish = (error: Error | null): void => {
        if (settled) {
          return;
        }
        settled = true;
        job.abort = null;
        MediaSupport.closeQuietly(file);
        file = null;
        try {
          request.off('dataReceive');
          request.off('dataEnd');
          request.destroy();
        } catch (e) {
          // 忽略
        }
        if (job.request === request) {
          job.request = null;
        }
        if (error === null) {
          resolve();
        } else {
          reject(error);
        }
      };
      // requestInStream 的 Promise 与 dataEnd 事件先后顺序不固定：两者都到了才算完成。
      const tryComplete = (): void => {
        if (!ended || code < 0) {
          return;
        }
        if (code !== 200) {
          finish(new Error(`http_${code}`));
        } else if (received !== expected) {
          finish(new Error(`size mismatch: ${received}/${expected}`));
        } else {
          finish(null);
        }
      };

      job.abort = (): void => {
        finish(new Error('cancelled'));
      };
      try {
        MediaSupport.remove(job.partPath);
        file = fs.openSync(job.partPath, fs.OpenMode.WRITE_ONLY | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      } catch (e) {
        finish(new Error(SD.errMsg(e as Object)));
        return;
      }
      request.on('dataReceive', (data: ArrayBuffer) => {
        if (settled || file === null) {
          return;
        }
        try {
          fs.writeSync(file.fd, data);
          received += data.byteLength;
        } catch (e) {
          finish(new Error(SD.errMsg(e as Object)));
          return;
        }
        if (received > MAX_BYTES || received > expected) {
          finish(new Error('too_large'));
        }
      });
      request.on('dataEnd', () => {
        ended = true;
        tryComplete();
      });
      const options: http.HttpRequestOptions = {
        method: http.RequestMethod.GET,
        connectTimeout: 15000,
        readTimeout: 5 * 60 * 1000
      };
      request.requestInStream(job.url, options).then((status: number) => {
        code = status;
        if (status !== 200) {
          finish(new Error(`http_${status}`));
          return;
        }
        tryComplete();
      }).catch((e: BusinessError) => {
        finish(new Error(job.cancelled ? 'cancelled' : SD.errMsg(e)));
      });
    });
  }

  // ---------------------------------------------------------------------------------------------
  // 淘汰
  // ---------------------------------------------------------------------------------------------

  private trimOnce(): void {
    if (this.trimmedOnce) {
      return;
    }
    this.trimmedOnce = true;
    this.trim();
  }

  /** 删除过期文件、遗留的 .part，超过 300 个时删最久未用的。 */
  private trim(): void {
    try {
      const dir: string = this.dir();
      const names: Array<string> = fs.listFileSync(dir);
      const now: number = Date.now();
      const entries: Array<CacheEntry> = [];
      for (let i = 0; i < names.length; i++) {
        const path: string = `${dir}/${names[i]}`;
        let mtimeMs: number = 0;
        try {
          mtimeMs = fs.statSync(path).mtime * 1000;
        } catch (e) {
          continue;
        }
        if (names[i].endsWith(PART_SUFFIX)) {
          if (now - mtimeMs > STALE_PART_MS) {
            MediaSupport.remove(path);
          }
          continue;
        }
        if (now - mtimeMs > MAX_AGE_MS) {
          MediaSupport.remove(path);
          continue;
        }
        const entry: CacheEntry = new CacheEntry();
        entry.path = path;
        entry.mtimeMs = mtimeMs;
        entries.push(entry);
      }
      if (entries.length > MAX_OBJECTS) {
        entries.sort((a: CacheEntry, b: CacheEntry): number => a.mtimeMs - b.mtimeMs);
        const excess: number = entries.length - MAX_OBJECTS;
        for (let i = 0; i < excess; i++) {
          MediaSupport.remove(entries[i].path);
        }
      }
    } catch (e) {
      SDLog.w(TAG, `trim failed: ${SD.errMsg(e as Object)}`);
    }
  }
}
