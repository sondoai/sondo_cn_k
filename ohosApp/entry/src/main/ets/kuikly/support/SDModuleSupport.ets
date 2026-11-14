/*
 * Sondo 原生模块公共支撑。所有 SD*Module 都基于这里的工具：
 *
 *  - SD.parse(params)            把 Kuikly 传来的唯一入参（JSON 字符串 / null）解析成 Record<string, Object>，永不抛异常
 *  - SD.str / num / bool / obj / arr / strMap / strList   带默认值的安全取值
 *  - SD.ok(extra) / SD.fail(error, code)                   异步回参构造：{ok, error?, code?, ...}
 *  - SDReply                                              包一层 callback，保证「只回调一次」
 *  - EventBroadcaster                                     常驻回调（addEventListener / listen）广播器
 *  - SDHost                                               当前 UIAbilityContext / WindowStage / 主窗口 / UIContext
 *  - SDLog                                                hilog 封装
 *
 * 线程说明：Kuikly 鸿蒙端所有 ArkTS 模块的 call() 都在 ArkTS 主线程执行
 * （同步调用时 Kuikly 线程阻塞等待），因此模块内可以直接使用 UI / 窗口 API，
 * 但同步方法里禁止做耗时操作。
 */
import { common } from '@kit.AbilityKit';
import { UIContext, window } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { KRAny, KuiklyRenderCallback } from '@kuikly-open/render';

/** 模块入参（JSON 对象）。 */
export type SDParams = Record<string, Object>;

/** 模块回参 / 事件（JSON 对象）。 */
export type SDResult = Record<string, Object>;

const LOG_DOMAIN: number = 0x5D00;

export class SDLog {
  static d(tag: string, msg: string): void {
    hilog.debug(LOG_DOMAIN, tag, '%{public}s', msg);
  }

  static i(tag: string, msg: string): void {
    hilog.info(LOG_DOMAIN, tag, '%{public}s', msg);
  }

  static w(tag: string, msg: string): void {
    hilog.warn(LOG_DOMAIN, tag, '%{public}s', msg);
  }

  static e(tag: string, msg: string): void {
    hilog.error(LOG_DOMAIN, tag, '%{public}s', msg);
  }
}

export class SD {
  /**
   * 解析模块入参。Kuikly 侧约定只传一个 JSON 字符串（或 null）；
   * 这里同时兼容已经是对象的情况，任何异常都返回空对象。
   */
  static parse(params: KRAny): SDParams {
    const empty: SDParams = {};
    if (params === null || params === undefined) {
      return empty;
    }
    if (typeof params === 'string') {
      const text: string = params as string;
      if (text.length === 0) {
        return empty;
      }
      return SD.parseJson(text);
    }
    if (typeof params === 'object' && !Array.isArray(params) && !(params instanceof ArrayBuffer)) {
      return params as SDParams;
    }
    return empty;
  }

  /** 解析任意 JSON 文本为对象；不是对象（数组 / 基本类型 / 非法 JSON）时返回空对象。 */
  static parseJson(text: string): SDParams {
    const empty: SDParams = {};
    try {
      const parsed: Object | null = JSON.parse(text) as Object | null;
      if (parsed !== null && parsed !== undefined && typeof parsed === 'object' && !Array.isArray(parsed)) {
        return parsed as SDParams;
      }
    } catch (e) {
      SDLog.w('SD', `parseJson failed: ${SD.errMsg(e as Object)}`);
    }
    return empty;
  }

  static has(p: SDParams, key: string): boolean {
    const v: Object | null | undefined = p[key] as Object | null | undefined;
    return v !== null && v !== undefined;
  }

  static str(p: SDParams, key: string, def: string = ''): string {
    const v: Object | null | undefined = p[key] as Object | null | undefined;
    if (v === null || v === undefined) {
      return def;
    }
    if (typeof v === 'string') {
      return v as string;
    }
    if (typeof v === 'number' || typeof v === 'boolean') {
      return `${v}`;
    }
    return def;
  }

  static num(p: SDParams, key: string, def: number = 0): number {
    const v: Object | null | undefined = p[key] as Object | null | undefined;
    if (v === null || v === undefined) {
      return def;
    }
    if (typeof v === 'number') {
      const n: number = v as number;
      return Number.isFinite(n) ? n : def;
    }
    if (typeof v === 'string') {
      const n: number = Number(v as string);
      return Number.isFinite(n) && (v as string).length > 0 ? n : def;
    }
    if (typeof v === 'boolean') {
      return (v as boolean) ? 1 : 0;
    }
    return def;
  }

  static int(p: SDParams, key: string, def: number = 0): number {
    return Math.trunc(SD.num(p, key, def));
  }

  static bool(p: SDParams, key: string, def: boolean = false): boolean {
    const v: Object | null | undefined = p[key] as Object | null | undefined;
    if (v === null || v === undefined) {
      return def;
    }
    if (typeof v === 'boolean') {
      return v as boolean;
    }
    if (typeof v === 'number') {
      return (v as number) !== 0;
    }
    if (typeof v === 'string') {
      const s: string = (v as string).toLowerCase();
      if (s === 'true' || s === '1') {
        return true;
      }
      if (s === 'false' || s === '0' || s === '') {
        return false;
      }
    }
    return def;
  }

  /** 取嵌套对象；值是 JSON 字符串时也会解析。取不到返回空对象。 */
  static obj(p: SDParams, key: string): SDParams {
    const empty: SDParams = {};
    const v: Object | null | undefined = p[key] as Object | null | undefined;
    if (v === null || v === undefined) {
      return empty;
    }
    if (typeof v === 'string') {
      return SD.parseJson(v as string);
    }
    if (typeof v === 'object' && !Array.isArray(v)) {
      return v as SDParams;
    }
    return empty;
  }

  /** 取数组；取不到返回空数组。 */
  static arr(p: SDParams, key: string): Array<Object> {
    const v: Object | null | undefined = p[key] as Object | null | undefined;
    if (v !== null && v !== undefined && Array.isArray(v)) {
      return v as Array<Object>;
    }
    return [];
  }

  /** 取字符串数组（非字符串元素被丢弃）。 */
  static strList(p: SDParams, key: string): Array<string> {
    const out: Array<string> = [];
    const list: Array<Object> = SD.arr(p, key);
    for (let i = 0; i < list.length; i++) {
      const item: Object | null | undefined = list[i] as Object | null | undefined;
      if (typeof item === 'string') {
        out.push(item as string);
      } else if (typeof item === 'number') {
        out.push(`${item}`);
      }
    }
    return out;
  }

  /** 取 {key: string} 形态的对象（如 HTTP headers），非字符串值转成字符串。 */
  static strMap(p: SDParams, key: string): Record<string, string> {
    const out: Record<string, string> = {};
    const src: SDParams = SD.obj(p, key);
    const keys: Array<string> = Object.keys(src);
    for (let i = 0; i < keys.length; i++) {
      const k: string = keys[i];
      const v: Object | null | undefined = src[k] as Object | null | undefined;
      if (v === null || v === undefined) {
        continue;
      }
      if (typeof v === 'string') {
        out[k] = v as string;
      } else if (typeof v === 'number' || typeof v === 'boolean') {
        out[k] = `${v}`;
      } else {
        out[k] = JSON.stringify(v);
      }
    }
    return out;
  }

  /** {ok:true, ...extra} */
  static ok(extra?: SDResult): SDResult {
    const r: SDResult = {};
    r['ok'] = true;
    if (extra !== undefined && extra !== null) {
      SD.merge(r, extra);
    }
    return r;
  }

  /** {ok:false, error, code?, ...extra} */
  static fail(error: string, code?: string | number, extra?: SDResult): SDResult {
    const r: SDResult = {};
    r['ok'] = false;
    r['error'] = error;
    if (code !== undefined && code !== null) {
      r['code'] = code;
    }
    if (extra !== undefined && extra !== null) {
      SD.merge(r, extra);
    }
    return r;
  }

  /** {event: name, ...extra} —— 常驻回调里的事件对象。 */
  static event(name: string, extra?: SDResult): SDResult {
    const r: SDResult = {};
    r['event'] = name;
    if (extra !== undefined && extra !== null) {
      SD.merge(r, extra);
    }
    return r;
  }

  static merge(target: SDResult, extra: SDResult): void {
    const keys: Array<string> = Object.keys(extra);
    for (let i = 0; i < keys.length; i++) {
      const k: string = keys[i];
      const v: Object | null | undefined = extra[k] as Object | null | undefined;
      if (v !== null && v !== undefined) {
        target[k] = v;
      }
    }
  }

  /** 把任意异常转成可读文本（BusinessError 带 code）。 */
  static errMsg(e: Object | null | undefined): string {
    if (e === null || e === undefined) {
      return 'unknown error';
    }
    if (typeof e === 'string') {
      return e as string;
    }
    const be: BusinessError = e as BusinessError;
    const msg: string | undefined = be.message;
    const code: number | undefined = be.code;
    if (msg !== undefined && msg !== null && msg.length > 0) {
      return code !== undefined && code !== null ? `${msg} (${code})` : msg;
    }
    if (code !== undefined && code !== null) {
      return `error code ${code}`;
    }
    try {
      return JSON.stringify(e);
    } catch (err) {
      return 'unknown error';
    }
  }

  /** BusinessError.code，没有时返回 0。 */
  static errCode(e: Object | null | undefined): number {
    if (e === null || e === undefined || typeof e !== 'object') {
      return 0;
    }
    const code: number | undefined = (e as BusinessError).code;
    return typeof code === 'number' ? code : 0;
  }

  /** 同步方法的布尔回参："1" / "0"。 */
  static flag(value: boolean): string {
    return value ? '1' : '0';
  }

  /** 同步方法的 JSON 回参。 */
  static json(value: SDResult): string {
    try {
      return JSON.stringify(value);
    } catch (e) {
      return '{}';
    }
  }

  /** 安全地回调（callback 可能为 null；回调内部异常不外抛）。 */
  static send(callback: KuiklyRenderCallback | null | undefined, result: SDResult): void {
    if (callback === null || callback === undefined) {
      return;
    }
    try {
      callback(result);
    } catch (e) {
      SDLog.e('SD', `callback failed: ${SD.errMsg(e as Object)}`);
    }
  }
}

/**
 * 一次性回参：无论走了多少条分支，callback 只会被调用一次。
 *
 *   const reply = new SDReply(callback);
 *   doAsync().then(() => reply.ok()).catch((e: BusinessError) => reply.fail(SD.errMsg(e)));
 */
export class SDReply {
  private callback: KuiklyRenderCallback | null;
  private done: boolean = false;

  constructor(callback: KuiklyRenderCallback | null | undefined) {
    this.callback = callback === undefined ? null : callback;
  }

  get finished(): boolean {
    return this.done;
  }

  send(result: SDResult): void {
    if (this.done) {
      return;
    }
    this.done = true;
    const cb: KuiklyRenderCallback | null = this.callback;
    this.callback = null;
    SD.send(cb, result);
  }

  ok(extra?: SDResult): void {
    this.send(SD.ok(extra));
  }

  fail(error: string, code?: string | number, extra?: SDResult): void {
    this.send(SD.fail(error, code, extra));
  }
}

class BroadcastEntry {
  owner: Object;
  callback: KuiklyRenderCallback;

  constructor(owner: Object, callback: KuiklyRenderCallback) {
    this.owner = owner;
    this.callback = callback;
  }
}

/**
 * 常驻回调广播器。
 *
 * Kuikly 的模块实例是「每个页面一份」，而应用级事件（网络变化、前后台、支付结果、推送…）
 * 要广播给所有页面，所以典型用法是：模块里放一个 static 广播器，
 * 每个页面的 addEventListener 把自己的常驻 callback 挂上去，页面销毁（onDestroy）时摘掉。
 *
 *   export class SDFooModule extends KuiklyRenderBaseModule {
 *     private static readonly events: EventBroadcaster = new EventBroadcaster();
 *     call(method, params, callback) {
 *       if (method === 'addEventListener') { SDFooModule.events.add(this, callback); return null; }
 *       ...
 *     }
 *     onDestroy(): void { SDFooModule.events.removeOwner(this); }
 *   }
 *   // 任意位置：SDFooModule.events.emit(SD.event('something', extra));
 */
export class EventBroadcaster {
  private entries: Array<BroadcastEntry> = [];

  /** 注册常驻回调。owner 一般传模块实例（this），用于销毁时批量移除。 */
  add(owner: Object, callback: KuiklyRenderCallback | null | undefined): void {
    if (callback === null || callback === undefined) {
      return;
    }
    this.entries.push(new BroadcastEntry(owner, callback));
  }

  /** 移除某个 owner 注册的全部回调（模块 onDestroy 时调用）。 */
  removeOwner(owner: Object): void {
    this.entries = this.entries.filter((entry: BroadcastEntry) => entry.owner !== owner);
  }

  remove(callback: KuiklyRenderCallback): void {
    this.entries = this.entries.filter((entry: BroadcastEntry) => entry.callback !== callback);
  }

  clear(): void {
    this.entries = [];
  }

  get size(): number {
    return this.entries.length;
  }

  hasOwner(owner: Object): boolean {
    for (let i = 0; i < this.entries.length; i++) {
      if (this.entries[i].owner === owner) {
        return true;
      }
    }
    return false;
  }

  /** 向所有已注册回调广播。 */
  emit(event: SDResult): void {
    const snapshot: Array<BroadcastEntry> = this.entries.slice();
    for (let i = 0; i < snapshot.length; i++) {
      SD.send(snapshot[i].callback, event);
    }
  }

  /** 只发给某个 owner（如冷启动深链补发给刚注册的页面）。 */
  emitTo(owner: Object, event: SDResult): void {
    const snapshot: Array<BroadcastEntry> = this.entries.slice();
    for (let i = 0; i < snapshot.length; i++) {
      if (snapshot[i].owner === owner) {
        SD.send(snapshot[i].callback, event);
      }
    }
  }
}

/**
 * 宿主环境：EntryAbility 在 onCreate / onWindowStageCreate 时写入，模块随处读取。
 */
export class SDHost {
  private static abilityContext: common.UIAbilityContext | null = null;
  private static stage: window.WindowStage | null = null;
  private static mainWin: window.Window | null = null;

  static attachAbility(context: common.UIAbilityContext): void {
    SDHost.abilityContext = context;
  }

  static attachWindowStage(stage: window.WindowStage): void {
    SDHost.stage = stage;
    try {
      SDHost.mainWin = stage.getMainWindowSync();
    } catch (e) {
      SDHost.mainWin = null;
      SDLog.e('SDHost', `getMainWindowSync failed: ${SD.errMsg(e as Object)}`);
    }
  }

  static detachWindowStage(): void {
    SDHost.stage = null;
    SDHost.mainWin = null;
  }

  /** 当前 UIAbilityContext（Ability 未创建时为 null）。 */
  static context(): common.UIAbilityContext | null {
    return SDHost.abilityContext;
  }

  /** 当前 UIAbilityContext；拿不到时抛 Error（调用方 try/catch 后回 fail）。 */
  static requireContext(): common.UIAbilityContext {
    const ctx: common.UIAbilityContext | null = SDHost.abilityContext;
    if (ctx === null) {
      throw new Error('UIAbilityContext unavailable');
    }
    return ctx;
  }

  static windowStage(): window.WindowStage | null {
    return SDHost.stage;
  }

  /** 应用主窗口（窗口未创建时为 null）。 */
  static mainWindow(): window.Window | null {
    return SDHost.mainWin;
  }

  /** 主窗口的 UIContext（px/vp 换算、promptAction、router 等）。 */
  static uiContext(): UIContext | null {
    const win: window.Window | null = SDHost.mainWin;
    if (win === null) {
      return null;
    }
    try {
      return win.getUIContext();
    } catch (e) {
      return null;
    }
  }

  /** px → vp（拿不到 UIContext 时按 1:1）。 */
  static px2vp(px: number): number {
    const ui: UIContext | null = SDHost.uiContext();
    if (ui === null) {
      return px;
    }
    return ui.px2vp(px);
  }
}
