/*
 * SDWebView —— 内置网页（隐私政策 / 用户协议 / 活动页），ArkWeb 的 Web 组件。
 * 契约见 shared/.../nativeapi/SDNativeViews.kt。假定 API 12（HarmonyOS NEXT 5.0）。
 *
 *  - 属性 url；事件 sdTitle {title}、sdLoad {state:"start"|"finish"|"error", progress(0..1)}；
 *    方法 reload / goBack（无法后退时不做任何事）。
 *  - 主文档的非 http(s) 链接（weixin://、alipays://、tel:、mailto:、应用市场…）不在 WebView 里加载，
 *    交给系统：先 openLink，失败再 startAbility(viewData)。
 *  - 需要 ohos.permission.INTERNET。
 */
import { KRAny, KRRecord, KuiklyRenderBaseView, KuiklyRenderCallback } from '@kuikly-open/render';
import { ComponentContent, UIContext } from '@kit.ArkUI';
import { webview } from '@kit.ArkWeb';
import { common, Want } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { SD, SDLog } from '../support/SDModuleSupport';
import { MediaSupport } from '../modules/media/MediaSupport';

const TAG: string = 'SDWebView';

export class SDWebView extends KuiklyRenderBaseView {
  static readonly VIEW_NAME: string = 'SDWebView';

  url: string = '';
  readonly controller: webview.WebviewController = new webview.WebviewController();
  /** Web 组件已与 controller 绑定（之前调用 controller 的方法会抛 17100001）。 */
  private attached: boolean = false;
  /** Web 组件创建时使用的初始地址。 */
  private initialUrl: string = '';
  private loading: boolean = false;
  private progress: number = 0;
  private titleCallback: KuiklyRenderCallback | null = null;
  private loadCallback: KuiklyRenderCallback | null = null;
  private destroyed: boolean = false;

  createArkUIView(): ComponentContent<KuiklyRenderBaseView> {
    const uiContext: UIContext = this.getUIContext() as UIContext;
    return new ComponentContent<KuiklyRenderBaseView>(uiContext,
      wrapBuilder<[KuiklyRenderBaseView]>(buildSDWebView), this);
  }

  setProp(propKey: string, propValue: KRAny | KuiklyRenderCallback): boolean {
    switch (propKey) {
      case 'url':
        this.setUrl(typeof propValue === 'string' ? propValue as string : '');
        return true;
      case 'sdTitle':
        this.titleCallback = propValue as KuiklyRenderCallback;
        return true;
      case 'sdLoad':
        this.loadCallback = propValue as KuiklyRenderCallback;
        return true;
      default:
        return super.setProp(propKey, propValue);
    }
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): void {
    switch (method) {
      case 'reload':
        this.withController((): void => {
          this.controller.refresh();
        });
        break;
      case 'goBack':
        this.withController((): void => {
          if (this.controller.accessBackward()) {
            this.controller.backward();
          }
        });
        break;
      default:
        super.call(method, params, callback);
        break;
    }
  }

  onDestroy(): void {
    this.destroyed = true;
    this.attached = false;
    this.titleCallback = null;
    this.loadCallback = null;
  }

  private withController(action: () => void): void {
    if (!this.attached || this.destroyed) {
      return;
    }
    try {
      action();
    } catch (e) {
      SDLog.w(TAG, `controller call failed: ${SD.errMsg(e as Object)}`);
    }
  }

  private setUrl(url: string): void {
    if (url === this.url) {
      return;
    }
    this.url = url;
    if (this.attached && url.length > 0) {
      this.withController((): void => {
        this.controller.loadUrl(url);
      });
    }
  }

  // ---------------------------------------------------------------------------------------------
  // ArkUI 组件回调
  // ---------------------------------------------------------------------------------------------

  /** Web 组件创建时取初始地址。 */
  takeInitialUrl(): string {
    this.initialUrl = this.url;
    return this.initialUrl;
  }

  onControllerAttached(): void {
    this.attached = true;
    // 组件创建之后、绑定完成之前 url 又变了：补一次加载。
    if (this.url.length > 0 && this.url !== this.initialUrl) {
      this.withController((): void => {
        this.controller.loadUrl(this.url);
      });
    }
  }

  onControllerDetached(): void {
    this.attached = false;
  }

  onPageBegin(): void {
    this.loading = true;
    this.progress = 0;
    this.emitLoad('start', 0);
  }

  onProgress(percent: number): void {
    let value: number = percent / 100;
    if (!(value >= 0)) {
      value = 0;
    }
    if (value > 1) {
      value = 1;
    }
    this.progress = value;
    if (this.loading) {
      this.emitLoad('start', value);
    }
  }

  onPageEnd(): void {
    if (!this.loading) {
      return;
    }
    this.loading = false;
    this.progress = 1;
    this.emitLoad('finish', 1);
  }

  onMainFrameError(): void {
    this.loading = false;
    this.emitLoad('error', this.progress);
  }

  onTitle(title: string): void {
    const callback: KuiklyRenderCallback | null = this.titleCallback;
    if (callback !== null && !this.destroyed) {
      const data: KRRecord = {};
      data['title'] = title;
      callback(data);
    }
  }

  /**
   * 主文档导航拦截：返回 true 表示已交给系统处理、WebView 不再加载。
   */
  interceptNavigation(url: string): boolean {
    const colon: number = url.indexOf(':');
    if (colon <= 0) {
      return false;
    }
    const scheme: string = url.substring(0, colon).toLowerCase();
    if (scheme === 'http' || scheme === 'https' || scheme === 'about' || scheme === 'data' || scheme === 'blob' ||
      scheme === 'javascript' || scheme === 'file' || scheme === 'resource') {
      return false;
    }
    this.openExternal(url);
    return true;
  }

  private openExternal(url: string): void {
    const ctx: common.UIAbilityContext | null = MediaSupport.context();
    if (ctx === null) {
      return;
    }
    const fallback = (): void => {
      const want: Want = { action: 'ohos.want.action.viewData', uri: url };
      ctx.startAbility(want).catch((e: BusinessError) => {
        SDLog.w(TAG, `no app can open ${url}: ${SD.errMsg(e)}`);
      });
    };
    try {
      ctx.openLink(url).catch((e: BusinessError) => {
        fallback();
      });
    } catch (e) {
      fallback();
    }
  }

  private emitLoad(state: string, progress: number): void {
    const callback: KuiklyRenderCallback | null = this.loadCallback;
    if (callback === null || this.destroyed) {
      return;
    }
    const data: KRRecord = {};
    data['state'] = state;
    data['progress'] = Math.round(progress * 100) / 100;
    callback(data);
  }
}

// ==== ArkUI ====

@Component
struct SDWebComponent {
  host: SDWebView | null = null;
  private initialUrl: string = '';
  private fallbackController: webview.WebviewController = new webview.WebviewController();

  aboutToAppear(): void {
    const host: SDWebView | null = this.host;
    if (host !== null) {
      this.initialUrl = host.takeInitialUrl();
    }
  }

  aboutToDisappear(): void {
    const host: SDWebView | null = this.host;
    if (host !== null) {
      host.onControllerDetached();
    }
  }

  build() {
    Web({ src: this.initialUrl, controller: this.host !== null ? this.host.controller : this.fallbackController })
      .width('100%')
      .height('100%')
      .javaScriptAccess(true)
      .domStorageAccess(true)
      .fileAccess(false)
      .mixedMode(MixedMode.Compatible)
      .onControllerAttached(() => {
        const host: SDWebView | null = this.host;
        if (host !== null) {
          host.onControllerAttached();
        }
      })
      .onPageBegin((event: OnPageBeginEvent) => {
        const host: SDWebView | null = this.host;
        if (host !== null) {
          host.onPageBegin();
        }
      })
      .onProgressChange((event: OnProgressChangeEvent) => {
        const host: SDWebView | null = this.host;
        if (host !== null) {
          host.onProgress(event.newProgress);
        }
      })
      .onPageEnd((event: OnPageEndEvent) => {
        const host: SDWebView | null = this.host;
        if (host !== null) {
          host.onPageEnd();
        }
      })
      .onTitleReceive((event: OnTitleReceiveEvent) => {
        const host: SDWebView | null = this.host;
        if (host !== null) {
          host.onTitle(event.title);
        }
      })
      .onErrorReceive((event: OnErrorReceiveEvent) => {
        const host: SDWebView | null = this.host;
        if (host !== null && event.request.isMainFrame()) {
          host.onMainFrameError();
        }
      })
      .onLoadIntercept((event: OnLoadInterceptEvent): boolean => {
        const host: SDWebView | null = this.host;
        if (host === null || !event.data.isMainFrame()) {
          return false;
        }
        return host.interceptNavigation(event.data.getRequestUrl());
      })
  }
}

@Builder
function buildSDWebView(view: KuiklyRenderBaseView) {
  SDWebComponent({ host: view as SDWebView })
}
