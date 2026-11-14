/*
 * 微信授权的应用级管理器：一个进程只有一个 WXApi，授权结果广播给所有页面上的 SDAuthModule 实例。
 *
 * 流程：wechatRegister（懒注册，用户点「微信登录」时）→ wechatAuth 拉起微信 →
 *       微信回跳 EntryAbility.onNewWant → AppEvents.NEW_WANT → WXApi.handleWant → onResp →
 *       广播 {event:"wechatAuthResp", errCode, code?, state?}
 *
 * 隐私：register 之前不创建 WXApi、不订阅 Want，也不调用 SDK 的任何方法。
 */
import { bundleManager, common, Want } from '@kit.AbilityKit';
import { AppEvent, AppEventListener, AppEvents } from '../../../support/AppEvents';
import { EventBroadcaster, SD, SDHost, SDLog, SDResult } from '../../../support/SDModuleSupport';
import { WechatAdapter, WechatAuthListener } from './WechatAdapter';

const TAG: string = 'WechatManager';
const WECHAT_LINK: string = 'weixin://';

class WechatAuthBridge implements WechatAuthListener {
  onAuthResp(errCode: number, code: string, state: string): void {
    const extra: SDResult = {};
    extra['errCode'] = errCode;
    if (code.length > 0) {
      extra['code'] = code;
    }
    if (state.length > 0) {
      extra['state'] = state;
    }
    SDLog.i(TAG, `auth resp errCode=${errCode} hasCode=${code.length > 0}`);
    WechatManager.events.emit(SD.event('wechatAuthResp', extra));
  }
}

export class WechatManager {
  static readonly events: EventBroadcaster = new EventBroadcaster();

  private static registeredAppId: string = '';
  private static hooked: boolean = false;
  private static readonly bridge: WechatAuthBridge = new WechatAuthBridge();
  private static readonly wantListener: AppEventListener = (event: AppEvent): void => {
    WechatManager.onWant(event);
  };

  static isRegistered(): boolean {
    return WechatManager.registeredAppId.length > 0;
  }

  /** 注册微信 AppID（幂等）。失败抛 Error，由模块转成 {ok:false, error}。 */
  static register(appId: string): void {
    if (appId.length === 0) {
      throw new Error('appId is empty');
    }
    if (WechatManager.registeredAppId.length > 0) {
      if (WechatManager.registeredAppId !== appId) {
        SDLog.w(TAG, 'already registered with another appId, keep the first one');
      }
      return;
    }
    WechatAdapter.create(appId, WechatManager.bridge);
    WechatManager.registeredAppId = appId;
    if (!WechatManager.hooked) {
      WechatManager.hooked = true;
      AppEvents.on(AppEvents.NEW_WANT, WechatManager.wantListener);
    }
  }

  /** 是否安装微信。未注册时只走系统的 canOpenLink（不碰 SDK）。 */
  static isInstalled(): boolean {
    try {
      if (bundleManager.canOpenLink(WECHAT_LINK)) {
        return true;
      }
    } catch (e) {
      // 17700056：querySchemes 里没有声明 weixin
      SDLog.w(TAG, `canOpenLink(${WECHAT_LINK}) failed: ${SD.errMsg(e as Object)}`);
    }
    if (!WechatManager.isRegistered()) {
      return false;
    }
    try {
      return WechatAdapter.isAppInstalled();
    } catch (e) {
      SDLog.w(TAG, `isWXAppInstalled failed: ${SD.errMsg(e as Object)}`);
      return false;
    }
  }

  /** 拉起微信授权；resolve 值为是否成功拉起。 */
  static async auth(scope: string, state: string): Promise<boolean> {
    if (!WechatManager.isRegistered()) {
      throw new Error('wechat not registered');
    }
    const context: common.UIAbilityContext = SDHost.requireContext();
    return await WechatAdapter.sendAuth(context, scope, state);
  }

  private static onWant(event: AppEvent): void {
    const want: Want | null = event.want;
    if (want === null || !WechatManager.isRegistered()) {
      return;
    }
    try {
      WechatAdapter.handleWant(want);
    } catch (e) {
      SDLog.w(TAG, `handleWant failed: ${SD.errMsg(e as Object)}`);
    }
  }
}
