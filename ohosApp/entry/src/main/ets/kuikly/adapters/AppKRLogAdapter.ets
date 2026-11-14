/*
 * Kuikly 日志适配器：框架与业务（KLog）日志统一输出到 hilog。
 */
import { hilog } from '@kit.PerformanceAnalysisKit';
import { IKRLogAdapter } from '@kuikly-open/render';

const KUIKLY_LOG_DOMAIN: number = 0x5D02;

export class AppKRLogAdapter implements IKRLogAdapter {
  d(tag: string, msg: string): void {
    hilog.debug(KUIKLY_LOG_DOMAIN, tag, '%{public}s', msg);
  }

  i(tag: string, msg: string): void {
    hilog.info(KUIKLY_LOG_DOMAIN, tag, '%{public}s', msg);
  }

  e(tag: string, msg: string): void {
    hilog.error(KUIKLY_LOG_DOMAIN, tag, '%{public}s', msg);
  }
}
