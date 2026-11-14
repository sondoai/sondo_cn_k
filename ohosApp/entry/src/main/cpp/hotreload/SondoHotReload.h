#pragma once

#include "napi/native_api.h"

// 仅由显式开启 SONDO_KUIKLY_HOT_RELOAD 的 Debug 目标链接。
int SondoInstallHotReloadBridge();
void SondoExportHotReload(napi_env env, napi_value exports);
