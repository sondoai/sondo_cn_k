import Foundation

/// SDK 模块（支付 / 登录 / 推送）共用的事件广播器。
///
/// 在 `EventBroadcaster`（每个页面的模块实例各注册一个常驻回调）之上多做一件事：
/// **还没有任何页面监听时先把事件存起来**，等第一个监听者注册后补发给它。
/// 典型场景：`Transaction.updates` 在冷启动时就吐出未完成交易、通知在根页面注册监听之前被点开。
///
/// 线程安全，任意线程可调用。
final class SDSDKEventHub {

    private let broadcaster = EventBroadcaster()
    private let lock = NSLock()
    private var pending: [[String: Any]] = []
    private let maxPending: Int

    /// - Parameter maxPending: 无人监听时最多暂存的事件数，超出后丢弃最旧的。
    init(maxPending: Int = 32) {
        self.maxPending = max(0, maxPending)
    }

    /// 注册常驻回调（`addEventListener`）。暂存的事件只补发给这一个监听者。
    func add(owner: AnyObject, callback: @escaping SDCallback) {
        lock.lock()
        broadcaster.add(owner: owner, callback: callback)
        let backlog = pending
        pending.removeAll()
        lock.unlock()

        guard !backlog.isEmpty else { return }
        let ownerId = ObjectIdentifier(owner)
        for event in backlog {
            broadcaster.emit(event, toOwnerId: ownerId)
        }
    }

    /// 模块 `deinit` 时调用。
    func remove(ownerId: ObjectIdentifier) {
        broadcaster.remove(ownerId: ownerId)
    }

    /// 向所有存活页面广播；没有监听者时暂存。
    func emit(_ event: [String: Any]) {
        lock.lock()
        if broadcaster.isEmpty {
            if maxPending > 0 {
                pending.append(event)
                if pending.count > maxPending {
                    pending.removeFirst(pending.count - maxPending)
                }
            }
            lock.unlock()
            return
        }
        lock.unlock()
        broadcaster.emit(event)
    }
}
