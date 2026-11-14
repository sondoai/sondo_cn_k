package com.sondo.app.nativeapi

import com.tencent.kuikly.core.module.Module

/**
 * 所有 Sondo 原生扩展模块的注册表。BasePager.createExternalModules 使用。
 * 新增模块：这里加一行 + 三端宿主各实现并注册同名模块。
 */
object NativeModules {
    fun create(): Map<String, Module> = hashMapOf(
        SDHttpModule.MODULE_NAME to SDHttpModule(),
        SDNavModule.MODULE_NAME to SDNavModule(),
        SDAppModule.MODULE_NAME to SDAppModule(),
        SDPermissionModule.MODULE_NAME to SDPermissionModule(),
        SDFileModule.MODULE_NAME to SDFileModule(),
        SDCryptoModule.MODULE_NAME to SDCryptoModule(),
        SDAudioPlayerModule.MODULE_NAME to SDAudioPlayerModule(),
        SDVideoCacheModule.MODULE_NAME to SDVideoCacheModule(),
        SDMediaPickerModule.MODULE_NAME to SDMediaPickerModule(),
        SDMediaProcessModule.MODULE_NAME to SDMediaProcessModule(),
        SDPayModule.MODULE_NAME to SDPayModule(),
        SDAuthModule.MODULE_NAME to SDAuthModule(),
        SDPushModule.MODULE_NAME to SDPushModule(),
        SDAnalyticsModule.MODULE_NAME to SDAnalyticsModule(),
    )
}
