/*
 * SDAuthModule —— 登录相关三方 SDK（契约：shared/.../nativeapi/SDBizModules.kt 的 SDAuthModule）。
 *
 * 微信授权（见 auth/WechatManager.ets、auth/WechatAdapter.ets）：
 *   - wechatRegister {appId, universalLink}   → {ok}               universalLink 仅 iOS 需要，鸿蒙忽略
 *   - wechatIsInstalled（同步）                → "1" / "0"
 *   - wechatAuth {scope, state}               → {ok, launched}     结果经事件 {event:"wechatAuthResp", errCode, code?, state?}
 *
 * 极光一键登录（见 auth/JVerifyAdapter.ets）—— 鸿蒙端当前不接入，统一回「不支持」，Kuikly 侧退回短信 / 微信登录：
 *   - jverifySetup       → {ok:true, code:-1}
 *   - jverifyCheckEnable → {ok:true, enable:false}
 *   - jverifyPreLogin    → {ok:true, code:-1}
 *   - jverifyLoginAuth   → {ok:true, action:"unsupported"}
 *   - jverifyDismiss     空操作
 *   本模块按 JVerifyAdapter 接口编排（未初始化保护、超时兜底、只回调一次），换成真实 SDK 适配器后无需改这里。
 *
 * 隐私：任何 SDK 都只在对应的 register / setup 被调用之后才会被触碰。
 */
import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { AppEvents } from '../../support/AppEvents';
import { SD, SDLog, SDParams, SDReply, SDResult } from '../../support/SDModuleSupport';
import {
  JVerifyAction,
  JVerifyAdapter,
  JVerifyCode,
  JVerifyLoginOptions,
  JVerifyLoginResult,
  JVerifyPrivacyItem,
  JVerifyProvider
} from './auth/JVerifyAdapter';
import { WechatManager } from './auth/WechatManager';

const TAG: string = 'SDAuthModule';
/** sendReq 的 Promise 迟迟不返回时的兜底。 */
const WECHAT_LAUNCH_WATCHDOG_MS: number = 8000;
const JVERIFY_CHECK_WATCHDOG_MS: number = 5000;
const JVERIFY_WATCHDOG_MARGIN_MS: number = 2000;

export class SDAuthModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME: string = 'SDAuthModule';

  /** jverifySetup 是否成功过（应用级，所有页面共享）。 */
  private static jverifyReady: boolean = false;
  private static jverifyLoginInFlight: boolean = false;

  /** 有同步方法（wechatIsInstalled），必须是同步模式。 */
  syncMode(): boolean {
    return true;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    if (method === 'wechatIsInstalled') {
      return SD.flag(WechatManager.isInstalled());
    }
    if (method === 'addEventListener') {
      WechatManager.events.add(this, callback);
      return null;
    }
    const reply: SDReply = new SDReply(callback);
    try {
      this.dispatch(method, SD.parse(params), reply);
    } catch (e) {
      SDLog.e(TAG, `${method} failed: ${SD.errMsg(e as Object)}`);
      reply.fail(SD.errMsg(e as Object));
    }
    return null;
  }

  onDestroy(): void {
    WechatManager.events.removeOwner(this);
  }

  private dispatch(method: string, p: SDParams, reply: SDReply): void {
    switch (method) {
      case 'wechatRegister':
        WechatManager.register(SD.str(p, 'appId'));
        reply.ok();
        break;
      case 'wechatAuth':
        this.wechatAuth(p, reply);
        break;
      case 'jverifySetup':
        this.jverifySetup(p, reply);
        break;
      case 'jverifyCheckEnable':
        this.jverifyCheckEnable(reply);
        break;
      case 'jverifyPreLogin':
        this.jverifyPreLogin(p, reply);
        break;
      case 'jverifyLoginAuth':
        this.jverifyLoginAuth(p, reply);
        break;
      case 'jverifyDismiss':
        this.jverifyDismiss(reply);
        break;
      default:
        SDLog.w(TAG, `unknown method: ${method}`);
        reply.fail(`unknown method: ${method}`);
        break;
    }
  }

  // ---------------------------------------------------------------------
  // 微信
  // ---------------------------------------------------------------------

  private wechatAuth(p: SDParams, reply: SDReply): void {
    let scope: string = SD.str(p, 'scope');
    if (scope.length === 0) {
      scope = 'snsapi_userinfo';
    }
    const state: string = SD.str(p, 'state');

    const timer: number = setTimeout(() => {
      // Promise 没返回：应用已经退到后台说明微信其实已被拉起，否则按失败处理
      if (AppEvents.isForeground()) {
        reply.fail('timeout', undefined, SDAuthModule.launched(false));
      } else {
        reply.ok(SDAuthModule.launched(true));
      }
    }, WECHAT_LAUNCH_WATCHDOG_MS);

    WechatManager.auth(scope, state).then((ok: boolean) => {
      clearTimeout(timer);
      reply.ok(SDAuthModule.launched(ok));
    }).catch((e: Object) => {
      clearTimeout(timer);
      SDLog.e(TAG, `wechatAuth failed: ${SD.errMsg(e)}`);
      reply.fail(SD.errMsg(e), undefined, SDAuthModule.launched(false));
    });
  }

  private static launched(value: boolean): SDResult {
    const r: SDResult = {};
    r['launched'] = value;
    return r;
  }

  // ---------------------------------------------------------------------
  // 极光一键登录
  // ---------------------------------------------------------------------

  private jverifySetup(p: SDParams, reply: SDReply): void {
    const adapter: JVerifyAdapter = JVerifyProvider.get();
    if (!adapter.isSupported()) {
      reply.ok(SDAuthModule.codeOf(JVerifyCode.UNSUPPORTED));
      return;
    }
    const appKey: string = SD.str(p, 'appKey');
    if (appKey.length === 0) {
      reply.fail('appKey is empty', JVerifyCode.FAILED);
      return;
    }
    const timeoutMs: number = SDAuthModule.clampTimeout(SD.int(p, 'timeoutMs', 10000));
    const timer: number = setTimeout(() => {
      reply.ok(SDAuthModule.codeOf(JVerifyCode.TIMEOUT));
    }, timeoutMs + JVERIFY_WATCHDOG_MARGIN_MS);

    adapter.setup(appKey, SD.str(p, 'channel'), SD.bool(p, 'debug'), timeoutMs).then((code: number) => {
      clearTimeout(timer);
      if (code === JVerifyCode.SETUP_OK) {
        // 即使模块侧已经按超时回过了，迟到的成功也记下来，后续调用可用
        SDAuthModule.jverifyReady = true;
      }
      reply.ok(SDAuthModule.codeOf(code));
    }).catch((e: Object) => {
      clearTimeout(timer);
      SDLog.e(TAG, `jverifySetup failed: ${SD.errMsg(e)}`);
      reply.fail(SD.errMsg(e), JVerifyCode.FAILED);
    });
  }

  private jverifyCheckEnable(reply: SDReply): void {
    const adapter: JVerifyAdapter = JVerifyProvider.get();
    if (!adapter.isSupported() || !SDAuthModule.jverifyReady) {
      reply.ok(SDAuthModule.enableOf(false));
      return;
    }
    const timer: number = setTimeout(() => {
      reply.ok(SDAuthModule.enableOf(false));
    }, JVERIFY_CHECK_WATCHDOG_MS);

    adapter.checkEnable().then((enable: boolean) => {
      clearTimeout(timer);
      reply.ok(SDAuthModule.enableOf(enable === true));
    }).catch((e: Object) => {
      clearTimeout(timer);
      SDLog.w(TAG, `jverifyCheckEnable failed: ${SD.errMsg(e)}`);
      reply.ok(SDAuthModule.enableOf(false));
    });
  }

  private jverifyPreLogin(p: SDParams, reply: SDReply): void {
    const adapter: JVerifyAdapter = JVerifyProvider.get();
    if (!adapter.isSupported() || !SDAuthModule.jverifyReady) {
      reply.ok(SDAuthModule.codeOf(JVerifyCode.UNSUPPORTED));
      return;
    }
    const timeoutMs: number = SDAuthModule.clampTimeout(SD.int(p, 'timeoutMs', 5000));
    const timer: number = setTimeout(() => {
      reply.ok(SDAuthModule.codeOf(JVerifyCode.TIMEOUT));
    }, timeoutMs + JVERIFY_WATCHDOG_MARGIN_MS);

    adapter.preLogin(timeoutMs).then((code: number) => {
      clearTimeout(timer);
      reply.ok(SDAuthModule.codeOf(code));
    }).catch((e: Object) => {
      clearTimeout(timer);
      SDLog.w(TAG, `jverifyPreLogin failed: ${SD.errMsg(e)}`);
      reply.fail(SD.errMsg(e), JVerifyCode.FAILED);
    });
  }

  private jverifyLoginAuth(p: SDParams, reply: SDReply): void {
    const adapter: JVerifyAdapter = JVerifyProvider.get();
    if (!adapter.isSupported() || !SDAuthModule.jverifyReady) {
      reply.ok(SDAuthModule.actionOf(JVerifyAction.UNSUPPORTED, ''));
      return;
    }
    if (SDAuthModule.jverifyLoginInFlight) {
      reply.ok(SDAuthModule.actionOf(JVerifyAction.ERROR, 'busy'));
      return;
    }
    SDAuthModule.jverifyLoginInFlight = true;
    try {
      // 授权页停留多久由用户决定，这里不加模块级超时；适配器负责「拉不起来」的超时并保证 Promise 一定 settle
      adapter.loginAuth(SDAuthModule.parseLoginOptions(p)).then((r: JVerifyLoginResult) => {
        SDAuthModule.jverifyLoginInFlight = false;
        reply.ok(SDAuthModule.loginResult(r));
      }).catch((e: Object) => {
        SDAuthModule.jverifyLoginInFlight = false;
        SDLog.e(TAG, `jverifyLoginAuth failed: ${SD.errMsg(e)}`);
        reply.ok(SDAuthModule.actionOf(JVerifyAction.ERROR, SD.errMsg(e)));
      });
    } catch (e) {
      SDAuthModule.jverifyLoginInFlight = false;
      SDLog.e(TAG, `jverifyLoginAuth threw: ${SD.errMsg(e as Object)}`);
      reply.ok(SDAuthModule.actionOf(JVerifyAction.ERROR, SD.errMsg(e as Object)));
    }
  }

  private jverifyDismiss(reply: SDReply): void {
    const adapter: JVerifyAdapter = JVerifyProvider.get();
    if (adapter.isSupported() && SDAuthModule.jverifyReady) {
      try {
        adapter.dismiss();
      } catch (e) {
        SDLog.w(TAG, `jverifyDismiss failed: ${SD.errMsg(e as Object)}`);
      }
    }
    reply.ok();
  }

  private static parseLoginOptions(p: SDParams): JVerifyLoginOptions {
    const o: JVerifyLoginOptions = new JVerifyLoginOptions();
    o.title = SD.str(p, 'title');
    o.loginButtonText = SD.str(p, 'loginButtonText');
    o.otherLoginText = SD.str(p, 'otherLoginText');
    o.wechatLoginText = SD.str(p, 'wechatLoginText');
    o.privacyPrefix = SD.str(p, 'privacyPrefix');
    o.privacySuffix = SD.str(p, 'privacySuffix');
    o.privacyUncheckedToast = SD.str(p, 'privacyUncheckedToast');
    o.timeoutMs = SDAuthModule.clampTimeout(SD.int(p, 'timeoutMs', 10000));
    const items: Array<Object> = SD.arr(p, 'privacyItems');
    for (let i = 0; i < items.length; i++) {
      const raw: Object | null | undefined = items[i] as Object | null | undefined;
      if (raw === null || raw === undefined || typeof raw !== 'object' || Array.isArray(raw)) {
        continue;
      }
      const rec: SDParams = raw as SDParams;
      const item: JVerifyPrivacyItem = new JVerifyPrivacyItem();
      item.name = SD.str(rec, 'name');
      item.url = SD.str(rec, 'url');
      if (item.name.length > 0 && item.url.length > 0) {
        o.privacyItems.push(item);
      }
    }
    return o;
  }

  private static loginResult(r: JVerifyLoginResult | null | undefined): SDResult {
    if (r === null || r === undefined) {
      return SDAuthModule.actionOf(JVerifyAction.ERROR, 'empty result');
    }
    let action: string = r.action;
    let error: string = r.error;
    if (!JVerifyAction.isValid(action)) {
      action = JVerifyAction.ERROR;
    }
    if (action === JVerifyAction.TOKEN && r.token.length === 0) {
      action = JVerifyAction.ERROR;
      error = 'empty token';
    }
    const out: SDResult = SDAuthModule.actionOf(action, error);
    if (action === JVerifyAction.TOKEN) {
      out['token'] = r.token;
    }
    if (r.operator.length > 0) {
      out['operator'] = r.operator;
    }
    if (r.code !== 0) {
      out['code'] = r.code;
    }
    return out;
  }

  private static actionOf(action: string, error: string): SDResult {
    const r: SDResult = {};
    r['action'] = action;
    if (error.length > 0) {
      r['error'] = error;
    }
    return r;
  }

  private static codeOf(code: number): SDResult {
    const r: SDResult = {};
    r['code'] = code;
    return r;
  }

  private static enableOf(enable: boolean): SDResult {
    const r: SDResult = {};
    r['enable'] = enable;
    return r;
  }

  private static clampTimeout(ms: number): number {
    if (ms < 1000) {
      return 1000;
    }
    if (ms > 30000) {
      return 30000;
    }
    return ms;
  }
}
