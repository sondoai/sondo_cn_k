/*
 * Kuikly 页面容器：一个 NavDestination 承载一个 Kuikly Pager。
 * 由 pages/Index 的 Navigation.navDestination 按路由名创建，页面信息从 SDNavigator 取。
 */
import { display, UIContext } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import {
  KRNativeRenderController,
  KRRenderExecuteModeBase,
  KRRenderNativeMode,
  Kuikly
} from '@kuikly-open/render';
import { AppConfig } from '../AppConfig';
import { KuiklyViewDelegate } from '../KuiklyViewDelegate';
import globalNativeManager from '../MyNativeManager';
import { HotReloadListener, SondoHotReload } from '../hotreload/SondoHotReload';
import { AppEvent, AppEventListener, AppEvents } from '../support/AppEvents';
import { SD, SDLog } from '../support/SDModuleSupport';
import { SDNavigator, SDPageEntry, SDStackListener } from './SDNavigator';

const TAG: string = 'KuiklyPage';
const ENTER_DURATION_MS: number = 300;
const EXIT_DURATION_MS: number = 250;

@Component
export struct KuiklyPage {
  /** NavPathStack 里的路由名（page_<id>） */
  routeName: string = '';

  @State private offsetY: number = 0;
  @State private contentOpacity: number = 1;
  @State private renderReady: boolean = !SondoHotReload.enabled;
  @State private renderGeneration: number = 0;

  private entry: SDPageEntry = new SDPageEntry(0, AppConfig.ROOT_PAGE, {}, SDPageEntry.TRANSITION_NONE, false);
  private delegate: KuiklyViewDelegate = new KuiklyViewDelegate();
  private controller: KRNativeRenderController | null = null;
  /** NavDestination 是否处于显示状态（onShown / onHidden） */
  private shown: boolean = false;
  /** 是否已向 Kuikly 发送 pageDidAppear（显示中且位于栈顶） */
  private active: boolean = false;
  private entered: boolean = false;
  private loadedVersion: string = '';
  private reloadListener: HotReloadListener = (version: string) => {
    if (version === this.loadedVersion) return;
    this.loadedVersion = version;
    if (this.active) {
      this.active = false;
      this.delegate.pageDidDisappear();
    }
    this.entry.controller = null;
    this.entry.attached = false;
    this.controller = null;
    this.delegate = new KuiklyViewDelegate();
    this.renderGeneration++;
    this.renderReady = true;
  };
  private stackListener: SDStackListener = () => {
    this.updateActive();
  };
  private appStateListener: AppEventListener = (event: AppEvent) => {
    this.updateActive();
  };

  aboutToAppear(): void {
    const found: SDPageEntry | null = SDNavigator.shared().entryForRoute(this.routeName);
    if (found !== null) {
      this.entry = found;
    } else {
      SDLog.e(TAG, `no page entry for route ${this.routeName}`);
    }
    // 入场动画的起始状态
    if (this.entry.transition === SDPageEntry.TRANSITION_BOTTOM) {
      this.offsetY = this.windowHeightVp();
    } else if (this.entry.transition === SDPageEntry.TRANSITION_FADE) {
      this.contentOpacity = 0;
    }
    if (this.entry.overlay) {
      this.entry.exitAnimator = (done: () => void) => {
        this.runExit(done);
      };
    }
    SDNavigator.shared().addStackListener(this.stackListener);
    AppEvents.on(AppEvents.FOREGROUND, this.appStateListener);
    AppEvents.on(AppEvents.BACKGROUND, this.appStateListener);
    SondoHotReload.subscribe(this.reloadListener);
  }

  aboutToDisappear(): void {
    SondoHotReload.unsubscribe(this.reloadListener);
    SDNavigator.shared().removeStackListener(this.stackListener);
    AppEvents.off(AppEvents.FOREGROUND, this.appStateListener);
    AppEvents.off(AppEvents.BACKGROUND, this.appStateListener);
    this.entry.exitAnimator = null;
    this.entry.controller = null;
    this.entry.attached = false;
    this.shown = false;
    // 父组件的 aboutToDisappear 先于内部 Kuikly 组件执行：销毁前补发一次不可见
    if (this.active) {
      this.active = false;
      this.delegate.pageDidDisappear();
    }
  }

  build() {
    NavDestination() {
      Stack({ alignContent: Alignment.TopStart }) {
        if (this.renderReady) {
          // key变化销毁旧容器并创建新Pager，进程、原生导航栈和宿主能力持续存活。
          ForEach([this.renderGeneration], (generation: number) => {
        Kuikly({
          pageName: this.entry.pageName,
          pageData: this.entry.pageData,
          delegate: this.delegate,
          contextCode: '',
          executeMode: KRRenderNativeMode.Native,
          onControllerReadyCallback: (controller: KRNativeRenderController) => {
            this.onControllerReady(controller);
          },
          nativeManager: globalNativeManager
        })
          }, (generation: number) => generation.toString())
        } else {
          Column({ space: 16 }) {
            Text('等待开发服务生成 Kuikly 页面…')
              .fontColor(Color.White)
            Button('重新连接')
              .onClick(() => { SondoHotReload.refresh(); })
          }
          .width('100%')
          .height('100%')
          .justifyContent(FlexAlign.Center)
        }
      }
      .width('100%')
      .height('100%')
      .backgroundColor(this.entry.translucent ? Color.Transparent : AppConfig.WINDOW_BACKGROUND)
      .translate({ x: 0, y: this.offsetY })
      .opacity(this.contentOpacity)
      .expandSafeArea([SafeAreaType.SYSTEM, SafeAreaType.KEYBOARD])
      .onAppear(() => {
        this.runEnter();
      })
    }
    .hideTitleBar(true)
    .mode(this.entry.overlay ? NavDestinationMode.DIALOG : NavDestinationMode.STANDARD)
    .backgroundColor(this.entry.overlay ? Color.Transparent : AppConfig.WINDOW_BACKGROUND)
    .expandSafeArea([SafeAreaType.SYSTEM, SafeAreaType.KEYBOARD])
    .onShown(() => {
      this.shown = true;
      this.updateActive();
    })
    .onHidden(() => {
      this.shown = false;
      this.updateActive();
    })
    .onBackPressed((): boolean => {
      return this.handleBackPressed();
    })
  }

  private onControllerReady(controller: KRNativeRenderController): void {
    this.controller = controller;
    this.entry.controller = controller;
    this.entry.attached = true;
    controller.registerExceptionCallback((executeMode: KRRenderExecuteModeBase, stack: string) => {
      this.logException(stack);
    });
    this.updateActive();
  }

  /**
   * 页面可见性：应用在前台、NavDestination 显示中且位于栈顶才算可见。
   * DIALOG 模式的页面盖在上面时，下面的页面不会收到 onHidden，这里按栈顶补发，
   * 与 Android（上层 Activity 盖住 / 应用退后台时 onPause）保持一致。
   */
  private updateActive(): void {
    const now: boolean = this.shown && AppEvents.isForeground() && SDNavigator.shared().isTop(this.entry);
    if (now === this.active) {
      return;
    }
    this.active = now;
    if (now) {
      this.delegate.pageDidAppear();
    } else {
      this.delegate.pageDidDisappear();
    }
  }

  /**
   * 系统返回（侧滑 / 返回键）。始终拦截，由这里决定是否关闭页面：
   * 1. 先交给 Kuikly（BackPressHandler：弹窗、页面自定义拦截）；
   * 2. 未消费且允许侧滑返回时关闭当前页；根页面则把应用退到后台。
   */
  private handleBackPressed(): boolean {
    if (this.entry.closing) {
      return true;
    }
    let consumed: boolean = false;
    const controller: KRNativeRenderController | null = this.controller;
    if (controller !== null) {
      try {
        consumed = controller.onBackPress();
      } catch (e) {
        SDLog.e(TAG, `onBackPress failed: ${SD.errMsg(e as Object)}`);
      }
    }
    if (consumed) {
      return true;
    }
    if (!this.entry.swipeBackEnabled) {
      return true;
    }
    SDNavigator.shared().close(this.entry, true);
    return true;
  }

  private runEnter(): void {
    if (this.entered) {
      return;
    }
    this.entered = true;
    if (this.offsetY === 0 && this.contentOpacity === 1) {
      return;
    }
    this.getUIContext().animateTo({ duration: ENTER_DURATION_MS, curve: Curve.EaseOut }, () => {
      this.offsetY = 0;
      this.contentOpacity = 1;
    });
  }

  private runExit(done: () => void): void {
    const transition: string = this.entry.transition;
    if (transition !== SDPageEntry.TRANSITION_BOTTOM && transition !== SDPageEntry.TRANSITION_FADE) {
      done();
      return;
    }
    const targetOffset: number = transition === SDPageEntry.TRANSITION_BOTTOM ? this.windowHeightVp() : 0;
    const targetOpacity: number = transition === SDPageEntry.TRANSITION_FADE ? 0 : 1;
    this.getUIContext().animateTo({
      duration: EXIT_DURATION_MS,
      curve: Curve.EaseIn,
      onFinish: () => {
        done();
      }
    }, () => {
      this.offsetY = targetOffset;
      this.contentOpacity = targetOpacity;
    });
  }

  private windowHeightVp(): number {
    try {
      const ui: UIContext = this.getUIContext();
      const height: number = ui.px2vp(display.getDefaultDisplaySync().height);
      return height > 0 ? height : 1000;
    } catch (e) {
      return 1000;
    }
  }

  private logException(stack: string): void {
    // 逐行输出，避免被 hilog 截断
    const lines: Array<string> = stack.split('\n');
    for (let i = 0; i < lines.length; i++) {
      hilog.error(0x5D03, 'KuiklyException', '%{public}s', lines[i]);
    }
  }
}
