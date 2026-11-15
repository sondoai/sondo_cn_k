/*
 * 华为支付（Payment Kit）适配层 —— 整个工程里唯一 import '@kit.PaymentKit' 的文件。
 *
 * API 依据：DevEco SDK 自带的 hms/ets/api/@hms.core.payment.paymentService.d.ts（API 12 起，Stage 模型）：
 *   paymentService.requestPayment(context: UIAbilityContext, orderStr: string): Promise<void>
 *   paymentService.requestContract(context: UIAbilityContext, contractStr: string): Promise<void>
 *   resolve = 收银台流程走完（支付 / 签约成功）；reject 的 BusinessError.code：
 *     1001930000 用户取消   1001930001 支付失败   1001930002 交易已处理
 *     1001930010 重复请求   1001930011 网络错误   401 参数错误
 *
 * orderStr / contractStr 由商户服务器生成并签名（预下单 prepay_id / 预签约 preSignNo），客户端原样透传，不能改写。
 * 权益只认后端订单查询结果；这里的成功只用来触发查单。
 * 不需要声明权限；AGC 要开通华为支付并登记签名证书指纹（否则 1001860002 应用未被授权）。
 */
import { common } from '@kit.AbilityKit';
import { paymentService } from '@kit.PaymentKit';

export class HuaweiPayAdapter {
  static readonly CODE_CANCELED: number = 1001930000;
  static readonly CODE_PAY_FAILED: number = 1001930001;
  static readonly CODE_PROCESSED: number = 1001930002;
  static readonly CODE_DUPLICATE: number = 1001930010;
  static readonly CODE_NETWORK: number = 1001930011;

  /** 拉起华为支付收银台（一次性支付）。 */
  static pay(context: common.UIAbilityContext, orderStr: string): Promise<void> {
    // >>> SDK CALL SITE
    return paymentService.requestPayment(context, orderStr);
    // <<< SDK CALL SITE
  }

  /** 拉起华为支付签约收银台（自动续费签约代扣）。 */
  static contract(context: common.UIAbilityContext, contractStr: string): Promise<void> {
    // >>> SDK CALL SITE
    return paymentService.requestContract(context, contractStr);
    // <<< SDK CALL SITE
  }
}
