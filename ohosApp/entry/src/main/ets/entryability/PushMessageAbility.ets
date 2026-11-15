/*
 * Push Kit 前台消息接收 Ability（极光《HarmonyOS SDK 集成指南》「配置前台不展示功能」）。
 *
 * 应用在前台、且下发参数 foregroundShow=false 时，系统不弹横幅，而是把消息交给声明了
 * action.ohos.push.listener 的 Ability（整个应用只能有一个）：这里用 pushService.receiveMessage('DEFAULT')
 * 接住，交给 PushManager → JPushInterface.defaultMessageBackgroundData → 极光回调 onUnShowMessage
 * → Kuikly 侧收到 {event:"notificationReceived"}，弹应用内提示（与 Android 的「前台不弹系统通知」一致）。
 *
 * 不加载任何页面、不碰 Kuikly；和 EntryAbility 同进程，共享 PushManager 的静态状态。
 * 隐私：PushManager 在极光 setup（隐私同意 + 通知权限授予）之前直接丢弃消息，不调用极光接口。
 */
import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { pushCommon, pushService } from '@kit.PushKit';
import { PushManager } from '../kuikly/modules/sdk/push/PushManager';
import { SD, SDLog } from '../kuikly/support/SDModuleSupport';

const TAG: string = 'PushMessageAbility';

export default class PushMessageAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    SDLog.i(TAG, 'onCreate');
    try {
      pushService.receiveMessage('DEFAULT', this, (payload: pushCommon.PushPayload): void => {
        PushManager.handleForegroundPayload(payload);
      });
    } catch (e) {
      SDLog.e(TAG, `receiveMessage(DEFAULT) failed: ${SD.errMsg(e as Object)}`);
    }
  }
}
