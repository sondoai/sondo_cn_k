/*
 * 应用唯一的 ArkUI 页面：一个 Navigation 承载全部 Kuikly 页面。
 * 根页面 launch 与后续 push 的页面都是 NavDestination（见 kuikly/container/KuiklyPage.ets）。
 */
import { AppConfig } from '../kuikly/AppConfig';
import { KuiklyPage } from '../kuikly/container/KuiklyPage';
import { SDNavigator } from '../kuikly/container/SDNavigator';

@Entry
@Component
struct Index {
  private navStack: NavPathStack = SDNavigator.shared().pathStack;

  aboutToAppear(): void {
    SDNavigator.shared().ensureRoot();
  }

  @Builder
  pageMap(name: string) {
    KuiklyPage({ routeName: name })
  }

  build() {
    Navigation(this.navStack) {
    }
    .mode(NavigationMode.Stack)
    .hideTitleBar(true)
    .hideToolBar(true)
    .hideBackButton(true)
    .navDestination(this.pageMap)
    .width('100%')
    .height('100%')
    .backgroundColor(AppConfig.WINDOW_BACKGROUND)
    .expandSafeArea([SafeAreaType.SYSTEM, SafeAreaType.KEYBOARD])
  }
}
