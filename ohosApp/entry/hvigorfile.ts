import { hapTasks, OhosHapContext, OhosPluginId } from '@ohos/hvigor-ohos-plugin';
import { getNode, hvigor, HvigorNode } from '@ohos/hvigor';

// 命令行 -p API_ENV=test -p SHOW_TOKEN_EDITOR=true（scripts/build_app.sh 传入）覆盖 build-profile.json5 的
// buildProfileFields；不传时保持文件里的默认值，DevEco 里直接运行不受影响。
const node = getNode(__filename);
node?.afterNodeEvaluate((n: HvigorNode) => {
  const apiEnv = hvigor.getParameter().getExtParam('API_ENV');
  const showTokenEditor = hvigor.getParameter().getExtParam('SHOW_TOKEN_EDITOR');
  if (apiEnv === undefined && showTokenEditor === undefined) {
    return;
  }
  const ctx = n.getContext(OhosPluginId.OHOS_HAP_PLUGIN) as OhosHapContext;
  const opt = ctx.getBuildProfileOpt();
  const fields = opt.buildOption?.arkOptions?.buildProfileFields;
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
  ctx.setBuildProfileOpt(opt);
  console.log(`[sondo] buildProfileFields API_ENV=${fields['API_ENV']} SHOW_TOKEN_EDITOR=${fields['SHOW_TOKEN_EDITOR']}`);
});

// 构建时显式开启，配置只驻留内存，不重写签名文件或Release依赖。
hvigor.afterNodeEvaluate((node) => {
  if (node.getNodeName() !== 'entry' || process.env.SONDO_KUIKLY_HOT_RELOAD !== '1') return;
  const context = node.getContext(OhosPluginId.OHOS_HAP_PLUGIN) as OhosHapContext;
  if (context.getBuildMode() !== 'debug') {
    throw new Error('SONDO_KUIKLY_HOT_RELOAD can only be enabled for a debug build');
  }
  const url = process.env.SONDO_HOT_RELOAD_URL || 'http://127.0.0.1:8088';
  if (!/^https?:\/\/[a-zA-Z0-9.:[\]-]+\/?$/.test(url)) {
    throw new Error('SONDO_HOT_RELOAD_URL must be an HTTP origin, for example http://192.168.1.8:8088');
  }
  const profile = context.getBuildProfileOpt();
  const option = profile.buildOption!;
  option.externalNativeOptions!.arguments =
    `${option.externalNativeOptions!.arguments || ''} -DSONDO_KUIKLY_HOT_RELOAD=ON`;
  option.arkOptions!.buildProfileFields = {
    ...option.arkOptions!.buildProfileFields,
    KUIKLY_HOT_RELOAD: true,
    KUIKLY_HOT_RELOAD_URL: url.replace(/\/$/, '')
  };
  context.setBuildProfileOpt(profile);
});

export default {
  system: hapTasks, /* Built-in plugin of Hvigor. It cannot be modified. */
  plugins: []       /* Custom plugin to extend the functionality of Hvigor. */
}
