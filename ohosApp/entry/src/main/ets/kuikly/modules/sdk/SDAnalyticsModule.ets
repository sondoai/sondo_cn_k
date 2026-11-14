/*
 * SDAnalyticsModule —— 友盟统计（契约：shared/.../nativeapi/SDBizModules.kt 的 SDAnalyticsModule）。
 *
 *   - init {appKey, channel}        隐私同意后才会被调用；幂等。之前到达的埋点一律丢弃（不缓存、不上报）
 *   - onEvent {name, props?}        props 的值只允许 String / Number（Boolean 转 0/1，其余转字符串）
 *   - onPageStart / onPageEnd {name}
 *   - signIn {userId} / signOff
 *
 * 契约里这些方法都不取回参；带了 callback 时统一回 {ok}（未初始化回 {ok:false, error:"not initialized"}）。
 * SDK 调用集中在 analytics/UmengAdapter.ets。没有同步方法，异步模式即可。
 */
import { common } from '@kit.AbilityKit';
import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { SD, SDHost, SDLog, SDParams, SDReply } from '../../support/SDModuleSupport';
import { UmengAdapter, UmengConfigCheck } from './analytics/UmengAdapter';

const TAG: string = 'SDAnalyticsModule';
const MAX_STRING_VALUE: number = 256;

export class SDAnalyticsModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME: string = 'SDAnalyticsModule';

  /** 应用级：友盟只初始化一次。 */
  private static inited: boolean = false;

  syncMode(): boolean {
    return false;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    const reply: SDReply = new SDReply(callback);
    try {
      const p: SDParams = SD.parse(params);
      if (method === 'init') {
        SDAnalyticsModule.init(p, reply);
        return null;
      }
      if (!SDAnalyticsModule.isKnown(method)) {
        SDLog.w(TAG, `unknown method: ${method}`);
        reply.fail(`unknown method: ${method}`);
        return null;
      }
      if (!SDAnalyticsModule.inited) {
        // 隐私同意之前（或初始化失败）：什么都不做
        reply.fail('not initialized');
        return null;
      }
      switch (method) {
        case 'onEvent':
          SDAnalyticsModule.onEvent(p);
          break;
        case 'onPageStart':
          SDAnalyticsModule.onPage(p, true);
          break;
        case 'onPageEnd':
          SDAnalyticsModule.onPage(p, false);
          break;
        case 'signIn':
          SDAnalyticsModule.signIn(p);
          break;
        case 'signOff':
          UmengAdapter.signOff();
          break;
        default:
          break;
      }
      reply.ok();
    } catch (e) {
      // 统计失败不能影响业务
      SDLog.w(TAG, `${method} failed: ${SD.errMsg(e as Object)}`);
      reply.fail(SD.errMsg(e as Object));
    }
    return null;
  }

  private static isKnown(method: string): boolean {
    return method === 'onEvent' || method === 'onPageStart' || method === 'onPageEnd' ||
      method === 'signIn' || method === 'signOff';
  }

  private static init(p: SDParams, reply: SDReply): void {
    if (SDAnalyticsModule.inited) {
      reply.ok();
      return;
    }
    const context: common.UIAbilityContext | null = SDHost.context();
    if (context === null) {
      reply.fail('UIAbilityContext unavailable');
      return;
    }
    const appContext: common.ApplicationContext = context.getApplicationContext();
    SDAnalyticsModule.checkConfig(appContext, SD.str(p, 'appKey'), SD.str(p, 'channel'));
    UmengAdapter.start(appContext).catch((e: Object) => {
      SDLog.e(TAG, `umeng init failed: ${SD.errMsg(e)}`);
    });
    SDAnalyticsModule.inited = true;
    SDLog.i(TAG, 'umeng initialized');
    reply.ok();
  }

  /**
   * 友盟鸿蒙 SDK 从 rawfile/umconfig.json 读 AppKey / 渠道，Kuikly 传入的值只用来核对。
   * 只打日志，不拦截初始化。
   */
  private static checkConfig(appContext: common.ApplicationContext, appKey: string, channel: string): void {
    const cfg: UmengConfigCheck = UmengAdapter.readConfig(appContext);
    if (!cfg.found) {
      SDLog.e(TAG, 'rawfile/umconfig.json not found or invalid: umeng will have no appKey (see modules/sdk/README.md)');
      return;
    }
    if (appKey.length > 0 && cfg.appKey !== appKey) {
      SDLog.e(TAG, 'umconfig.json appKey differs from the appKey passed by shared code; umconfig.json wins');
    }
    if (channel.length > 0 && cfg.channel !== channel) {
      SDLog.w(TAG, `umconfig.json channel "${cfg.channel}" differs from "${channel}"; umconfig.json wins`);
    }
  }

  private static onEvent(p: SDParams): void {
    const name: string = SD.str(p, 'name');
    if (name.length === 0) {
      return;
    }
    UmengAdapter.event(name, SDAnalyticsModule.sanitizeProps(SD.obj(p, 'props')));
  }

  private static onPage(p: SDParams, start: boolean): void {
    const name: string = SD.str(p, 'name');
    if (name.length === 0) {
      return;
    }
    const reported: boolean = start ? UmengAdapter.pageStart(name) : UmengAdapter.pageEnd(name);
    if (!reported) {
      SDLog.d(TAG, `${start ? 'onPageStart' : 'onPageEnd'}(${name}) skipped: page API not enabled on HarmonyOS`);
    }
  }

  private static signIn(p: SDParams): void {
    const userId: string = SD.str(p, 'userId');
    if (userId.length === 0) {
      return;
    }
    UmengAdapter.signIn(userId);
  }

  /** 值只保留 String（截断到 256）/ Number；Boolean → 0/1；其余 → JSON 文本。没有有效属性时返回 null。 */
  private static sanitizeProps(src: SDParams): Record<string, string | number> | null {
    const out: Record<string, string | number> = {};
    let count: number = 0;
    const keys: Array<string> = Object.keys(src);
    for (let i = 0; i < keys.length; i++) {
      const k: string = keys[i];
      const v: Object | null | undefined = src[k] as Object | null | undefined;
      if (v === null || v === undefined) {
        continue;
      }
      if (typeof v === 'string') {
        out[k] = SDAnalyticsModule.clip(v as string);
      } else if (typeof v === 'number') {
        if (!Number.isFinite(v as number)) {
          continue;
        }
        out[k] = v as number;
      } else if (typeof v === 'boolean') {
        out[k] = (v as boolean) ? 1 : 0;
      } else {
        let text: string = '';
        try {
          text = JSON.stringify(v);
        } catch (e) {
          text = '';
        }
        out[k] = SDAnalyticsModule.clip(text);
      }
      count++;
    }
    return count > 0 ? out : null;
  }

  private static clip(text: string): string {
    return text.length > MAX_STRING_VALUE ? text.substring(0, MAX_STRING_VALUE) : text;
  }
}
