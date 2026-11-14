/*
 * SDAppModule —— 应用 / 设备 / 系统 UI（契约见 shared/.../nativeapi/SDAppModule.kt）。
 *
 * 同步（同步模式模块，直接返回字符串 / JSON 字符串）：
 *   getAppInfo / getDeviceInfo / getInstallChannel / getSystemNavMode / getConnectivity /
 *   getPaths / getInitialLink / now / canOpenUrl
 * 异步无回参：
 *   setStatusBarStyle / setKeepScreenOn / setGestureExclusionRects / copyToClipboard / hideKeyboard /
 *   haptic / openAppSettings / openNotificationSettings / exitApp / openStoreListing
 * 异步有回参：
 *   openUrl → {ok, opened}   requestReview → {ok, shown}   share → {ok, status}
 * 常驻回调 addEventListener {listenerId}（removeEventListener {listenerId} 注销单个）：
 *   {event:"connectivity", types:[…]}  {event:"lifecycle", state}  {event:"keyboard", height}   向所有已注册页面广播
 *   {event:"deepLink", url}   只投递给「深链消费者」（调用过 getInitialLink 的页面）在 getInitialLink 之后注册的监听，
 *                             没有这样的监听时暂存，等 getInitialLink 读取或补发（规则见契约类注释）
 */
import { bundleManager, common, Want } from '@kit.AbilityKit';
import { uniformTypeDescriptor as utd } from '@kit.ArkData';
import { UIContext, window } from '@kit.ArkUI';
import { BusinessError, deviceInfo, pasteboard } from '@kit.BasicServicesKit';
import { fileUri } from '@kit.CoreFileKit';
import { inputMethod } from '@kit.IMEKit';
import { connection } from '@kit.NetworkKit';
import { vibrator } from '@kit.SensorServiceKit';
import { systemShare } from '@kit.ShareKit';
import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { AppConfig } from '../../AppConfig';
import { AppEvent, AppEvents } from '../../support/AppEvents';
import { EventBroadcaster, SD, SDHost, SDLog, SDParams, SDReply, SDResult } from '../../support/SDModuleSupport';
import { SDFiles } from './SDFileModule';

const TAG: string = 'SDAppModule';

const DEEP_LINK_SCHEME_PREFIX: string = 'sondo://';
const DEEP_LINK_HTTPS_PREFIX: string = 'https://go.sondo.vip/link';
const SETTINGS_BUNDLE: string = 'com.huawei.hmos.settings';
const SETTINGS_ABILITY: string = 'com.huawei.hmos.settings.MainAbility';

export class SDAppModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME: string = 'SDAppModule';

  // ---- 进程级状态（所有页面的模块实例共享） ----
  private static readonly events: EventBroadcaster = new EventBroadcaster();
  private static hooksInstalled: boolean = false;
  private static monitorsStarted: boolean = false;
  /** 待投递的深链（冷启动链接，或到达时没有消费者监听的链接）：getInitialLink 读取或补发给消费者监听后清空 */
  private static initialLink: string = '';
  /** 深链消费者：最近一次调用 getInitialLink 的模块实例（页面销毁时清空） */
  private static linkConsumer: SDAppModule | null = null;
  /** 消费者在 getInitialLink 之后注册的监听 */
  private static linkCallbacks: Array<KuiklyRenderCallback> = [];
  /** 消费者页面已销毁、还没有新的 getInitialLink：退回为向所有监听广播（与改动前一致） */
  private static linkConsumerLost: boolean = false;

  /** 本页面注册的监听：listenerId → callback（removeEventListener 用） */
  private listenerCallbacks: Map<string, KuiklyRenderCallback> = new Map<string, KuiklyRenderCallback>();
  private static netConnection: connection.NetConnection | null = null;
  private static lastConnectivity: string = '';
  private static connectivityTimer: number = -1;
  private static keyboardWindow: window.Window | null = null;
  private static lastKeyboardHeight: number = -1;

  /**
   * 订阅 AppEvents（深链 / 前后台）。在模块首次创建时安装；
   * 冷启动 Want 在 Ability.onCreate 时已经记录在 AppEvents 里，这里补读一次。
   */
  private static installHooks(): void {
    if (SDAppModule.hooksInstalled) {
      return;
    }
    SDAppModule.hooksInstalled = true;

    const launchWant: Want | null = AppEvents.launchWant();
    if (launchWant !== null) {
      const uri: string | undefined = launchWant.uri;
      if (uri !== undefined && uri !== null && SDAppModule.isDeepLink(uri)) {
        SDAppModule.initialLink = uri;
      }
    }
    // Ability 被销毁后在同一进程内重新创建（再次冷启动）：新的启动 Want 作为初始深链
    AppEvents.on(AppEvents.CREATE, (event: AppEvent) => {
      if (SDAppModule.isDeepLink(event.uri)) {
        SDAppModule.initialLink = event.uri;
      }
    });
    AppEvents.on(AppEvents.NEW_WANT, (event: AppEvent) => {
      if (!SDAppModule.isDeepLink(event.uri)) {
        return;
      }
      SDAppModule.deliverOrHold(event.uri);
    });
    AppEvents.on(AppEvents.FOREGROUND, (event: AppEvent) => {
      SDAppModule.emitLifecycle('resumed');
    });
    AppEvents.on(AppEvents.BACKGROUND, (event: AppEvent) => {
      SDAppModule.emitLifecycle('paused');
    });
  }

  private static isDeepLink(uri: string): boolean {
    if (uri.length === 0) {
      return false;
    }
    const lower: string = uri.toLowerCase();
    return lower.startsWith(DEEP_LINK_SCHEME_PREFIX) || lower.startsWith(DEEP_LINK_HTTPS_PREFIX);
  }

  /** 有消费者监听就投递，否则暂存（如隐私协议未同意、深链入口还没就绪），等 getInitialLink / 补发。 */
  private static deliverOrHold(url: string): void {
    const extra: SDResult = {};
    extra['url'] = url;
    if (SDAppModule.linkConsumer === null && SDAppModule.linkConsumerLost && SDAppModule.events.size > 0) {
      SDAppModule.events.emit(SD.event('deepLink', extra));
      return;
    }
    const targets: Array<KuiklyRenderCallback> = SDAppModule.linkConsumer === null ? [] : SDAppModule.linkCallbacks.slice();
    if (targets.length === 0) {
      SDAppModule.initialLink = url;
      return;
    }
    for (let i = 0; i < targets.length; i++) {
      SD.send(targets[i], SD.event('deepLink', extra));
    }
  }

  private static emitLifecycle(state: string): void {
    const extra: SDResult = {};
    extra['state'] = state;
    SDAppModule.events.emit(SD.event('lifecycle', extra));
  }

  constructor() {
    super();
    SDAppModule.installHooks();
  }

  syncMode(): boolean {
    return true;
  }

  onDestroy(): void {
    SDAppModule.events.removeOwner(this);
    this.listenerCallbacks.clear();
    if (SDAppModule.linkConsumer === this) {
      SDAppModule.linkConsumer = null;
      SDAppModule.linkCallbacks = [];
      SDAppModule.linkConsumerLost = true;
    }
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    const p: SDParams = SD.parse(params);
    try {
      switch (method) {
        // ---------------- 同步 ----------------
        case 'getAppInfo':
          return SD.json(this.getAppInfo());
        case 'getDeviceInfo':
          return SD.json(this.getDeviceInfo());
        case 'getInstallChannel':
          return '';
        case 'getSystemNavMode':
          return SD.json(this.getSystemNavMode());
        case 'getConnectivity': {
          const result: SDResult = {};
          result['types'] = SDAppModule.connectivityTypes();
          return SD.json(result);
        }
        case 'getPaths':
          return SD.json(this.getPaths());
        case 'getInitialLink': {
          SDAppModule.linkConsumerLost = false;
          if (SDAppModule.linkConsumer !== this) {
            SDAppModule.linkConsumer = this;
            SDAppModule.linkCallbacks = [];
          }
          const link: string = SDAppModule.initialLink;
          SDAppModule.initialLink = '';
          return link;
        }
        case 'now':
          return `${Date.now()}`;
        case 'canOpenUrl':
          return SD.flag(this.canOpenUrl(SD.str(p, 'url')));
        // ---------------- 异步无回参 ----------------
        case 'setStatusBarStyle':
          this.setStatusBarStyle(SD.str(p, 'style', 'light'));
          return null;
        case 'setKeepScreenOn':
          this.setKeepScreenOn(SD.bool(p, 'on', false));
          return null;
        case 'setGestureExclusionRects':
          // 仅 Android 有意义
          return null;
        case 'copyToClipboard':
          this.copyToClipboard(SD.str(p, 'text'));
          return null;
        case 'hideKeyboard':
          this.hideKeyboard();
          return null;
        case 'haptic':
          this.haptic(SD.str(p, 'type', 'light'));
          return null;
        case 'openAppSettings':
          this.openAppSettings();
          return null;
        case 'openNotificationSettings':
          this.openNotificationSettings();
          return null;
        case 'exitApp':
          this.exitApp();
          return null;
        case 'openStoreListing':
          this.openStoreListing(new SDReply(null));
          return null;
        // ---------------- 异步有回参 ----------------
        case 'openUrl':
          this.openUrl(SD.str(p, 'url'), SD.str(p, 'mode', 'external'), new SDReply(callback));
          return null;
        case 'requestReview':
          this.openStoreListing(new SDReply(callback));
          return null;
        case 'share':
          this.share(p, new SDReply(callback));
          return null;
        // ---------------- 常驻回调 ----------------
        case 'addEventListener':
          this.addEventListener(SD.str(p, 'listenerId'), callback);
          return null;
        case 'removeEventListener':
          this.removeEventListener(SD.str(p, 'listenerId'));
          return null;
        default:
          SDLog.w(TAG, `unknown method: ${method}`);
          SD.send(callback, SD.fail(`unknown method: ${method}`));
          return null;
      }
    } catch (e) {
      SDLog.e(TAG, `${method} failed: ${SD.errMsg(e as Object)}`);
      SD.send(callback, SD.fail(SD.errMsg(e as Object)));
      return SDAppModule.syncFallback(method);
    }
  }

  /** 同步方法出错时的回参：JSON 方法回 "{}"，布尔方法回 "0"，其余回空串。 */
  private static syncFallback(method: string): string {
    switch (method) {
      case 'getAppInfo':
      case 'getDeviceInfo':
      case 'getSystemNavMode':
      case 'getConnectivity':
      case 'getPaths':
        return '{}';
      case 'canOpenUrl':
        return '0';
      case 'now':
        return `${Date.now()}`;
      default:
        return '';
    }
  }

  // -----------------------------------------------------------------------
  // 同步信息
  // -----------------------------------------------------------------------

  private getAppInfo(): SDResult {
    const result: SDResult = {};
    let appName: string = 'Sondo';
    let version: string = AppConfig.versionName();
    let buildNumber: string = `${AppConfig.versionCode()}`;
    let packageName: string = AppConfig.bundleName();
    try {
      const info: bundleManager.BundleInfo = bundleManager.getBundleInfoForSelfSync(
        bundleManager.BundleFlag.GET_BUNDLE_INFO_WITH_APPLICATION);
      version = info.versionName;
      buildNumber = `${info.versionCode}`;
      packageName = info.name;
      const context: common.UIAbilityContext | null = SDHost.context();
      if (context !== null && info.appInfo.labelId > 0) {
        appName = context.resourceManager.getStringSync(info.appInfo.labelId);
      }
    } catch (e) {
      SDLog.w(TAG, `getAppInfo fallback: ${SD.errMsg(e as Object)}`);
    }
    result['appName'] = appName;
    result['version'] = version;
    result['buildNumber'] = buildNumber;
    result['packageName'] = packageName;
    return result;
  }

  private getDeviceInfo(): SDResult {
    const result: SDResult = {};
    result['platform'] = 'ohos';
    result['osVersion'] = deviceInfo.displayVersion.length > 0 ? deviceInfo.displayVersion : deviceInfo.osFullName;
    result['sdkInt'] = deviceInfo.sdkApiVersion;
    result['manufacturer'] = deviceInfo.manufacture;
    result['brand'] = deviceInfo.brand;
    result['model'] = deviceInfo.productModel;
    result['hardware'] = deviceInfo.hardwareModel;
    result['deviceName'] = deviceInfo.marketName;
    return result;
  }

  private getSystemNavMode(): SDResult {
    const result: SDResult = {};
    result['threeButton'] = false;
    let bottomInset: number = 0;
    const mainWindow: window.Window | null = SDHost.mainWindow();
    if (mainWindow !== null) {
      try {
        const area: window.AvoidArea =
          mainWindow.getWindowAvoidArea(window.AvoidAreaType.TYPE_NAVIGATION_INDICATOR);
        bottomInset = SDHost.px2vp(area.bottomRect.height);
      } catch (e) {
        SDLog.w(TAG, `getWindowAvoidArea failed: ${SD.errMsg(e as Object)}`);
      }
    }
    result['bottomInset'] = bottomInset;
    return result;
  }

  private getPaths(): SDResult {
    const result: SDResult = {};
    const context: common.UIAbilityContext = SDHost.requireContext();
    result['tempDir'] = context.tempDir;
    result['documentsDir'] = context.filesDir;
    result['cacheDir'] = context.cacheDir;
    return result;
  }

  private canOpenUrl(url: string): boolean {
    if (url.length === 0) {
      return false;
    }
    try {
      // 需要在 module.json5 的 querySchemes 里声明对应 scheme
      return bundleManager.canOpenLink(url);
    } catch (e) {
      SDLog.w(TAG, `canOpenLink(${url}) failed: ${SD.errMsg(e as Object)}`);
      return false;
    }
  }

  // -----------------------------------------------------------------------
  // 网络状态
  // -----------------------------------------------------------------------

  /** 当前默认网络的类型列表：["wifi"|"mobile"|"ethernet"] 或 ["none"]。 */
  private static connectivityTypes(): Array<string> {
    const types: Array<string> = [];
    try {
      const handle: connection.NetHandle = connection.getDefaultNetSync();
      if (handle.netId === 0) {
        types.push('none');
        return types;
      }
      const capabilities: connection.NetCapabilities = connection.getNetCapabilitiesSync(handle);
      const bearers: Array<connection.NetBearType> = capabilities.bearerTypes;
      for (let i = 0; i < bearers.length; i++) {
        let name: string = '';
        if (bearers[i] === connection.NetBearType.BEARER_WIFI) {
          name = 'wifi';
        } else if (bearers[i] === connection.NetBearType.BEARER_CELLULAR) {
          name = 'mobile';
        } else if (bearers[i] === connection.NetBearType.BEARER_ETHERNET) {
          name = 'ethernet';
        }
        if (name.length > 0 && types.indexOf(name) < 0) {
          types.push(name);
        }
      }
      if (types.length === 0) {
        // 有默认网络但类型未知（VPN / 蓝牙等）：按已联网处理
        types.push('wifi');
      }
    } catch (e) {
      // 查询失败（如未授权 GET_NETWORK_INFO）时返回空数组，Kuikly 侧按「已联网」处理
      SDLog.w(TAG, `connectivity query failed: ${SD.errMsg(e as Object)}`);
    }
    return types;
  }

  /** 网络事件去抖后广播（默认网络切换时会连续触发 lost / available）。 */
  private static scheduleConnectivityEmit(): void {
    if (SDAppModule.connectivityTimer >= 0) {
      clearTimeout(SDAppModule.connectivityTimer);
    }
    SDAppModule.connectivityTimer = setTimeout(() => {
      SDAppModule.connectivityTimer = -1;
      const types: Array<string> = SDAppModule.connectivityTypes();
      const signature: string = types.join(',');
      if (signature === SDAppModule.lastConnectivity) {
        return;
      }
      SDAppModule.lastConnectivity = signature;
      const extra: SDResult = {};
      extra['types'] = types;
      SDAppModule.events.emit(SD.event('connectivity', extra));
    }, 300);
  }

  private static startMonitors(): void {
    if (SDAppModule.monitorsStarted) {
      return;
    }
    SDAppModule.monitorsStarted = true;

    // 网络
    SDAppModule.lastConnectivity = SDAppModule.connectivityTypes().join(',');
    try {
      const net: connection.NetConnection = connection.createNetConnection();
      // 先 register 再订阅事件（register 之后才会收到通知）
      net.register((err: BusinessError) => {
        if (err) {
          SDLog.w(TAG, `net register failed: ${SD.errMsg(err)}`);
        }
      });
      net.on('netAvailable', () => {
        SDAppModule.scheduleConnectivityEmit();
      });
      net.on('netCapabilitiesChange', () => {
        SDAppModule.scheduleConnectivityEmit();
      });
      net.on('netLost', () => {
        SDAppModule.scheduleConnectivityEmit();
      });
      net.on('netUnavailable', () => {
        SDAppModule.scheduleConnectivityEmit();
      });
      SDAppModule.netConnection = net;
    } catch (e) {
      SDLog.w(TAG, `start connectivity monitor failed: ${SD.errMsg(e as Object)}`);
    }

    // 软键盘高度
    SDAppModule.attachKeyboardListener();
  }

  private static attachKeyboardListener(): void {
    const mainWindow: window.Window | null = SDHost.mainWindow();
    if (mainWindow === null || SDAppModule.keyboardWindow === mainWindow) {
      return;
    }
    try {
      mainWindow.on('keyboardHeightChange', (heightPx: number) => {
        const height: number = SDHost.px2vp(heightPx);
        if (height === SDAppModule.lastKeyboardHeight) {
          return;
        }
        SDAppModule.lastKeyboardHeight = height;
        const extra: SDResult = {};
        extra['height'] = height;
        SDAppModule.events.emit(SD.event('keyboard', extra));
      });
      SDAppModule.keyboardWindow = mainWindow;
    } catch (e) {
      SDLog.w(TAG, `listen keyboardHeightChange failed: ${SD.errMsg(e as Object)}`);
    }
  }

  private addEventListener(listenerId: string, callback: KuiklyRenderCallback | null): void {
    if (callback === null) {
      return;
    }
    SDAppModule.events.add(this, callback);
    if (listenerId.length > 0) {
      this.listenerCallbacks.set(listenerId, callback);
    }
    SDAppModule.startMonitors();
    // 窗口重建后（Ability 被销毁重建）重新挂键盘监听
    SDAppModule.attachKeyboardListener();
    // 深链消费者在 getInitialLink 之后注册的监听：接收 deepLink，并补发期间暂存的链接
    if (SDAppModule.linkConsumer === this) {
      SDAppModule.linkCallbacks.push(callback);
      if (SDAppModule.initialLink.length > 0) {
        const link: string = SDAppModule.initialLink;
        SDAppModule.initialLink = '';
        SDAppModule.deliverOrHold(link);
      }
    }
  }

  /** Kuikly 侧注销单个监听（根页面常驻，靠页面销毁释放不够）。 */
  private removeEventListener(listenerId: string): void {
    const callback: KuiklyRenderCallback | undefined = this.listenerCallbacks.get(listenerId);
    if (callback === undefined) {
      return;
    }
    this.listenerCallbacks.delete(listenerId);
    SDAppModule.events.remove(callback);
    SDAppModule.linkCallbacks = SDAppModule.linkCallbacks.filter((cb: KuiklyRenderCallback) => cb !== callback);
  }

  // -----------------------------------------------------------------------
  // 系统 UI
  // -----------------------------------------------------------------------

  private setStatusBarStyle(style: string): void {
    const mainWindow: window.Window | null = SDHost.mainWindow();
    if (mainWindow === null) {
      return;
    }
    // "light" = 浅色图标（深色背景用）；"dark" = 深色图标
    const contentColor: string = style === 'dark' ? '#FF000000' : '#FFFFFFFF';
    const props: window.SystemBarProperties = {
      statusBarContentColor: contentColor,
      navigationBarContentColor: contentColor
    };
    mainWindow.setWindowSystemBarProperties(props).catch((e: BusinessError) => {
      SDLog.w(TAG, `setWindowSystemBarProperties failed: ${SD.errMsg(e)}`);
    });
  }

  private setKeepScreenOn(on: boolean): void {
    const mainWindow: window.Window | null = SDHost.mainWindow();
    if (mainWindow === null) {
      return;
    }
    mainWindow.setWindowKeepScreenOn(on).catch((e: BusinessError) => {
      SDLog.w(TAG, `setWindowKeepScreenOn failed: ${SD.errMsg(e)}`);
    });
  }

  private copyToClipboard(text: string): void {
    const data: pasteboard.PasteData = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text);
    pasteboard.getSystemPasteboard().setData(data).catch((e: BusinessError) => {
      SDLog.w(TAG, `copyToClipboard failed: ${SD.errMsg(e)}`);
    });
  }

  private hideKeyboard(): void {
    const ui: UIContext | null = SDHost.uiContext();
    if (ui !== null) {
      try {
        ui.getFocusController().clearFocus();
      } catch (e) {
        SDLog.d(TAG, `clearFocus failed: ${SD.errMsg(e as Object)}`);
      }
    }
    try {
      inputMethod.getController().stopInputSession().catch((e: BusinessError) => {
        SDLog.d(TAG, `stopInputSession failed: ${SD.errMsg(e)}`);
      });
    } catch (e) {
      SDLog.d(TAG, `stopInputSession failed: ${SD.errMsg(e as Object)}`);
    }
  }

  private haptic(type: string): void {
    let duration: number = 10;
    if (type === 'medium') {
      duration = 20;
    } else if (type === 'heavy') {
      duration = 35;
    } else if (type === 'selection') {
      duration = 5;
    }
    try {
      const effect: vibrator.VibrateTime = { type: 'time', duration: duration };
      const attribute: vibrator.VibrateAttribute = { usage: 'touch' };
      vibrator.startVibration(effect, attribute).catch((e: BusinessError) => {
        SDLog.d(TAG, `haptic failed: ${SD.errMsg(e)}`);
      });
    } catch (e) {
      SDLog.d(TAG, `haptic failed: ${SD.errMsg(e as Object)}`);
    }
  }

  // -----------------------------------------------------------------------
  // 跳转
  // -----------------------------------------------------------------------

  /**
   * 打开外部链接。鸿蒙没有系统级「应用内浏览器」，mode="inApp" 同样交给系统浏览器 / 目标应用。
   * 回参 {ok:true, opened}。
   */
  private openUrl(url: string, mode: string, reply: SDReply): void {
    const result: SDResult = {};
    const context: common.UIAbilityContext | null = SDHost.context();
    if (url.length === 0 || context === null) {
      result['opened'] = false;
      reply.ok(result);
      return;
    }
    this.launchUrl(context, url)
      .then((opened: boolean) => {
        result['opened'] = opened;
        reply.ok(result);
      })
      .catch((e: Error) => {
        result['opened'] = false;
        reply.ok(result);
      });
  }

  private async launchUrl(context: common.UIAbilityContext, url: string): Promise<boolean> {
    const lower: string = url.toLowerCase();
    if (lower.startsWith('mailto:')) {
      const mailWant: Want = { action: 'ohos.want.action.sendToData', uri: url };
      try {
        await context.startAbility(mailWant);
        return true;
      } catch (e) {
        SDLog.w(TAG, `open mailto failed: ${SD.errMsg(e as Object)}`);
        return false;
      }
    }
    try {
      await context.openLink(url, { appLinkingOnly: false });
      return true;
    } catch (e) {
      SDLog.i(TAG, `openLink failed, fallback to viewData: ${SD.errMsg(e as Object)}`);
    }
    const want: Want = {
      action: 'ohos.want.action.viewData',
      entities: ['entity.system.browsable'],
      uri: url
    };
    try {
      await context.startAbility(want);
      return true;
    } catch (e) {
      SDLog.w(TAG, `open url failed: ${SD.errMsg(e as Object)}`);
      return false;
    }
  }

  /** 应用详情设置页。 */
  private openAppSettings(): void {
    const context: common.UIAbilityContext | null = SDHost.context();
    if (context === null) {
      return;
    }
    const want: Want = {
      bundleName: SETTINGS_BUNDLE,
      abilityName: SETTINGS_ABILITY,
      uri: 'application_info_entry',
      parameters: {
        'pushParams': AppConfig.bundleName()
      }
    };
    context.startAbility(want).catch((e: BusinessError) => {
      SDLog.w(TAG, `openAppSettings failed: ${SD.errMsg(e)}`);
    });
  }

  /** 本应用的通知设置页；失败时退回应用详情设置页。 */
  private openNotificationSettings(): void {
    const context: common.UIAbilityContext | null = SDHost.context();
    if (context === null) {
      return;
    }
    const pushParams: Record<string, Object> = {};
    pushParams['bundleName'] = AppConfig.bundleName();
    const want: Want = {
      bundleName: SETTINGS_BUNDLE,
      abilityName: SETTINGS_ABILITY,
      uri: 'systemui_notification_settings',
      parameters: {
        'pushParams': pushParams
      }
    };
    context.startAbility(want).catch((e: BusinessError) => {
      SDLog.w(TAG, `openNotificationSettings failed, fallback: ${SD.errMsg(e)}`);
      this.openAppSettings();
    });
  }

  private exitApp(): void {
    const context: common.UIAbilityContext | null = SDHost.context();
    if (context === null) {
      return;
    }
    context.terminateSelf().catch((e: BusinessError) => {
      SDLog.w(TAG, `terminateSelf failed: ${SD.errMsg(e)}`);
    });
  }

  /**
   * 打开应用市场（AppGallery）详情页。requestReview 也走这里：
   * 鸿蒙没有等价于 in_app_review 的通用应用内评分面板，评分入口在详情页。回参 {ok, shown}。
   */
  private openStoreListing(reply: SDReply): void {
    const result: SDResult = {};
    const context: common.UIAbilityContext | null = SDHost.context();
    if (context === null) {
      result['shown'] = false;
      reply.ok(result);
      return;
    }
    const want: Want = {
      action: 'ohos.want.action.appdetail',
      uri: `store://appgallery.huawei.com/app/detail?id=${AppConfig.bundleName()}`
    };
    context.startAbility(want)
      .then(() => {
        result['shown'] = true;
        reply.ok(result);
      })
      .catch((e: BusinessError) => {
        SDLog.w(TAG, `openStoreListing failed: ${SD.errMsg(e)}`);
        result['shown'] = false;
        reply.ok(result);
      });
  }

  // -----------------------------------------------------------------------
  // 系统分享
  // -----------------------------------------------------------------------

  /**
   * params: {text?, subject?, files?: [path], mimeType?} → {ok, status}
   * 系统分享面板不回传用户是否真的完成了分享，只有「面板关闭」事件，无法区分完成与取消：
   * 按契约面板关闭一律 → "dismissed"（Kotlin 侧不提示分享成功、不累加转发数）；拉起失败 → "unavailable"。
   */
  private share(p: SDParams, reply: SDReply): void {
    const result: SDResult = {};
    const context: common.UIAbilityContext | null = SDHost.context();
    const text: string = SD.str(p, 'text');
    const subject: string = SD.str(p, 'subject');
    const files: Array<string> = SD.strList(p, 'files');
    const mimeType: string = SD.str(p, 'mimeType');
    if (context === null || (text.length === 0 && files.length === 0)) {
      result['status'] = 'unavailable';
      reply.ok(result);
      return;
    }
    try {
      let data: systemShare.SharedData | null = null;
      if (text.length > 0) {
        const isLink: boolean = text.startsWith('http://') || text.startsWith('https://');
        const record: systemShare.SharedRecord = {
          utd: isLink ? utd.UniformDataType.HYPERLINK : utd.UniformDataType.PLAIN_TEXT,
          content: text
        };
        if (subject.length > 0) {
          record.title = subject;
        }
        data = new systemShare.SharedData(record);
      }
      for (let i = 0; i < files.length; i++) {
        const path: string = SDFiles.toPath(files[i]);
        const record: systemShare.SharedRecord = {
          utd: this.fileUtd(path, mimeType),
          uri: fileUri.getUriFromPath(path)
        };
        if (data === null) {
          if (subject.length > 0) {
            record.title = subject;
          }
          data = new systemShare.SharedData(record);
        } else {
          data.addRecord(record);
        }
      }
      if (data === null) {
        result['status'] = 'unavailable';
        reply.ok(result);
        return;
      }
      const controller: systemShare.ShareController = new systemShare.ShareController(data);
      controller.on('dismiss', () => {
        result['status'] = 'dismissed';
        reply.ok(result);
      });
      controller.show(context, {
        previewMode: systemShare.SharePreviewMode.DETAIL,
        selectionMode: systemShare.SelectionMode.SINGLE
      }).catch((e: BusinessError) => {
        SDLog.w(TAG, `share show failed: ${SD.errMsg(e)}`);
        result['status'] = 'unavailable';
        reply.ok(result);
      });
    } catch (e) {
      SDLog.w(TAG, `share failed: ${SD.errMsg(e as Object)}`);
      result['status'] = 'unavailable';
      reply.ok(result);
    }
  }

  /** 文件的统一数据类型：优先 mimeType，其次扩展名，兜底 general.file。 */
  private fileUtd(path: string, mimeType: string): string {
    try {
      if (mimeType.length > 0) {
        const byMime: string = utd.getUniformDataTypeByMIMEType(mimeType);
        if (byMime.length > 0) {
          return byMime;
        }
      }
      const dot: number = path.lastIndexOf('.');
      if (dot >= 0 && dot > path.lastIndexOf('/')) {
        const byExt: string = utd.getUniformDataTypeByFilenameExtension(path.substring(dot).toLowerCase());
        if (byExt.length > 0) {
          return byExt;
        }
      }
    } catch (e) {
      SDLog.d(TAG, `resolve utd failed: ${SD.errMsg(e as Object)}`);
    }
    return utd.UniformDataType.FILE;
  }
}
