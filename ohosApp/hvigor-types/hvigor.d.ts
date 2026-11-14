// 对应 DevEco 里的 @ohos/hvigor。构建时仍由 hvigor 自己解析真包，这里只给编辑器类型。
export interface HvigorNode {
  getContext(pluginId: string): unknown;
  afterNodeEvaluate(fn: (node: HvigorNode) => void): void;
}

export interface HvigorParameter {
  getExtParam(key: string): string | undefined;
}

export interface Hvigor {
  getParameter(): HvigorParameter;
}

export const hvigor: Hvigor;

export function getNode(buildScriptFilePath?: string): HvigorNode | undefined;
