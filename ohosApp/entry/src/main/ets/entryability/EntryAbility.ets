/*
 * 应用入口 Ability。
 *
 * - 窗口：沉浸式全屏布局、黑色底、状态栏浅色图标、竖屏；
 * - Kuikly：注册适配器（路由 / 日志）与自定义字体，加载唯一页面 pages/Index；
 * - 生命周期 / Want：全部转发到 AppEvents，原生模块在那里订阅（深链、SDK 回跳、前后台）。
 */
import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { KuiklyRenderAdapterManager } from '@kuikly-open/render';
import { AppConfig } from '../kuikly/AppConfig';
import { AppFonts } from '../kuikly/adapters/AppFonts';
import { AppKRLogAdapter } from '../kuikly/adapters/AppKRLogAdapter';
import { AppKRRouterAdapter } from '../kuikly/adapters/AppKRRouterAdapter';
import { SDNavigator } from '../kuikly/container/SDNavigator';
import { AppEvents } from '../kuikly/support/AppEvents';
import { SD, SDHost, SDLog } from '../kuikly/support/SDModuleSupport';

const TAG: string = 'EntryAbility';

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    SDLog.i(TAG, 'onCreate');
    SDHost.attachAbility(this.context);
    KuiklyRenderAdapterManager.krLogAdapter = new AppKRLogAdapter();
    KuiklyRenderAdapterManager.krRouterAdapter = new AppKRRouterAdapter();
    AppEvents.dispatchCreate(want);
  }

  onNewWant(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    SDLog.i(TAG, 'onNewWant');
    AppEvents.dispatchNewWant(want);
  }

  onDestroy(): void {
    SDLog.i(TAG, 'onDestroy');
    AppEvents.dispatchSimple(AppEvents.DESTROY);
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    SDLog.i(TAG, 'onWindowStageCreate');
    SDHost.attachWindowStage(windowStage);
    // 新窗口从干净的页面栈开始（pages/Index 出现时再压入根页面）
    SDNavigator.shared().reset();
    this.setupWindow();

    try {
      windowStage.on('windowStageEvent', (event: window.WindowStageEventType) => {
        if (event === window.WindowStageEventType.ACTIVE) {
          AppEvents.dispatchSimple(AppEvents.ACTIVE);
        } else if (event === window.WindowStageEventType.INACTIVE) {
          AppEvents.dispatchSimple(AppEvents.INACTIVE);
        }
      });
    } catch (e) {
      SDLog.e(TAG, `listen windowStageEvent failed: ${SD.errMsg(e as Object)}`);
    }

    windowStage.loadContent('pages/Index', (err: BusinessError) => {
      if (err.code) {
        SDLog.e(TAG, `loadContent failed: ${SD.errMsg(err)}`);
        return;
      }
      const mainWindow: window.Window | null = SDHost.mainWindow();
      if (mainWindow === null) {
        return;
      }
      try {
        // 需在 loadContent 生效后调用
        mainWindow.setWindowBackgroundColor(AppConfig.WINDOW_BACKGROUND);
      } catch (e) {
        SDLog.e(TAG, `setWindowBackgroundColor failed: ${SD.errMsg(e as Object)}`);
      }
      try {
        AppFonts.registerAll(mainWindow.getUIContext());
      } catch (e) {
        SDLog.e(TAG, `register fonts failed: ${SD.errMsg(e as Object)}`);
      }
    });
  }

  onWindowStageDestroy(): void {
    SDLog.i(TAG, 'onWindowStageDestroy');
    SDHost.detachWindowStage();
  }

  onForeground(): void {
    AppEvents.dispatchForeground();
  }

  onBackground(): void {
    AppEvents.dispatchBackground();
  }

  /** 沉浸式：内容铺满整窗（状态栏 / 导航条区域由 Kuikly 页面按 safeArea 自己避让）。 */
  private setupWindow(): void {
    const mainWindow: window.Window | null = SDHost.mainWindow();
    if (mainWindow === null) {
      SDLog.e(TAG, 'main window unavailable');
      return;
    }
    mainWindow.setWindowLayoutFullScreen(true).catch((e: BusinessError) => {
      SDLog.e(TAG, `setWindowLayoutFullScreen failed: ${SD.errMsg(e)}`);
    });
    // 全站深色主题：状态栏 / 导航条透明底 + 浅色图标
    const barProps: window.SystemBarProperties = {
      statusBarColor: '#00000000',
      statusBarContentColor: '#FFFFFFFF',
      navigationBarColor: '#00000000',
      navigationBarContentColor: '#FFFFFFFF'
    };
    mainWindow.setWindowSystemBarProperties(barProps).catch((e: BusinessError) => {
      SDLog.e(TAG, `setWindowSystemBarProperties failed: ${SD.errMsg(e)}`);
    });
    mainWindow.setPreferredOrientation(window.Orientation.PORTRAIT).catch((e: BusinessError) => {
      SDLog.e(TAG, `setPreferredOrientation failed: ${SD.errMsg(e)}`);
    });
  }
}
