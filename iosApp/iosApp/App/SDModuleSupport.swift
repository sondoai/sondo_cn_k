import Foundation
import UIKit

// =====================================================================================
// 所有 SD*Module 共用的小工具。
//
// 模块写法（类名必须等于 Kuikly 侧的 moduleName，方法名等于 method 字符串）：
//
//     @objc(SDFooModule)
//     @objcMembers
//     final class SDFooModule: KRBaseModule {
//
//         // 异步方法：Kuikly 在【主线程】调用
//         func doSomething(_ args: NSDictionary) {
//             let p = SD.params(args)                 // JSON 入参 → [String: Any]
//             let done = SD.once(args)                // 只会回调一次的包装
//             DispatchQueue.global().async {
//                 done.resolve(SD.success(["value": p.sdString("key")]))
//             }
//         }
//
//         // 同步方法：Kuikly 在【Kuikly 线程】调用，必须返回对象（NSString / JSON 字符串）
//         func isReady(_ args: NSDictionary) -> NSString {
//             return SD.bool(true)                    // "1" / "0"
//         }
//
//         // 常驻回调（listen / addEventListener）
//         func addEventListener(_ args: NSDictionary) {
//             guard let cb = SD.callback(args) else { return }
//             SDFooModule.events.add(owner: self, callback: cb)
//         }
//         static let events = EventBroadcaster()
//         deinit { SDFooModule.events.remove(ownerId: ObjectIdentifier(self)) }
//     }
//
// 线程约定（见 KuiklyRenderCore.m / expand-native-api.md）：
// - 异步调用（invoke / listen / await）在主线程进入模块方法；
// - 同步调用（invokeSync）在 Kuikly 线程进入模块方法，禁止耗时、禁止碰 UIKit；
// - 模块实例每个页面一份，可能在 Kuikly 线程上被创建（`init` 里不要碰 UIKit）；
// - 回调 block 可在任意线程调用，结果必须能被 NSJSONSerialization 序列化。
// =====================================================================================

/// Kuikly 回调：`(Any?) -> Void`，传 `[String: Any]`。
typealias SDCallback = KuiklyRenderCallback

enum SD {

    // MARK: - 入参 / 回调

    /// `args[KR_PARAM_KEY]`（JSON 字符串）→ 字典；无参 / 解析失败返回空字典。
    static func params(_ args: NSDictionary?) -> [String: Any] {
        guard let text = SDKuiklyArgs.paramString(args) else { return [:] }
        return jsonObject(text) ?? [:]
    }

    /// `args[KR_CALLBACK_KEY]`；Kuikly 侧没传回调时为 nil。
    static func callback(_ args: NSDictionary?) -> SDCallback? {
        return SDKuiklyArgs.callback(args)
    }

    /// 只回调一次的包装（异步方法用它保证「恰好回调一次」）。
    static func once(_ args: NSDictionary?) -> SDOnce {
        return SDOnce(SDKuiklyArgs.callback(args))
    }

    // MARK: - 回参

    /// `{ok: true, ...extra}`
    static func success(_ extra: [String: Any] = [:]) -> [String: Any] {
        var result = extra
        result["ok"] = true
        return result
    }

    /// `{ok: false, error, code?, ...extra}`
    static func failure(_ error: String, code: Any? = nil, extra: [String: Any] = [:]) -> [String: Any] {
        var result = extra
        result["ok"] = false
        result["error"] = error
        if let code = code {
            result["code"] = code
        }
        return result
    }

    /// 同步布尔返回值："1" / "0"。
    static func bool(_ value: Bool) -> NSString {
        return value ? "1" : "0"
    }

    // MARK: - JSON

    /// JSON 文本 → 字典。
    static func jsonObject(_ text: String) -> [String: Any]? {
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data, options: []) else {
            return nil
        }
        return object as? [String: Any]
    }

    /// 字典 / 数组 → JSON 文本；失败返回 fallback。
    static func jsonString(_ object: Any, fallback: String = "") -> String {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: []),
              let text = String(data: data, encoding: .utf8) else {
            return fallback
        }
        return text
    }

    /// 同步方法返回 JSON 字符串用。
    static func jsonResult(_ object: Any) -> NSString {
        let fallback = (object is [Any]) ? "[]" : "{}"
        return jsonString(object, fallback: fallback) as NSString
    }

    // MARK: - 线程

    /// 在主线程执行（已在主线程则立即执行）。
    static func onMain(_ block: @escaping () -> Void) {
        if Thread.isMainThread {
            block()
        } else {
            DispatchQueue.main.async(execute: block)
        }
    }

    /// 后台串行 / 并发执行 IO。
    static func onIO(_ block: @escaping () -> Void) {
        DispatchQueue.global(qos: .userInitiated).async(execute: block)
    }

    // MARK: - UI（仅主线程）

    /// 当前前台 key window。
    static func keyWindow() -> UIWindow? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let ordered = scenes.filter { $0.activationState == .foregroundActive } + scenes.filter { $0.activationState != .foregroundActive }
        for scene in ordered {
            if let window = scene.windows.first(where: { $0.isKeyWindow }) {
                return window
            }
        }
        for scene in ordered {
            if let window = scene.windows.first(where: { !$0.isHidden }) {
                return window
            }
        }
        if let delegateWindow = UIApplication.shared.delegate?.window {
            return delegateWindow
        }
        return nil
    }

    /// 根导航控制器（Kuikly 页面栈）。
    static func rootNavigationController() -> UINavigationController? {
        return keyWindow()?.rootViewController as? UINavigationController
    }

    /// 最上层可用于 present 的控制器（沿 presented / navigation / tab 向上找）。
    static func topViewController(_ base: UIViewController? = nil) -> UIViewController? {
        guard let start = base ?? keyWindow()?.rootViewController else { return nil }
        var current: UIViewController = start
        while true {
            if let presented = current.presentedViewController, !presented.isBeingDismissed {
                current = presented
            } else if let nav = current as? UINavigationController, let visible = nav.topViewController {
                current = visible
            } else if let tab = current as? UITabBarController, let selected = tab.selectedViewController {
                current = selected
            } else {
                return current
            }
        }
    }
}

/// 保证回调恰好一次（线程安全）。多次 resolve 只有第一次生效。
final class SDOnce {
    private let lock = NSLock()
    private var callback: SDCallback?

    init(_ callback: SDCallback?) {
        self.callback = callback
    }

    /// 是否已经回调过（或本来就没有回调）。
    var isResolved: Bool {
        lock.lock()
        defer { lock.unlock() }
        return callback == nil
    }

    func resolve(_ result: [String: Any]) {
        lock.lock()
        let cb = callback
        callback = nil
        lock.unlock()
        cb?(result)
    }

    func success(_ extra: [String: Any] = [:]) {
        resolve(SD.success(extra))
    }

    func failure(_ error: String, code: Any? = nil, extra: [String: Any] = [:]) {
        resolve(SD.failure(error, code: code, extra: extra))
    }
}

/// 常驻回调列表（`addEventListener` / `listen` 的实现基础），线程安全。
///
/// 每个页面的模块实例各注册一个回调，`emit` 时向所有仍存活的页面广播；
/// owner（模块实例）被释放后对应回调自动剔除。
final class EventBroadcaster {

    private struct Entry {
        let ownerId: ObjectIdentifier
        weak var owner: AnyObject?
        let callback: SDCallback
        /// Kuikly 侧给的监听 id（removeEventListener 用；没有为 nil）。
        let listenerId: String?
    }

    private let lock = NSLock()
    private var entries: [Entry] = []

    init() {}

    /// 注册一个常驻回调。同一个 owner 可以注册多个。
    func add(owner: AnyObject, listenerId: String? = nil, callback: @escaping SDCallback) {
        lock.lock()
        entries.removeAll { $0.owner == nil }
        entries.append(Entry(ownerId: ObjectIdentifier(owner), owner: owner, callback: callback, listenerId: listenerId))
        lock.unlock()
    }

    /// 移除某个 owner 下 id 为 listenerId 的回调（Kuikly 侧主动注销单个监听）。
    func remove(ownerId: ObjectIdentifier, listenerId: String) {
        lock.lock()
        entries.removeAll { ($0.ownerId == ownerId && $0.listenerId == listenerId) || $0.owner == nil }
        lock.unlock()
    }

    /// 移除某个 owner 的全部回调（在模块 `deinit` 里用 `ObjectIdentifier(self)` 调用）。
    func remove(ownerId: ObjectIdentifier) {
        lock.lock()
        entries.removeAll { $0.ownerId == ownerId || $0.owner == nil }
        lock.unlock()
    }

    /// 当前存活的监听数。
    var count: Int {
        lock.lock()
        defer { lock.unlock() }
        return entries.filter { $0.owner != nil }.count
    }

    var isEmpty: Bool {
        return count == 0
    }

    /// 向所有存活的监听者广播（任意线程）。
    func emit(_ dict: [String: Any]) {
        lock.lock()
        entries.removeAll { $0.owner == nil }
        let callbacks = entries.map { $0.callback }
        lock.unlock()
        for callback in callbacks {
            callback(dict)
        }
    }

    /// 只发给满足条件的 owner（主线程调用时可以在条件里读 UIKit）。
    func emit(_ dict: [String: Any], toOwnersWhere match: (AnyObject) -> Bool) {
        lock.lock()
        entries.removeAll { $0.owner == nil }
        let snapshot = entries
        lock.unlock()
        for entry in snapshot {
            if let owner = entry.owner, match(owner) {
                entry.callback(dict)
            }
        }
    }

    /// 只发给某个 owner 下 id 在 listenerIds 里的回调。→ 实际投递的回调数。
    @discardableResult
    func emit(_ dict: [String: Any], toOwnerId ownerId: ObjectIdentifier, listenerIds: Set<String>) -> Int {
        lock.lock()
        let callbacks = entries.filter {
            $0.ownerId == ownerId && $0.owner != nil && ($0.listenerId.map { listenerIds.contains($0) } ?? false)
        }.map { $0.callback }
        lock.unlock()
        for callback in callbacks {
            callback(dict)
        }
        return callbacks.count
    }

    /// 只发给某一个 owner。
    func emit(_ dict: [String: Any], toOwnerId ownerId: ObjectIdentifier) {
        lock.lock()
        let callbacks = entries.filter { $0.ownerId == ownerId && $0.owner != nil }.map { $0.callback }
        lock.unlock()
        for callback in callbacks {
            callback(dict)
        }
    }
}

// MARK: - 字典取值（JSON 入参容错）

extension Dictionary where Key == String, Value == Any {

    func sdString(_ key: String, _ fallback: String = "") -> String {
        return sdOptString(key) ?? fallback
    }

    /// 没有该字段 / 为 null 时返回 nil；数字会转成字符串。
    func sdOptString(_ key: String) -> String? {
        guard let value = self[key], !(value is NSNull) else { return nil }
        if let text = value as? String {
            return text
        }
        if let number = value as? NSNumber {
            return number.stringValue
        }
        return nil
    }

    func sdInt(_ key: String, _ fallback: Int = 0) -> Int {
        return sdOptInt64(key).map { Int($0) } ?? fallback
    }

    func sdInt64(_ key: String, _ fallback: Int64 = 0) -> Int64 {
        return sdOptInt64(key) ?? fallback
    }

    func sdOptInt64(_ key: String) -> Int64? {
        guard let value = self[key], !(value is NSNull) else { return nil }
        if let number = value as? NSNumber {
            return number.int64Value
        }
        if let text = value as? String {
            if let parsed = Int64(text) {
                return parsed
            }
            if let parsed = Double(text), parsed.isFinite {
                return Int64(parsed)
            }
        }
        return nil
    }

    func sdDouble(_ key: String, _ fallback: Double = 0) -> Double {
        guard let value = self[key], !(value is NSNull) else { return fallback }
        if let number = value as? NSNumber {
            return number.doubleValue
        }
        if let text = value as? String, let parsed = Double(text) {
            return parsed
        }
        return fallback
    }

    /// 兼容 true/false、1/0、"1"/"true"。
    func sdBool(_ key: String, _ fallback: Bool = false) -> Bool {
        guard let value = self[key], !(value is NSNull) else { return fallback }
        if let number = value as? NSNumber {
            return number.boolValue
        }
        if let text = value as? String {
            let lower = text.lowercased()
            if lower == "1" || lower == "true" || lower == "yes" {
                return true
            }
            if lower == "0" || lower == "false" || lower == "no" || lower.isEmpty {
                return false
            }
        }
        return fallback
    }

    func sdDict(_ key: String) -> [String: Any] {
        if let dict = self[key] as? [String: Any] {
            return dict
        }
        // 兼容把对象序列化成字符串再传的情况
        if let text = self[key] as? String, let dict = SD.jsonObject(text) {
            return dict
        }
        return [:]
    }

    func sdArray(_ key: String) -> [Any] {
        return (self[key] as? [Any]) ?? []
    }

    func sdStringArray(_ key: String) -> [String] {
        return sdArray(key).compactMap { $0 as? String }
    }

    /// 值全部转成字符串的字典（HTTP 头、表单字段）。
    func sdStringDict(_ key: String) -> [String: String] {
        var result: [String: String] = [:]
        for (name, value) in sdDict(key) {
            if let text = value as? String {
                result[name] = text
            } else if let number = value as? NSNumber {
                result[name] = number.stringValue
            }
        }
        return result
    }
}
