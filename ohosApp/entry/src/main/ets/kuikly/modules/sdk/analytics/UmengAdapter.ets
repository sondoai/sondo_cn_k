/*
 * 友盟统计鸿蒙 SDK 适配层 —— 整个工程里唯一 import '@umeng/analytics' 的文件。
 *
 * API 依据：已对照 ohpm 上 @umeng/analytics 1.2.12 / @umeng/common 1.2.2 包内的 Index.d.ts、
 *   src/main/d/l.d.ts（UMConfig）与包内 README 核对，并经 hvigor CompileArkTS 编译通过：
 *     preInit(cfg: UMConfig)          UMConfig { context: ApplicationContext; plugins?; appKey?; channel?; enableLog?; disable*? }
 *     init(): Promise<void>           同意隐私政策后调用，之后才采集与上报
 *     onEventObject(eventID, params?: Record<string, string | number>)
 *     onProfileSignIn(provider, puid) **注意参数顺序：账号类型在前、账号在后**（与 Android 的 (puid, provider) 相反）
 *     onProfileSignOff()
 *   AppKey / 渠道：SDK 读 AppScope/resources/rawfile/umconfig.json（包内 README 的接入方式）；UMConfig 虽然也有
 *   appKey? / channel? 字段，这里不传，避免两处配置不一致时行为不明。
 *
 * 已确认：SDK **没有导出** onPageStart / onPageEnd（Index.d.ts 里只有 init / agree / onProfileSignIn /
 *   onProfileSignOff / setLogEnabled / onEventObject / trackEvent / preInit），所以 pageStart / pageEnd 保持空实现。
 *   页面路径由业务自定义事件 page_view{current_page_name, from_page_name} 覆盖。
 */
import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { init, InternalPlugin, onEventObject, onProfileSignIn, onProfileSignOff, preInit } from '@umeng/analytics';

/** 账号来源标识（友盟要求大写字母 / 数字，不以下划线开头）。 */
const PROFILE_PROVIDER: string = 'SONDO';
const CONFIG_RAWFILE: string = 'umconfig.json';

export class UmengConfigCheck {
  /** umconfig.json 是否存在且可解析。 */
  found: boolean = false;
  appKey: string = '';
  channel: string = '';
}

export class UmengAdapter {
  /**
   * preInit + init。调用方保证只在用户同意隐私政策之后调用、且只调用一次。返回 init 的 Promise（调用方负责 catch）。
   */
  static start(appContext: common.ApplicationContext): Promise<void> {
    // >>> SDK CALL SITE
    preInit({
      context: appContext,
      plugins: [new InternalPlugin({})]
    });
    const done: Promise<void> = init();
    // <<< SDK CALL SITE
    return done;
  }

  /** 自定义事件；props 为 null 表示无属性。 */
  static event(name: string, props: Record<string, string | number> | null): void {
    // >>> SDK CALL SITE
    if (props === null) {
      onEventObject(name);
    } else {
      onEventObject(name, props);
    }
    // <<< SDK CALL SITE
  }

  static signIn(userId: string): void {
    // >>> SDK CALL SITE
    onProfileSignIn(PROFILE_PROVIDER, userId);
    // <<< SDK CALL SITE
  }

  static signOff(): void {
    // >>> SDK CALL SITE
    onProfileSignOff();
    // <<< SDK CALL SITE
  }

  /** 手动页面统计：@umeng/analytics 1.2.12 不提供，恒返回 false（见文件头）。 */
  static pageStart(name: string): boolean {
    return false;
  }

  /** 同 pageStart。 */
  static pageEnd(name: string): boolean {
    return false;
  }

  /** 读取 rawfile/umconfig.json（只读本地资源，不触碰 SDK）。 */
  static readConfig(appContext: common.ApplicationContext): UmengConfigCheck {
    const out: UmengConfigCheck = new UmengConfigCheck();
    try {
      const bytes: Uint8Array = appContext.resourceManager.getRawFileContentSync(CONFIG_RAWFILE);
      const text: string = util.TextDecoder.create('utf-8').decodeToString(bytes);
      const parsed: Object | null = JSON.parse(text) as Object | null;
      if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
        const rec: Record<string, Object> = parsed as Record<string, Object>;
        out.found = true;
        const appKey: Object | null | undefined = rec['appKey'] as Object | null | undefined;
        const channel: Object | null | undefined = rec['channel'] as Object | null | undefined;
        out.appKey = typeof appKey === 'string' ? appKey as string : '';
        out.channel = typeof channel === 'string' ? channel as string : '';
      }
    } catch (e) {
      out.found = false;
    }
    return out;
  }
}
