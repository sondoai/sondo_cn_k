// 对应 DevEco 里的 @ohos/hvigor-ohos-plugin，只覆盖 ohosApp 两份 hvigorfile 用到的面。
export const hapTasks: unknown;
export const appTasks: unknown;

export class OhosPluginId {
  static readonly OHOS_APP_PLUGIN: string;
  static readonly OHOS_HAP_PLUGIN: string;
}

export interface ExternalNativeOpt {
  arguments?: string | string[];
}

export interface ModuleBuildOpt {
  buildOption?: {
    arkOptions?: { buildProfileFields?: object };
    externalNativeOptions?: ExternalNativeOpt;
  };
}

export interface OhosHapContext {
  getBuildMode(): string;
  getBuildProfileOpt(): ModuleBuildOpt;
  setBuildProfileOpt(opt: ModuleBuildOpt): void;
}

export interface SigningMaterial {
  storeFile: string;
  certpath: string;
  profile: string;
}

export interface SigningConfig {
  name: string;
  material: SigningMaterial;
}

export interface ProductProfile {
  name: string;
  signingConfig?: string;
}

export interface ProjectProfileOpt {
  app: {
    signingConfigs?: SigningConfig[];
    products?: ProductProfile[];
  };
}

export interface OhosAppContext {
  getBuildProfileOpt(): ProjectProfileOpt;
  setBuildProfileOpt(opt: ProjectProfileOpt): void;
}
