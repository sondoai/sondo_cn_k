/*
 * 微信 OpenSDK 适配层 —— 整个工程里唯一 import '@tencent/wechat_open_sdk' 的文件。
 * 只用到授权登录（SendAuthReq / SendAuthResp），没有分享、支付、小程序。
 *
 * API 依据：已对照 ohpm 上 @tencent/wechat_open_sdk 1.0.15 包内的 .d.ets（open_api/WXAPIFactory、model/Base、
 *   model/SendAuth）核对，并经 hvigor CompileArkTS 编译通过：
 *     WXAPIFactory.createWXAPI(appId: string): WXApi
 *     WXApi.isWXAppInstalled(): boolean
 *     WXApi.sendReq(UIAbilityContext, BaseReq): boolean | Promise<boolean>（是否成功拉起）
 *     WXApi.handleWant(want, WXApiEventHandler { onReq(BaseReq); onResp(BaseResp) }): boolean
 *     SendAuthReq: scope? / state? / extData? / isOption1 / nonAutomatic；BaseReq.callbackAbility?（默认 'EntryAbility'）
 *     SendAuthResp: errCode: number / errStr? / code? / state? / authResult
 */
import { common, Want } from '@kit.AbilityKit';
import * as wxopensdk from '@tencent/wechat_open_sdk';

/** 授权结果回调（由 WechatManager 实现）。 */
export interface WechatAuthListener {
  onAuthResp(errCode: number, code: string, state: string): void;
}

/** 写法与 fluwx 保持一致：onReq / onResp 用箭头函数属性实现 WXApiEventHandler。 */
class WechatEventHandler implements wxopensdk.WXApiEventHandler {
  private listener: WechatAuthListener;

  constructor(listener: WechatAuthListener) {
    this.listener = listener;
  }

  onReq = (req: wxopensdk.BaseReq): void => {
    // 微信主动拉起（开放标签等）本应用不处理
  }

  onResp = (resp: wxopensdk.BaseResp): void => {
    if (resp instanceof wxopensdk.SendAuthResp) {
      const auth: wxopensdk.SendAuthResp = resp as wxopensdk.SendAuthResp;
      const errCode: number = auth.errCode ?? -1;
      const code: string = auth.code ?? '';
      const state: string = auth.state ?? '';
      this.listener.onAuthResp(errCode, code, state);
    }
  }
}

export class WechatAdapter {
  private static api: wxopensdk.WXApi | null = null;
  private static handler: WechatEventHandler | null = null;

  static isReady(): boolean {
    return WechatAdapter.api !== null;
  }

  /** 创建 WXApi（幂等）。必须在用户同意隐私政策之后调用。 */
  static create(appId: string, listener: WechatAuthListener): void {
    if (WechatAdapter.api !== null) {
      return;
    }
    // >>> SDK CALL SITE
    WechatAdapter.api = wxopensdk.WXAPIFactory.createWXAPI(appId);
    // <<< SDK CALL SITE
    WechatAdapter.handler = new WechatEventHandler(listener);
  }

  /** SDK 自己的安装检测（未 create 时返回 false）。 */
  static isAppInstalled(): boolean {
    const api: wxopensdk.WXApi | null = WechatAdapter.api;
    if (api === null) {
      return false;
    }
    // >>> SDK CALL SITE
    return api.isWXAppInstalled() === true;
    // <<< SDK CALL SITE
  }

  /** 拉起微信授权页；resolve 值为「是否成功拉起」。授权结果经 handleWant → onResp 返回。 */
  static async sendAuth(context: common.UIAbilityContext, scope: string, state: string): Promise<boolean> {
    const api: wxopensdk.WXApi | null = WechatAdapter.api;
    if (api === null) {
      throw new Error('wechat not registered');
    }
    // >>> SDK CALL SITE
    const req: wxopensdk.SendAuthReq = new wxopensdk.SendAuthReq();
    req.isOption1 = false;
    req.scope = scope;
    req.state = state;
    req.nonAutomatic = false;
    const done = await api.sendReq(context, req);
    // <<< SDK CALL SITE
    return done === true;
  }

  /** 把 Ability 收到的 Want 交给 SDK 解析；不是微信回跳的 Want 会被 SDK 忽略。 */
  static handleWant(want: Want): void {
    const api: wxopensdk.WXApi | null = WechatAdapter.api;
    const handler: WechatEventHandler | null = WechatAdapter.handler;
    if (api === null || handler === null) {
      return;
    }
    // >>> SDK CALL SITE
    api.handleWant(want, handler);
    // <<< SDK CALL SITE
  }
}
