import { appTasks, OhosAppContext, OhosPluginId } from '@ohos/hvigor-ohos-plugin';
import { getNode, hvigor, HvigorNode } from '@ohos/hvigor';
import * as fs from 'fs';

// scripts/build/build_app.sh 传 -p SONDO_SIGN_FALLBACK=true：build-profile.json5 里引用的签名文件在本机不存在时
// （签名路径是别人机器上的绝对路径），去掉 product 的 signingConfig，让 hvigor 只出 unsigned hap，
// 再由脚本用 hap-sign-tool + scripts/build/build.local.sh 里的证书签名。不传参数（DevEco 里运行）时不做任何改动。
const node = getNode(__filename);
node?.afterNodeEvaluate((n: HvigorNode) => {
  if (hvigor.getParameter().getExtParam('SONDO_SIGN_FALLBACK') !== 'true') {
    return;
  }
  const ctx = n.getContext(OhosPluginId.OHOS_APP_PLUGIN) as OhosAppContext;
  const opt = ctx.getBuildProfileOpt();
  const configs = opt.app.signingConfigs ?? [];
  let changed = false;
  for (const product of opt.app.products ?? []) {
    const name = product.signingConfig;
    if (!name) {
      continue;
    }
    const material = configs.find((c) => c.name === name)?.material;
    const files = [material?.storeFile, material?.certpath, material?.profile];
    if (files.every((f) => typeof f === 'string' && f.length > 0 && fs.existsSync(f))) {
      continue;
    }
    console.log(`[sondo] signingConfig "${name}" 的签名文件不在本机，product "${product.name}" 改为不签名`);
    delete product.signingConfig;
    changed = true;
  }
  if (changed) {
    ctx.setBuildProfileOpt(opt);
  }
});

export default {
  system: appTasks, /* Built-in plugin of Hvigor. It cannot be modified. */
  plugins: []       /* Custom plugin to extend the functionality of Hvigor. */
}
