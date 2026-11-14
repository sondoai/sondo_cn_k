/*
 * 极光一键登录（JVerify）适配接口 + 当前生效的「不支持」实现。
 *
 * 现状：鸿蒙端默认 **不接入** 极光认证 SDK。SDAuthModule 的 jverify* 方法经由这里的
 * UnsupportedJVerifyAdapter 统一回「不支持」，Kuikly 侧据此退回应用内登录弹层（微信 + 短信）：
 *     jverifySetup       → {ok:true, code:-1}
 *     jverifyCheckEnable → {ok:true, enable:false}
 *     jverifyPreLogin    → {ok:true, code:-1}
 *     jverifyLoginAuth   → {ok:true, action:"unsupported"}
 *
 * 为什么不直接接：极光认证鸿蒙 SDK（ohpm 包 @jg/verify）的接口只在官网文档里看到过，
 * 没有可对照的源码 / 类型声明；授权页还要用 SDK 的 UI 配置 + 自定义视图重做底部弹层
 * （短信 / 微信入口、协议行、未勾选提示），并依赖宿主的 router / Navigation 结构，
 * 这些在本机都无法编译或运行验证。
 *
 * 要接入时：实现 JVerifyAdapter（同目录的 JVerifySdkAdapter.ets.draft 是按官网文档写的草稿，
 * 未编译、未验证），然后把 JVerifyProvider.create() 改成返回它。SDAuthModule 不需要改：
 * 超时兜底、只回调一次、未初始化保护都在模块里。
 */

/** jverifyLoginAuth 的入参（文案 / 协议链接由 Kuikly 侧传入）。 */
export class JVerifyPrivacyItem {
  name: string = '';
  url: string = '';
}

export class JVerifyLoginOptions {
  title: string = '';
  loginButtonText: string = '';
  /** 短信登录入口文案；空串表示不显示该入口。 */
  otherLoginText: string = '';
  /** 微信登录入口文案；空串表示不显示该入口。 */
  wechatLoginText: string = '';
  privacyPrefix: string = '';
  privacySuffix: string = '';
  privacyItems: Array<JVerifyPrivacyItem> = [];
  privacyUncheckedToast: string = '';
  timeoutMs: number = 10000;
}

export class JVerifyLoginResult {
  /** "token" | "wechat" | "sms" | "cancel" | "unsupported" | "error" */
  action: string = 'unsupported';
  token: string = '';
  operator: string = '';
  /** SDK 原始结果码（6000 成功、6002 取消、2016 网络不支持 …）；没有时为 0。 */
  code: number = 0;
  error: string = '';
}

export class JVerifyAction {
  static readonly TOKEN: string = 'token';
  static readonly WECHAT: string = 'wechat';
  static readonly SMS: string = 'sms';
  static readonly CANCEL: string = 'cancel';
  static readonly UNSUPPORTED: string = 'unsupported';
  static readonly ERROR: string = 'error';

  static isValid(action: string): boolean {
    return action === JVerifyAction.TOKEN || action === JVerifyAction.WECHAT || action === JVerifyAction.SMS ||
      action === JVerifyAction.CANCEL || action === JVerifyAction.UNSUPPORTED || action === JVerifyAction.ERROR;
  }
}

export class JVerifyCode {
  /** 初始化成功 */
  static readonly SETUP_OK: number = 8000;
  /** 本端未接入 / 不支持 */
  static readonly UNSUPPORTED: number = -1;
  /** 模块侧超时兜底 */
  static readonly TIMEOUT: number = -2;
  /** 适配器抛错 */
  static readonly FAILED: number = -3;
}

/**
 * 适配器约定：
 *  - 所有 Promise 都必须 settle（成功或 reject），实现方自己负责 SDK 级别的超时；
 *  - loginAuth 在授权页关闭后 resolve，一次调用只 resolve 一次；
 *  - dismiss 让进行中的 loginAuth 以 action="cancel" 结束；
 *  - setup 之前不得触碰 SDK（隐私合规）。
 */
export interface JVerifyAdapter {
  /** 本端是否真的接了一键登录 SDK。false 时模块不会调用下面任何方法。 */
  isSupported(): boolean;

  /** 初始化；resolve SDK 结果码（8000 = 成功）。 */
  setup(appKey: string, channel: string, debug: boolean, timeoutMs: number): Promise<number>;

  /** 当前网络环境是否支持一键登录。 */
  checkEnable(): Promise<boolean>;

  /** 预取号；resolve SDK 结果码（7000 = 成功）。 */
  preLogin(timeoutMs: number): Promise<number>;

  /** 拉起授权页。 */
  loginAuth(options: JVerifyLoginOptions): Promise<JVerifyLoginResult>;

  /** 关闭授权页。 */
  dismiss(): void;
}

/** 当前生效的实现：不依赖任何 SDK，全部回「不支持」。 */
export class UnsupportedJVerifyAdapter implements JVerifyAdapter {
  isSupported(): boolean {
    return false;
  }

  async setup(appKey: string, channel: string, debug: boolean, timeoutMs: number): Promise<number> {
    return JVerifyCode.UNSUPPORTED;
  }

  async checkEnable(): Promise<boolean> {
    return false;
  }

  async preLogin(timeoutMs: number): Promise<number> {
    return JVerifyCode.UNSUPPORTED;
  }

  async loginAuth(options: JVerifyLoginOptions): Promise<JVerifyLoginResult> {
    return new JVerifyLoginResult();
  }

  dismiss(): void {
  }
}

export class JVerifyProvider {
  private static instance: JVerifyAdapter | null = null;

  /** 接入极光认证 SDK 后，把这里换成 `return new JVerifySdkAdapter();`（并 import 它）。 */
  private static create(): JVerifyAdapter {
    return new UnsupportedJVerifyAdapter();
  }

  static get(): JVerifyAdapter {
    let adapter: JVerifyAdapter | null = JVerifyProvider.instance;
    if (adapter === null) {
      adapter = JVerifyProvider.create();
      JVerifyProvider.instance = adapter;
    }
    return adapter;
  }
}
