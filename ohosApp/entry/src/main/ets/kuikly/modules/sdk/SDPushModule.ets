/*
 * SDPushModule —— 极光推送（契约：shared/.../nativeapi/SDBizModules.kt 的 SDPushModule）。
 *
 *   - setup {appKey, channel, production, debug}  → {ok}      隐私同意 + 通知权限授予之后才会被调用；幂等
 *   - getRegistrationId                           → {ok, rid} 未连上时 rid 为空串
 *   - getLaunchNotification                       → {ok, title?, alert?, extras?}  冷启动点击的通知，读取后清空
 *   - setBadge {count}                            系统角标（notificationManager.setBadgeNumber）
 *   - addEventListener                            {event:"connected"} / {event:"notificationReceived"|"notificationOpened", title, alert, extras}
 *
 * 全部逻辑在 push/PushManager.ets（应用级单例）；SDK 调用集中在 push/JPushAdapter.ets。
 * 没有同步方法，异步模式即可（call 运行在主线程）。
 */
import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { SD, SDLog, SDParams, SDReply } from '../../support/SDModuleSupport';
import { PushManager } from './push/PushManager';

const TAG: string = 'SDPushModule';

export class SDPushModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME: string = 'SDPushModule';

  syncMode(): boolean {
    return false;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    if (method === 'addEventListener') {
      PushManager.addListener(this, callback);
      return null;
    }
    const reply: SDReply = new SDReply(callback);
    try {
      const p: SDParams = SD.parse(params);
      switch (method) {
        case 'setup':
          PushManager.setup(SD.str(p, 'appKey'), SD.str(p, 'channel'), SD.bool(p, 'production'),
            SD.bool(p, 'debug'), reply);
          break;
        case 'getRegistrationId':
          PushManager.getRegistrationId(reply);
          break;
        case 'getLaunchNotification':
          PushManager.getLaunchNotification(reply);
          break;
        case 'setBadge':
          PushManager.setBadge(SD.int(p, 'count'));
          reply.ok();
          break;
        default:
          SDLog.w(TAG, `unknown method: ${method}`);
          reply.fail(`unknown method: ${method}`);
          break;
      }
    } catch (e) {
      SDLog.e(TAG, `${method} failed: ${SD.errMsg(e as Object)}`);
      reply.fail(SD.errMsg(e as Object));
    }
    return null;
  }

  onDestroy(): void {
    PushManager.removeOwner(this);
  }
}
