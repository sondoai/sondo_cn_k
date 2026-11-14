/*
 * SDHttpModule —— 通用 HTTP（契约见 shared/.../nativeapi/SDHttpModule.kt）。基于 @kit.NetworkKit 的 http。
 *
 *   request {id, method, url, headers, body?, timeoutMs}
 *       → {ok, status, headers, body} | {ok:false, error, errorType:"timeout"|"network"|"cancel"|"other"}
 *       ok 只表示拿到了 HTTP 响应（任意状态码）；传输层失败 ok=false。
 *   cancel {id}                       取消进行中的 request / upload / download（对应调用会收到 errorType="cancel"）
 *   upload {id, method, url, headers, filePath, contentType?, multipart?, fieldName?, fileName?, formFields?,
 *           offset?, length?, timeoutMs}                                   （常驻回调）
 *       → {event:"progress", sent, total} … {event:"done", ok, status, headers, body, error?, errorType?}
 *       非 multipart：把文件（或 [offset, offset+length) 区间）作为请求体直接发送，Content-Length 精确、不分块。
 *   download {id, url, headers?, savePath?, fileName?, timeoutMs}           （常驻回调）
 *       → {event:"progress", received, total} … {event:"done", ok, path, status, error?, errorType?}
 *       先写临时文件，成功（2xx）后原子重命名到 savePath。
 *   probeContentLength {url}          GET Range: bytes=0-0，5 秒超时 → {ok, length}（未知为 -1）
 *
 * 已知限制（http 模块本身的行为）：
 *   - 只支持 GET/HEAD/OPTIONS/DELETE/TRACE/CONNECT/POST/PUT，其它方法（如 PATCH）返回 errorType="other"；
 *   - 只有 POST / PUT 能带请求体，其它方法的 body 会被忽略。
 */
import { common } from '@kit.AbilityKit';
import { buffer } from '@kit.ArkTS';
import { BusinessError } from '@kit.BasicServicesKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { http } from '@kit.NetworkKit';
import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { SD, SDHost, SDLog, SDParams, SDReply, SDResult } from '../../support/SDModuleSupport';
import { SDFiles } from './SDFileModule';

const TAG: string = 'SDHttpModule';

const DEFAULT_TIMEOUT_MS: number = 60 * 1000;
const MAX_CONNECT_TIMEOUT_MS: number = 60 * 1000;
/** request() 允许的最大响应体（http 模块上限 100MB，默认只有 5MB） */
const MAX_RESPONSE_BYTES: number = 100 * 1024 * 1024;
/** 非 multipart 上传需要把请求体读入内存，超过此大小直接拒绝（大文件请走分片） */
const MAX_UPLOAD_BODY_BYTES: number = 512 * 1024 * 1024;
const PROGRESS_INTERVAL_MS: number = 100;
const PROBE_TIMEOUT_MS: number = 5 * 1000;

const ERROR_TIMEOUT: string = 'timeout';
const ERROR_NETWORK: string = 'network';
const ERROR_CANCEL: string = 'cancel';
const ERROR_OTHER: string = 'other';

/** 一个进行中的请求。 */
class HttpTask {
  id: string;
  owner: Object;
  request: http.HttpRequest;
  cancelled: boolean = false;
  /** upload / download（页面销毁时取消）；普通 request 为 false */
  transfer: boolean = false;
  /** 取消时交付结果（由发起方设置） */
  onCancel: (() => void) | null = null;

  constructor(id: string, owner: Object, request: http.HttpRequest) {
    this.id = id;
    this.owner = owner;
    this.request = request;
  }
}

/** 常驻回调的事件发送器：progress 任意次，done 仅一次，done 之后不再发送任何事件。 */
class StreamEmitter {
  private callback: KuiklyRenderCallback | null;
  private finished: boolean = false;
  private lastProgressAt: number = 0;

  constructor(callback: KuiklyRenderCallback | null) {
    this.callback = callback;
  }

  get isFinished(): boolean {
    return this.finished;
  }

  /** 进度（按 PROGRESS_INTERVAL_MS 节流；force=true 时强制发送）。 */
  progress(firstKey: string, value: number, total: number, force: boolean): void {
    if (this.finished) {
      return;
    }
    const now: number = Date.now();
    if (!force && now - this.lastProgressAt < PROGRESS_INTERVAL_MS) {
      return;
    }
    this.lastProgressAt = now;
    const event: SDResult = {};
    event['event'] = 'progress';
    event[firstKey] = value;
    event['total'] = total;
    SD.send(this.callback, event);
  }

  done(result: SDResult): void {
    if (this.finished) {
      return;
    }
    this.finished = true;
    result['event'] = 'done';
    const cb: KuiklyRenderCallback | null = this.callback;
    this.callback = null;
    SD.send(cb, result);
  }
}

export class SDHttpModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME: string = 'SDHttpModule';

  /** 进行中的请求（id → task），所有页面共享，cancel 可以跨页面生效 */
  private static tasks: Map<string, HttpTask> = new Map();
  private static anonymousSeq: number = 0;
  private static tempSeq: number = 0;

  syncMode(): boolean {
    return false;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    const p: SDParams = SD.parse(params);
    switch (method) {
      case 'request':
        this.request(p, callback);
        break;
      case 'cancel':
        SDHttpModule.cancelTask(SD.str(p, 'id'));
        SD.send(callback, SD.ok());
        break;
      case 'upload':
        this.upload(p, callback);
        break;
      case 'download':
        this.download(p, callback);
        break;
      case 'probeContentLength':
        this.probeContentLength(SD.str(p, 'url'), callback);
        break;
      default:
        SD.send(callback, SDHttpModule.failure(ERROR_OTHER, `unknown method: ${method}`));
        break;
    }
    return null;
  }

  /**
   * 页面销毁：只取消它发起且仍在进行的上传 / 下载（与 Android / iOS 一致）。
   * 普通 request 让它跑完，只是结果不再有人接收——已经发出的写操作不应因为用户离开页面而中断。
   */
  onDestroy(): void {
    const mine: Array<string> = [];
    SDHttpModule.tasks.forEach((task: HttpTask, id: string) => {
      if (task.owner === this && task.transfer) {
        mine.push(id);
      }
    });
    for (let i = 0; i < mine.length; i++) {
      SDHttpModule.cancelTask(mine[i]);
    }
  }

  // -----------------------------------------------------------------------
  // request
  // -----------------------------------------------------------------------

  private request(p: SDParams, callback: KuiklyRenderCallback | null): void {
    const reply: SDReply = new SDReply(callback);
    const url: string = SD.str(p, 'url');
    const methodName: string = SD.str(p, 'method', 'GET').toUpperCase();
    const method: http.RequestMethod | null = SDHttpModule.toMethod(methodName);
    if (url.length === 0) {
      reply.send(SDHttpModule.failure(ERROR_OTHER, 'url is empty'));
      return;
    }
    if (method === null) {
      reply.send(SDHttpModule.failure(ERROR_OTHER, `HTTP method ${methodName} is not supported on HarmonyOS`));
      return;
    }
    const task: HttpTask | null = this.createTask(SD.str(p, 'id'));
    if (task === null) {
      reply.send(SDHttpModule.failure(ERROR_OTHER, 'create http request failed'));
      return;
    }
    task.onCancel = () => {
      reply.send(SDHttpModule.failure(ERROR_CANCEL, 'cancelled'));
    };

    const timeoutMs: number = SDHttpModule.timeoutOf(p);
    const options: http.HttpRequestOptions = {
      method: method,
      header: SD.strMap(p, 'headers'),
      expectDataType: http.HttpDataType.STRING,
      usingCache: false,
      connectTimeout: Math.min(timeoutMs, MAX_CONNECT_TIMEOUT_MS),
      readTimeout: timeoutMs,
      maxLimit: MAX_RESPONSE_BYTES
    };
    if (SD.has(p, 'body') && SDHttpModule.methodHasBody(methodName)) {
      const body: string = SD.str(p, 'body');
      if (body.length > 0) {
        options.extraData = body;
      }
    }

    task.request.request(url, options)
      .then((response: http.HttpResponse) => {
        SDHttpModule.releaseTask(task);
        reply.send(SDHttpModule.responseResult(response));
      })
      .catch((e: BusinessError) => {
        SDHttpModule.releaseTask(task);
        reply.send(task.cancelled ? SDHttpModule.failure(ERROR_CANCEL, 'cancelled') : SDHttpModule.failureOf(e));
      });
  }

  // -----------------------------------------------------------------------
  // upload
  // -----------------------------------------------------------------------

  private upload(p: SDParams, callback: KuiklyRenderCallback | null): void {
    const emitter: StreamEmitter = new StreamEmitter(callback);
    this.runUpload(p, emitter).catch((e: Error) => {
      emitter.done(SDHttpModule.failureOf(e));
    });
  }

  private async runUpload(p: SDParams, emitter: StreamEmitter): Promise<void> {
    const url: string = SD.str(p, 'url');
    const methodName: string = SD.str(p, 'method', 'PUT').toUpperCase();
    const filePath: string = SDFiles.toPath(SD.str(p, 'filePath'));
    if (url.length === 0 || filePath.length === 0) {
      emitter.done(SDHttpModule.failure(ERROR_OTHER, 'url or filePath is empty'));
      return;
    }
    if (methodName !== 'PUT' && methodName !== 'POST') {
      emitter.done(SDHttpModule.failure(ERROR_OTHER, `upload method ${methodName} is not supported`));
      return;
    }
    const fileSize: number = await SDFiles.fileSize(filePath);
    if (fileSize < 0) {
      emitter.done(SDHttpModule.failure(ERROR_OTHER, `file not found: ${filePath}`));
      return;
    }

    const multipart: boolean = SD.bool(p, 'multipart', false);
    const contentType: string = SD.str(p, 'contentType');
    const hasRange: boolean = SD.has(p, 'offset') || SD.has(p, 'length');
    const offset: number = Math.max(0, SD.int(p, 'offset', 0));
    const length: number = SD.has(p, 'length') ? Math.max(0, SD.int(p, 'length', 0)) : Math.max(0, fileSize - offset);
    const bodySize: number = Math.min(length, Math.max(0, fileSize - offset));
    let headers: Record<string, string> = SD.strMap(p, 'headers');
    const timeoutMs: number = SDHttpModule.timeoutOf(p);

    const options: http.HttpRequestOptions = {
      method: methodName === 'POST' ? http.RequestMethod.POST : http.RequestMethod.PUT,
      expectDataType: http.HttpDataType.STRING,
      usingCache: false,
      connectTimeout: Math.min(timeoutMs, MAX_CONNECT_TIMEOUT_MS),
      readTimeout: timeoutMs,
      maxLimit: MAX_RESPONSE_BYTES
    };

    if (multipart) {
      // multipart/form-data：普通字段在前，文件在最后
      const parts: Array<http.MultiFormData> = [];
      const fields: Record<string, string> = SD.strMap(p, 'formFields');
      const fieldKeys: Array<string> = Object.keys(fields);
      for (let i = 0; i < fieldKeys.length; i++) {
        const fieldPart: http.MultiFormData = {
          name: fieldKeys[i],
          contentType: 'text/plain',
          data: fields[fieldKeys[i]]
        };
        parts.push(fieldPart);
      }
      const filePart: http.MultiFormData = {
        name: SD.str(p, 'fieldName', 'file'),
        contentType: contentType.length > 0 ? contentType : 'application/octet-stream',
        remoteFileName: SD.str(p, 'fileName', SDFiles.baseName(filePath))
      };
      if (hasRange) {
        SDHttpModule.checkBodySize(bodySize);
        filePart.data = await SDFiles.readRange(filePath, offset, bodySize);
      } else {
        filePart.filePath = filePath;
      }
      parts.push(filePart);
      headers = SDHttpModule.withHeader(headers, 'Content-Type', 'multipart/form-data');
      options.multiFormDataList = parts;
    } else {
      // 请求体 = 文件字节（整文件或区间），以 ArrayBuffer 发送，Content-Length 精确
      SDHttpModule.checkBodySize(bodySize);
      const body: ArrayBuffer = await SDFiles.readRange(filePath, offset, bodySize);
      if (body.byteLength !== bodySize) {
        emitter.done(SDHttpModule.failure(ERROR_OTHER, `read file failed: expect ${bodySize} bytes, got ${body.byteLength}`));
        return;
      }
      if (!SDHttpModule.hasHeader(headers, 'content-type')) {
        headers = SDHttpModule.withHeader(headers, 'Content-Type',
          contentType.length > 0 ? contentType : 'application/octet-stream');
      }
      if (body.byteLength > 0) {
        options.extraData = body;
      }
    }
    options.header = headers;

    if (emitter.isFinished) {
      return;
    }
    const task: HttpTask | null = this.createTask(SD.str(p, 'id'), true);
    if (task === null) {
      emitter.done(SDHttpModule.failure(ERROR_OTHER, 'create http request failed'));
      return;
    }
    task.onCancel = () => {
      emitter.done(SDHttpModule.failure(ERROR_CANCEL, 'cancelled'));
    };
    let lastTotal: number = bodySize;
    try {
      task.request.on('dataSendProgress', (info: http.DataSendProgressInfo) => {
        if (info.totalSize > 0) {
          lastTotal = info.totalSize;
        }
        emitter.progress('sent', info.sendSize, lastTotal, false);
      });
    } catch (e) {
      SDLog.w(TAG, `listen dataSendProgress failed: ${SD.errMsg(e as Object)}`);
    }

    try {
      const response: http.HttpResponse = await task.request.request(url, options);
      SDHttpModule.releaseTask(task);
      const status: number = response.responseCode;
      if (status >= 200 && status < 300) {
        emitter.progress('sent', lastTotal, lastTotal, true);
      }
      emitter.done(SDHttpModule.responseResult(response));
    } catch (e) {
      SDHttpModule.releaseTask(task);
      emitter.done(task.cancelled
        ? SDHttpModule.failure(ERROR_CANCEL, 'cancelled')
        : SDHttpModule.failureOf(e as BusinessError));
    }
  }

  // -----------------------------------------------------------------------
  // download
  // -----------------------------------------------------------------------

  private download(p: SDParams, callback: KuiklyRenderCallback | null): void {
    const emitter: StreamEmitter = new StreamEmitter(callback);
    this.runDownload(p, emitter).catch((e: Error) => {
      emitter.done(SDHttpModule.failureOf(e));
    });
  }

  private async runDownload(p: SDParams, emitter: StreamEmitter): Promise<void> {
    const url: string = SD.str(p, 'url');
    if (url.length === 0) {
      emitter.done(SDHttpModule.failure(ERROR_OTHER, 'url is empty'));
      return;
    }
    const savePath: string = this.resolveSavePath(SD.str(p, 'savePath'), SD.str(p, 'fileName'), url);
    SDHttpModule.tempSeq += 1;
    const tempPath: string = `${savePath}.${Date.now()}_${SDHttpModule.tempSeq}.tmp`;
    await SDFiles.ensureParentDir(savePath);

    const file: fs.File = await fs.open(tempPath,
      fs.OpenMode.CREATE | fs.OpenMode.TRUNC | fs.OpenMode.WRITE_ONLY);
    let fileOpen: boolean = true;
    let received: number = 0;
    let total: number = -1;
    let writeError: string = '';
    let settled: boolean = false;

    const closeFile: () => void = () => {
      if (!fileOpen) {
        return;
      }
      fileOpen = false;
      try {
        fs.closeSync(file);
      } catch (e) {
        SDLog.w(TAG, `close temp file failed: ${SD.errMsg(e as Object)}`);
      }
    };
    const discardTemp: () => void = () => {
      closeFile();
      fs.unlink(tempPath).catch((e: BusinessError) => {
        // 临时文件可能尚未创建成功，忽略
      });
    };

    const task: HttpTask | null = this.createTask(SD.str(p, 'id'), true);
    if (task === null) {
      discardTemp();
      emitter.done(SDHttpModule.failure(ERROR_OTHER, 'create http request failed'));
      return;
    }
    task.onCancel = () => {
      settled = true;
      discardTemp();
      emitter.done(SDHttpModule.failure(ERROR_CANCEL, 'cancelled'));
    };

    const timeoutMs: number = SDHttpModule.timeoutOf(p);
    const options: http.HttpRequestOptions = {
      method: http.RequestMethod.GET,
      header: SD.strMap(p, 'headers'),
      usingCache: false,
      connectTimeout: Math.min(timeoutMs, MAX_CONNECT_TIMEOUT_MS),
      readTimeout: timeoutMs
    };

    // 数据按到达顺序同步写盘（异步写无法保证顺序）；dataEnd 表示所有数据已投递
    let dataEnded: boolean = false;
    let resolveDataEnd: () => void = () => {
    };
    const dataEndPromise: Promise<void> = new Promise<void>((resolve) => {
      resolveDataEnd = () => {
        resolve();
      };
    });
    task.request.on('dataReceive', (data: ArrayBuffer) => {
      if (!fileOpen || settled || writeError.length > 0) {
        return;
      }
      try {
        fs.writeSync(file.fd, data);
        received += data.byteLength;
        emitter.progress('received', received, total, false);
      } catch (e) {
        // 磁盘写失败（空间不足等）：立即交付失败并中止请求，不依赖中止后的回调
        writeError = SD.errMsg(e as Object);
        settled = true;
        discardTemp();
        const writeFailure: SDResult = SDHttpModule.failure(ERROR_OTHER, `write file failed: ${writeError}`);
        writeFailure['path'] = '';
        emitter.done(writeFailure);
        SDHttpModule.releaseTask(task);
      }
    });
    task.request.on('dataReceiveProgress', (info: http.DataReceiveProgressInfo) => {
      if (info.totalSize > 0) {
        total = info.totalSize;
      }
    });
    task.request.on('dataEnd', () => {
      dataEnded = true;
      resolveDataEnd();
    });

    let status: number = 0;
    let failure: SDResult | null = null;
    try {
      status = await task.request.requestInStream(url, options);
      if (!dataEnded) {
        // 响应码先于最后一批数据回调到达时，等 dataEnd（最多 2 秒兜底）
        await Promise.race([dataEndPromise, SDHttpModule.delay(2000)]);
      }
    } catch (e) {
      failure = task.cancelled
        ? SDHttpModule.failure(ERROR_CANCEL, 'cancelled')
        : SDHttpModule.failureOf(e as BusinessError);
    }
    SDHttpModule.releaseTask(task);
    if (settled || emitter.isFinished) {
      return;
    }
    settled = true;
    closeFile();

    if (failure === null && writeError.length > 0) {
      failure = SDHttpModule.failure(ERROR_OTHER, `write file failed: ${writeError}`);
    }
    if (failure === null && (status < 200 || status >= 300)) {
      failure = SDHttpModule.failure(ERROR_OTHER, `HTTP ${status}`);
    }
    if (failure !== null) {
      discardTemp();
      failure['status'] = status;
      failure['path'] = '';
      emitter.done(failure);
      return;
    }

    try {
      await SDFiles.remove(savePath);
      await fs.rename(tempPath, savePath);
    } catch (e) {
      discardTemp();
      const renameFailure: SDResult = SDHttpModule.failure(ERROR_OTHER, `save file failed: ${SD.errMsg(e as Object)}`);
      renameFailure['status'] = status;
      renameFailure['path'] = '';
      emitter.done(renameFailure);
      return;
    }
    emitter.progress('received', received, total > 0 ? total : received, true);
    const result: SDResult = SD.ok();
    result['status'] = status;
    result['path'] = savePath;
    emitter.done(result);
  }

  /** savePath 为空时存入缓存目录：<cacheDir>/<fileName | URL 最后一段 | download_时间戳>。 */
  private resolveSavePath(savePath: string, fileName: string, url: string): string {
    if (savePath.length > 0) {
      return SDFiles.toPath(savePath);
    }
    const context: common.UIAbilityContext = SDHost.requireContext();
    let name: string = fileName;
    if (name.length === 0) {
      let path: string = url;
      const query: number = path.indexOf('?');
      if (query >= 0) {
        path = path.substring(0, query);
      }
      const hash: number = path.indexOf('#');
      if (hash >= 0) {
        path = path.substring(0, hash);
      }
      name = SDFiles.baseName(path);
    }
    if (name.length === 0 || name.length > 120) {
      name = `download_${Date.now()}`;
    }
    return `${context.cacheDir}/${name}`;
  }

  // -----------------------------------------------------------------------
  // probeContentLength
  // -----------------------------------------------------------------------

  private probeContentLength(url: string, callback: KuiklyRenderCallback | null): void {
    const reply: SDReply = new SDReply(callback);
    const settle: (length: number) => void = (length: number) => {
      const extra: SDResult = {};
      extra['length'] = length;
      reply.ok(extra);
    };
    if (url.length === 0) {
      settle(-1);
      return;
    }
    const created: http.HttpRequest | null = SDHttpModule.newRequest();
    if (created === null) {
      settle(-1);
      return;
    }
    const probe: http.HttpRequest = created;
    let contentLength: number = -1;
    const timer: number = setTimeout(() => {
      settle(-1);
      SDHttpModule.destroyQuietly(probe);
    }, PROBE_TIMEOUT_MS);
    const finish: (length: number) => void = (length: number) => {
      clearTimeout(timer);
      settle(length);
      SDHttpModule.destroyQuietly(probe);
    };
    probe.on('headersReceive', (header: Object) => {
      // 206：Content-Range: bytes 0-0/<total>，拿到总长度即可结束，不必等响应体
      const range: string = SDHttpModule.headerValue(header as Record<string, Object>, 'content-range');
      const slash: number = range.lastIndexOf('/');
      if (slash >= 0) {
        const totalText: string = range.substring(slash + 1).trim();
        const totalValue: number = Number(totalText);
        if (totalText.length > 0 && Number.isFinite(totalValue) && totalValue >= 0) {
          finish(totalValue);
          return;
        }
      }
      const lengthText: string = SDHttpModule.headerValue(header as Record<string, Object>, 'content-length');
      const lengthValue: number = Number(lengthText);
      if (lengthText.length > 0 && Number.isFinite(lengthValue) && lengthValue >= 0) {
        contentLength = lengthValue;
      }
    });
    const rangeHeader: Record<string, string> = {};
    rangeHeader['Range'] = 'bytes=0-0';
    const options: http.HttpRequestOptions = {
      method: http.RequestMethod.GET,
      header: rangeHeader,
      usingCache: false,
      connectTimeout: PROBE_TIMEOUT_MS,
      readTimeout: PROBE_TIMEOUT_MS
    };
    probe.requestInStream(url, options)
      .then((status: number) => {
        // 服务器忽略 Range（200）：Content-Length 就是总长度
        finish(status === 200 ? contentLength : -1);
      })
      .catch((e: BusinessError) => {
        finish(-1);
      });
  }

  // -----------------------------------------------------------------------
  // 任务管理
  // -----------------------------------------------------------------------

  private createTask(id: string, transfer: boolean = false): HttpTask | null {
    const request: http.HttpRequest | null = SDHttpModule.newRequest();
    if (request === null) {
      return null;
    }
    let taskId: string = id;
    if (taskId.length === 0) {
      SDHttpModule.anonymousSeq += 1;
      taskId = `anonymous_${SDHttpModule.anonymousSeq}`;
    }
    const task: HttpTask = new HttpTask(taskId, this, request);
    task.transfer = transfer;
    // 同 id 的旧任务视为被新任务顶替
    const previous: HttpTask | undefined = SDHttpModule.tasks.get(taskId);
    if (previous !== undefined) {
      SDHttpModule.cancelTask(taskId);
    }
    SDHttpModule.tasks.set(taskId, task);
    return task;
  }

  private static newRequest(): http.HttpRequest | null {
    try {
      return http.createHttp();
    } catch (e) {
      SDLog.e(TAG, `createHttp failed: ${SD.errMsg(e as Object)}`);
      return null;
    }
  }

  /** 请求结束：移出任务表并释放 HttpRequest。 */
  private static releaseTask(task: HttpTask): void {
    if (SDHttpModule.tasks.get(task.id) === task) {
      SDHttpModule.tasks.delete(task.id);
    }
    task.onCancel = null;
    SDHttpModule.destroyQuietly(task.request);
  }

  private static cancelTask(id: string): void {
    if (id.length === 0) {
      return;
    }
    const task: HttpTask | undefined = SDHttpModule.tasks.get(id);
    if (task === undefined) {
      return;
    }
    SDHttpModule.tasks.delete(id);
    task.cancelled = true;
    const onCancel: (() => void) | null = task.onCancel;
    task.onCancel = null;
    // 先交付取消结果（保证调用方一定收到回调），再中止底层请求
    if (onCancel !== null) {
      try {
        onCancel();
      } catch (e) {
        SDLog.e(TAG, `cancel callback failed: ${SD.errMsg(e as Object)}`);
      }
    }
    SDHttpModule.destroyQuietly(task.request);
  }

  private static destroyQuietly(request: http.HttpRequest): void {
    try {
      request.destroy();
    } catch (e) {
      // ignore
    }
  }

  // -----------------------------------------------------------------------
  // 工具
  // -----------------------------------------------------------------------

  private static delay(ms: number): Promise<void> {
    return new Promise<void>((resolve) => {
      setTimeout(() => {
        resolve();
      }, ms);
    });
  }

  private static timeoutOf(p: SDParams): number {
    const timeoutMs: number = SD.int(p, 'timeoutMs', DEFAULT_TIMEOUT_MS);
    return timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
  }

  private static checkBodySize(size: number): void {
    if (size > MAX_UPLOAD_BODY_BYTES) {
      throw new Error(`upload body too large (${size} bytes), use ranged upload`);
    }
  }

  private static toMethod(name: string): http.RequestMethod | null {
    switch (name) {
      case 'GET':
        return http.RequestMethod.GET;
      case 'POST':
        return http.RequestMethod.POST;
      case 'PUT':
        return http.RequestMethod.PUT;
      case 'DELETE':
        return http.RequestMethod.DELETE;
      case 'HEAD':
        return http.RequestMethod.HEAD;
      case 'OPTIONS':
        return http.RequestMethod.OPTIONS;
      case 'TRACE':
        return http.RequestMethod.TRACE;
      case 'CONNECT':
        return http.RequestMethod.CONNECT;
      default:
        return null;
    }
  }

  private static methodHasBody(name: string): boolean {
    return name === 'POST' || name === 'PUT';
  }

  /** 大小写不敏感地取 header 值。 */
  private static headerValue(headers: Record<string, Object> | null | undefined, name: string): string {
    if (headers === null || headers === undefined) {
      return '';
    }
    const target: string = name.toLowerCase();
    const keys: Array<string> = Object.keys(headers);
    for (let i = 0; i < keys.length; i++) {
      if (keys[i].toLowerCase() === target) {
        return SDHttpModule.headerText(headers[keys[i]] as Object | null | undefined);
      }
    }
    return '';
  }

  /** 请求头里是否已有（非空的）某个 header，大小写不敏感。 */
  private static hasHeader(headers: Record<string, string>, name: string): boolean {
    const target: string = name.toLowerCase();
    const keys: Array<string> = Object.keys(headers);
    for (let i = 0; i < keys.length; i++) {
      if (keys[i].toLowerCase() === target && headers[keys[i]].length > 0) {
        return true;
      }
    }
    return false;
  }

  /** 返回设置了 name=value 的新请求头（去掉大小写不同的同名项）。 */
  private static withHeader(headers: Record<string, string>, name: string, value: string): Record<string, string> {
    const out: Record<string, string> = {};
    const target: string = name.toLowerCase();
    const keys: Array<string> = Object.keys(headers);
    for (let i = 0; i < keys.length; i++) {
      if (keys[i].toLowerCase() !== target) {
        out[keys[i]] = headers[keys[i]];
      }
    }
    out[name] = value;
    return out;
  }

  private static headerText(value: Object | null | undefined): string {
    if (value === null || value === undefined) {
      return '';
    }
    if (typeof value === 'string') {
      return value as string;
    }
    if (Array.isArray(value)) {
      const parts: Array<string> = [];
      const list: Array<Object> = value as Array<Object>;
      for (let i = 0; i < list.length; i++) {
        parts.push(`${list[i]}`);
      }
      return parts.join(', ');
    }
    return `${value}`;
  }

  private static headersResult(header: Object | null | undefined): SDResult {
    const out: SDResult = {};
    if (header === null || header === undefined || typeof header !== 'object') {
      return out;
    }
    const source: Record<string, Object> = header as Record<string, Object>;
    const keys: Array<string> = Object.keys(source);
    for (let i = 0; i < keys.length; i++) {
      out[keys[i]] = SDHttpModule.headerText(source[keys[i]] as Object | null | undefined);
    }
    return out;
  }

  private static bodyText(result: string | Object | ArrayBuffer | null | undefined): string {
    if (result === null || result === undefined) {
      return '';
    }
    if (typeof result === 'string') {
      return result as string;
    }
    if (result instanceof ArrayBuffer) {
      return buffer.from(result as ArrayBuffer).toString('utf-8');
    }
    try {
      return JSON.stringify(result);
    } catch (e) {
      return '';
    }
  }

  private static responseResult(response: http.HttpResponse): SDResult {
    const result: SDResult = SD.ok();
    result['status'] = response.responseCode;
    result['headers'] = SDHttpModule.headersResult(response.header);
    result['body'] = SDHttpModule.bodyText(response.result);
    return result;
  }

  private static failure(errorType: string, message: string): SDResult {
    const result: SDResult = SD.fail(message);
    result['errorType'] = errorType;
    result['status'] = 0;
    result['headers'] = SDHttpModule.headersResult(null);
    result['body'] = '';
    return result;
  }

  /** 传输层错误 → errorType。http 模块的错误码 = 2300000 + libcurl 错误码。 */
  private static failureOf(e: Object | null | undefined): SDResult {
    const code: number = SD.errCode(e);
    let errorType: string = ERROR_OTHER;
    if (code === 2300028) {
      errorType = ERROR_TIMEOUT;
    } else if (code === 2300005 || code === 2300006 || code === 2300007 || code === 2300035 ||
      code === 2300052 || code === 2300055 || code === 2300056 || code === 2300016 || code === 2300018) {
      // 代理 / DNS / 连接 / TLS 握手 / 空响应 / 发送 / 接收 / HTTP2 / 传输中断
      errorType = ERROR_NETWORK;
    }
    const result: SDResult = SDHttpModule.failure(errorType, SD.errMsg(e));
    if (code !== 0) {
      result['code'] = code;
    }
    return result;
  }
}
