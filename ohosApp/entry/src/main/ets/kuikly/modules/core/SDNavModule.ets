/*
 * SDNavModule —— 页面栈操作（契约见 shared/.../nativeapi/SDNavModule.kt）。
 *
 *   popTo {pageName}            sync → "1" / "0"
 *   popToRoot
 *   replace {pageName, pageData}
 *   resetRoot {pageName, pageData}
 *   stack                       sync → JSON 数组字符串（自底向上的 pageName）
 *   setSwipeBackEnabled {enabled}
 *
 * 有同步方法，所以是同步模式模块。
 */
import { KRAny, KRRecord, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { SDNavigator, SDPageEntry } from '../../container/SDNavigator';
import { SD, SDParams } from '../../support/SDModuleSupport';

export class SDNavModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME: string = 'SDNavModule';

  syncMode(): boolean {
    return true;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    const p: SDParams = SD.parse(params);
    const navigator: SDNavigator = SDNavigator.shared();
    switch (method) {
      case 'popTo':
        return SD.flag(navigator.popTo(SD.str(p, 'pageName')));
      case 'popToRoot':
        navigator.popToRoot();
        return null;
      case 'replace':
        navigator.replace(this.callerEntry(), SD.str(p, 'pageName'), SD.obj(p, 'pageData') as KRRecord);
        return null;
      case 'resetRoot':
        navigator.resetRoot(SD.str(p, 'pageName'), SD.obj(p, 'pageData') as KRRecord);
        return null;
      case 'stack':
        return JSON.stringify(navigator.stackNames());
      case 'setSwipeBackEnabled': {
        const entry: SDPageEntry | null = this.callerEntry();
        if (entry !== null) {
          entry.swipeBackEnabled = SD.bool(p, 'enabled', true);
        }
        return null;
      }
      default:
        return null;
    }
  }

  /** 发起调用的页面：模块实例属于某个 Kuikly 页面，通过其 controller 反查；查不到时取栈顶。 */
  private callerEntry(): SDPageEntry | null {
    const navigator: SDNavigator = SDNavigator.shared();
    const entry: SDPageEntry | null = navigator.entryForController(this.controller);
    return entry !== null ? entry : navigator.topEntry();
  }
}
