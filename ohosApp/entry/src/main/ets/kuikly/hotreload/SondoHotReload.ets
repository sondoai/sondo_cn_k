import { http } from '@kit.NetworkKit';
import BuildProfile from 'BuildProfile';
import Napi from 'libkuikly_entry.so';
import { SDCryptoUtil } from '../modules/core/SDCryptoModule';
import { SD, SDLog } from '../support/SDModuleSupport';

export type HotReloadListener = (version: string) => void;

interface HotReloadVersion {
  ready?: boolean;
  building?: boolean;
  version?: string;
  ohosVersion?: string;
}

/** 只有开发者显式选择的Debug包才访问本机服务；所有页面共享一个版本轮询器。 */
export class SondoHotReload {
  static readonly enabled: boolean = BuildProfile.DEBUG && BuildProfile.KUIKLY_HOT_RELOAD;
  private static listeners: Array<HotReloadListener> = [];
  private static version: string = '';
  private static timer: number = -1;
  private static generation: number = 0;
  private static fetching: boolean = false;
  private static lastError: string = '';

  static subscribe(listener: HotReloadListener): void {
    if (!SondoHotReload.enabled) return;
    if (SondoHotReload.listeners.indexOf(listener) < 0) SondoHotReload.listeners.push(listener);
    if (SondoHotReload.version !== '') listener(SondoHotReload.version);
    if (SondoHotReload.timer < 0) {
      SondoHotReload.timer = setInterval(() => { SondoHotReload.refresh(); }, 1000);
      SondoHotReload.refresh();
    }
  }

  static unsubscribe(listener: HotReloadListener): void {
    const index: number = SondoHotReload.listeners.indexOf(listener);
    if (index >= 0) SondoHotReload.listeners.splice(index, 1);
    if (SondoHotReload.listeners.length === 0 && SondoHotReload.timer >= 0) {
      clearInterval(SondoHotReload.timer);
      SondoHotReload.timer = -1;
      SondoHotReload.generation++;
    }
  }

  static refresh(): void {
    if (!SondoHotReload.enabled || SondoHotReload.fetching || SondoHotReload.listeners.length === 0) return;
    SondoHotReload.fetching = true;
    SondoHotReload.poll().catch((error: Object) => {
      const message: string = SD.errMsg(error);
      if (message !== SondoHotReload.lastError) SDLog.e('SondoHotReload', message);
      SondoHotReload.lastError = message;
    }).finally(() => { SondoHotReload.fetching = false; });
  }

  private static async poll(): Promise<void> {
    const generation: number = SondoHotReload.generation;
    const base: string = BuildProfile.KUIKLY_HOT_RELOAD_URL;
    const versionText: string = await SondoHotReload.get(`${base}/version`);
    const status: HotReloadVersion = JSON.parse(versionText) as HotReloadVersion;
    const version: string = status.ohosVersion ?? status.version ?? '';
    if (status.ready !== true || status.building === true || version === '' || version === SondoHotReload.version) return;
    const source: string = await SondoHotReload.get(`${base}/ohos/bundle.js?version=${encodeURIComponent(version)}`);
    if (generation !== SondoHotReload.generation || SondoHotReload.listeners.length === 0) return;
    if (!/^[a-f0-9]{64}$/.test(version) ||
      SDCryptoUtil.digestHex('SHA256', SDCryptoUtil.utf8Bytes(source)) !== version) {
      throw new Error('Development bundle SHA-256 does not match its version');
    }
    // 新bundle解析/执行失败时，Native抛出错误，旧页面和版本保持可用。
    if (!await Napi.setHotReloadBundle(version, source)) throw new Error('JSVM did not accept the bundle');
    if (generation !== SondoHotReload.generation || SondoHotReload.listeners.length === 0) return;
    SondoHotReload.version = version;
    SondoHotReload.lastError = '';
    SDLog.i('SondoHotReload', `loaded ${version}`);
    const callbacks: Array<HotReloadListener> = SondoHotReload.listeners.slice();
    for (let i = 0; i < callbacks.length; i++) callbacks[i](version);
  }

  private static async get(url: string): Promise<string> {
    const request: http.HttpRequest = http.createHttp();
    try {
      const response: http.HttpResponse = await request.request(url, {
        method: http.RequestMethod.GET,
        expectDataType: http.HttpDataType.STRING,
        usingCache: false,
        connectTimeout: 3000,
        readTimeout: 15000,
        maxLimit: 50 * 1024 * 1024
      });
      if (response.responseCode !== 200) throw new Error(`Development server returned HTTP ${response.responseCode}`);
      return response.result as string;
    } finally {
      request.destroy();
    }
  }
}
