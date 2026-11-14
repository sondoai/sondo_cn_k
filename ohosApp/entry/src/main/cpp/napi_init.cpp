/*
 * Sondo Kuikly 入口（NAPI）。
 *
 * 导出 initKuikly()：
 *   1. 首次调用时注册 C 侧适配器（日志、自定义字体）；
 *   2. 调用业务产物 libshared.so 的 initKuikly（由 Kuikly KSP 生成），返回值交给
 *      ArkTS 侧 KuiklyNativeManager.loadNative()。
 *
 * 图片：Kuikly 鸿蒙渲染器内置了 http(s) / file:// / data: / assets:// 的加载，
 * 其中 assets://common/xxx 会映射到 hap 的 resfile/common/xxx（由 ohos_build.sh 同步），
 * 所以这里不需要再注册图片适配器。
 */
#include <cstring>
#include <string>
#include <unordered_map>

#include <hilog/log.h>
#include <Kuikly/Kuikly.h>

#include "napi/native_api.h"
#include "thirdparty/biz_entry/libshared_api.h"

namespace {

constexpr unsigned int kLogDomain = 0x5D01;

// ---------------------------------------------------------------------------
// 日志适配器：Kuikly C++ 渲染层与 Kotlin 侧 KLog 的输出统一进 hilog。
// ---------------------------------------------------------------------------
void SondoLogAdapter(int logLevel, const char *tag, const char *message) {
    LogLevel level = LOG_INFO;
    if (logLevel == KRLogLevelError) {
        level = LOG_ERROR;
    } else if (logLevel == KRLogLevelDebug) {
        level = LOG_DEBUG;
    }
    OH_LOG_Print(LOG_APP, level, kLogDomain, tag != nullptr ? tag : "Kuikly", "%{public}s",
                 message != nullptr ? message : "");
}

// ---------------------------------------------------------------------------
// 字体适配器：fontFamily -> hap rawfile 路径（"rawfile:" 前缀由渲染器识别并读取）。
//
// family 名与 Flutter 参考工程 pubspec.yaml 的 fonts 段一致；鸿蒙一个 family 名只能绑定
// 一个字体文件，所以多字重的 family 额外提供「显式字面」名（与 iOS PostScript 名一致），
// 如 Poppins-SemiBold。不带后缀的 family 取常规字重。
// 这张表必须与 ets/kuikly/adapters/AppFonts.ets 保持一致。
// ---------------------------------------------------------------------------
const std::unordered_map<std::string, std::string> &FontTable() {
    static const std::unordered_map<std::string, std::string> table = {
        {"Poppins", "rawfile:fonts/poppins_regular.ttf"},
        {"Poppins-Thin", "rawfile:fonts/poppins_thin.ttf"},
        {"Poppins-Light", "rawfile:fonts/poppins_light.ttf"},
        {"Poppins-Regular", "rawfile:fonts/poppins_regular.ttf"},
        {"Poppins-Medium", "rawfile:fonts/poppins_medium.ttf"},
        {"Poppins-SemiBold", "rawfile:fonts/poppins_semibold.ttf"},
        {"Poppins-Bold", "rawfile:fonts/poppins_bold.ttf"},
        {"Montserrat", "rawfile:fonts/Montserrat-Medium.otf"},
        {"Montserrat-Medium", "rawfile:fonts/Montserrat-Medium.otf"},
        {"Montserrat-SemiBold", "rawfile:fonts/Montserrat-SemiBold.otf"},
        {"Montserrat-Bold", "rawfile:fonts/Montserrat-Bold.otf"},
        {"Anton", "rawfile:fonts/anton_regular.ttf"},
        {"Bangers", "rawfile:fonts/Bangers.ttf"},
        {"LemonMilk", "rawfile:fonts/LemonMilk-1.otf"},
        {"MomcakeBold", "rawfile:fonts/MOMCAKE-Bold.ttf"},
        {"MomcakeThin", "rawfile:fonts/MOMCAKE-Thin.ttf"},
        {"MonotonRegular", "rawfile:fonts/Monoton-Regular.ttf"},
        {"MontserratBlack", "rawfile:fonts/Montserrat-Black.ttf"},
        {"MontserratBlackItalic", "rawfile:fonts/MontserratAlternates-BlackItalic.ttf"},
        {"Roboto", "rawfile:fonts/roboto_medium_numbers.ttf"},
        {"Carter", "rawfile:fonts/Carter-One.ttf"},
        {"PaytoneOne", "rawfile:fonts/PaytoneOne.ttf"},
    };
    return table;
}

// 返回值指向 FontTable() 内的静态字符串，生命周期覆盖整个进程，无需 deallocator。
char *SondoFontAdapter(const char *fontFamily, char **fontBuffer, size_t *len, KRFontDataDeallocator *deallocator) {
    (void)fontBuffer;
    (void)len;
    (void)deallocator;
    if (fontFamily == nullptr) {
        return nullptr;
    }
    const auto &table = FontTable();
    const auto it = table.find(fontFamily);
    if (it == table.end()) {
        return nullptr;
    }
    return const_cast<char *>(it->second.c_str());
}

bool g_adapters_registered = false;

void RegisterAdaptersOnce() {
    if (g_adapters_registered) {
        return;
    }
    g_adapters_registered = true;
    KRRegisterLogAdapter(SondoLogAdapter);
    for (const auto &entry : FontTable()) {
        KRRegisterFontAdapter(SondoFontAdapter, entry.first.c_str());
    }
}

napi_value InitKuikly(napi_env env, napi_callback_info info) {
    (void)info;
    RegisterAdaptersOnce();

    // 入口符号由 Kotlin/Native 产物头文件 libshared_api.h 决定：
    // KSP 生成的 initKuikly 位于根包，因此是 kotlin.root.initKuikly。
    auto api = libshared_symbols();
    int handler = api->kotlin.root.initKuikly();
    napi_value result = nullptr;
    napi_create_int32(env, handler, &result);
    return result;
}

}  // namespace

EXTERN_C_START
static napi_value Init(napi_env env, napi_value exports) {
    napi_property_descriptor desc[] = {
        {"initKuikly", nullptr, InitKuikly, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);
    return exports;
}
EXTERN_C_END

static napi_module entry_module = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = nullptr,
    .nm_register_func = Init,
    .nm_modname = "kuikly_entry",
    .nm_priv = static_cast<void *>(0),
    .reserved = {0},
};

extern "C" __attribute__((constructor)) void RegisterKuikly_EntryModule(void) {
    napi_module_register(&entry_module);
}
