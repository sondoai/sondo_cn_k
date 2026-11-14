/*
 * SDPermissionModule —— 运行时权限（契约见 shared/.../nativeapi/SDAppModule.kt 里的 SDPermissionModule）。
 *
 *   status {permission}  → {ok, status}
 *   request {permission} → {ok, status}
 *
 * permission → 鸿蒙：
 *   notification   notificationManager.isNotificationEnabled / requestEnableNotification
 *   camera         ohos.permission.CAMERA（module.json5 未声明时视为 granted：系统相机 Picker 不需要权限）
 *   microphone     ohos.permission.MICROPHONE
 *   photos / photosAddOnly / storage   granted（相册读写走系统 Picker / 保存控件，不需要权限）
 *   tracking       granted（仅 iOS ATT 有意义）
 *
 * status："granted" | "denied" | "permanentlyDenied"。
 * 鸿蒙没有公开的「是否永久拒绝」查询：系统弹窗只在首次申请时出现，用户拒绝后再次申请会直接返回拒绝。
 * 这里把「申请时系统没有弹窗且结果为拒绝」记为 permanentlyDenied（并持久化，供 status 使用），
 * 引导去设置页的弹窗由 Kuikly 侧负责（SDAppModule.openAppSettings）。
 */
import { abilityAccessCtrl, bundleManager, common, Permissions } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { BusinessError } from '@kit.BasicServicesKit';
import { notificationManager } from '@kit.NotificationKit';
import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { SD, SDHost, SDLog, SDParams, SDReply, SDResult } from '../../support/SDModuleSupport';

const TAG: string = 'SDPermissionModule';
const STORE_NAME: string = 'sd_permission_state';

const GRANTED: string = 'granted';
const DENIED: string = 'denied';
const PERMANENTLY_DENIED: string = 'permanentlyDenied';

export class SDPermissionModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME: string = 'SDPermissionModule';

  /** 进程内缓存：module.json5 里声明过的权限 */
  private static declaredPermissions: Array<string> | null = null;

  syncMode(): boolean {
    return false;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    const p: SDParams = SD.parse(params);
    const reply: SDReply = new SDReply(callback);
    const permission: string = SD.str(p, 'permission');
    let work: Promise<string>;
    if (method === 'status') {
      work = this.status(permission);
    } else if (method === 'request') {
      work = this.request(permission);
    } else {
      reply.fail(`unknown method: ${method}`);
      return null;
    }
    work
      .then((status: string) => {
        const extra: SDResult = {};
        extra['status'] = status;
        reply.ok(extra);
      })
      .catch((e: Error) => {
        const extra: SDResult = {};
        extra['status'] = DENIED;
        reply.fail(SD.errMsg(e), SD.errCode(e), extra);
      });
    return null;
  }

  // -----------------------------------------------------------------------

  private async status(permission: string): Promise<string> {
    switch (permission) {
      case 'notification':
        return await this.notificationStatus();
      case 'camera':
        return await this.systemPermissionStatus('ohos.permission.CAMERA', true);
      case 'microphone':
        return await this.systemPermissionStatus('ohos.permission.MICROPHONE', false);
      case 'photos':
      case 'photosAddOnly':
      case 'storage':
      case 'tracking':
        return GRANTED;
      default:
        SDLog.w(TAG, `status: unknown permission ${permission}`);
        return DENIED;
    }
  }

  private async request(permission: string): Promise<string> {
    switch (permission) {
      case 'notification':
        return await this.requestNotification();
      case 'camera':
        return await this.requestSystemPermission('ohos.permission.CAMERA', true);
      case 'microphone':
        return await this.requestSystemPermission('ohos.permission.MICROPHONE', false);
      case 'photos':
      case 'photosAddOnly':
      case 'storage':
      case 'tracking':
        return GRANTED;
      default:
        SDLog.w(TAG, `request: unknown permission ${permission}`);
        return DENIED;
    }
  }

  // -----------------------------------------------------------------------
  // 通知
  // -----------------------------------------------------------------------

  private async notificationStatus(): Promise<string> {
    const enabled: boolean = await notificationManager.isNotificationEnabled();
    if (enabled) {
      await this.setPermanentlyDenied('notification', false);
      return GRANTED;
    }
    return (await this.isPermanentlyDenied('notification')) ? PERMANENTLY_DENIED : DENIED;
  }

  private async requestNotification(): Promise<string> {
    if (await notificationManager.isNotificationEnabled()) {
      await this.setPermanentlyDenied('notification', false);
      return GRANTED;
    }
    const context: common.UIAbilityContext = SDHost.requireContext();
    const startedAt: number = Date.now();
    try {
      await notificationManager.requestEnableNotification(context);
    } catch (e) {
      // 1600004：通知未开启（用户拒绝，或此前已拒绝导致系统不再弹窗）
      SDLog.i(TAG, `requestEnableNotification rejected: ${SD.errMsg(e as Object)}`);
    }
    if (await notificationManager.isNotificationEnabled()) {
      await this.setPermanentlyDenied('notification', false);
      return GRANTED;
    }
    // 系统弹窗需要用户操作；几乎立即返回说明没有弹窗 → 之前已经拒绝过
    const noDialog: boolean = Date.now() - startedAt < 350;
    if (noDialog) {
      await this.setPermanentlyDenied('notification', true);
      return PERMANENTLY_DENIED;
    }
    return DENIED;
  }

  // -----------------------------------------------------------------------
  // 系统敏感权限（user_grant）
  // -----------------------------------------------------------------------

  private async systemPermissionStatus(permission: Permissions, grantedIfUndeclared: boolean): Promise<string> {
    if (!this.isDeclared(permission)) {
      return grantedIfUndeclared ? GRANTED : DENIED;
    }
    if (this.isGranted(permission)) {
      await this.setPermanentlyDenied(permission, false);
      return GRANTED;
    }
    return (await this.isPermanentlyDenied(permission)) ? PERMANENTLY_DENIED : DENIED;
  }

  private async requestSystemPermission(permission: Permissions, grantedIfUndeclared: boolean): Promise<string> {
    if (!this.isDeclared(permission)) {
      SDLog.w(TAG, `${permission} is not declared in module.json5`);
      return grantedIfUndeclared ? GRANTED : DENIED;
    }
    if (this.isGranted(permission)) {
      await this.setPermanentlyDenied(permission, false);
      return GRANTED;
    }
    const context: common.UIAbilityContext = SDHost.requireContext();
    const atManager: abilityAccessCtrl.AtManager = abilityAccessCtrl.createAtManager();
    const requested: Array<Permissions> = [permission];
    const result = await atManager.requestPermissionsFromUser(context, requested);
    const granted: boolean = result.authResults.length > 0 &&
      result.authResults[0] === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
    if (granted) {
      await this.setPermanentlyDenied(permission, false);
      return GRANTED;
    }
    // dialogShownResults：本次申请系统是否弹了窗。没弹窗且被拒绝 = 之前拒绝过，只能去设置里开启
    const shown: Array<boolean> | undefined = result.dialogShownResults;
    const dialogShown: boolean = shown === undefined || shown === null || shown.length === 0 || shown[0];
    if (!dialogShown) {
      await this.setPermanentlyDenied(permission, true);
      return PERMANENTLY_DENIED;
    }
    return DENIED;
  }

  private isGranted(permission: Permissions): boolean {
    try {
      const info: bundleManager.BundleInfo = bundleManager.getBundleInfoForSelfSync(
        bundleManager.BundleFlag.GET_BUNDLE_INFO_WITH_APPLICATION);
      const atManager: abilityAccessCtrl.AtManager = abilityAccessCtrl.createAtManager();
      const state: abilityAccessCtrl.GrantStatus =
        atManager.checkAccessTokenSync(info.appInfo.accessTokenId, permission);
      return state === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
    } catch (e) {
      SDLog.e(TAG, `checkAccessToken failed: ${SD.errMsg(e as Object)}`);
      return false;
    }
  }

  /** 权限是否在 module.json5 的 requestPermissions 里声明过。 */
  private isDeclared(permission: string): boolean {
    if (SDPermissionModule.declaredPermissions === null) {
      const declared: Array<string> = [];
      try {
        const info: bundleManager.BundleInfo = bundleManager.getBundleInfoForSelfSync(
          bundleManager.BundleFlag.GET_BUNDLE_INFO_WITH_REQUESTED_PERMISSION);
        const details: Array<bundleManager.ReqPermissionDetail> = info.reqPermissionDetails;
        for (let i = 0; i < details.length; i++) {
          declared.push(details[i].name);
        }
      } catch (e) {
        SDLog.e(TAG, `read requested permissions failed: ${SD.errMsg(e as Object)}`);
      }
      SDPermissionModule.declaredPermissions = declared;
    }
    return SDPermissionModule.declaredPermissions.indexOf(permission) >= 0;
  }

  // -----------------------------------------------------------------------
  // 「永久拒绝」标记持久化
  // -----------------------------------------------------------------------

  private async store(): Promise<preferences.Preferences> {
    const context: common.UIAbilityContext = SDHost.requireContext();
    return await preferences.getPreferences(context, STORE_NAME);
  }

  private async isPermanentlyDenied(key: string): Promise<boolean> {
    try {
      const store: preferences.Preferences = await this.store();
      const value: preferences.ValueType = await store.get(key, false);
      return value === true;
    } catch (e) {
      return false;
    }
  }

  private async setPermanentlyDenied(key: string, denied: boolean): Promise<void> {
    try {
      const store: preferences.Preferences = await this.store();
      const current: preferences.ValueType = await store.get(key, false);
      if ((current === true) === denied) {
        return;
      }
      await store.put(key, denied);
      await store.flush();
    } catch (e) {
      SDLog.w(TAG, `persist permission state failed: ${SD.errMsg(e as Object)}`);
    }
  }
}
