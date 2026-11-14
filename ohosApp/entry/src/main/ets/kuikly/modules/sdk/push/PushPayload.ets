/*
 * 推送消息的归一化（不依赖任何三方 SDK）。
 *
 *  - PushMessage            发给 Kuikly 的统一结构：{title, alert, extras:{}}
 *  - PushPayload.normalizeExtras   把极光给的 extras（JSON 字符串 / 对象，Android 厂商通道还可能套一层
 *                                  "cn.jpush.android.EXTRA"）拍平成 {jumpAddress, jumpParams, ...}
 *  - PushPayload.fromWant   从通知点击的 Want 里直接解析消息 —— 仅作为「极光 SDK 没有回调」时的兜底
 *
 * UNVERIFIED（仅 fromWant）：极光鸿蒙通道把消息放进 Want.parameters 的具体键名没有官方说明可对照。
 *   @jg/push 1.4.2 的字节码字符串表里能看到 "JMessageExtra"、"_j_data_" 与 msg_id / n_title / n_content / n_extras，
 *   但看不到结构，所以这里依次尝试这两个键（{msg_id, n_title, n_content, n_extras}，JSON 字符串或对象），
 *   再尝试业务字段直接平铺在 parameters 里（jumpAddress / jumpParams）。两者都不命中就返回 null，
 *   不会误判普通启动。正常路径是 JPushInterface.setClickWant → onClickMessage，不走这里。
 */
import { Want } from '@kit.AbilityKit';
import { SD, SDParams, SDResult } from '../../../support/SDModuleSupport';

const ANDROID_EXTRA_KEY: string = 'cn.jpush.android.EXTRA';
/** Want.parameters 里可能装着极光消息的键（这两个键名都出现在 @jg/push 1.4.2 的字节码字符串表里）。 */
const WANT_JMESSAGE_KEYS: Array<string> = ['JMessageExtra', '_j_data_'];

export class PushMessage {
  msgId: string = '';
  title: string = '';
  alert: string = '';
  extras: SDResult = {};

  /** {title, alert, extras} */
  toResult(): SDResult {
    const r: SDResult = {};
    r['title'] = this.title;
    r['alert'] = this.alert;
    r['extras'] = this.extras;
    return r;
  }

  /** 是否同一条通知（msgId 优先，其次标题 + 内容）。 */
  sameAs(other: PushMessage): boolean {
    if (this.msgId.length > 0 && other.msgId.length > 0) {
      return this.msgId === other.msgId;
    }
    return this.title === other.title && this.alert === other.alert;
  }
}

export class PushPayload {
  /** 任意形态的 extras → 平铺的对象；解析不了返回空对象。 */
  static normalizeExtras(raw: Object | null | undefined): SDResult {
    const out: SDResult = {};
    const src: SDParams = PushPayload.toRecord(raw);
    const keys: Array<string> = Object.keys(src);
    // 先放外层字段，再用 cn.jpush.android.EXTRA 里的业务字段覆盖（与参考工程 jpushBusinessExtras 一致）
    for (let i = 0; i < keys.length; i++) {
      const k: string = keys[i];
      if (k === ANDROID_EXTRA_KEY) {
        continue;
      }
      const v: Object | null | undefined = src[k] as Object | null | undefined;
      if (v !== null && v !== undefined) {
        out[k] = v;
      }
    }
    const inner: SDParams = PushPayload.toRecord(src[ANDROID_EXTRA_KEY] as Object | null | undefined);
    const innerKeys: Array<string> = Object.keys(inner);
    for (let i = 0; i < innerKeys.length; i++) {
      const k: string = innerKeys[i];
      const v: Object | null | undefined = inner[k] as Object | null | undefined;
      if (v !== null && v !== undefined) {
        out[k] = v;
      }
    }
    return out;
  }

  /** 兜底：直接从 Want 里解析通知（见文件头的 UNVERIFIED 说明）。不是通知点击时返回 null。 */
  static fromWant(want: Want | null | undefined): PushMessage | null {
    if (want === null || want === undefined) {
      return null;
    }
    const params: Record<string, Object> | undefined = want.parameters;
    if (params === undefined || params === null) {
      return null;
    }
    try {
      let jmsg: SDParams = {};
      for (let i = 0; i < WANT_JMESSAGE_KEYS.length && Object.keys(jmsg).length === 0; i++) {
        jmsg = PushPayload.toRecord(params[WANT_JMESSAGE_KEYS[i]] as Object | null | undefined);
      }
      if (Object.keys(jmsg).length > 0) {
        const msg: PushMessage = new PushMessage();
        msg.msgId = SD.str(jmsg, 'msg_id');
        msg.title = SD.str(jmsg, 'n_title');
        msg.alert = SD.str(jmsg, 'n_content');
        msg.extras = PushPayload.normalizeExtras(jmsg['n_extras'] as Object | null | undefined);
        return msg;
      }
      const flat: SDParams = params as SDParams;
      if (SD.str(flat, 'jumpAddress').length > 0) {
        const msg: PushMessage = new PushMessage();
        msg.title = SD.str(flat, 'title');
        msg.alert = SD.str(flat, 'alert', SD.str(flat, 'body'));
        const extras: SDResult = {};
        extras['jumpAddress'] = SD.str(flat, 'jumpAddress');
        const jumpParams: Object | null | undefined = flat['jumpParams'] as Object | null | undefined;
        if (jumpParams !== null && jumpParams !== undefined) {
          extras['jumpParams'] = jumpParams;
        }
        msg.extras = extras;
        return msg;
      }
    } catch (e) {
      return null;
    }
    return null;
  }

  /** JSON 字符串 / 对象 → Record；其余（null、数组、基本类型、非法 JSON）→ 空对象。 */
  private static toRecord(raw: Object | null | undefined): SDParams {
    const empty: SDParams = {};
    if (raw === null || raw === undefined) {
      return empty;
    }
    if (typeof raw === 'string') {
      const text: string = raw as string;
      if (text.length === 0) {
        return empty;
      }
      return SD.parseJson(text);
    }
    if (typeof raw === 'object' && !Array.isArray(raw)) {
      return raw as SDParams;
    }
    return empty;
  }
}
