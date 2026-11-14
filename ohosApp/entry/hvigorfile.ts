import { hapTasks, OhosHapContext, OhosPluginId } from '@ohos/hvigor-ohos-plugin';
import { getNode, hvigor, HvigorNode } from '@ohos/hvigor';

// 命令行 -p API_ENV / -p SHOW_TOKEN_EDITOR（scripts/build/build_app.sh 传入）覆盖 build-profile.json5 的
// buildProfileFields。SONDO_KUIKLY_HOT_RELOAD=1 只为本次 Debug 构建打开热重载，并给 CMake 加上
// -DSONDO_KUIKLY_HOT_RELOAD=ON。两者都只改内存配置；都不传时保持文件里的默认值，DevEco 直接运行不受影响。
const HOT_RELOAD_CMAKE_ARG = '-DSONDO_KUIKLY_HOT_RELOAD=ON';

const node = getNode(__filename);
node?.afterNodeEvaluate((n: HvigorNode) => {
  const apiEnv = hvigor.getParameter().getExtParam('API_ENV');
  const showTokenEditor = hvigor.getParameter().getExtParam('SHOW_TOKEN_EDITOR');
  const hotReload = process.env.SONDO_KUIKLY_HOT_RELOAD === '1';
  if (apiEnv === undefined && showTokenEditor === undefined && !hotReload) {
    return;
  }
  const ctx = n.getContext(OhosPluginId.OHOS_HAP_PLUGIN) as OhosHapContext;
  let hotReloadUrl: string | undefined;
  if (hotReload) {
    if (ctx.getBuildMode() !== 'debug') {
      throw new Error('SONDO_KUIKLY_HOT_RELOAD can only be enabled for a debug build');
    }
    const configuredUrl = process.env.SONDO_HOT_RELOAD_URL || 'http://127.0.0.1:8088';
    if (!/^https?:\/\/[a-zA-Z0-9.:[\]-]+\/?$/.test(configuredUrl)) {
      throw new Error('SONDO_HOT_RELOAD_URL must be an HTTP origin, for example http://192.168.1.8:8088');
    }
    hotReloadUrl = configuredUrl;
  }
  const opt = ctx.getBuildProfileOpt();
  // hvigor 把自定义字段标成 object，没有索引签名，这里收成可写的键值表。
  const fields = opt.buildOption?.arkOptions?.buildProfileFields as
    | Record<string, string | boolean>
    | undefined;
  if (!fields) {
    throw new Error('entry/build-profile.json5 缺少 buildOption.arkOptions.buildProfileFields');
  }
  if (apiEnv !== undefined) {
    if (!['local', 'test', 'prod'].includes(apiEnv)) {
      throw new Error(`API_ENV 只能是 local / test / prod：${apiEnv}`);
    }
    fields['API_ENV'] = apiEnv;
  }
  if (showTokenEditor !== undefined) {
    fields['SHOW_TOKEN_EDITOR'] = showTokenEditor === 'true';
  }
  if (hotReloadUrl !== undefined) {
    const nativeOptions = opt.buildOption?.externalNativeOptions;
    if (!nativeOptions) {
      throw new Error('entry/build-profile.json5 缺少 buildOption.externalNativeOptions');
    }
    const rawArgs = nativeOptions.arguments;
    const parts = (Array.isArray(rawArgs) ? rawArgs : (rawArgs ?? '').split(/\s+/)).filter((part) => part.length > 0);
    if (!parts.includes(HOT_RELOAD_CMAKE_ARG)) {
      nativeOptions.arguments = [...parts, HOT_RELOAD_CMAKE_ARG].join(' ');
    }
    fields['KUIKLY_HOT_RELOAD'] = true;
    fields['KUIKLY_HOT_RELOAD_URL'] = hotReloadUrl.replace(/\/$/, '');
  }
  ctx.setBuildProfileOpt(opt);
  const hotReloadLog = hotReloadUrl === undefined ? '' : ` KUIKLY_HOT_RELOAD_URL=${fields['KUIKLY_HOT_RELOAD_URL']}`;
  console.log(`[sondo] buildProfileFields API_ENV=${fields['API_ENV']} SHOW_TOKEN_EDITOR=${fields['SHOW_TOKEN_EDITOR']}${hotReloadLog}`);
});

export default {
  system: hapTasks, /* Built-in plugin of Hvigor. It cannot be modified. */
  plugins: []       /* Custom plugin to extend the functionality of Hvigor. */
}
