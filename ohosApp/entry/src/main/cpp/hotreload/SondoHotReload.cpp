/*
 * 开发期 JSVM 执行器。复用 Kuikly 2.30.0 的 Kotlin/Native C ABI，保留现有渲染器、
 * SD*Module 和原生 View；只把业务代码的执行端换成共享的 Kotlin/JS bundle。
 * 每个 bundle 版本一个 VM，所有页面共享单例。已销毁页面的异步回调不会转给新版本。
 */
#include "SondoHotReload.h"

#include <ark_runtime/jsvm.h>
#include <Kuikly/KRJSON.h>
#include <hilog/log.h>
#include <cmath>
#include <atomic>
#include <cstring>
#include <functional>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

// 官方 2.30.0 foundation/type/KRRenderCValue.h 中显式导出的既有 C ABI。
extern "C" {
using CallKotlin = void (*)(int, KRJSON, KRJSON, KRJSON, KRJSON, KRJSON, KRJSON);
int com_tencent_kuikly_SetCallKotlin(CallKotlin callback);
KRJSON com_tencent_kuikly_CallNative(int, KRJSON, KRJSON, KRJSON, KRJSON, KRJSON, KRJSON);
void com_tencent_kuikly_ScheduleContextTask(const char *, void (*)(const char *));
bool com_tencent_kuikly_IsCurrentOnContextThread(const char *);
}

namespace {
constexpr unsigned int kDomain = 0x5D04;
constexpr int kCreateInstance = 1;
constexpr int kDestroyInstance = 3;
constexpr size_t kMaxDepth = 64;
std::mutex g_jobsMutex;
std::atomic<uint64_t> g_nextJob{0};
std::unordered_map<std::string, std::function<void()>> g_jobs;

void RunJob(const char *id) {
    std::function<void()> job;
    {
        std::lock_guard<std::mutex> lock(g_jobsMutex);
        auto found = g_jobs.find(id);
        if (found == g_jobs.end()) return;
        job = std::move(found->second);
        g_jobs.erase(found);
    }
    job();
}

void Schedule(std::function<void()> job) {
    const std::string id = "sondo-jsvm-" + std::to_string(++g_nextJob);
    {
        std::lock_guard<std::mutex> lock(g_jobsMutex);
        g_jobs.emplace(id, std::move(job));
    }
    com_tencent_kuikly_ScheduleContextTask(id.c_str(), RunJob);
}

void Check(JSVM_Status status, const char *operation) {
    if (status != JSVM_OK) {
        throw std::runtime_error(std::string(operation) + " failed (JSVM " + std::to_string(status) + ")");
    }
}

std::string JsString(JSVM_Env env, JSVM_Value value) {
    size_t length = 0;
    Check(OH_JSVM_GetValueStringUtf8(env, value, nullptr, 0, &length), "string length");
    std::vector<char> bytes(length + 1);
    Check(OH_JSVM_GetValueStringUtf8(env, value, bytes.data(), bytes.size(), &length), "string value");
    return std::string(bytes.data(), length);
}

std::string JsonString(KRJSON value) {
    char *text = KRJSONDump(value);
    if (!text) {
        throw std::runtime_error("KRJSON serialization failed");
    }
    std::string result(text);
    KRJSONFreeString(text);
    return result;
}

JSVM_Value MakeString(JSVM_Env env, const std::string &value) {
    JSVM_Value result = nullptr;
    Check(OH_JSVM_CreateStringUtf8(env, value.data(), value.size(), &result), "create string");
    return result;
}

// 和官方 Web/iOS 执行器保持一致：Native 回传的 map/array 是 JSON 文本。
JSVM_Value ToJS(JSVM_Env env, KRJSON value) {
    JSVM_Value result = nullptr;
    switch (KRJSONGetType(value)) {
        case KRJSON_NULL:
            Check(OH_JSVM_GetNull(env, &result), "null");
            return result;
        case KRJSON_STRING: {
            size_t length = 0;
            const char *text = KRJSONGetString(value, &length);
            Check(OH_JSVM_CreateStringUtf8(env, text, length, &result), "UTF-8 string");
            return result;
        }
        case KRJSON_U16STRING: {
            size_t length = 0;
            const uint16_t *text = KRJSONGetStringUTF16(value, &length);
            Check(OH_JSVM_CreateStringUtf16(env, reinterpret_cast<const char16_t *>(text), length, &result),
                  "UTF-16 string");
            return result;
        }
        case KRJSON_BOOL:
            Check(OH_JSVM_GetBoolean(env, KRJSONGetBool(value, false), &result), "boolean");
            return result;
        case KRJSON_ARRAY:
        case KRJSON_OBJECT:
            return MakeString(env, JsonString(value));
        case KRJSON_BYTES: {
            size_t length = 0;
            const uint8_t *bytes = KRJSONGetBytes(value, &length);
            void *target = nullptr;
            JSVM_Value buffer = nullptr;
            Check(OH_JSVM_CreateArraybuffer(env, length, &target, &buffer), "bytes buffer");
            if (length) std::memcpy(target, bytes, length);
            Check(OH_JSVM_CreateTypedarray(env, JSVM_INT8_ARRAY, length, buffer, 0, &result), "bytes view");
            return result;
        }
        default:
            Check(OH_JSVM_CreateDouble(env, KRJSONGetDouble(value, 0), &result), "number");
            return result;
    }
}

KRJSON FromJS(JSVM_Env env, JSVM_Value value, size_t depth = 0) {
    if (depth > kMaxDepth) throw std::runtime_error("Native bridge value is too deeply nested");
    JSVM_ValueType type;
    Check(OH_JSVM_Typeof(env, value, &type), "value type");
    if (type == JSVM_UNDEFINED || type == JSVM_NULL) return KRJSONNewNull();
    if (type == JSVM_BOOLEAN) {
        bool boolean = false;
        Check(OH_JSVM_GetValueBool(env, value, &boolean), "boolean value");
        return KRJSONNewBool(boolean);
    }
    if (type == JSVM_NUMBER) {
        double number = 0;
        Check(OH_JSVM_GetValueDouble(env, value, &number), "number value");
        if (std::isfinite(number) && number >= INT32_MIN && number <= INT32_MAX && std::trunc(number) == number) {
            return KRJSONNewInt32(static_cast<int32_t>(number));
        }
        return KRJSONNewDouble(number);
    }
    if (type == JSVM_STRING) {
        const std::string text = JsString(env, value);
        return KRJSONNewString(text.data(), text.size());
    }
    if (type == JSVM_OBJECT) {
        bool typed = false;
        Check(OH_JSVM_IsTypedarray(env, value, &typed), "typed array check");
        if (typed) {
            JSVM_TypedarrayType elementType;
            size_t length = 0, offset = 0;
            void *bytes = nullptr;
            JSVM_Value buffer = nullptr;
            Check(OH_JSVM_GetTypedarrayInfo(env, value, &elementType, &length, &bytes, &buffer, &offset),
                  "typed array value");
            if (elementType != JSVM_INT8_ARRAY && elementType != JSVM_UINT8_ARRAY &&
                elementType != JSVM_UINT8_CLAMPED_ARRAY) {
                throw std::runtime_error("Only byte typed arrays may cross the Native bridge");
            }
            return KRJSONNewBytes(static_cast<const uint8_t *>(bytes), length);
        }
        // Native 模块接受动态对象/数组。使用同一引擎的 JSON 实现消除 Kotlin 包装对象。
        JSVM_Value json = nullptr;
        Check(OH_JSVM_JsonStringify(env, value, &json), "JSON stringify");
        const std::string text = JsString(env, json);
        KRJSON result = KRJSONParse(text.data(), text.size(), nullptr);
        if (result == KRJSON_INVALID) throw std::runtime_error("Native bridge JSON parse failed");
        return result;
    }
    throw std::runtime_error("Unsupported Native bridge value type");
}

void LogException(JSVM_Env env, const char *operation, const std::exception &error) {
    std::string message = std::string(operation) + ": " + error.what();
    bool pending = false;
    if (env && OH_JSVM_IsExceptionPending(env, &pending) == JSVM_OK && pending) {
        JSVM_Value exception = nullptr, stack = nullptr;
        if (OH_JSVM_GetAndClearLastException(env, &exception) == JSVM_OK &&
            OH_JSVM_GetNamedProperty(env, exception, "stack", &stack) == JSVM_OK) {
            try { message += "\n" + JsString(env, stack); } catch (...) {}
        }
    }
    // hilog 单行限制较短，按行输出 Kotlin/JS 异常堆栈。
    size_t start = 0;
    do {
        size_t end = message.find('\n', start);
        const std::string line = message.substr(start, end == std::string::npos ? end : end - start);
        OH_LOG_Print(LOG_APP, LOG_ERROR, kDomain, "SondoHotReload", "%{public}s", line.c_str());
        if (end == std::string::npos) break;
        start = end + 1;
    } while (start < message.size());
}

JSVM_Value NativeCall(JSVM_Env env, JSVM_CallbackInfo info) {
    KRJSON arguments[6] = {};
    size_t converted = 0;
    try {
        size_t count = 7;
        JSVM_Value values[7];
        Check(OH_JSVM_GetCbInfo(env, info, &count, values, nullptr, nullptr), "Native callback arguments");
        int32_t method = 0;
        Check(OH_JSVM_GetValueInt32(env, values[0], &method), "Native method id");
        for (; converted < 6; converted++) arguments[converted] = FromJS(env, values[converted + 1]);
        KRJSON nativeResult = com_tencent_kuikly_CallNative(method, arguments[0], arguments[1], arguments[2],
                                                         arguments[3], arguments[4], arguments[5]);
        JSVM_Value result;
        try { result = ToJS(env, nativeResult); } catch (...) { KRJSONRelease(nativeResult); throw; }
        KRJSONRelease(nativeResult);
        for (KRJSON argument : arguments) KRJSONRelease(argument);
        return result;
    } catch (const std::exception &error) {
        for (size_t i = 0; i < converted; i++) KRJSONRelease(arguments[i]);
        LogException(env, "callNative", error);
        OH_JSVM_ThrowError(env, nullptr, error.what());
        return nullptr;
    }
}

JSVM_Value ConsoleLog(JSVM_Env env, JSVM_CallbackInfo info) {
    size_t count = 1;
    JSVM_Value value = nullptr, text = nullptr, result = nullptr;
    if (OH_JSVM_GetCbInfo(env, info, &count, &value, nullptr, nullptr) == JSVM_OK && count > 0 &&
        OH_JSVM_CoerceToString(env, value, &text) == JSVM_OK) {
        try { OH_LOG_Print(LOG_APP, LOG_INFO, kDomain, "SondoJS", "%{public}s", JsString(env, text).c_str()); }
        catch (...) {}
    }
    OH_JSVM_GetUndefined(env, &result);
    return result;
}

JSVM_CallbackStruct g_nativeCallback = {NativeCall, nullptr};
JSVM_CallbackStruct g_consoleCallback = {ConsoleLog, nullptr};

class Scope {
public:
    Scope(JSVM_VM vm, JSVM_Env env) : vm_(vm), env_(env) {
      try {
        bool locked = false;
        Check(OH_JSVM_IsLocked(env_, &locked), "VM lock state");
        ownsLock_ = !locked;
        if (ownsLock_) Check(OH_JSVM_AcquireLock(env_), "VM lock");
        Check(OH_JSVM_OpenVMScope(vm_, &vmScope_), "VM scope");
        Check(OH_JSVM_OpenEnvScope(env_, &envScope_), "environment scope");
        Check(OH_JSVM_OpenHandleScope(env_, &handleScope_), "handle scope");
      } catch (...) {
        Close();
        throw;
      }
    }
    ~Scope() { Close(); }
private:
    void Close() {
        if (handleScope_) OH_JSVM_CloseHandleScope(env_, handleScope_);
        if (envScope_) OH_JSVM_CloseEnvScope(env_, envScope_);
        if (vmScope_) OH_JSVM_CloseVMScope(vm_, vmScope_);
        if (ownsLock_) OH_JSVM_ReleaseLock(env_);
    }
    JSVM_VM vm_;
    JSVM_Env env_;
    JSVM_VMScope vmScope_ = nullptr;
    JSVM_EnvScope envScope_ = nullptr;
    JSVM_HandleScope handleScope_ = nullptr;
    bool ownsLock_ = false;
};

class Runtime {
public:
    explicit Runtime(std::string version, const std::string &source) : version_(std::move(version)) {
        static std::once_flag initialized;
        std::call_once(initialized, [] {
            // JSVM_GENERIC_FAILURE 表示进程里的其他组件已完成初始化，可继续创建VM。
            const JSVM_Status status = OH_JSVM_Init(nullptr);
            if (status != JSVM_OK && status != JSVM_GENERIC_FAILURE) Check(status, "JSVM initialization");
        });
        JSVM_CreateVMOptions options = {};
        Check(OH_JSVM_CreateVM(&options, &vm_), "create VM");
        JSVM_VMScope creationScope = nullptr;
        Check(OH_JSVM_OpenVMScope(vm_, &creationScope), "VM creation scope");
        JSVM_PropertyDescriptor properties[] = {
            {"__sondoCallNative", nullptr, &g_nativeCallback, nullptr, nullptr, nullptr, JSVM_DEFAULT},
            {"__sondoLog", nullptr, &g_consoleCallback, nullptr, nullptr, nullptr, JSVM_DEFAULT}
        };
        JSVM_Status status = OH_JSVM_CreateEnv(vm_, 2, properties, &env_);
        OH_JSVM_CloseVMScope(vm_, creationScope);
        if (status != JSVM_OK) {
            OH_JSVM_DestroyVM(vm_);
            vm_ = nullptr;
            Check(status, "create environment");
        }
        try {
            Scope scope(vm_, env_);
            Evaluate("globalThis.window=globalThis;globalThis.self=globalThis;"
                     "globalThis.console={log:__sondoLog,info:__sondoLog,warn:__sondoLog,error:__sondoLog,debug:__sondoLog};");
            Evaluate(source);
            Evaluate("if(typeof SondoHotReload!=='object'||typeof SondoHotReload.callKotlinMethod!=='function')"
                     "throw Error('Bundle must export SondoHotReload.callKotlinMethod');");
        } catch (const std::exception &error) {
            // 构造失败也释放VM；异常文本在scope有效期间输出。
            {
                Scope scope(vm_, env_);
                LogException(env_, "load bundle", error);
            }
            OH_JSVM_DestroyEnv(env_);
            OH_JSVM_DestroyVM(vm_);
            env_ = nullptr;
            vm_ = nullptr;
            throw;
        }
    }

    ~Runtime() = default;

    const std::string &Version() const { return version_; }

    void Call(int method, const KRJSON arguments[6]) {
        Scope scope(vm_, env_);
        try {
            JSVM_Value global = nullptr, bundle = nullptr, function = nullptr, result = nullptr;
            Check(OH_JSVM_GetGlobal(env_, &global), "global object");
            Check(OH_JSVM_GetNamedProperty(env_, global, "SondoHotReload", &bundle), "bundle exports");
            if (method == kCreateInstance) {
                // registerCallNative 需要在createInstance前调用，以处理构造期Native交互。
                JSVM_Value registration = bundle;
                for (const char *key : {"com", "tencent", "kuikly", "core", "nvi"}) {
                    Check(OH_JSVM_GetNamedProperty(env_, registration, key, &registration), "bridge exports");
                }
                Check(OH_JSVM_GetNamedProperty(env_, registration, "registerCallNative", &function),
                      "registerCallNative export");
                JSVM_Value callback = nullptr;
                Check(OH_JSVM_GetNamedProperty(env_, global, "__sondoCallNative", &callback), "Native dispatcher");
                JSVM_Value values[] = {ToJS(env_, arguments[0]), callback};
                Check(OH_JSVM_CallFunction(env_, registration, function, 2, values, &result), "register Native bridge");
            }
            Check(OH_JSVM_GetNamedProperty(env_, bundle, "callKotlinMethod", &function), "callKotlinMethod export");
            JSVM_Value values[7];
            Check(OH_JSVM_CreateInt32(env_, method, &values[0]), "Kotlin method id");
            for (size_t i = 0; i < 6; i++) values[i + 1] = ToJS(env_, arguments[i]);
            Check(OH_JSVM_CallFunction(env_, bundle, function, 7, values, &result), "call Kotlin/JS");
        } catch (const std::exception &error) {
            LogException(env_, "callKotlin", error);
        }
    }

private:
    void Evaluate(const std::string &source) {
        JSVM_Value text = MakeString(env_, source), result = nullptr;
        JSVM_Script script = nullptr;
        Check(OH_JSVM_CompileScript(env_, text, nullptr, 0, false, nullptr, &script), "compile JS");
        Check(OH_JSVM_RunScript(env_, script, &result), "run JS");
    }

    std::string version_;
    // 成员的析构在构造函数抛错时同样执行，覆盖CreateVM后任何一步失败的路径。
    struct EngineResources {
        JSVM_VM vm = nullptr;
        JSVM_Env env = nullptr;
        ~EngineResources() {
            if (env) OH_JSVM_DestroyEnv(env);
            if (vm) OH_JSVM_DestroyVM(vm);
        }
    } engine_;
    JSVM_VM &vm_ = engine_.vm;
    JSVM_Env &env_ = engine_.env;
};

std::shared_ptr<Runtime> g_current;
std::unordered_map<std::string, std::shared_ptr<Runtime>> g_pages;

void DispatchOnContext(int method, const KRJSON arguments[6]) {
    try {
        // KRJSON 字符串可能为UTF-16，统一借由JSON序列化提取pager ID，避免本机编码假设。
        const std::string key = JsonString(arguments[0]);
        if (method == kCreateInstance && g_current) g_pages[key] = g_current;
        auto found = g_pages.find(key);
        if (found == g_pages.end()) return;
        std::shared_ptr<Runtime> runtime = found->second;
        runtime->Call(method, arguments);
        if (method == kDestroyInstance) g_pages.erase(key);
    } catch (const std::exception &error) {
        LogException(nullptr, "dispatch", error);
    }
}

struct PendingCall {
    explicit PendingCall(const KRJSON values[6]) {
        for (size_t i = 0; i < 6; i++) arguments[i] = KRJSONRetain(values[i]);
    }
    ~PendingCall() { for (KRJSON argument : arguments) KRJSONRelease(argument); }
    KRJSON arguments[6];
};

void Dispatch(int method, KRJSON arg0, KRJSON arg1, KRJSON arg2, KRJSON arg3, KRJSON arg4, KRJSON arg5) {
    const KRJSON arguments[] = {arg0, arg1, arg2, arg3, arg4, arg5};
    if (com_tencent_kuikly_IsCurrentOnContextThread("")) {
        DispatchOnContext(method, arguments);
    } else {
        // Native模式的首次create可能来自主线程。异步交还同一个context线程，
        // 防止JSVM跨线程并发，也不阻塞主线程等待正在执行同步Native调用的worker。
        const std::shared_ptr<PendingCall> pending = std::make_shared<PendingCall>(arguments);
        Schedule([method, pending] { DispatchOnContext(method, pending->arguments); });
    }
}

std::string NapiString(napi_env env, napi_value value) {
    size_t size = 0;
    if (napi_get_value_string_utf8(env, value, nullptr, 0, &size) != napi_ok) {
        throw std::runtime_error("Expected a UTF-8 string");
    }
    std::vector<char> text(size + 1);
    if (napi_get_value_string_utf8(env, value, text.data(), text.size(), &size) != napi_ok) {
        throw std::runtime_error("Cannot read UTF-8 string");
    }
    return std::string(text.data(), size);
}

struct BundleJob {
    std::string version;
    std::string source;
    std::string error;
    napi_deferred deferred = nullptr;
    napi_threadsafe_function completion = nullptr;
};

void CompleteBundle(napi_env env, napi_value, void *context, void *) {
    BundleJob *job = static_cast<BundleJob *>(context);
    if (!env) return;
    napi_value result = nullptr;
    if (job->error.empty()) {
        napi_get_boolean(env, true, &result);
        napi_resolve_deferred(env, job->deferred, result);
    } else {
        napi_value message = nullptr;
        napi_create_string_utf8(env, job->error.c_str(), NAPI_AUTO_LENGTH, &message);
        napi_create_error(env, nullptr, message, &result);
        napi_reject_deferred(env, job->deferred, result);
    }
}

void FinalizeBundle(napi_env, void *data, void *) {
    // 最后一个排队的回调结束后由NAPI释放，兼容VM退出时completion未执行的路径。
    delete static_cast<BundleJob *>(data);
}

napi_value SetBundle(napi_env env, napi_callback_info info) {
    try {
        size_t count = 2;
        napi_value values[2], promise = nullptr;
        if (napi_get_cb_info(env, info, &count, values, nullptr, nullptr) != napi_ok || count != 2) {
            throw std::runtime_error("setHotReloadBundle(version, source) requires two strings");
        }
        const std::string version = NapiString(env, values[0]);
        const std::string source = NapiString(env, values[1]);
        if (version.empty() || source.empty()) throw std::runtime_error("Bundle version and source must be nonempty");
        std::unique_ptr<BundleJob> pending = std::make_unique<BundleJob>();
        pending->version = version;
        pending->source = source;
        if (napi_create_promise(env, &pending->deferred, &promise) != napi_ok) {
            throw std::runtime_error("Cannot create bundle promise");
        }
        napi_value resourceName = nullptr;
        napi_create_string_utf8(env, "SondoHotReloadBundle", NAPI_AUTO_LENGTH, &resourceName);
        if (napi_create_threadsafe_function(env, nullptr, nullptr, resourceName, 0, 1, pending.get(), FinalizeBundle,
                                          pending.get(), CompleteBundle, &pending->completion) != napi_ok) {
            throw std::runtime_error("Cannot create bundle completion callback");
        }
        BundleJob *job = pending.release();
        Schedule([job] {
            try {
                if (!g_current || g_current->Version() != job->version) {
                    // 先完整解析、执行新代码，成功后再发布；失败保留当前页面及旧版本。
                    g_current = std::make_shared<Runtime>(job->version, job->source);
                }
            } catch (const std::exception &error) { job->error = error.what(); }
            job->source.clear();
            const napi_threadsafe_function completion = job->completion;
            napi_call_threadsafe_function(completion, nullptr, napi_tsfn_nonblocking);
            napi_release_threadsafe_function(completion, napi_tsfn_release);
        });
        return promise;
    } catch (const std::exception &error) {
        napi_throw_error(env, nullptr, error.what());
        return nullptr;
    }
}
}  // namespace

int SondoInstallHotReloadBridge() {
    return com_tencent_kuikly_SetCallKotlin(Dispatch);
}

void SondoExportHotReload(napi_env env, napi_value exports) {
    napi_property_descriptor method = {
        "setHotReloadBundle", nullptr, SetBundle, nullptr, nullptr, nullptr, napi_default, nullptr
    };
    napi_define_properties(env, exports, 1, &method);
}
