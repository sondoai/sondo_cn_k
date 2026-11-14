/*
 * SDFileModule —— 文件操作（契约见 shared/.../nativeapi/SDAppModule.kt 里的 SDFileModule）。
 * 路径均为应用沙箱内的绝对路径（也接受 file:// URI）。全部异步，使用 fs 的 Promise 接口，不阻塞 UI 线程。
 *
 *   exists {path}                → {ok, exists}
 *   size {path}                  → {ok, size}            不存在为 -1
 *   delete {path}                → {ok}                  文件或目录（递归）；不存在也算成功
 *   copy {path, dest}            → {ok}
 *   rename {path, dest}          → {ok}                  覆盖已存在的目标
 *   readText {path}              → {ok, text}
 *   writeText {path, text}       → {ok}
 *   readHead {path, length}      → {ok, base64}
 *   md5File {path}               → {ok, md5}             流式计算，小写十六进制
 *   copyAsset {asset, destPath}  → {ok}                  asset 为相对 common/ 的路径（hap resfile/common/…）
 *   cacheSize                    → {ok, bytes}           cacheDir + tempDir
 *   clearCache                   → {ok}
 *   list {path}                  → {ok, files:[{name, path, size, isDir, modifiedMs}]}
 */
import { common } from '@kit.AbilityKit';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { fileIo as fs, fileUri } from '@kit.CoreFileKit';
import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { SD, SDHost, SDLog, SDParams, SDReply, SDResult } from '../../support/SDModuleSupport';
import { SDCryptoUtil } from './SDCryptoModule';

const TAG: string = 'SDFileModule';
const MD5_CHUNK_SIZE: number = 256 * 1024;

/** 文件工具（其他模块也可复用）。 */
export class SDFiles {
  /** file:// URI → 沙箱路径；普通路径原样返回。 */
  static toPath(input: string): string {
    if (input.startsWith('file://')) {
      try {
        return new fileUri.FileUri(input).path;
      } catch (e) {
        SDLog.w(TAG, `toPath failed for uri: ${SD.errMsg(e as Object)}`);
      }
    }
    return input;
  }

  static parentDir(path: string): string {
    const index: number = path.lastIndexOf('/');
    return index > 0 ? path.substring(0, index) : '';
  }

  static baseName(path: string): string {
    const index: number = path.lastIndexOf('/');
    return index >= 0 ? path.substring(index + 1) : path;
  }

  static async exists(path: string): Promise<boolean> {
    if (path.length === 0) {
      return false;
    }
    try {
      return await fs.access(path);
    } catch (e) {
      return false;
    }
  }

  /** 递归创建目录（已存在时不报错）。 */
  static async ensureDir(dir: string): Promise<void> {
    if (dir.length === 0) {
      return;
    }
    if (await SDFiles.exists(dir)) {
      return;
    }
    try {
      await fs.mkdir(dir, true);
    } catch (e) {
      // 并发创建时可能已被别人建好
      if (!(await SDFiles.exists(dir))) {
        throw new Error(`mkdir failed: ${SD.errMsg(e as Object)}`);
      }
    }
  }

  static async ensureParentDir(path: string): Promise<void> {
    await SDFiles.ensureDir(SDFiles.parentDir(path));
  }

  /** 删除文件或目录（递归）。不存在时直接返回。 */
  static async remove(path: string): Promise<void> {
    if (!(await SDFiles.exists(path))) {
      return;
    }
    const stat: fs.Stat = await fs.stat(path);
    if (stat.isDirectory()) {
      await fs.rmdir(path);
    } else {
      await fs.unlink(path);
    }
  }

  /** 文件大小；不存在或是目录时返回 -1。 */
  static async fileSize(path: string): Promise<number> {
    try {
      const stat: fs.Stat = await fs.stat(path);
      return stat.isDirectory() ? -1 : stat.size;
    } catch (e) {
      return -1;
    }
  }

  /** 目录占用字节数（递归）。 */
  static async dirSize(dir: string): Promise<number> {
    if (!(await SDFiles.exists(dir))) {
      return 0;
    }
    let total: number = 0;
    let names: Array<string> = [];
    try {
      names = await fs.listFile(dir);
    } catch (e) {
      return 0;
    }
    for (let i = 0; i < names.length; i++) {
      const child: string = `${dir}/${names[i]}`;
      try {
        const stat: fs.Stat = await fs.stat(child);
        if (stat.isDirectory()) {
          total += await SDFiles.dirSize(child);
        } else {
          total += stat.size;
        }
      } catch (e) {
        // 遍历过程中文件被删除，忽略
      }
    }
    return total;
  }

  /** 删除目录下的所有内容（保留目录本身）。 */
  static async clearDir(dir: string): Promise<void> {
    if (!(await SDFiles.exists(dir))) {
      return;
    }
    let names: Array<string> = [];
    try {
      names = await fs.listFile(dir);
    } catch (e) {
      return;
    }
    for (let i = 0; i < names.length; i++) {
      try {
        await SDFiles.remove(`${dir}/${names[i]}`);
      } catch (e) {
        SDLog.w(TAG, `clearDir skip ${names[i]}: ${SD.errMsg(e as Object)}`);
      }
    }
  }

  /**
   * 读取文件的一段到内存。length < 0 表示读到文件末尾。
   * 返回的 ArrayBuffer 长度等于实际读到的字节数。
   */
  static async readRange(path: string, offset: number, length: number): Promise<ArrayBuffer> {
    const file: fs.File = await fs.open(path, fs.OpenMode.READ_ONLY);
    try {
      const stat: fs.Stat = await fs.stat(file.fd);
      const start: number = Math.max(0, offset);
      const available: number = Math.max(0, stat.size - start);
      const want: number = length < 0 ? available : Math.min(length, available);
      const out: ArrayBuffer = new ArrayBuffer(want);
      const view: Uint8Array = new Uint8Array(out);
      let done: number = 0;
      while (done < want) {
        const chunkSize: number = Math.min(want - done, 4 * 1024 * 1024);
        const chunk: ArrayBuffer = new ArrayBuffer(chunkSize);
        const read: number = await fs.read(file.fd, chunk, { offset: start + done, length: chunkSize });
        if (read <= 0) {
          break;
        }
        view.set(new Uint8Array(chunk, 0, read), done);
        done += read;
      }
      return done === want ? out : out.slice(0, done);
    } finally {
      await SDFiles.closeQuietly(file);
    }
  }

  static async md5(path: string): Promise<string> {
    const md: cryptoFramework.Md = cryptoFramework.createMd('MD5');
    const file: fs.File = await fs.open(path, fs.OpenMode.READ_ONLY);
    try {
      const chunk: ArrayBuffer = new ArrayBuffer(MD5_CHUNK_SIZE);
      let position: number = 0;
      while (true) {
        const read: number = await fs.read(file.fd, chunk, { offset: position, length: MD5_CHUNK_SIZE });
        if (read <= 0) {
          break;
        }
        const blob: cryptoFramework.DataBlob = { data: new Uint8Array(chunk.slice(0, read)) };
        await md.update(blob);
        position += read;
      }
      const digest: cryptoFramework.DataBlob = await md.digest();
      return SDCryptoUtil.toHex(digest.data);
    } finally {
      await SDFiles.closeQuietly(file);
    }
  }

  static async closeQuietly(file: fs.File): Promise<void> {
    try {
      await fs.close(file);
    } catch (e) {
      // ignore
    }
  }
}

export class SDFileModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME: string = 'SDFileModule';

  syncMode(): boolean {
    return false;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    const p: SDParams = SD.parse(params);
    const reply: SDReply = new SDReply(callback);
    this.dispatch(method, p)
      .then((result: SDResult) => {
        reply.send(result);
      })
      .catch((e: Error) => {
        reply.fail(SD.errMsg(e), SD.errCode(e));
      });
    return null;
  }

  private async dispatch(method: string, p: SDParams): Promise<SDResult> {
    const path: string = SDFiles.toPath(SD.str(p, 'path'));
    switch (method) {
      case 'exists': {
        const extra: SDResult = {};
        extra['exists'] = await SDFiles.exists(path);
        return SD.ok(extra);
      }
      case 'size': {
        const extra: SDResult = {};
        extra['size'] = await SDFiles.fileSize(path);
        return SD.ok(extra);
      }
      case 'delete':
        this.requirePath(path);
        await SDFiles.remove(path);
        return SD.ok();
      case 'copy': {
        const dest: string = SDFiles.toPath(SD.str(p, 'dest'));
        this.requirePath(path);
        this.requirePath(dest);
        await SDFiles.ensureParentDir(dest);
        await fs.copyFile(path, dest);
        return SD.ok();
      }
      case 'rename': {
        const dest: string = SDFiles.toPath(SD.str(p, 'dest'));
        this.requirePath(path);
        this.requirePath(dest);
        if (path === dest) {
          return SD.ok();
        }
        await SDFiles.ensureParentDir(dest);
        await SDFiles.remove(dest);
        await fs.rename(path, dest);
        return SD.ok();
      }
      case 'readText': {
        this.requirePath(path);
        const extra: SDResult = {};
        extra['text'] = await fs.readText(path);
        return SD.ok(extra);
      }
      case 'writeText':
        this.requirePath(path);
        await this.writeText(path, SD.str(p, 'text'));
        return SD.ok();
      case 'readHead': {
        this.requirePath(path);
        const length: number = Math.max(0, SD.int(p, 'length', 0));
        const data: ArrayBuffer = await SDFiles.readRange(path, 0, length);
        const extra: SDResult = {};
        extra['base64'] = SDCryptoUtil.toBase64(new Uint8Array(data));
        return SD.ok(extra);
      }
      case 'md5File': {
        this.requirePath(path);
        const extra: SDResult = {};
        extra['md5'] = await SDFiles.md5(path);
        return SD.ok(extra);
      }
      case 'copyAsset':
        await this.copyAsset(SD.str(p, 'asset'), SDFiles.toPath(SD.str(p, 'destPath')));
        return SD.ok();
      case 'cacheSize': {
        const extra: SDResult = {};
        extra['bytes'] = await this.cacheSize();
        return SD.ok(extra);
      }
      case 'clearCache':
        await this.clearCache();
        return SD.ok();
      case 'list': {
        this.requirePath(path);
        const extra: SDResult = {};
        extra['files'] = await this.list(path);
        return SD.ok(extra);
      }
      default:
        return SD.fail(`unknown method: ${method}`);
    }
  }

  private requirePath(path: string): void {
    if (path.length === 0) {
      throw new Error('path is empty');
    }
  }

  private async writeText(path: string, text: string): Promise<void> {
    await SDFiles.ensureParentDir(path);
    const file: fs.File = await fs.open(path,
      fs.OpenMode.CREATE | fs.OpenMode.TRUNC | fs.OpenMode.WRITE_ONLY);
    try {
      if (text.length > 0) {
        await fs.write(file.fd, text);
      }
    } finally {
      await SDFiles.closeQuietly(file);
    }
  }

  /**
   * shared 的 assets/common/<asset> 在鸿蒙上位于 hap 的 resfile/common/<asset>
   * （ohos_build.sh 同步），运行时可通过 context.resourceDir 直接按文件访问。
   */
  private async copyAsset(asset: string, destPath: string): Promise<void> {
    if (asset.length === 0 || destPath.length === 0) {
      throw new Error('asset or destPath is empty');
    }
    const context: common.UIAbilityContext = SDHost.requireContext();
    let relative: string = asset;
    while (relative.startsWith('/')) {
      relative = relative.substring(1);
    }
    if (relative.startsWith('common/')) {
      relative = relative.substring('common/'.length);
    }
    const source: string = `${context.resourceDir}/common/${relative}`;
    if (!(await SDFiles.exists(source))) {
      throw new Error(`asset not found: ${relative}`);
    }
    await SDFiles.ensureParentDir(destPath);
    await fs.copyFile(source, destPath);
  }

  private async cacheSize(): Promise<number> {
    const context: common.UIAbilityContext = SDHost.requireContext();
    const cache: number = await SDFiles.dirSize(context.cacheDir);
    const temp: number = await SDFiles.dirSize(context.tempDir);
    return cache + temp;
  }

  private async clearCache(): Promise<void> {
    const context: common.UIAbilityContext = SDHost.requireContext();
    await SDFiles.clearDir(context.cacheDir);
    await SDFiles.clearDir(context.tempDir);
  }

  private async list(dir: string): Promise<Array<SDResult>> {
    const files: Array<SDResult> = [];
    const names: Array<string> = await fs.listFile(dir);
    for (let i = 0; i < names.length; i++) {
      const child: string = `${dir}/${names[i]}`;
      const item: SDResult = {};
      item['name'] = names[i];
      item['path'] = child;
      try {
        const stat: fs.Stat = await fs.stat(child);
        const isDir: boolean = stat.isDirectory();
        item['size'] = isDir ? 0 : stat.size;
        item['isDir'] = isDir;
        item['modifiedMs'] = stat.mtime * 1000;
      } catch (e) {
        item['size'] = 0;
        item['isDir'] = false;
        item['modifiedMs'] = 0;
      }
      files.push(item);
    }
    return files;
  }
}
