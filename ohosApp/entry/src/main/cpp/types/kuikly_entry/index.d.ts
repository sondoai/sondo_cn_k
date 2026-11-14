/**
 * libkuikly_entry.so 的 NAPI 接口声明（实现见 ../../napi_init.cpp）。
 */

/**
 * 初始化 Kuikly 业务运行时（libshared.so），并注册 C 侧适配器（日志、字体）。
 * 返回值交给 KuiklyNativeManager.loadNative()。
 */
export const initKuikly: () => number;

/** 只存在于显式开启热重载的Debug原生库；先验证新bundle，再原子切换新页面的执行器。 */
export const setHotReloadBundle: (version: string, source: string) => Promise<boolean>;
