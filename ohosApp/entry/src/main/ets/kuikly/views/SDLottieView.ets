/*
 * SDLottieView —— Lottie 动画（ohpm 包 @ohos/lottie，Canvas 渲染）。
 * 契约见 shared/.../nativeapi/SDNativeViews.kt。
 *
 *  - src：shared assets 相对 common/ 的路径（如 "lottie/loading.json"，运行时位于
 *    context.resourceDir/common/…）、沙箱绝对路径，或 http(s) URL。JSON 文本由本类读取 / 下载后
 *    以 animationData 交给 lottie（每个视图各自 JSON.parse 一份，lottie 会原地修改这份数据）。
 *  - loop（默认 1）/ autoPlay（默认 1）/ speed；事件 sdComplete（非循环播放结束时）；方法 play / pause / stop。
 *  - 限制：JSON 里引用的外部图片（assets[].u / p）不会被加载——resfile 目录不在 lottie 的图片查找路径里；
 *    内嵌 base64 图片和纯矢量动画不受影响。
 */
import { KRAny, KRRecord, KuiklyRenderBaseView, KuiklyRenderCallback } from '@kuikly-open/render';
import { ComponentContent, UIContext } from '@kit.ArkUI';
import { http } from '@kit.NetworkKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { BusinessError } from '@kit.BasicServicesKit';
import lottie, { AnimationItem } from '@ohos/lottie';
import { SD, SDLog } from '../support/SDModuleSupport';
import { AVPlayerSupport } from '../modules/media/AudioPlayerManager';
import { MediaSupport } from '../modules/media/MediaSupport';

const TAG: string = 'SDLottieView';
const MAX_JSON_BYTES: number = 8 * 1024 * 1024;

/** 视图 → ArkUI 组件的通知通道（组件在 aboutToAppear 里填入回调）。 */
export class SDLottieUiBridge {
  onData: (json: string) => void = (json: string): void => {
  };
  onLoop: (loop: boolean) => void = (loop: boolean): void => {
  };
  onSpeed: (speed: number) => void = (speed: number): void => {
  };
  /** command: 'play' | 'pause' | 'stop' */
  onCommand: (command: string) => void = (command: string): void => {
  };
}

export class SDLottieView extends KuiklyRenderBaseView {
  static readonly VIEW_NAME: string = 'SDLottieView';
  private static sequence: number = 0;
  /** 已读取的 JSON 文本缓存（同一个动画在列表里会出现很多次）。 */
  private static textCache: Map<string, string> = new Map();

  src: string = '';
  loop: boolean = true;
  autoPlay: boolean = true;
  speed: number = 1;
  /** 当前 src 对应的 JSON 文本；还没加载完为空串。 */
  json: string = '';
  /** lottie 动画实例名（全局唯一，用于 lottie.destroy(name)）。 */
  readonly animationName: string;
  /** 组件创建之前收到的 play / pause / stop，创建后补发。 */
  pendingCommand: string = '';

  private ui: SDLottieUiBridge | null = null;
  private completeCallback: KuiklyRenderCallback | null = null;
  private generation: number = 0;
  private destroyed: boolean = false;

  constructor() {
    super();
    SDLottieView.sequence += 1;
    this.animationName = `sd_lottie_${SDLottieView.sequence}`;
  }

  createArkUIView(): ComponentContent<KuiklyRenderBaseView> {
    const uiContext: UIContext = this.getUIContext() as UIContext;
    return new ComponentContent<KuiklyRenderBaseView>(uiContext,
      wrapBuilder<[KuiklyRenderBaseView]>(buildSDLottieView), this);
  }

  setProp(propKey: string, propValue: KRAny | KuiklyRenderCallback): boolean {
    switch (propKey) {
      case 'src':
        this.setSrc(typeof propValue === 'string' ? propValue as string : '');
        return true;
      case 'loop': {
        this.loop = SDLottieView.flag(propValue, true);
        const ui: SDLottieUiBridge | null = this.ui;
        if (ui !== null) {
          ui.onLoop(this.loop);
        }
        return true;
      }
      case 'autoPlay':
        this.autoPlay = SDLottieView.flag(propValue, true);
        return true;
      case 'speed': {
        const value: number = typeof propValue === 'number' ? propValue as number : Number(`${propValue}`);
        this.speed = Number.isFinite(value) && value > 0 ? value : 1;
        const ui: SDLottieUiBridge | null = this.ui;
        if (ui !== null) {
          ui.onSpeed(this.speed);
        }
        return true;
      }
      case 'sdComplete':
        this.completeCallback = propValue as KuiklyRenderCallback;
        return true;
      default:
        return super.setProp(propKey, propValue);
    }
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): void {
    if (method === 'play' || method === 'pause' || method === 'stop') {
      const ui: SDLottieUiBridge | null = this.ui;
      if (ui !== null) {
        ui.onCommand(method);
      } else {
        this.pendingCommand = method;
      }
      return;
    }
    super.call(method, params, callback);
  }

  onDestroy(): void {
    this.destroyed = true;
    this.generation += 1;
    this.completeCallback = null;
    this.ui = null;
  }

  attachUi(bridge: SDLottieUiBridge): void {
    this.ui = bridge;
  }

  detachUi(bridge: SDLottieUiBridge): void {
    if (this.ui === bridge) {
      this.ui = null;
    }
  }

  /** 组件：非循环动画播放结束。 */
  fireComplete(): void {
    const callback: KuiklyRenderCallback | null = this.completeCallback;
    if (callback !== null && !this.destroyed) {
      const data: KRRecord = {};
      callback(data);
    }
  }

  private static flag(value: KRAny | KuiklyRenderCallback, fallback: boolean): boolean {
    if (typeof value === 'boolean') {
      return value as boolean;
    }
    if (typeof value === 'number') {
      return (value as number) !== 0;
    }
    if (typeof value === 'string') {
      return value === '1' || value === 'true';
    }
    return fallback;
  }

  private setSrc(src: string): void {
    if (src === this.src) {
      return;
    }
    this.src = src;
    this.generation += 1;
    const gen: number = this.generation;
    this.json = '';
    if (src.length === 0) {
      this.publish('');
      return;
    }
    const cached: string | undefined = SDLottieView.textCache.get(src);
    if (cached !== undefined) {
      this.publish(cached);
      return;
    }
    this.readText(src).then((text: string) => {
      if (text.length > 0 && text.length <= 512 * 1024) {
        if (SDLottieView.textCache.size >= 24) {
          SDLottieView.textCache.clear();
        }
        SDLottieView.textCache.set(src, text);
      }
      if (gen === this.generation && !this.destroyed) {
        this.publish(text);
      }
    }).catch((e: BusinessError) => {
      SDLog.e(TAG, `load ${src} failed: ${SD.errMsg(e)}`);
    });
  }

  private publish(json: string): void {
    this.json = json;
    const ui: SDLottieUiBridge | null = this.ui;
    if (ui !== null) {
      ui.onData(json);
    }
  }

  private async readText(src: string): Promise<string> {
    if (MediaSupport.isHttp(src)) {
      const request: http.HttpRequest = http.createHttp();
      try {
        const options: http.HttpRequestOptions = {
          method: http.RequestMethod.GET,
          expectDataType: http.HttpDataType.STRING,
          connectTimeout: 15000,
          readTimeout: 30000,
          maxLimit: MAX_JSON_BYTES
        };
        const response: http.HttpResponse = await request.request(src, options);
        if ((response.responseCode as number) !== 200 || typeof response.result !== 'string') {
          throw new Error(`http ${response.responseCode}`);
        }
        return response.result as string;
      } finally {
        request.destroy();
      }
    }
    const path: string = AVPlayerSupport.resolveLocal(src);
    if (MediaSupport.fileSize(path) > MAX_JSON_BYTES) {
      throw new Error('lottie json too large');
    }
    return fs.readText(path);
  }
}

// ==== ArkUI ====

@Component
struct SDLottieComponent {
  host: SDLottieView | null = null;
  private settings: RenderingContextSettings = new RenderingContextSettings(true);
  private context2d: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.settings);
  private bridge: SDLottieUiBridge = new SDLottieUiBridge();
  private item: AnimationItem | null = null;
  private canvasReady: boolean = false;
  private json: string = '';
  private loop: boolean = true;
  private speed: number = 1;

  aboutToAppear(): void {
    const host: SDLottieView | null = this.host;
    if (host === null) {
      return;
    }
    this.json = host.json;
    this.loop = host.loop;
    this.speed = host.speed;
    this.bridge.onData = (json: string): void => {
      this.json = json;
      this.rebuild();
    };
    this.bridge.onLoop = (loop: boolean): void => {
      this.loop = loop;
      const item: AnimationItem | null = this.item;
      if (item !== null) {
        item.loop = loop;
      }
    };
    this.bridge.onSpeed = (speed: number): void => {
      this.speed = speed;
      const item: AnimationItem | null = this.item;
      if (item !== null) {
        item.setSpeed(speed);
      }
    };
    this.bridge.onCommand = (command: string): void => {
      this.runCommand(command);
    };
    host.attachUi(this.bridge);
  }

  aboutToDisappear(): void {
    const host: SDLottieView | null = this.host;
    if (host !== null) {
      host.detachUi(this.bridge);
    }
    this.destroyAnimation();
  }

  private runCommand(command: string): void {
    const item: AnimationItem | null = this.item;
    if (item === null) {
      const host: SDLottieView | null = this.host;
      if (host !== null) {
        host.pendingCommand = command; // 动画还没建好，建好后补发
      }
      return;
    }
    if (command === 'play') {
      item.play();
    } else if (command === 'pause') {
      item.pause();
    } else if (command === 'stop') {
      item.stop();
    }
  }

  private destroyAnimation(): void {
    const host: SDLottieView | null = this.host;
    if (this.item !== null && host !== null) {
      try {
        lottie.destroy(host.animationName);
      } catch (e) {
        // 忽略
      }
    }
    this.item = null;
  }

  /** Canvas 就绪且拿到 JSON 后（重新）创建动画。 */
  private rebuild(): void {
    const host: SDLottieView | null = this.host;
    if (host === null || !this.canvasReady) {
      return;
    }
    this.destroyAnimation();
    if (this.json.length === 0) {
      return;
    }
    let data: Object | null = null;
    try {
      data = JSON.parse(this.json) as Object;
    } catch (e) {
      data = null;
    }
    if (data === null) {
      return;
    }
    let autoplay: boolean = host.autoPlay;
    const pending: string = host.pendingCommand;
    host.pendingCommand = '';
    if (pending === 'play') {
      autoplay = true;
    } else if (pending === 'pause' || pending === 'stop') {
      autoplay = false;
    }
    this.context2d.imageSmoothingEnabled = true;
    this.context2d.imageSmoothingQuality = 'medium';
    const item: AnimationItem = lottie.loadAnimation({
      container: this.context2d,
      renderer: 'canvas',
      loop: this.loop,
      autoplay: autoplay,
      name: host.animationName,
      contentMode: 'Contain',
      animationData: data
    });
    this.item = item;
    if (this.speed !== 1) {
      item.setSpeed(this.speed);
    }
    item.addEventListener('complete', (args: Object): void => {
      const target: SDLottieView | null = this.host;
      if (target !== null) {
        target.fireComplete();
      }
    });
  }

  build() {
    Canvas(this.context2d)
      .width('100%')
      .height('100%')
      .backgroundColor(Color.Transparent)
      .onReady(() => {
        const item: AnimationItem | null = this.item;
        if (this.canvasReady && item !== null) {
          item.resize(); // 画布尺寸变化
          return;
        }
        this.canvasReady = true;
        this.rebuild();
      })
  }
}

@Builder
function buildSDLottieView(view: KuiklyRenderBaseView) {
  SDLottieComponent({ host: view as SDLottieView })
}
