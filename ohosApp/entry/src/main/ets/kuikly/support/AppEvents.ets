/*
 * 应用级事件中心。EntryAbility 把 Ability / 窗口生命周期喂进来，
 * 各原生模块（SDAppModule、支付 / 登录 / 推送 SDK …）在这里订阅，不需要改 EntryAbility。
 *
 *   AppEvents.on(AppEvents.NEW_WANT, (e: AppEvent) => { handle(e.want, e.uri); });
 *   AppEvents.on(AppEvents.FOREGROUND, (e: AppEvent) => { ... });
 *
 * 事件：
 *   CREATE      Ability.onCreate(want)            e.want / e.uri（冷启动 Want）
 *   NEW_WANT    Ability.onNewWant(want)           e.want / e.uri（热启动：深链、SDK 回跳）
 *   FOREGROUND  Ability.onForeground
 *   BACKGROUND  Ability.onBackground
 *   ACTIVE      窗口获得焦点（WindowStageEventType.ACTIVE）
 *   INACTIVE    窗口失去焦点（WindowStageEventType.INACTIVE）
 *   DESTROY     Ability.onDestroy
 *
 * 另外保留最近一次 Want：AppEvents.launchWant()（冷启动）、AppEvents.latestWant()。
 */
import { Want } from '@kit.AbilityKit';
import { SD, SDLog } from './SDModuleSupport';

export class AppEvent {
  type: string;
  /** want.uri，没有时为空串。 */
  uri: string = '';
  want: Want | null = null;

  constructor(type: string) {
    this.type = type;
  }
}

export type AppEventListener = (event: AppEvent) => void;

export class AppEvents {
  static readonly CREATE: string = 'create';
  static readonly NEW_WANT: string = 'newWant';
  static readonly FOREGROUND: string = 'foreground';
  static readonly BACKGROUND: string = 'background';
  static readonly ACTIVE: string = 'active';
  static readonly INACTIVE: string = 'inactive';
  static readonly DESTROY: string = 'destroy';

  private static listeners: Map<string, Array<AppEventListener>> = new Map();
  private static coldWant: Want | null = null;
  private static lastWant: Want | null = null;
  private static foreground: boolean = false;

  /** 订阅。同一个 listener 重复订阅同一事件只生效一次。 */
  static on(type: string, listener: AppEventListener): void {
    let list: Array<AppEventListener> | undefined = AppEvents.listeners.get(type);
    if (list === undefined) {
      list = [];
      AppEvents.listeners.set(type, list);
    }
    if (list.indexOf(listener) < 0) {
      list.push(listener);
    }
  }

  static off(type: string, listener: AppEventListener): void {
    const list: Array<AppEventListener> | undefined = AppEvents.listeners.get(type);
    if (list === undefined) {
      return;
    }
    const index: number = list.indexOf(listener);
    if (index >= 0) {
      list.splice(index, 1);
    }
  }

  /** 冷启动 Want（Ability.onCreate 收到的那个）。 */
  static launchWant(): Want | null {
    return AppEvents.coldWant;
  }

  /** 最近一次 Want（onCreate 或 onNewWant）。 */
  static latestWant(): Want | null {
    return AppEvents.lastWant;
  }

  /** App 当前是否在前台。 */
  static isForeground(): boolean {
    return AppEvents.foreground;
  }

  // ---------------------------------------------------------------------
  // 以下由 EntryAbility 调用
  // ---------------------------------------------------------------------

  static dispatchCreate(want: Want): void {
    AppEvents.coldWant = want;
    AppEvents.lastWant = want;
    AppEvents.dispatchWant(AppEvents.CREATE, want);
  }

  static dispatchNewWant(want: Want): void {
    AppEvents.lastWant = want;
    AppEvents.dispatchWant(AppEvents.NEW_WANT, want);
  }

  static dispatchForeground(): void {
    AppEvents.foreground = true;
    AppEvents.dispatch(new AppEvent(AppEvents.FOREGROUND));
  }

  static dispatchBackground(): void {
    AppEvents.foreground = false;
    AppEvents.dispatch(new AppEvent(AppEvents.BACKGROUND));
  }

  static dispatchSimple(type: string): void {
    AppEvents.dispatch(new AppEvent(type));
  }

  private static dispatchWant(type: string, want: Want): void {
    const event: AppEvent = new AppEvent(type);
    event.want = want;
    const uri: string | undefined = want.uri;
    event.uri = uri === undefined || uri === null ? '' : uri;
    AppEvents.dispatch(event);
  }

  private static dispatch(event: AppEvent): void {
    const list: Array<AppEventListener> | undefined = AppEvents.listeners.get(event.type);
    if (list === undefined || list.length === 0) {
      return;
    }
    const snapshot: Array<AppEventListener> = list.slice();
    for (let i = 0; i < snapshot.length; i++) {
      try {
        snapshot[i](event);
      } catch (e) {
        SDLog.e('AppEvents', `listener for ${event.type} failed: ${SD.errMsg(e as Object)}`);
      }
    }
  }
}
