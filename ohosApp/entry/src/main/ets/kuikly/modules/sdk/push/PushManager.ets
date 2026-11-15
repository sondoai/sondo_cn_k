/*
 * 推送的应用级管理器：一个进程只初始化一次极光，事件广播给所有页面上的 SDPushModule 实例。
 *
 * 隐私 / 合规：
 *   - setup() 之前不调用任何极光接口。期间收到的 Want 只是原样留在内存里，setup 之后再交给 SDK。
 *   - setup 由 Kuikly 侧在「用户已同意隐私政策 + 通知权限已授予」之后调用；SDK 不会自己弹授权框。
 *
 * 通知点击：
 *   热启动  EntryAbility.onNewWant → AppEvents.NEW_WANT → JPush.setClickWant → onClickMessage
 *           → 广播 {event:"notificationOpened", title, alert, extras}
 *   冷启动  启动 Want（AppEvents.launchWant()）在 setup 后交给 JPush；它的点击回调不广播，
 *           而是留给 getLaunchNotification()（读取后清空），避免 Kuikly 侧重复跳转。
 *           SDK 在 LAUNCH_SETTLE_MS 内没有回调时，退回 PushPayload.fromWant 直接解析 Want。
 *
 * 前台通知（取舍，官方文档给出的触发条件见 JPushAdapter 头注释，仍需真机确认）：
 *   鸿蒙没有 Android 的 setUnShowAtTheForeground；前台是否弹系统横幅由下发参数 foregroundShow 决定。
 *   - onUnShowMessage（系统没展示）→ 广播 notificationReceived，Kuikly 侧弹应用内提示；
 *   - onArrivedMessage（系统已展示）→ 默认不广播，否则横幅 + 应用内提示会重复。
 *     如果实测 onUnShowMessage 不触发，把 EMIT_ARRIVED_AS_RECEIVED 改成 true（按 msgId 去重）。
 *     鸿蒙通道：前台 + foregroundShow=false 的消息由 entryability/PushMessageAbility 收到，
 *     经 handleForegroundPayload → defaultMessageBackgroundData 触发 onUnShowMessage；
 *     onArrivedMessage 只在极光通道触发。
 */
import { common, Want } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { notificationManager } from '@kit.NotificationKit';
import { pushCommon } from '@kit.PushKit';
import { KuiklyRenderCallback } from '@kuikly-open/render';
import { AppEvent, AppEventListener, AppEvents } from '../../../support/AppEvents';
import { EventBroadcaster, SD, SDHost, SDLog, SDReply, SDResult } from '../../../support/SDModuleSupport';
import { JPushAdapter, JPushListener, JPushNotification } from './JPushAdapter';
import { PushMessage, PushPayload } from './PushPayload';

const TAG: string = 'PushManager';
/** setup 之前最多暂存多少个 Want。 */
const MAX_PENDING_WANTS: number = 4;
/** 没有监听者时最多暂存多少个 notificationOpened。 */
const MAX_PENDING_OPENED: number = 4;
/** 启动 Want 交给 SDK 后，getLaunchNotification 最多等它回调多久。 */
const LAUNCH_SETTLE_MS: number = 1500;
/** 启动 Want 交给 SDK 后，这段时间内的第一个点击回调认作「冷启动点击」。 */
const LAUNCH_CLICK_WINDOW_MS: number = 10000;
const REGISTRATION_ID_WATCHDOG_MS: number = 3000;
const EMIT_ARRIVED_AS_RECEIVED: boolean = false;

class PushBridge implements JPushListener {
  onRegister(registrationId: string): void {
    PushManager.handleRegister(registrationId);
  }

  onConnected(connected: boolean): void {
    PushManager.handleConnected(connected);
  }

  onNotificationClicked(message: JPushNotification): void {
    PushManager.handleClicked(PushManager.toMessage(message));
  }

  onNotificationArrived(message: JPushNotification): void {
    PushManager.handleArrived(PushManager.toMessage(message), true);
  }

  onNotificationUnshown(message: JPushNotification): void {
    PushManager.handleArrived(PushManager.toMessage(message), false);
  }
}

export class PushManager {
  private static readonly events: EventBroadcaster = new EventBroadcaster();
  private static readonly bridge: PushBridge = new PushBridge();
  private static readonly wantListener: AppEventListener = (event: AppEvent): void => {
    PushManager.onNewWant(event);
  };

  private static hooked: boolean = false;
  private static started: boolean = false;
  private static rid: string = '';
  private static pendingWants: Array<Want> = [];
  private static pendingOpened: Array<SDResult> = [];
  private static lastReceivedId: string = '';

  // ---- 冷启动通知 ----
  /** 启动 Want 是否已交给 SDK。 */
  private static launchSubmitted: boolean = false;
  /** SDK 对启动 Want 的处理是否已有结论（回调到了，或等待超时）。 */
  private static launchSettled: boolean = false;
  /** 在此时间戳之前到达的第一个点击回调认作冷启动点击。 */
  private static launchClickDeadline: number = 0;
  private static launchClickSeen: boolean = false;
  /** 尚未被 Kuikly 读走的冷启动通知。 */
  private static launchMessage: PushMessage | null = null;
  /** getLaunchNotification 是否已经被调用过（读取后清空）。 */
  private static launchRead: boolean = false;
  /** 已经交给 Kuikly 的那条冷启动通知（用于去重迟到的 SDK 回调）。 */
  private static launchDelivered: PushMessage | null = null;
  private static launchWaiters: Array<SDReply> = [];

  /**
   * 订阅 AppEvents（幂等，不触碰任何 SDK）。文件末尾在模块加载时调用一次，
   * 保证 setup 之前到达的 onNewWant 也不会丢。
   */
  static hook(): void {
    if (PushManager.hooked) {
      return;
    }
    PushManager.hooked = true;
    AppEvents.on(AppEvents.NEW_WANT, PushManager.wantListener);
  }

  static isStarted(): boolean {
    return PushManager.started;
  }

  // ---------------------------------------------------------------------
  // 模块入口
  // ---------------------------------------------------------------------

  /** 登记常驻事件回调，并把没人接收时暂存的点击事件补发给它。 */
  static addListener(owner: Object, callback: KuiklyRenderCallback | null): void {
    if (callback === null) {
      return;
    }
    PushManager.hook();
    PushManager.events.add(owner, callback);
    if (PushManager.pendingOpened.length > 0) {
      const queued: Array<SDResult> = PushManager.pendingOpened;
      PushManager.pendingOpened = [];
      for (let i = 0; i < queued.length; i++) {
        PushManager.events.emitTo(owner, queued[i]);
      }
    }
  }

  static removeOwner(owner: Object): void {
    PushManager.events.removeOwner(owner);
  }

  /** 初始化极光（幂等）。 */
  static setup(appKey: string, channel: string, production: boolean, debug: boolean, reply: SDReply): void {
    PushManager.hook();
    if (PushManager.started) {
      reply.ok();
      return;
    }
    if (appKey.length === 0) {
      reply.fail('appKey is empty');
      return;
    }
    const context: common.UIAbilityContext | null = SDHost.context();
    if (context === null) {
      reply.fail('UIAbilityContext unavailable');
      return;
    }
    try {
      // production 在鸿蒙 SDK 没有对应接口（见 JPushAdapter 头注释）
      JPushAdapter.start(context, appKey, channel, debug, PushManager.bridge).catch((e: Object) => {
        SDLog.e(TAG, `JPush init failed: ${SD.errMsg(e)}`);
      });
    } catch (e) {
      SDLog.e(TAG, `JPush start failed: ${SD.errMsg(e as Object)}`);
      reply.fail(SD.errMsg(e as Object));
      return;
    }
    PushManager.started = true;
    SDLog.i(TAG, `JPush started, production=${production} debug=${debug}`);
    PushManager.submitLaunchWant(context);
    PushManager.flushPendingWants(context);
    reply.ok();
  }

  /** → {ok:true, rid}；未 setup 或还没注册上时 rid 为空串。 */
  static getRegistrationId(reply: SDReply): void {
    if (!PushManager.started || PushManager.rid.length > 0) {
      reply.ok(PushManager.ridResult(PushManager.rid));
      return;
    }
    const timer: number = setTimeout(() => {
      reply.ok(PushManager.ridResult(PushManager.rid));
    }, REGISTRATION_ID_WATCHDOG_MS);
    JPushAdapter.getRegistrationId().then((rid: string) => {
      clearTimeout(timer);
      if (rid.length > 0) {
        PushManager.rid = rid;
      }
      reply.ok(PushManager.ridResult(PushManager.rid));
    }).catch((e: Object) => {
      clearTimeout(timer);
      SDLog.w(TAG, `getRegistrationId failed: ${SD.errMsg(e)}`);
      reply.ok(PushManager.ridResult(PushManager.rid));
    });
  }

  /** → {ok:true, title?, alert?, extras?}；读取后清空。 */
  static getLaunchNotification(reply: SDReply): void {
    if (PushManager.started && PushManager.launchSubmitted && !PushManager.launchSettled &&
      !PushManager.launchRead) {
      // SDK 还在处理启动 Want，等它回调或超时（settleLaunch）
      PushManager.launchWaiters.push(reply);
      return;
    }
    PushManager.deliverLaunch(reply);
  }

  /**
   * PushMessageAbility 收到的 Push Kit 前台消息（'DEFAULT'）。交给极光解析，结果走 onUnShowMessage 回调。
   * 极光还没 setup（隐私未同意 / 通知权限未授予）时直接丢弃，不碰 SDK。
   */
  static handleForegroundPayload(payload: pushCommon.PushPayload): void {
    if (!PushManager.started) {
      SDLog.w(TAG, 'foreground push before setup, dropped');
      return;
    }
    try {
      JPushAdapter.handleDefaultMessage(payload).then((handled: boolean) => {
        if (!handled) {
          SDLog.w(TAG, 'foreground push is not a JPush message, ignored');
        }
      }).catch((e: Object) => {
        SDLog.w(TAG, `defaultMessageBackgroundData failed: ${SD.errMsg(e)}`);
      });
    } catch (e) {
      SDLog.w(TAG, `defaultMessageBackgroundData threw: ${SD.errMsg(e as Object)}`);
    }
  }

  static setBadge(count: number): void {
    const value: number = count > 0 ? Math.trunc(count) : 0;
    try {
      notificationManager.setBadgeNumber(value).catch((e: BusinessError) => {
        SDLog.w(TAG, `setBadgeNumber failed: ${SD.errMsg(e)}`);
      });
    } catch (e) {
      SDLog.w(TAG, `setBadgeNumber threw: ${SD.errMsg(e as Object)}`);
    }
  }

  // ---------------------------------------------------------------------
  // JPush 回调（PushBridge → 这里）
  // ---------------------------------------------------------------------

  static toMessage(n: JPushNotification): PushMessage {
    const m: PushMessage = new PushMessage();
    m.msgId = n.msgId;
    m.title = n.title;
    m.alert = n.content;
    m.extras = PushPayload.normalizeExtras(n.extras);
    return m;
  }

  static handleRegister(registrationId: string): void {
    if (registrationId.length === 0 || registrationId === PushManager.rid) {
      return;
    }
    PushManager.rid = registrationId;
    // Registration ID 刚拿到 / 发生变化：按「已连接」通知一次，Kuikly 侧据此重新绑定 token
    PushManager.events.emit(SD.event('connected'));
  }

  static handleConnected(connected: boolean): void {
    if (connected) {
      PushManager.events.emit(SD.event('connected'));
    }
  }

  static handleArrived(message: PushMessage, shownBySystem: boolean): void {
    if (shownBySystem && !EMIT_ARRIVED_AS_RECEIVED) {
      return;
    }
    if (message.msgId.length > 0) {
      if (message.msgId === PushManager.lastReceivedId) {
        return;
      }
      PushManager.lastReceivedId = message.msgId;
    }
    PushManager.events.emit(SD.event('notificationReceived', message.toResult()));
  }

  static handleClicked(message: PushMessage): void {
    const isLaunchClick: boolean = !PushManager.launchClickSeen && PushManager.launchClickDeadline > 0 &&
      Date.now() <= PushManager.launchClickDeadline;
    if (!isLaunchClick) {
      PushManager.emitOpened(message);
      return;
    }
    PushManager.launchClickSeen = true;
    if (PushManager.launchRead) {
      // Kuikly 已经读过冷启动通知：读到的就是这一条则丢弃（去重），否则当作普通点击补发
      const delivered: PushMessage | null = PushManager.launchDelivered;
      if (delivered !== null && delivered.sameAs(message)) {
        return;
      }
      PushManager.emitOpened(message);
      return;
    }
    PushManager.launchMessage = message;
    PushManager.settleLaunch();
  }

  // ---------------------------------------------------------------------
  // 内部
  // ---------------------------------------------------------------------

  private static onNewWant(event: AppEvent): void {
    const want: Want | null = event.want;
    if (want === null) {
      return;
    }
    const context: common.UIAbilityContext | null = SDHost.context();
    if (!PushManager.started || context === null) {
      // 还没 setup：只暂存，不碰 SDK
      PushManager.pendingWants.push(want);
      if (PushManager.pendingWants.length > MAX_PENDING_WANTS) {
        PushManager.pendingWants.shift();
      }
      return;
    }
    PushManager.submitWant(want, context);
  }

  private static submitWant(want: Want, context: common.UIAbilityContext): boolean {
    try {
      JPushAdapter.handleClickWant(want, context).catch((e: Object) => {
        SDLog.w(TAG, `setClickWant failed: ${SD.errMsg(e)}`);
      });
      return true;
    } catch (e) {
      SDLog.w(TAG, `setClickWant failed: ${SD.errMsg(e as Object)}`);
      return false;
    }
  }

  private static flushPendingWants(context: common.UIAbilityContext): void {
    const wants: Array<Want> = PushManager.pendingWants;
    PushManager.pendingWants = [];
    for (let i = 0; i < wants.length; i++) {
      PushManager.submitWant(wants[i], context);
    }
  }

  private static submitLaunchWant(context: common.UIAbilityContext): void {
    if (PushManager.launchSubmitted) {
      return;
    }
    PushManager.launchSubmitted = true;
    const want: Want | null = AppEvents.launchWant();
    if (want === null) {
      PushManager.settleLaunch();
      return;
    }
    PushManager.launchClickSeen = false;
    PushManager.launchClickDeadline = Date.now() + LAUNCH_CLICK_WINDOW_MS;
    if (!PushManager.submitWant(want, context)) {
      PushManager.launchClickDeadline = 0;
      PushManager.settleLaunch();
      return;
    }
    // SDK 可能已在 submitWant 内同步回调（launchSettled 已为 true），否则最多等 LAUNCH_SETTLE_MS
    if (!PushManager.launchSettled) {
      setTimeout(() => {
        PushManager.settleLaunch();
      }, LAUNCH_SETTLE_MS);
    }
  }

  /** SDK 对启动 Want 的处理有了结论：放行等待中的 getLaunchNotification。 */
  private static settleLaunch(): void {
    if (PushManager.launchSettled) {
      return;
    }
    PushManager.launchSettled = true;
    const waiters: Array<SDReply> = PushManager.launchWaiters;
    PushManager.launchWaiters = [];
    for (let i = 0; i < waiters.length; i++) {
      PushManager.deliverLaunch(waiters[i]);
    }
  }

  /** 第一次调用带回冷启动通知（如果有），之后都是空结果。 */
  private static deliverLaunch(reply: SDReply): void {
    if (PushManager.launchRead) {
      reply.ok();
      return;
    }
    PushManager.launchRead = true;
    let message: PushMessage | null = PushManager.launchMessage;
    PushManager.launchMessage = null;
    if (message === null) {
      // SDK 没给（或还没 setup）：直接从启动 Want 解析兜底
      message = PushPayload.fromWant(AppEvents.launchWant());
    }
    PushManager.launchDelivered = message;
    if (message === null) {
      reply.ok();
    } else {
      reply.ok(message.toResult());
    }
  }

  private static emitOpened(message: PushMessage): void {
    const event: SDResult = SD.event('notificationOpened', message.toResult());
    if (PushManager.events.size === 0) {
      // 还没有页面注册监听（如根页面尚未创建）：暂存，等第一个监听者
      PushManager.pendingOpened.push(event);
      if (PushManager.pendingOpened.length > MAX_PENDING_OPENED) {
        PushManager.pendingOpened.shift();
      }
      return;
    }
    PushManager.events.emit(event);
  }

  private static ridResult(rid: string): SDResult {
    const r: SDResult = {};
    r['rid'] = rid;
    return r;
  }
}

// 模块加载时就订阅 onNewWant（只暂存 Want，不触碰 SDK），见 PushManager.hook() 注释。
PushManager.hook();
