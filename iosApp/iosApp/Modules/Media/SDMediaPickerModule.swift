import AVFoundation
import Photos
import PhotosUI
import UIKit
import UniformTypeIdentifiers

/// 相册 / 相机 / 文件选择与保存（契约见 shared 的 `SDMediaModules.kt`）。
///
/// - 相册用 PHPickerViewController：独立进程，不需要相册读取权限，所以不会出现 `photo_access_denied`
///   （该错误码只在保存到相册被拒时返回）；
/// - 相机用 UIImagePickerController，先查 / 申请相机权限，被拒返回 `camera_access_denied`；
/// - 所有选中的图片统一重编码为 JPEG（方向烘焙进像素），输出在 App 临时目录；
/// - 用户取消一律返回 `{ok: false, cancelled: true}`。
@objc(SDMediaPickerModule)
@objcMembers
final class SDMediaPickerModule: KRBaseModule {

    private static let cancelledResult: [String: Any] = ["ok": false, "cancelled": true]

    // MARK: - 选图

    /// params: {source: "camera" | "gallery", quality: 1-100, maxSide}
    /// → {ok, path, width, height, size} | {ok:false, cancelled:true} | {ok:false, error}
    func pickImage(_ args: NSDictionary) {
        let params = SD.params(args)
        let done = SD.once(args)
        let source = params.sdString("source", "gallery")
        let quality = params.sdInt("quality", 100)
        let maxSide = params.sdInt("maxSide", 0)
        SD.onMain {
            if source == "camera" {
                SDMediaPickerModule.pickFromCamera(quality: quality, maxSide: maxSide, done: done)
                return
            }
            SDMediaPickerModule.presentPhotoPicker(filter: .images, limit: 1, mode: .current, done: done) { results in
                guard let provider = results.first?.itemProvider else {
                    done.failure("no image")
                    return
                }
                SDMediaPickerModule.loadJPEG(from: provider, quality: quality, maxSide: maxSide) { output in
                    if let output = output {
                        done.success(output.dict)
                    } else {
                        done.failure("image load failed")
                    }
                }
            }
        }
    }

    /// params: {maxCount, quality} → {ok, items: [{path, width, height, size}]}
    func pickImages(_ args: NSDictionary) {
        let params = SD.params(args)
        let done = SD.once(args)
        // PHPicker 的 selectionLimit：0 表示不限。
        let limit = max(0, params.sdInt("maxCount", 0))
        let quality = params.sdInt("quality", 100)
        SD.onMain {
            SDMediaPickerModule.presentPhotoPicker(filter: .images, limit: limit, mode: .current, done: done) { results in
                let group = DispatchGroup()
                let lock = NSLock()
                var outputs = [SDImageCodec.Output?](repeating: nil, count: results.count)
                for (index, result) in results.enumerated() {
                    group.enter()
                    SDMediaPickerModule.loadJPEG(from: result.itemProvider, quality: quality, maxSide: 0) { output in
                        lock.lock()
                        outputs[index] = output
                        lock.unlock()
                        group.leave()
                    }
                }
                group.notify(queue: .global(qos: .userInitiated)) {
                    // 保持用户选择的顺序；个别图片读取失败时跳过。
                    let items = outputs.compactMap { $0?.dict }
                    if items.isEmpty {
                        done.failure("image load failed")
                    } else {
                        done.success(["items": items])
                    }
                }
            }
        }
    }

    // MARK: - 选视频

    /// → {ok, path, durationMs, width, height, size} | {ok:false, cancelled:true}
    func pickVideo(_ args: NSDictionary) {
        let done = SD.once(args)
        SD.onMain {
            // .compatible：必要时由系统转成 H.264，保证后续上传 / 处理的兼容性。
            SDMediaPickerModule.presentPhotoPicker(filter: .videos, limit: 1, mode: .compatible, done: done) { results in
                guard let provider = results.first?.itemProvider else {
                    done.failure("no video")
                    return
                }
                let type = UTType.movie.identifier
                guard provider.hasItemConformingToTypeIdentifier(type) else {
                    done.failure("not a video")
                    return
                }
                provider.loadFileRepresentation(forTypeIdentifier: type) { url, error in
                    // 这个回调返回后系统会删除 url 指向的文件，必须在这里同步拷走。
                    guard let url = url else {
                        done.failure(error?.localizedDescription ?? "video load failed")
                        return
                    }
                    let ext = url.pathExtension.isEmpty ? "mov" : url.pathExtension.lowercased()
                    let copy = SDMedia.tempFileURL(prefix: "video_pick", ext: ext)
                    do {
                        try FileManager.default.copyItem(at: url, to: copy)
                    } catch {
                        done.failure(error.localizedDescription)
                        return
                    }
                    let info = SDMediaProbe.info(of: copy)
                    done.success([
                        "path": copy.path,
                        "durationMs": info?.durationMs ?? 0,
                        "width": info?.width ?? 0,
                        "height": info?.height ?? 0,
                        "size": SDMedia.fileSize(copy),
                    ])
                }
            }
        }
    }

    // MARK: - 选音频文件

    /// params: {extensions: ["mp3", "m4a", …]} → {ok, path, name, size, extension} | {ok:false, cancelled:true}
    func pickAudioFile(_ args: NSDictionary) {
        let params = SD.params(args)
        let done = SD.once(args)
        var extensions = params.sdStringArray("extensions").map { $0.lowercased() }
        if extensions.isEmpty {
            extensions = ["mp3", "m4a", "aac", "wav", "flac"]
        }
        var types: [UTType] = []
        for ext in extensions {
            if let type = UTType(filenameExtension: ext), !types.contains(type) {
                types.append(type)
            }
        }
        if types.isEmpty {
            types = [.audio]
        }
        SD.onMain {
            // asCopy: 系统先把文件（含 iCloud 上未下载的）拷到 App 的临时收件箱，再把副本地址交给我们。
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: true)
            picker.allowsMultipleSelection = false
            let session = SDPickerSession()
            session.onDocuments = { urls in
                guard let url = urls.first else {
                    done.resolve(SDMediaPickerModule.cancelledResult)
                    return
                }
                SD.onIO {
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer {
                        if scoped {
                            url.stopAccessingSecurityScopedResource()
                        }
                    }
                    let ext = url.pathExtension.lowercased()
                    let copy = SDMedia.tempFileURL(prefix: "audio_pick", ext: ext)
                    do {
                        try FileManager.default.copyItem(at: url, to: copy)
                    } catch {
                        done.failure(error.localizedDescription)
                        return
                    }
                    done.success([
                        "path": copy.path,
                        "name": url.lastPathComponent,
                        "size": SDMedia.fileSize(copy),
                        "extension": ext,
                    ])
                }
            }
            picker.delegate = session
            SDMediaPickerModule.present(picker, session: session, done: done)
        }
    }

    // MARK: - 存储为

    /// params: {srcPath, fileName, mime} → {ok, savedPath?} | {ok:false, cancelled:true}
    func saveFileAs(_ args: NSDictionary) {
        let params = SD.params(args)
        let done = SD.once(args)
        let fileName = params.sdString("fileName")
        let mime = params.sdString("mime")
        guard let source = SDMedia.fileURL(params.sdString("srcPath")),
              FileManager.default.fileExists(atPath: source.path) else {
            done.failure("file not found")
            return
        }
        SD.onIO {
            // 导出面板用文件本身的名字作为默认文件名：先拷一份按目标名字命名的副本。
            var name = fileName.replacingOccurrences(of: "/", with: "_")
            if name.isEmpty {
                name = source.lastPathComponent
            }
            if (name as NSString).pathExtension.isEmpty {
                let ext = UTType(mimeType: mime)?.preferredFilenameExtension ?? source.pathExtension
                if !ext.isEmpty {
                    name += "." + ext
                }
            }
            let folder = SDMedia.tempFileURL(prefix: "export", ext: "")
            let staged = folder.appendingPathComponent(name)
            do {
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true, attributes: nil)
                try FileManager.default.copyItem(at: source, to: staged)
            } catch {
                done.failure(error.localizedDescription)
                return
            }
            SD.onMain {
                let picker = UIDocumentPickerViewController(forExporting: [staged], asCopy: true)
                let session = SDPickerSession()
                session.onDocuments = { urls in
                    try? FileManager.default.removeItem(at: folder)
                    if let saved = urls.first {
                        done.success(["savedPath": saved.path])
                    } else {
                        done.resolve(SDMediaPickerModule.cancelledResult)
                    }
                }
                picker.delegate = session
                SDMediaPickerModule.present(picker, session: session, done: done)
            }
        }
    }

    // MARK: - 保存到相册

    /// params: {path, type: "image" | "video", fileName?} → {ok, error?}
    func saveToGallery(_ args: NSDictionary) {
        let params = SD.params(args)
        let done = SD.once(args)
        let isVideo = params.sdString("type", "image") == "video"
        let fileName = params.sdOptString("fileName") ?? ""
        guard let file = SDMedia.fileURL(params.sdString("path")),
              FileManager.default.fileExists(atPath: file.path) else {
            done.failure("file not found")
            return
        }
        // 只申请「仅添加」权限（NSPhotoLibraryAddUsageDescription）。
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                done.failure("photo_access_denied")
                return
            }
            PHPhotoLibrary.shared().performChanges({
                let request = PHAssetCreationRequest.forAsset()
                let options = PHAssetResourceCreationOptions()
                if !fileName.isEmpty {
                    options.originalFilename = fileName
                }
                request.addResource(with: isVideo ? .video : .photo, fileURL: file, options: options)
            }, completionHandler: { success, error in
                if success {
                    done.success()
                } else {
                    done.failure(error?.localizedDescription ?? "save failed")
                }
            })
        }
    }

    // MARK: - 图片信息 / 压缩

    /// params: {path} → {ok, width, height}；图片损坏时 ok=false
    func getImageSize(_ args: NSDictionary) {
        let path = SD.params(args).sdString("path")
        let done = SD.once(args)
        SD.onIO {
            guard let file = SDMedia.fileURL(path), let size = SDImageCodec.pixelSize(of: file) else {
                done.failure("invalid image")
                return
            }
            done.success(["width": size.width, "height": size.height])
        }
    }

    /// params: {path, quality, maxSide} → {ok, path, width, height, size}
    func compressImage(_ args: NSDictionary) {
        let params = SD.params(args)
        let done = SD.once(args)
        let path = params.sdString("path")
        let quality = params.sdInt("quality", 100)
        let maxSide = params.sdInt("maxSide", 0)
        SD.onIO {
            guard let file = SDMedia.fileURL(path),
                  let output = SDImageCodec.reencodeJPEG(from: file, quality: quality, maxSide: maxSide) else {
                done.failure("compress failed")
                return
            }
            done.success(output.dict)
        }
    }

    // MARK: - 内部：相机

    private static func pickFromCamera(quality: Int, maxSide: Int, done: SDOnce) {
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            done.failure("camera_unavailable")
            return
        }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            SDMediaPickerModule.presentCamera(quality: quality, maxSide: maxSide, done: done)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                SD.onMain {
                    if granted {
                        SDMediaPickerModule.presentCamera(quality: quality, maxSide: maxSide, done: done)
                    } else {
                        done.failure("camera_access_denied")
                    }
                }
            }
        default:
            done.failure("camera_access_denied")
        }
    }

    private static func presentCamera(quality: Int, maxSide: Int, done: SDOnce) {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.mediaTypes = [UTType.image.identifier]
        if UIImagePickerController.isCameraDeviceAvailable(.rear) {
            picker.cameraDevice = .rear
        }
        picker.allowsEditing = false
        picker.modalPresentationStyle = .fullScreen
        let session = SDPickerSession()
        session.onCamera = { image, cancelled in
            if cancelled {
                done.resolve(SDMediaPickerModule.cancelledResult)
                return
            }
            guard let image = image else {
                done.failure("capture failed")
                return
            }
            SD.onIO {
                if let output = SDImageCodec.encodeJPEG(image, quality: quality, maxSide: maxSide) {
                    done.success(output.dict)
                } else {
                    done.failure("image encode failed")
                }
            }
        }
        picker.delegate = session
        SDMediaPickerModule.present(picker, session: session, done: done)
    }

    // MARK: - 内部：相册

    private static func presentPhotoPicker(filter: PHPickerFilter,
                                           limit: Int,
                                           mode: PHPickerConfiguration.AssetRepresentationMode,
                                           done: SDOnce,
                                           onPicked: @escaping ([PHPickerResult]) -> Void) {
        var configuration = PHPickerConfiguration()
        configuration.filter = filter
        configuration.selectionLimit = limit
        configuration.preferredAssetRepresentationMode = mode
        let picker = PHPickerViewController(configuration: configuration)
        let session = SDPickerSession()
        session.onPhotos = { results in
            if results.isEmpty {
                done.resolve(SDMediaPickerModule.cancelledResult)
            } else {
                onPicked(results)
            }
        }
        picker.delegate = session
        SDMediaPickerModule.present(picker, session: session, done: done)
    }

    /// 从 PHPicker 的结果里取出图片文件并重编码为 JPEG；`completion` 在后台线程回调。
    private static func loadJPEG(from provider: NSItemProvider,
                                 quality: Int,
                                 maxSide: Int,
                                 completion: @escaping (SDImageCodec.Output?) -> Void) {
        let type = UTType.image.identifier
        guard provider.hasItemConformingToTypeIdentifier(type) else {
            completion(nil)
            return
        }
        provider.loadFileRepresentation(forTypeIdentifier: type) { url, _ in
            // 回调返回后源文件即被删除：在回调内同步完成解码 + 重编码。
            guard let url = url else {
                completion(nil)
                return
            }
            completion(SDImageCodec.reencodeJPEG(from: url, quality: quality, maxSide: maxSide))
        }
    }

    // MARK: - 内部：present

    private static func present(_ controller: UIViewController, session: SDPickerSession, done: SDOnce) {
        guard let presenter = SD.topViewController() else {
            done.failure("no view controller")
            return
        }
        session.retainUntilFinished()
        // 下滑关闭 sheet 时部分系统版本不回调 picker 的 delegate，这里兜底成「取消」。
        controller.presentationController?.delegate = session
        presenter.present(controller, animated: true, completion: nil)
    }
}

/// 一次系统选择器会话：充当各选择器的 delegate，在结束前自持有，保证结果恰好处理一次。仅主线程使用。
private final class SDPickerSession: NSObject,
    PHPickerViewControllerDelegate,
    UIImagePickerControllerDelegate,
    UINavigationControllerDelegate,
    UIDocumentPickerDelegate,
    UIAdaptivePresentationControllerDelegate {

    private static var active: [ObjectIdentifier: SDPickerSession] = [:]

    /// 相册结果；空数组表示取消。
    var onPhotos: (([PHPickerResult]) -> Void)?
    /// 相机结果：(image, cancelled)。
    var onCamera: ((UIImage?, Bool) -> Void)?
    /// 文件选择 / 导出结果；空数组表示取消。
    var onDocuments: (([URL]) -> Void)?

    private var finished = false

    func retainUntilFinished() {
        SDPickerSession.active[ObjectIdentifier(self)] = self
    }

    private func complete(_ body: () -> Void) {
        if finished {
            return
        }
        finished = true
        body()
        SDPickerSession.active[ObjectIdentifier(self)] = nil
    }

    private func finishCancelled() {
        complete {
            onPhotos?([])
            onCamera?(nil, true)
            onDocuments?([])
        }
    }

    // PHPickerViewControllerDelegate

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true, completion: nil)
        complete {
            onPhotos?(results)
        }
    }

    // UIImagePickerControllerDelegate

    func imagePickerController(_ picker: UIImagePickerController,
                               didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
        let image = info[.originalImage] as? UIImage
        picker.dismiss(animated: true, completion: nil)
        complete {
            onCamera?(image, false)
        }
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true, completion: nil)
        finishCancelled()
    }

    // UIDocumentPickerDelegate

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        complete {
            onDocuments?(urls)
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        finishCancelled()
    }

    // UIAdaptivePresentationControllerDelegate

    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        finishCancelled()
    }
}
