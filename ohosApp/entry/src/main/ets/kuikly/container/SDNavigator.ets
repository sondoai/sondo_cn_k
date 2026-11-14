/*
 * 原生页面栈：一个 Kuikly 页面 = NavPathStack 里的一个 NavDestination。
 *
 * - 根页面（launch）也是一个 NavDestination，永远在栈底，不会被 pop；
 * - 每个页面的路由名唯一（page_<id>），NavPathStack 的按名操作因此可以精确命中某一页；
 * - 所有栈变更都经过这里，系统返回手势由 KuiklyPage.onBackPressed 拦截后再回到这里，
 *   所以 entries 与 NavPathStack 始终一一对应。
 *
 * 转场（pageData.sdTransition / sdTranslucent，见 shared 的 Nav.kt / Routes.kt）：
 *   默认          STANDARD 模式 + 系统转场
 *   "none"        STANDARD 模式，无动画
 *   "bottom"      DIALOG 模式（上一页保持可见），页面内容自底向上滑入 / 滑出
 *   "fade"        DIALOG 模式，淡入 / 淡出
 *   sdTranslucent DIALOG 模式 + 透明背景（盖在上一页之上）
 */
import { common } from '@kit.AbilityKit';
import { KRNativeRenderController, KRRecord } from '@kuikly-open/render';
import { AppConfig } from '../AppConfig';
import { SD, SDHost, SDLog } from '../support/SDModuleSupport';

const TAG: string = 'SDNavigator';

export type SDExitAnimator = (done: () => void) => void;
export type SDStackListener = () => void;

export class SDPageEntry {
  static readonly TRANSITION_DEFAULT: string = '';
  static readonly TRANSITION_NONE: string = 'none';
  static readonly TRANSITION_FADE: string = 'fade';
  static readonly TRANSITION_BOTTOM: string = 'bottom';

  readonly id: number;
  /** NavPathStack 里的唯一路由名 */
  readonly routeName: string;
  /** Kuikly 页面名（@Page 注解） */
  readonly pageName: string;
  /** 传给 Kuikly 的页面参数（已注入宿主环境参数） */
  readonly pageData: KRRecord;
  readonly transition: string;
  readonly translucent: boolean;

  /** 是否允许系统侧滑 / 返回键关闭（SDNavModule.setSwipeBackEnabled） */
  swipeBackEnabled: boolean = true;
  /** 已进入关闭流程（退场动画中 / 等待移除），不再参与栈查询 */
  closing: boolean = false;
  /** Kuikly 容器已创建（controller ready）。未创建的页面不可能发起模块调用。 */
  attached: boolean = false;
  controller: KRNativeRenderController | null = null;
  /** DIALOG 模式页面的退场动画（由 KuiklyPage 注册） */
  exitAnimator: SDExitAnimator | null = null;

  constructor(id: number, pageName: string, pageData: KRRecord, transition: string, translucent: boolean) {
    this.id = id;
    this.routeName = `page_${id}`;
    this.pageName = pageName;
    this.pageData = pageData;
    this.transition = transition;
    this.translucent = translucent;
  }

  /** 是否以 DIALOG 模式盖在上一页之上（转场由页面自己做）。 */
  get overlay(): boolean {
    return this.translucent ||
      this.transition === SDPageEntry.TRANSITION_BOTTOM ||
      this.transition === SDPageEntry.TRANSITION_FADE;
  }

  /** 入栈 / 出栈是否使用系统转场。 */
  get systemAnimated(): boolean {
    return !this.overlay && this.transition !== SDPageEntry.TRANSITION_NONE;
  }
}

export class SDNavigator {
  private static instance: SDNavigator | null = null;

  static shared(): SDNavigator {
    if (SDNavigator.instance === null) {
      SDNavigator.instance = new SDNavigator();
    }
    return SDNavigator.instance;
  }

  /** 绑定到 pages/Index 的 Navigation。 */
  readonly pathStack: NavPathStack = new NavPathStack();

  private entries: Array<SDPageEntry> = [];
  private nextId: number = 1;
  private listeners: Array<SDStackListener> = [];

  // ---------------------------------------------------------------------
  // 查询
  // ---------------------------------------------------------------------

  /** 页面栈（自底向上的 pageName），不含正在关闭的页面。 */
  stackNames(): Array<string> {
    const names: Array<string> = [];
    for (let i = 0; i < this.entries.length; i++) {
      if (!this.entries[i].closing) {
        names.push(this.entries[i].pageName);
      }
    }
    return names;
  }

  entryForRoute(routeName: string): SDPageEntry | null {
    for (let i = 0; i < this.entries.length; i++) {
      if (this.entries[i].routeName === routeName) {
        return this.entries[i];
      }
    }
    return null;
  }

  entryForController(controller: KRNativeRenderController | null): SDPageEntry | null {
    if (controller === null) {
      return null;
    }
    for (let i = this.entries.length - 1; i >= 0; i--) {
      if (this.entries[i].controller === controller) {
        return this.entries[i];
      }
    }
    return null;
  }

  /** 栈顶（最上面一个未在关闭中的页面）。 */
  topEntry(): SDPageEntry | null {
    for (let i = this.entries.length - 1; i >= 0; i--) {
      if (!this.entries[i].closing) {
        return this.entries[i];
      }
    }
    return null;
  }

  isTop(entry: SDPageEntry): boolean {
    return this.topEntry() === entry;
  }

  /** 是否是根页面（栈底）。 */
  isRoot(entry: SDPageEntry): boolean {
    return this.entries.length > 0 && this.entries[0] === entry;
  }

  addStackListener(listener: SDStackListener): void {
    if (this.listeners.indexOf(listener) < 0) {
      this.listeners.push(listener);
    }
  }

  removeStackListener(listener: SDStackListener): void {
    const index: number = this.listeners.indexOf(listener);
    if (index >= 0) {
      this.listeners.splice(index, 1);
    }
  }

  // ---------------------------------------------------------------------
  // 变更
  // ---------------------------------------------------------------------

  /**
   * Ability 重新创建窗口时调用：丢弃上一次的页面栈（进程可能还活着，例如 exitApp 之后再次启动）。
   */
  reset(): void {
    if (this.entries.length === 0) {
      return;
    }
    for (let i = 0; i < this.entries.length; i++) {
      this.entries[i].closing = true;
    }
    this.entries = [];
    this.pathStack.clear(false);
    this.notifyChanged();
  }

  /** 首次进入时压入根页面。 */
  ensureRoot(): void {
    if (this.entries.length > 0) {
      return;
    }
    const empty: KRRecord = {};
    const root: SDPageEntry = this.createEntry(AppConfig.ROOT_PAGE, empty);
    this.entries.push(root);
    this.pathStack.pushPathByName(root.routeName, root.pageName, false);
    this.notifyChanged();
  }

  /** 打开页面（RouterModule.openPage）。 */
  push(pageName: string, pageData: KRRecord | null | undefined): SDPageEntry | null {
    if (pageName.length === 0) {
      SDLog.e(TAG, 'push ignored: empty pageName');
      return null;
    }
    const entry: SDPageEntry = this.createEntry(pageName, pageData);
    this.entries.push(entry);
    this.pathStack.pushPathByName(entry.routeName, entry.pageName, entry.systemAnimated);
    this.notifyChanged();
    return entry;
  }

  /**
   * RouterModule.closePage：关闭「调用方」页面。
   *
   * Kuikly 的路由适配器接口不带调用方信息，这里按如下规则推断：自顶向下找到第一个
   * 「未在关闭中且 Kuikly 容器已创建」的页面。容器尚未创建的页面不可能发起调用，
   * 因此 `nav.push(X); nav.pop()` 这种连续调用关闭的是发起页而不是刚压入的 X。
   */
  closeFromRouter(): void {
    for (let i = this.entries.length - 1; i >= 0; i--) {
      const entry: SDPageEntry = this.entries[i];
      if (!entry.closing && entry.attached) {
        this.close(entry, true);
        return;
      }
    }
    SDLog.w(TAG, 'closeFromRouter: no page to close');
  }

  /**
   * 关闭指定页面（可以不是栈顶）。根页面不会被移除：对根页面调用等价于把应用退到后台。
   */
  close(entry: SDPageEntry, animated: boolean): void {
    if (entry.closing || this.entries.indexOf(entry) < 0) {
      return;
    }
    if (this.isRoot(entry)) {
      this.moveToBackground();
      return;
    }
    const wasTop: boolean = this.isTop(entry);
    entry.closing = true;
    this.notifyChanged();
    const animator: SDExitAnimator | null = entry.exitAnimator;
    if (animated && wasTop && entry.overlay && animator !== null) {
      // DIALOG 模式：先做页面自己的退场动画，再无动画出栈
      let finished: boolean = false;
      const finish: () => void = () => {
        if (finished) {
          return;
        }
        finished = true;
        this.removeEntry(entry, false);
      };
      try {
        animator(finish);
      } catch (e) {
        SDLog.e(TAG, `exit animation failed: ${SD.errMsg(e as Object)}`);
        finish();
      }
      // 兜底：动画回调丢失时也保证出栈
      setTimeout(finish, 600);
      return;
    }
    this.removeEntry(entry, animated && wasTop && entry.systemAnimated);
  }

  /**
   * 关闭 pageName 之上的所有页面；栈内没有该页面时回到根页面。返回是否命中。
   */
  popTo(pageName: string): boolean {
    let target: number = -1;
    for (let i = this.entries.length - 1; i >= 0; i--) {
      if (!this.entries[i].closing && this.entries[i].pageName === pageName) {
        target = i;
        break;
      }
    }
    const hit: boolean = target >= 0;
    this.popToIndex(hit ? target : 0);
    return hit;
  }

  popToRoot(): void {
    this.popToIndex(0);
  }

  /**
   * 关闭 caller 并打开新页面（Get.offNamed）。caller 为 null 时退化为 push。
   */
  replace(caller: SDPageEntry | null, pageName: string, pageData: KRRecord | null | undefined): void {
    const created: SDPageEntry | null = this.push(pageName, pageData);
    if (created === null || caller === null || caller.closing || this.isRoot(caller)) {
      return;
    }
    caller.closing = true;
    this.notifyChanged();
    // 新页面若自带入场动画（DIALOG 模式），等它盖住旧页面后再移除旧页面，避免露出更下面的页面
    const delay: number = created.overlay && created.transition !== SDPageEntry.TRANSITION_NONE ? 400 : 0;
    if (delay === 0) {
      this.removeEntry(caller, false);
    } else {
      setTimeout(() => {
        this.removeEntry(caller, false);
      }, delay);
    }
  }

  /**
   * 清空整个栈并以 pageName 作为新的根（Get.offAllNamed）。
   */
  resetRoot(pageName: string, pageData: KRRecord | null | undefined): void {
    if (pageName.length === 0) {
      SDLog.e(TAG, 'resetRoot ignored: empty pageName');
      return;
    }
    for (let i = 0; i < this.entries.length; i++) {
      this.entries[i].closing = true;
    }
    this.entries = [];
    this.pathStack.clear(false);
    const root: SDPageEntry = this.createEntry(pageName, pageData);
    this.entries.push(root);
    this.pathStack.pushPathByName(root.routeName, root.pageName, false);
    this.notifyChanged();
  }

  /** 把应用退到后台（根页面上未被消费的返回）。 */
  moveToBackground(): void {
    const context: common.UIAbilityContext | null = SDHost.context();
    if (context === null) {
      return;
    }
    context.moveAbilityToBackground().catch((e: Error) => {
      SDLog.e(TAG, `moveAbilityToBackground failed: ${SD.errMsg(e)}`);
    });
  }

  // ---------------------------------------------------------------------
  // 内部
  // ---------------------------------------------------------------------

  private popToIndex(index: number): void {
    if (index < 0 || index >= this.entries.length - 1) {
      return;
    }
    const target: SDPageEntry = this.entries[index];
    const removed: Array<SDPageEntry> = this.entries.splice(index + 1);
    let animated: boolean = false;
    for (let i = 0; i < removed.length; i++) {
      // 以原栈顶页面的转场类型决定是否走系统动画
      if (i === removed.length - 1) {
        animated = !removed[i].closing && removed[i].systemAnimated;
      }
      removed[i].closing = true;
    }
    this.pathStack.popToName(target.routeName, animated);
    this.notifyChanged();
  }

  private removeEntry(entry: SDPageEntry, animated: boolean): void {
    const index: number = this.entries.indexOf(entry);
    if (index < 0) {
      return;
    }
    const isLast: boolean = index === this.entries.length - 1;
    this.entries.splice(index, 1);
    if (isLast) {
      this.pathStack.pop(animated);
    } else {
      this.pathStack.removeByName(entry.routeName);
    }
    this.notifyChanged();
  }

  private createEntry(pageName: string, pageData: KRRecord | null | undefined): SDPageEntry {
    const data: KRRecord = {};
    if (pageData !== null && pageData !== undefined) {
      const keys: Array<string> = Object.keys(pageData);
      for (let i = 0; i < keys.length; i++) {
        data[keys[i]] = pageData[keys[i]];
      }
    }
    const view: Record<string, Object> = data as Record<string, Object>;
    const transition: string = SD.str(view, 'sdTransition', SDPageEntry.TRANSITION_DEFAULT);
    const translucent: boolean = SD.int(view, 'sdTranslucent', 0) === 1;
    // 宿主注入的环境参数（shared: AppEnv.initFrom）
    data['debug'] = AppConfig.DEBUG ? 1 : 0;
    data['apiEnv'] = AppConfig.API_ENV;
    data['showTokenEditor'] = AppConfig.SHOW_TOKEN_EDITOR ? 1 : 0;
    data['appVersionName'] = AppConfig.versionName();
    data['appVersionCode'] = AppConfig.versionCode();
    const entry: SDPageEntry = new SDPageEntry(this.nextId, pageName, data, transition, translucent);
    this.nextId += 1;
    return entry;
  }

  private notifyChanged(): void {
    const snapshot: Array<SDStackListener> = this.listeners.slice();
    for (let i = 0; i < snapshot.length; i++) {
      try {
        snapshot[i]();
      } catch (e) {
        SDLog.e(TAG, `stack listener failed: ${SD.errMsg(e as Object)}`);
      }
    }
  }
}
