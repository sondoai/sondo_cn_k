/*
 * Kuikly 容器委托：向渲染器注册 Sondo 的原生模块与原生视图。
 *
 * 注册名必须与 shared/src/commonMain/kotlin/com/sondo/app/nativeapi/ 里的声明完全一致。
 * 新增模块 / 视图：在对应分区加一行 import + 一行 map.set(...)，不要改动其他分区。
 */
import {
  IKuiklyViewDelegate,
  KRRenderModuleExportCreator,
  KRRenderViewExportCreator
} from '@kuikly-open/render';
// --- core ---
import { SDHttpModule } from './modules/core/SDHttpModule';
import { SDNavModule } from './modules/core/SDNavModule';
import { SDAppModule } from './modules/core/SDAppModule';
import { SDPermissionModule } from './modules/core/SDPermissionModule';
import { SDFileModule } from './modules/core/SDFileModule';
import { SDCryptoModule } from './modules/core/SDCryptoModule';
// --- media ---
import { SDAudioPlayerModule } from './modules/media/SDAudioPlayerModule';
import { SDVideoCacheModule } from './modules/media/SDVideoCacheModule';
import { SDMediaPickerModule } from './modules/media/SDMediaPickerModule';
import { SDMediaProcessModule } from './modules/media/SDMediaProcessModule';
// --- sdk ---
import { SDPayModule } from './modules/sdk/SDPayModule';
import { SDAuthModule } from './modules/sdk/SDAuthModule';
import { SDPushModule } from './modules/sdk/SDPushModule';
import { SDAnalyticsModule } from './modules/sdk/SDAnalyticsModule';
// --- views ---
import { SDVideoView } from './views/SDVideoView';
import { SDLottieView } from './views/SDLottieView';
import { SDWebView } from './views/SDWebView';

export class KuiklyViewDelegate extends IKuiklyViewDelegate {
  getCustomRenderViewCreatorRegisterMap(): Map<string, KRRenderViewExportCreator> {
    const map: Map<string, KRRenderViewExportCreator> = new Map();
    // --- views ---
    map.set(SDVideoView.VIEW_NAME, () => new SDVideoView());
    map.set(SDLottieView.VIEW_NAME, () => new SDLottieView());
    map.set(SDWebView.VIEW_NAME, () => new SDWebView());
    return map;
  }

  getCustomRenderModuleCreatorRegisterMap(): Map<string, KRRenderModuleExportCreator> {
    const map: Map<string, KRRenderModuleExportCreator> = new Map();
    // --- core ---
    map.set(SDHttpModule.MODULE_NAME, () => new SDHttpModule());
    map.set(SDNavModule.MODULE_NAME, () => new SDNavModule());
    map.set(SDAppModule.MODULE_NAME, () => new SDAppModule());
    map.set(SDPermissionModule.MODULE_NAME, () => new SDPermissionModule());
    map.set(SDFileModule.MODULE_NAME, () => new SDFileModule());
    map.set(SDCryptoModule.MODULE_NAME, () => new SDCryptoModule());
    // --- media ---
    map.set(SDAudioPlayerModule.MODULE_NAME, () => new SDAudioPlayerModule());
    map.set(SDVideoCacheModule.MODULE_NAME, () => new SDVideoCacheModule());
    map.set(SDMediaPickerModule.MODULE_NAME, () => new SDMediaPickerModule());
    map.set(SDMediaProcessModule.MODULE_NAME, () => new SDMediaProcessModule());
    // --- sdk ---
    map.set(SDPayModule.MODULE_NAME, () => new SDPayModule());
    map.set(SDAuthModule.MODULE_NAME, () => new SDAuthModule());
    map.set(SDPushModule.MODULE_NAME, () => new SDPushModule());
    map.set(SDAnalyticsModule.MODULE_NAME, () => new SDAnalyticsModule());
    return map;
  }
}
