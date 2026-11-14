import CoreGraphics
import Foundation
import ImageIO
import UIKit
import UniformTypeIdentifiers

/// 图片尺寸读取与 JPEG 重编码（ImageIO）。全部可在后台线程调用。
enum SDImageCodec {

    struct Output {
        let path: String
        let width: Int
        let height: Int
        let size: Int64

        /// {path, width, height, size}
        var dict: [String: Any] {
            return ["path": path, "width": width, "height": height, "size": size]
        }
    }

    // MARK: - 尺寸

    /// 只读文件头得到像素尺寸（不解码整张图）；EXIF 方向为 5–8（旋转 90°）时宽高已对调。
    /// 文件不存在、不是图片或数据不完整时返回 nil。
    static func pixelSize(of url: URL) -> (width: Int, height: Int)? {
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithURL(url as CFURL, options) else {
            return nil
        }
        return pixelSize(of: source)
    }

    private static func pixelSize(of source: CGImageSource) -> (width: Int, height: Int)? {
        guard CGImageSourceGetCount(source) > 0,
              CGImageSourceGetStatus(source) == .statusComplete,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue,
              let height = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue,
              width > 0, height > 0 else {
            return nil
        }
        let orientation = (properties[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1
        if orientation >= 5 && orientation <= 8 {
            return (width: height, height: width)
        }
        return (width: width, height: height)
    }

    // MARK: - 重编码

    /// 把任意系统支持的图片（HEIC / PNG / JPEG …）重编码成 JPEG：
    /// 方向烘焙进像素、可限制最长边（maxSide ≤ 0 表示不缩放）、quality 1–100。
    /// `destination` 为 nil 时写到 App 临时目录。失败返回 nil。
    static func reencodeJPEG(from url: URL, quality: Int, maxSide: Int, to destination: URL? = nil) -> Output? {
        let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithURL(url as CFURL, sourceOptions),
              let size = pixelSize(of: source) else {
            return nil
        }
        let longest = max(size.width, size.height)
        let target = maxSide > 0 ? min(maxSide, longest) : longest
        // 用「缩略图」接口解码：一步完成方向校正 + 等比缩放，且不会先解出一张原尺寸位图再缩。
        let thumbnailOptions: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: target,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions as CFDictionary) else {
            return nil
        }
        let output = destination ?? SDMedia.tempFileURL(prefix: "img", ext: "jpg")
        return writeJPEG(flattened(image), quality: quality, to: output)
    }

    /// 相机拍出来的 UIImage → JPEG（方向烘焙进像素，可限制最长边）。
    static func encodeJPEG(_ image: UIImage, quality: Int, maxSide: Int) -> Output? {
        let pixelWidth = image.size.width * image.scale
        let pixelHeight = image.size.height * image.scale
        guard pixelWidth >= 1, pixelHeight >= 1 else {
            return nil
        }
        var ratio: CGFloat = 1
        let longest = max(pixelWidth, pixelHeight)
        if maxSide > 0 && longest > CGFloat(maxSide) {
            ratio = CGFloat(maxSide) / longest
        }
        let target = CGSize(width: max(1, (pixelWidth * ratio).rounded()),
                            height: max(1, (pixelHeight * ratio).rounded()))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        format.preferredRange = .standard
        let renderer = UIGraphicsImageRenderer(size: target, format: format)
        let rendered = renderer.image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: target))
            // UIImage.draw 会按 imageOrientation 摆正。
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        guard let cgImage = rendered.cgImage else {
            return nil
        }
        return writeJPEG(cgImage, quality: quality, to: SDMedia.tempFileURL(prefix: "img", ext: "jpg"))
    }

    /// CGImage → JPEG 文件。
    static func writeJPEG(_ image: CGImage, quality: Int, to url: URL) -> Output? {
        SDMedia.prepareDestination(url)
        guard let destination = CGImageDestinationCreateWithURL(
            url as CFURL, UTType.jpeg.identifier as CFString, 1, nil
        ) else {
            return nil
        }
        let clamped = Double(min(100, max(1, quality))) / 100
        let properties = [kCGImageDestinationLossyCompressionQuality: clamped] as CFDictionary
        CGImageDestinationAddImage(destination, image, properties)
        guard CGImageDestinationFinalize(destination) else {
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        return Output(path: url.path, width: image.width, height: image.height, size: SDMedia.fileSize(url))
    }

    /// JPEG 没有透明通道：带 alpha 的图（PNG 等）先铺到白底上，避免透明区域变黑。
    private static func flattened(_ image: CGImage) -> CGImage {
        switch image.alphaInfo {
        case .none, .noneSkipFirst, .noneSkipLast:
            return image
        default:
            break
        }
        let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: nil,
            width: image.width,
            height: image.height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else {
            return image
        }
        let rect = CGRect(x: 0, y: 0, width: image.width, height: image.height)
        context.setFillColor(red: 1, green: 1, blue: 1, alpha: 1)
        context.fill(rect)
        context.draw(image, in: rect)
        return context.makeImage() ?? image
    }
}
