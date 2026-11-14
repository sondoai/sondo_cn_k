// Cursor 没有 DevEco 自带的 Node 类型。只声明这两份构建脚本用到的全局量。
declare const __filename: string;
declare const process: {
  env: Record<string, string | undefined>;
};
