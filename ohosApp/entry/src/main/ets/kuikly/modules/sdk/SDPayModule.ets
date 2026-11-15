/*
 * SDPayModule —— 支付（契约：shared/.../nativeapi/SDBizModules.kt 的 SDPayModule）。
 *
 * 鸿蒙走华为支付（Payment Kit，见 pay/HuaweiPayAdapter.ets）；不接支付宝（Android 才是支付宝，iOS 是 Apple 内购）：
 *   - huaweiPay {orderStr}            积分包：requestPayment → {ok:true} / {ok:false, error, code}
 *   - huaweiContract {contractStr}    会员自动续费：requestContract（签约代扣）→ 同上
 *       code 1001930000 = 用户取消，Kuikly 侧按「未完成支付」处理；其余失败按「繁忙」并查一次订单
 *   - alipayIsInstalled（同步）       恒 "0"
 *   - alipayPay                       {ok:false, error:"unsupported"}
 *   - iap*                            {ok:false, available:false, error:"unsupported"}
 *   - openSubscriptionManagement      unsupported（解约走后端接口）
 *   - addEventListener                只登记回调（iapUpdate 事件仅 iOS 会发）
 *
 * 权益只认后端订单轮询结果（PayService），这里的成功只用于提示 / 触发查询。
 */
import { common } from '@kit.AbilityKit';
import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { EventBroadcaster, SD, SDHost, SDLog, SDParams, SDReply, SDResult } from '../../support/SDModuleSupport';
import { HuaweiPayAdapter } from './pay/HuaweiPayAdapter';

const TAG: string = 'SDPayModule';
/** 收银台 Promise 一直不返回时的兜底，避免 Kuikly 侧协程永远挂起。 */
const CASHIER_WATCHDOG_MS: number = 10 * 60 * 1000;

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
        return SD.flag(false);
      case 'addEventListener':
        SDPayModule.events.add(this, callback);
        return null;
      case 'huaweiPay':
        SDPayModule.runCashier('huaweiPay', SD.str(SD.parse(params), 'orderStr'), false, new SDReply(callback));
        return null;
      case 'huaweiContract':
        SDPayModule.runCashier('huaweiContract', SD.str(SD.parse(params), 'contractStr'), true,
          new SDReply(callback));
        return null;
      case 'alipayPay':
        SD.send(callback, SD.fail('unsupported'));
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

  /** 拉起支付 / 签约收银台，只回调一次。 */
  private static runCashier(method: string, payload: string, contract: boolean, reply: SDReply): void {
    if (payload.length === 0) {
      reply.fail(contract ? 'contractStr is empty' : 'orderStr is empty');
      return;
    }
    const context: common.UIAbilityContext | null = SDHost.context();
    if (context === null) {
      reply.fail('UIAbilityContext unavailable');
      return;
    }
    let settled: boolean = false;
    const timer: number = setTimeout(() => {
      if (settled) {
        return;
      }
      settled = true;
      SDLog.w(TAG, `${method} watchdog fired`);
      reply.fail('timeout');
    }, CASHIER_WATCHDOG_MS);
    const finish = (result: SDResult): void => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(timer);
      reply.send(result);
    };
    try {
      const pending: Promise<void> = contract ? HuaweiPayAdapter.contract(context, payload) :
        HuaweiPayAdapter.pay(context, payload);
      pending.then(() => {
        finish(SD.ok());
      }).catch((e: Object) => {
        const code: number = SD.errCode(e);
        if (code === HuaweiPayAdapter.CODE_CANCELED) {
          SDLog.i(TAG, `${method} canceled by user`);
        } else {
          SDLog.e(TAG, `${method} failed: ${code} ${SD.errMsg(e)}`);
        }
        finish(SD.fail(SD.errMsg(e), code !== 0 ? code : undefined));
      });
    } catch (e) {
      // 401 等同步抛出的参数错误
      const code: number = SD.errCode(e as Object);
      SDLog.e(TAG, `${method} threw: ${code} ${SD.errMsg(e as Object)}`);
      finish(SD.fail(SD.errMsg(e as Object), code !== 0 ? code : undefined));
    }
  }
}
