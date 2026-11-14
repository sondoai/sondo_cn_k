/*
 * Kuikly 路由适配器：RouterModule.openPage / closePage → 原生页面栈（SDNavigator）。
 */
import { common } from '@kit.AbilityKit';
import { IKRRouterAdapter, KRRecord } from '@kuikly-open/render';
import { SDNavigator } from '../container/SDNavigator';

export class AppKRRouterAdapter implements IKRRouterAdapter {
  openPage(context: common.UIAbilityContext, pageName: string, pageData: KRRecord): void {
    SDNavigator.shared().push(pageName, pageData);
  }

  closePage(context: common.UIAbilityContext): void {
    // 适配器拿不到「是哪个页面调用的」，由 SDNavigator 推断（见 closeFromRouter 注释）。
    SDNavigator.shared().closeFromRouter();
  }
}
