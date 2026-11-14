/*
 * 支付宝收银台 SDK 适配层 —— 整个工程里唯一 import '@cashier_alipay/cashiersdk' 的文件。
 *
 * API 依据：已对照 ohpm 上 @cashier_alipay/cashiersdk 15.8.37 包内的 src/main/ets/api/Pay.d.ets 与包内 README，
 *   并经 hvigor CompileArkTS 编译通过：
 *     new Pay().pay(orderInfo: string, showPayLoading?: boolean): Promise<Map<string, string>>
 *     返回 Map 的键：resultStatus / result / memo（与 Android payV2 一致）。
 *   未安装支付宝时 pay() 会用 router 打开 SDK 自带的 H5 收银台页面（包内 README「默认方式，H5 通过 router 进行跳转」）；
 *   SDK 另有 payWithNav(…, navPathStack) 走 Navigation，本工程没用（H5 页不进 SDNavigator 的页面栈更简单）。
 *
 * 版本：15.8.38 起包的 compatibleSdkVersion 是 13，本工程最低 API 12，所以钉死 15.8.37（最后一个 API 12 版本）。
 *   把工程 compatibleSdkVersion 提到 5.0.1(13) 之后才能升级。
 */
import { Pay } from '@cashier_alipay/cashiersdk';

export class AlipayPayResult {
  resultStatus: string = '';
  result: string = '';
  memo: string = '';
}

export class AlipayAdapter {
  /**
   * 拉起支付宝收银台。Promise 在 SDK 拿到支付结果后 resolve；SDK 抛错时 reject。
   * 未安装支付宝时 SDK 的行为没有核对过，调用方应先用 SDPayModule.alipayIsInstalled 判断。
   */
  static async pay(orderStr: string, showLoading: boolean): Promise<AlipayPayResult> {
    // >>> SDK CALL SITE（@cashier_alipay/cashiersdk）
    const raw: Map<string, string> = await new Pay().pay(orderStr, showLoading);
    // <<< SDK CALL SITE

    const out: AlipayPayResult = new AlipayPayResult();
    out.resultStatus = AlipayAdapter.readField(raw, 'resultStatus');
    out.result = AlipayAdapter.readField(raw, 'result');
    out.memo = AlipayAdapter.readField(raw, 'memo');
    return out;
  }

  /** SDK 声明为 Map；同时兼容普通对象，任何异常都返回空串。 */
  private static readField(raw: Object | null | undefined, key: string): string {
    if (raw === null || raw === undefined) {
      return '';
    }
    let value: Object | null | undefined = undefined;
    try {
      if (raw instanceof Map) {
        value = (raw as Map<string, Object>).get(key);
      } else if (typeof raw === 'object') {
        value = (raw as Record<string, Object>)[key] as Object | null | undefined;
      }
    } catch (e) {
      return '';
    }
    if (value === null || value === undefined) {
      return '';
    }
    if (typeof value === 'string') {
      return value as string;
    }
    if (typeof value === 'number' || typeof value === 'boolean') {
      return `${value}`;
    }
    return '';
  }
}
