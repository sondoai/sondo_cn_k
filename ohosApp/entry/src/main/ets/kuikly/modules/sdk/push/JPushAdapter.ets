/*
 * 极光推送鸿蒙 SDK 适配层 —— 整个工程里唯一 import '@jg/push' 的文件。
 *
 * API 依据：已对照 ohpm 上 @jg/push 1.4.2 包内的 .d.ets（src/main/ets/e/i.d.ets、src/g/ets/l1/n1.d.ets、
 *   src/main/ets/e/l1/m1.d.ets）与极光官方《HarmonyOS SDK API》文档逐条核对，并经 hvigor CompileArkTS 编译通过：
 *     JPushInterface.setContext(ApplicationContext) / setCallBackMsg(CallBackMsg) / setDebug / setChannel / setAppKey
 *     JPushInterface.setUserRequestNotificationPermission(ApplicationContext, true)   true = 开发者自己申请通知权限
 *     JPushInterface.init(UIAbilityContext | AbilityStageContext): Promise<void>
 *     JPushInterface.getRegistrationId(): Promise<string>
 *     JPushInterface.setClickWant(want, ApplicationContext?): Promise<JMessage | undefined>（同时回调 onClickMessage）
 *     JMessage: msgId? / title? / content? / extras?: Record<string, Object> / channel / notificationType
 *
 * 版本：依赖钉死在 1.4.2。升级前对照 SDK 的 .d.ets 复核本文件。
 *
 * 已知（来自官方文档，需要真机确认的行为）：
 *   - 鸿蒙 SDK 没有 Android 的 setAuth(boolean) / production：隐私合规靠「setup 之前不调用任何 JPush 接口」；
 *   - onArrivedMessage：远端通知只有极光通道触发，鸿蒙系统通道不触发；
 *   - onUnShowMessage（前台不展示）走鸿蒙通道时，需要额外的 PushMessageAbility +
 *     pushService.receiveMessage('DEFAULT', …) → JPushInterface.defaultMessageBackgroundData（本工程未接，见 sdk/README.md）。
 */
import { common, Want } from '@kit.AbilityKit';
import {
  CallBackMsg,
  JAliasMessage,
  JCmdMessage,
  JCustomMessage,
  JMessage,
  JMessageExtra,
  JMessageVoIP,
  JPushInterface,
  JTagMessage
} from '@jg/push';

/** 与 SDK 类型解耦后的通知消息。 */
export class JPushNotification {
  msgId: string = '';
  title: string = '';
  content: string = '';
  /** SDK 给的原始 extras（JSON 字符串或对象），由 PushManager 归一化。 */
  extras: Object | null = null;
}

export interface JPushListener {
  onRegister(registrationId: string): void;
  onConnected(connected: boolean): void;
  /** 用户点击了通知。 */
  onNotificationClicked(message: JPushNotification): void;
  /** 通知到达。 */
  onNotificationArrived(message: JPushNotification): void;
  /** 通知到达但系统没有展示（应用在前台且下发时 foregroundShow=false）。 */
  onNotificationUnshown(message: JPushNotification): void;
}

function textOf(value: Object | null | undefined): string {
  if (value === null || value === undefined) {
    return '';
  }
  if (typeof value === 'string') {
    return value as string;
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return `${value}`;
  }
  return '';
}

function convert(message: JMessage | null | undefined): JPushNotification {
  const out: JPushNotification = new JPushNotification();
  if (message === null || message === undefined) {
    return out;
  }
  out.msgId = textOf(message.msgId);
  out.title = textOf(message.title);
  out.content = textOf(message.content);
  const extras: Object | null | undefined = message.extras;
  out.extras = extras === undefined ? null : extras;
  return out;
}

/** 覆写的方法集合与 jpush_flutter 的 MyCallBackMsg 完全一致。 */
class JPushCallback extends CallBackMsg {
  private listener: JPushListener;

  constructor(listener: JPushListener) {
    super();
    this.listener = listener;
  }

  onRegister(registrationId: string): void {
    this.listener.onRegister(textOf(registrationId));
  }

  onConnected(isConnected: boolean): void {
    this.listener.onConnected(isConnected === true);
  }

  onClickMessage(jMessage: JMessage): void {
    this.listener.onNotificationClicked(convert(jMessage));
  }

  onArrivedMessage(jMessage: JMessage): void {
    this.listener.onNotificationArrived(convert(jMessage));
  }

  onUnShowMessage(jMessage: JMessage): void {
    this.listener.onNotificationUnshown(convert(jMessage));
  }

  // 以下能力本应用未使用（自定义消息 / 通知扩展 / VoIP / 标签别名 / 推送开关），保持空实现
  onCustomMessage(jCustomMessage: JCustomMessage): void {
  }

  onJMessageExtra(jme: JMessageExtra): void {
  }

  onJMessageVoIP(jmVoIP: JMessageVoIP): void {
  }

  onCommandResult(cmdMessage: JCmdMessage): void {
  }

  onTagOperatorResult(jTagMessage: JTagMessage): void {
  }

  onAliasOperatorResult(jAliasMessage: JAliasMessage): void {
  }
}

export class JPushAdapter {
  private static callback: JPushCallback | null = null;

  /**
   * 初始化极光推送。调用方（PushManager）保证：只在用户同意隐私政策且通知权限已授予之后调用，且只调用一次。
   * 同步步骤抛错会原样抛出；返回 init 的 Promise（调用方负责 catch）。
   */
  static start(context: common.UIAbilityContext, appKey: string, channel: string, debug: boolean,
    listener: JPushListener): Promise<void> {
    const appContext: common.ApplicationContext = context.getApplicationContext();
    const callback: JPushCallback = new JPushCallback(listener);
    JPushAdapter.callback = callback;

    // >>> SDK CALL SITE（顺序与 jpush_flutter 一致：context / 回调 → debug → channel → appKey → 权限开关 → init）
    JPushInterface.setContext(appContext);
    JPushInterface.setCallBackMsg(callback);
    JPushInterface.setDebug(debug);
    if (channel.length > 0) {
      JPushInterface.setChannel(channel);
    }
    JPushInterface.setAppKey(appKey);
    // 通知权限由应用自己申请（SDPermissionModule），不让 SDK 在 init 时弹系统授权框
    JPushInterface.setUserRequestNotificationPermission(appContext, true);
    const initDone: Promise<void> = JPushInterface.init(context);
    // <<< SDK CALL SITE
    return initDone;
  }

  /** 极光 Registration ID；还没注册上时为空串。 */
  static async getRegistrationId(): Promise<string> {
    // >>> SDK CALL SITE
    const rid: string = await JPushInterface.getRegistrationId();
    // <<< SDK CALL SITE
    return textOf(rid);
  }

  /** 把 Ability 收到的 Want 交给 SDK；是通知点击时 SDK 上报点击并回调 onClickMessage，否则忽略。 */
  static handleClickWant(want: Want, context: common.UIAbilityContext): Promise<void> {
    // >>> SDK CALL SITE（返回值里的 JMessage 与 onClickMessage 回调重复，这里只用回调）
    const done: Promise<JMessage | undefined> = JPushInterface.setClickWant(want, context.getApplicationContext());
    // <<< SDK CALL SITE
    return done.then((): void => {
    });
  }
}
