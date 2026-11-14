/*
 * SDPayModule —— 支付（契约：shared/.../nativeapi/SDBizModules.kt 的 SDPayModule）。
 *
 * 鸿蒙与 Android 一样走支付宝：
 *   - alipayIsInstalled（同步）   bundleManager.canOpenLink('alipays://')，需要 module.json5 的 querySchemes 声明 alipays
 *   - alipayPay {orderStr}        支付宝收银台 SDK（见 pay/AlipayAdapter.ets）→ {ok, resultStatus, result, memo}
 *   - 自动续费签约不经过本模块：Kuikly 侧用 SDAppModule.openUrl 拉起 alipays:// 链接
 *   - iap*                        鸿蒙没有 StoreKit：立即回 {ok:false, available:false, error:"unsupported"}
 *   - openSubscriptionManagement  空实现
 *   - addEventListener            只登记回调（iapUpdate 事件仅 iOS 会发）
 *
 * 权益只认后端订单轮询结果（PayService），这里的 resultStatus 仅用于提示 / 触发查询。
 * 隐私：支付宝 SDK 只在 alipayPay 被调用时才会执行任何代码路径。
 */
import { bundleManager } from '@kit.AbilityKit';
import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { EventBroadcaster, SD, SDLog, SDParams, SDReply, SDResult } from '../../support/SDModuleSupport';
import { AlipayAdapter, AlipayPayResult } from './pay/AlipayAdapter';

const TAG: string = 'SDPayModule';
const ALIPAY_LINK: string = 'alipays://';
/** SDK 的 Promise 一直不返回时的兜底，避免 Kuikly 侧协程永远挂起。 */
const ALIPAY_WATCHDOG_MS: number = 10 * 60 * 1000;

export class SDPayModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME: string = 'SDPayModule';
  private static readonly events: EventBroadcaster = new EventBroadcaster();

  /** 有同步方法（alipayIsInstalled），必须是同步模式。 */
  syncMode(): boolean {
    return true;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    switch (method) {
      case 'alipayIsInstalled':
        return SD.flag(SDPayModule.alipayInstalled());
      case 'addEventListener':
        SDPayModule.events.add(this, callback);
        return null;
      case 'alipayPay':
        this.alipayPay(SD.parse(params), new SDReply(callback));
        return null;
      case 'iapIsAvailable':
      case 'iapQueryProducts':
      case 'iapIsIntroEligible':
      case 'iapPurchase':
      case 'iapFinish':
      case 'iapRestore':
      case 'iapUnfinishedTransactions':
      case 'iapTransactionHistory':
      case 'openSubscriptionManagement':
        SD.send(callback, SDPayModule.unsupported());
        return null;
      default:
        SDLog.w(TAG, `unknown method: ${method}`);
        SD.send(callback, SD.fail(`unknown method: ${method}`));
        return null;
    }
  }

  onDestroy(): void {
    SDPayModule.events.removeOwner(this);
  }

  /** {ok:false, available:false, error:"unsupported"} */
  private static unsupported(): SDResult {
    const extra: SDResult = {};
    extra['available'] = false;
    return SD.fail('unsupported', undefined, extra);
  }

  private static alipayInstalled(): boolean {
    try {
      return bundleManager.canOpenLink(ALIPAY_LINK);
    } catch (e) {
      // 17700056：querySchemes 里没有声明 alipays
      SDLog.w(TAG, `canOpenLink(${ALIPAY_LINK}) failed: ${SD.errMsg(e as Object)}`);
      return false;
    }
  }

  private alipayPay(p: SDParams, reply: SDReply): void {
    const orderStr: string = SD.str(p, 'orderStr');
    if (orderStr.length === 0) {
      reply.fail('orderStr is empty');
      return;
    }
    const timer: number = setTimeout(() => {
      SDLog.w(TAG, 'alipayPay watchdog fired');
      reply.fail('timeout');
    }, ALIPAY_WATCHDOG_MS);

    AlipayAdapter.pay(orderStr, true).then((r: AlipayPayResult) => {
      clearTimeout(timer);
      if (r.resultStatus.length === 0) {
        // SDK 没给出状态码：按失败回，Kuikly 侧提示「繁忙」并查一次订单
        reply.fail('empty resultStatus');
        return;
      }
      const out: SDResult = {};
      out['resultStatus'] = r.resultStatus;
      out['result'] = r.result;
      out['memo'] = r.memo;
      reply.ok(out);
    }).catch((e: Object) => {
      clearTimeout(timer);
      SDLog.e(TAG, `alipayPay failed: ${SD.errMsg(e)}`);
      const code: number = SD.errCode(e);
      reply.fail(SD.errMsg(e), code !== 0 ? code : undefined);
    });
  }
}
