import UIKit
import AVFoundation
import Photos
import UserNotifications
import AppTrackingTransparency

/// 运行时权限。契约见 shared/.../nativeapi/SDAppModule.kt（SDPermissionModule）。
///
/// permission: "notification" | "camera" | "photos" | "photosAddOnly" | "storage" | "microphone" | "tracking"
/// status:     "granted" | "limited" | "denied" | "permanentlyDenied" | "restricted" | "notDetermined"
///
/// iOS 的系统弹窗每个权限只出现一次，所以「用户拒绝过」统一返回 permanentlyDenied（只能去系统设置里改），
/// 与参考工程 permission_handler 的语义一致；从未询问过返回 notDetermined。
@objc(SDPermissionModule)
@objcMembers
final class SDPermissionModule: KRBaseModule {

    private static let granted = "granted"
    private static let limited = "limited"
    private static let denied = "denied"
    private static let permanentlyDenied = "permanentlyDenied"
    private static let restricted = "restricted"
    private static let notDetermined = "notDetermined"

    // MARK: - 模块方法（异步，主线程进入）

    /// params: {permission} → {ok, status}
    func status(_ args: NSDictionary) {
        let permission = SD.params(args).sdString("permission")
        let done = SD.once(args)
        SDPermissionModule.queryStatus(permission) { status in
            if let status = status {
                done.resolve(SD.success(["status": status]))
            } else {
                done.resolve(SD.failure("unknown permission: \(permission)", extra: ["status": SDPermissionModule.denied]))
            }
        }
    }

    /// params: {permission} → {ok, status}
    func request(_ args: NSDictionary) {
        let permission = SD.params(args).sdString("permission")
        let done = SD.once(args)
        SDPermissionModule.requestPermission(permission) { status in
            if let status = status {
                done.resolve(SD.success(["status": status]))
            } else {
                done.resolve(SD.failure("unknown permission: \(permission)", extra: ["status": SDPermissionModule.denied]))
            }
        }
    }

    // MARK: - 查询

    /// completion 在任意线程回调；未知权限回调 nil。
    private static func queryStatus(_ permission: String, completion: @escaping (String?) -> Void) {
        switch permission {
        case "notification":
            UNUserNotificationCenter.current().getNotificationSettings { settings in
                completion(SDPermissionModule.mapNotification(settings.authorizationStatus))
            }
        case "camera":
            completion(SDPermissionModule.mapCapture(AVCaptureDevice.authorizationStatus(for: .video)))
        case "microphone":
            completion(SDPermissionModule.mapCapture(AVCaptureDevice.authorizationStatus(for: .audio)))
        case "photos":
            completion(SDPermissionModule.mapPhotos(PHPhotoLibrary.authorizationStatus(for: .readWrite)))
        case "photosAddOnly":
            completion(SDPermissionModule.mapPhotos(PHPhotoLibrary.authorizationStatus(for: .addOnly)))
        case "tracking":
            completion(SDPermissionModule.mapTracking(ATTrackingManager.trackingAuthorizationStatus))
        case "storage":
            // 仅 Android ≤ 32 有意义
            completion(SDPermissionModule.granted)
        default:
            completion(nil)
        }
    }

    // MARK: - 申请

    private static func requestPermission(_ permission: String, completion: @escaping (String?) -> Void) {
        switch permission {
        case "notification":
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { _, _ in
                // 以系统设置为准（用户可能只开了部分提醒方式）
                UNUserNotificationCenter.current().getNotificationSettings { settings in
                    completion(SDPermissionModule.mapNotification(settings.authorizationStatus))
                }
            }
        case "camera":
            SDPermissionModule.requestCapture(.video, completion: completion)
        case "microphone":
            SDPermissionModule.requestCapture(.audio, completion: completion)
        case "photos":
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in
                completion(SDPermissionModule.mapPhotos(status))
            }
        case "photosAddOnly":
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
                completion(SDPermissionModule.mapPhotos(status))
            }
        case "tracking":
            SDPermissionModule.requestTracking(attempt: 1, completion: completion)
        case "storage":
            completion(SDPermissionModule.granted)
        default:
            completion(nil)
        }
    }

    private static func requestCapture(_ mediaType: AVMediaType, completion: @escaping (String?) -> Void) {
        let current = AVCaptureDevice.authorizationStatus(for: mediaType)
        guard current == .notDetermined else {
            completion(SDPermissionModule.mapCapture(current))
            return
        }
        AVCaptureDevice.requestAccess(for: mediaType) { _ in
            completion(SDPermissionModule.mapCapture(AVCaptureDevice.authorizationStatus(for: mediaType)))
        }
    }

    /// ATT 弹窗只有在 App 处于 active 状态时才会出现：非 active 时等到下一次 didBecomeActive 再申请；
    /// 申请后仍是 notDetermined（系统没有真正弹窗）则再等一次前台，最多尝试 2 次。
    private static func requestTracking(attempt: Int, completion: @escaping (String?) -> Void) {
        SD.onMain {
            let current = ATTrackingManager.trackingAuthorizationStatus
            guard current == .notDetermined else {
                completion(SDPermissionModule.mapTracking(current))
                return
            }
            let perform = {
                // 稍等片刻再弹，避开启动转场 / 其它系统弹窗刚消失的瞬间（此时系统会直接忽略申请）。
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    ATTrackingManager.requestTrackingAuthorization { status in
                        if status == .notDetermined && attempt < 2 {
                            SD.onMain {
                                if UIApplication.shared.applicationState == .active {
                                    // App 在前台但系统没有弹窗：如实返回，由上层决定何时再试。
                                    completion(SDPermissionModule.mapTracking(status))
                                } else {
                                    SDPermissionModule.waitUntilNextActive {
                                        SDPermissionModule.requestTracking(attempt: attempt + 1, completion: completion)
                                    }
                                }
                            }
                        } else {
                            completion(SDPermissionModule.mapTracking(status))
                        }
                    }
                }
            }
            if UIApplication.shared.applicationState == .active {
                perform()
            } else {
                SDPermissionModule.waitUntilNextActive(perform)
            }
        }
    }

    /// 下一次 App 进入 active 时执行一次（主线程）。
    private static func waitUntilNextActive(_ block: @escaping () -> Void) {
        var token: NSObjectProtocol?
        token = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { _ in
            if let current = token {
                NotificationCenter.default.removeObserver(current)
                token = nil
            }
            block()
        }
    }

    // MARK: - 状态映射

    private static func mapNotification(_ status: UNAuthorizationStatus) -> String {
        switch status {
        case .authorized, .provisional, .ephemeral:
            return SDPermissionModule.granted
        case .denied:
            return SDPermissionModule.permanentlyDenied
        case .notDetermined:
            return SDPermissionModule.notDetermined
        @unknown default:
            return SDPermissionModule.denied
        }
    }

    private static func mapCapture(_ status: AVAuthorizationStatus) -> String {
        switch status {
        case .authorized:
            return SDPermissionModule.granted
        case .denied:
            return SDPermissionModule.permanentlyDenied
        case .restricted:
            return SDPermissionModule.restricted
        case .notDetermined:
            return SDPermissionModule.notDetermined
        @unknown default:
            return SDPermissionModule.denied
        }
    }

    private static func mapPhotos(_ status: PHAuthorizationStatus) -> String {
        switch status {
        case .authorized:
            return SDPermissionModule.granted
        case .limited:
            return SDPermissionModule.limited
        case .denied:
            return SDPermissionModule.permanentlyDenied
        case .restricted:
            return SDPermissionModule.restricted
        case .notDetermined:
            return SDPermissionModule.notDetermined
        @unknown default:
            return SDPermissionModule.denied
        }
    }

    private static func mapTracking(_ status: ATTrackingManager.AuthorizationStatus) -> String {
        switch status {
        case .authorized:
            return SDPermissionModule.granted
        case .denied:
            return SDPermissionModule.permanentlyDenied
        case .restricted:
            return SDPermissionModule.restricted
        case .notDetermined:
            return SDPermissionModule.notDetermined
        @unknown default:
            return SDPermissionModule.denied
        }
    }
}
