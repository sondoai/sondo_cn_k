/*
 * 把 Kuikly 运行时初始化入口（libkuikly_entry.so 的 initKuikly）交给渲染器。
 */
import { KuiklyNativeManager } from '@kuikly-open/render';
import Napi from 'libkuikly_entry.so';

class MyNativeManager extends KuiklyNativeManager {
  loadNative(): number {
    return Napi.initKuikly();
  }
}

// 全局唯一实例，所有 Kuikly 页面共用
const globalNativeManager = new MyNativeManager();

export default globalNativeManager;
