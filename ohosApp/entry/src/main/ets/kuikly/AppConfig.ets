/*
 * 宿主配置：注入给 Kuikly 页面的环境参数（shared 侧由 AppEnv.initFrom(pageData) 读取）。
 *
 * API_ENV / SHOW_TOKEN_EDITOR 来自 entry/build-profile.json5 的 buildProfileFields
 * （构建时生成到 BuildProfile），对应 Android 的 -PAPI_ENV / -PSHOW_TOKEN_EDITOR。
 */
import { bundleManager } from '@kit.AbilityKit';
import BuildProfile from 'BuildProfile';
import { SD, SDLog } from './support/SDModuleSupport';

export class AppConfig {
  /** 根页面（主框架），页面栈栈底。 */
  static readonly ROOT_PAGE: string = 'launch';

  /** Debug 构建（DevEco 的 debug buildMode）。 */
  static readonly DEBUG: boolean = BuildProfile.DEBUG;

  /** 接口环境：local | test | prod */
  static readonly API_ENV: string = BuildProfile.API_ENV;

  /** 是否展示设置页「修改 Token」测试入口 */
  static readonly SHOW_TOKEN_EDITOR: boolean = BuildProfile.SHOW_TOKEN_EDITOR;

  /** 全站深色主题的窗口底色 */
  static readonly WINDOW_BACKGROUND: string = '#000000';

  private static bundleInfo: bundleManager.BundleInfo | null = null;

  private static loadBundleInfo(): bundleManager.BundleInfo | null {
    if (AppConfig.bundleInfo === null) {
      try {
        AppConfig.bundleInfo = bundleManager.getBundleInfoForSelfSync(
          bundleManager.BundleFlag.GET_BUNDLE_INFO_WITH_APPLICATION);
      } catch (e) {
        SDLog.e('AppConfig', `getBundleInfoForSelfSync failed: ${SD.errMsg(e as Object)}`);
      }
    }
    return AppConfig.bundleInfo;
  }

  static bundleName(): string {
    const info: bundleManager.BundleInfo | null = AppConfig.loadBundleInfo();
    return info === null ? '' : info.name;
  }

  static versionName(): string {
    const info: bundleManager.BundleInfo | null = AppConfig.loadBundleInfo();
    return info === null ? '' : info.versionName;
  }

  static versionCode(): number {
    const info: bundleManager.BundleInfo | null = AppConfig.loadBundleInfo();
    return info === null ? 0 : info.versionCode;
  }
}
