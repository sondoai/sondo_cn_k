/*
 * 媒体模块 / 原生视图共用的小工具：上下文、路径与 URI、临时目录、shared assets 定位。
 */
import { common } from '@kit.AbilityKit';
import { fileIo as fs, fileUri } from '@kit.CoreFileKit';
import { SD, SDHost, SDLog } from '../../support/SDModuleSupport';

const TAG: string = 'MediaSupport';

export class MediaSupport {
  private static sequence: number = 0;

  /** 当前 UIAbilityContext；Ability 还没创建时为 null。 */
  static context(): common.UIAbilityContext | null {
    return SDHost.context();
  }

  static isHttp(uri: string): boolean {
    const lower: string = uri.substring(0, 8).toLowerCase();
    return lower.startsWith('http://') || lower.startsWith('https://');
  }

  /** 媒体库 / 文档等只能通过 URI 打开的地址。 */
  static isProviderUri(uri: string): boolean {
    return uri.startsWith('file://media/') || uri.startsWith('file://docs/') || uri.startsWith('datashare://');
  }

  /**
   * 把 Kuikly 传来的本地地址变成 fs 能直接使用的形式：
   *  - 沙箱绝对路径：原样返回
   *  - file://<bundle>/data/storage/... 或 file:///data/storage/...：转成沙箱路径
   *  - file://media/...、file://docs/...：原样返回（fs.open 支持 URI）
   */
  static localPath(pathOrUri: string): string {
    if (!pathOrUri.startsWith('file://') || MediaSupport.isProviderUri(pathOrUri)) {
      return pathOrUri;
    }
    try {
      const path: string = new fileUri.FileUri(pathOrUri).path;
      if (path.length > 0) {
        return path;
      }
    } catch (e) {
      SDLog.w(TAG, `bad file uri: ${SD.errMsg(e as Object)}`);
    }
    return pathOrUri;
  }

  /**
   * shared assets 的相对路径 → 沙箱里的绝对路径。
   * Kuikly 把 shared/src/commonMain/assets/common 拷到 entry/src/main/resources/resfile/common，
   * 运行时位于 context.resourceDir/common。接受 "lottie/a.json"、"common/lottie/a.json"、"assets://common/lottie/a.json"。
   * 文件不存在返回空串。
   */
  static assetPath(relative: string): string {
    const ctx: common.UIAbilityContext | null = MediaSupport.context();
    if (ctx === null) {
      return '';
    }
    let rel: string = relative;
    if (rel.startsWith('assets://')) {
      rel = rel.substring(9);
    }
    while (rel.startsWith('/')) {
      rel = rel.substring(1);
    }
    if (rel.length === 0 || rel.indexOf('..') >= 0) {
      return '';
    }
    const base: string = ctx.resourceDir;
    const candidates: Array<string> = [`${base}/common/${rel}`, `${base}/${rel}`];
    for (let i = 0; i < candidates.length; i++) {
      if (MediaSupport.exists(candidates[i])) {
        return candidates[i];
      }
    }
    return '';
  }

  static exists(path: string): boolean {
    try {
      return fs.accessSync(path);
    } catch (e) {
      return false;
    }
  }

  static fileSize(path: string): number {
    try {
      return fs.statSync(path).size;
    } catch (e) {
      return -1;
    }
  }

  static remove(path: string): void {
    if (path.length === 0) {
      return;
    }
    try {
      if (fs.accessSync(path)) {
        fs.unlinkSync(path);
      }
    } catch (e) {
      SDLog.w(TAG, `remove ${path} failed: ${SD.errMsg(e as Object)}`);
    }
  }

  /** 确保目录存在；失败返回 false。 */
  static ensureDir(dir: string): boolean {
    try {
      if (!fs.accessSync(dir)) {
        fs.mkdirSync(dir, true);
      }
      return true;
    } catch (e) {
      SDLog.e(TAG, `mkdir ${dir} failed: ${SD.errMsg(e as Object)}`);
      return false;
    }
  }

  /** 确保文件所在目录存在。 */
  static ensureParent(path: string): boolean {
    const index: number = path.lastIndexOf('/');
    if (index <= 0) {
      return true;
    }
    return MediaSupport.ensureDir(path.substring(0, index));
  }

  /**
   * App 缓存目录下的子目录（如 "sd_pick"）；拿不到上下文或建目录失败时抛 Error。
   */
  static cacheSubDir(name: string): string {
    const ctx: common.UIAbilityContext | null = MediaSupport.context();
    if (ctx === null) {
      throw new Error('context unavailable');
    }
    const dir: string = `${ctx.cacheDir}/${name}`;
    if (!MediaSupport.ensureDir(dir)) {
      throw new Error(`cannot create ${dir}`);
    }
    return dir;
  }

  /** 生成不会重名的临时文件路径：<cache>/<dir>/<prefix>_<时间戳>_<序号>.<ext> */
  static tempFile(dir: string, prefix: string, extension: string): string {
    MediaSupport.sequence = (MediaSupport.sequence + 1) % 1000000;
    const ext: string = extension.length > 0 ? `.${extension}` : '';
    return `${MediaSupport.cacheSubDir(dir)}/${prefix}_${Date.now()}_${MediaSupport.sequence}${ext}`;
  }

  /** 取小写扩展名（不含点）；没有返回空串。 */
  static extensionOf(name: string): string {
    let clean: string = name;
    const query: number = clean.indexOf('?');
    if (query >= 0) {
      clean = clean.substring(0, query);
    }
    const slash: number = clean.lastIndexOf('/');
    const dot: number = clean.lastIndexOf('.');
    if (dot < 0 || dot < slash || dot === clean.length - 1) {
      return '';
    }
    const ext: string = clean.substring(dot + 1).toLowerCase();
    return ext.length <= 8 ? ext : '';
  }

  /** 路径 / URI 的最后一段（已做 URI 解码）。 */
  static fileNameOf(pathOrUri: string): string {
    if (pathOrUri.startsWith('file://')) {
      try {
        const name: string = new fileUri.FileUri(pathOrUri).name;
        if (name.length > 0) {
          return name;
        }
      } catch (e) {
        // 落到下面的字符串切分
      }
    }
    const index: number = pathOrUri.lastIndexOf('/');
    const raw: string = index >= 0 ? pathOrUri.substring(index + 1) : pathOrUri;
    try {
      return decodeURIComponent(raw);
    } catch (e) {
      return raw;
    }
  }

  /**
   * 把任意可读地址（沙箱路径 / file URI / 媒体库 URI / 文档 URI）拷贝到 dest（沙箱路径）。
   * 返回拷贝的字节数。
   */
  static async copyToSandbox(srcPathOrUri: string, dest: string): Promise<number> {
    const src: fs.File = fs.openSync(MediaSupport.localPath(srcPathOrUri), fs.OpenMode.READ_ONLY);
    try {
      MediaSupport.ensureParent(dest);
      MediaSupport.remove(dest);
      await fs.copyFile(src.fd, dest);
    } finally {
      MediaSupport.closeQuietly(src);
    }
    return MediaSupport.fileSize(dest);
  }

  /** 把沙箱文件写入一个已由系统创建好的目标 URI（文件选择器 / 媒体库返回的 URI）。 */
  static async copyToUri(srcPath: string, destUri: string): Promise<void> {
    const src: fs.File = fs.openSync(MediaSupport.localPath(srcPath), fs.OpenMode.READ_ONLY);
    let dst: fs.File | null = null;
    try {
      dst = fs.openSync(destUri, fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
      await fs.copyFile(src.fd, dst.fd);
    } finally {
      MediaSupport.closeQuietly(src);
      if (dst !== null) {
        MediaSupport.closeQuietly(dst);
      }
    }
  }

  static closeQuietly(file: fs.File | null): void {
    if (file === null) {
      return;
    }
    try {
      fs.closeSync(file);
    } catch (e) {
      SDLog.w(TAG, `close failed: ${SD.errMsg(e as Object)}`);
    }
  }
}
