/*
 * 图片解码 / 重编码（@kit.ImageKit）：读尺寸、按 EXIF 方向摆正、限制最长边、输出 JPEG。
 * 对应 Flutter 侧 image_picker + FlutterImageCompress（format: jpeg，autoCorrectionAngle）的行为。
 */
import { image } from '@kit.ImageKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { SD, SDLog } from '../../support/SDModuleSupport';
import { MediaSupport } from './MediaSupport';

const TAG: string = 'ImageCodec';

export class EncodedImage {
  path: string = '';
  width: number = 0;
  height: number = 0;
  size: number = 0;
}

export class ImageCodec {
  /**
   * EXIF Orientation → 需要顺时针旋转的角度（0 / 90 / 180 / 270）。
   * 系统返回的是描述文本（"Right-top" 等），个别版本返回数字，两种都认；镜像方向按不旋转处理。
   */
  private static async rotation(source: image.ImageSource): Promise<number> {
    let text: string = '';
    try {
      text = await source.getImageProperty(image.PropertyKey.ORIENTATION);
    } catch (e) {
      return 0; // 没有 EXIF（PNG / WebP…）
    }
    const value: string = text.toLowerCase().replace(' ', '');
    if (value === 'right-top' || value === '6') {
      return 90;
    }
    if (value === 'bottom-right' || value === '3') {
      return 180;
    }
    if (value === 'left-bottom' || value === '8') {
      return 270;
    }
    return 0;
  }

  private static releaseSource(source: image.ImageSource | null): void {
    if (source !== null) {
      source.release().catch((e: BusinessError) => {
        SDLog.w(TAG, `source release: ${SD.errMsg(e)}`);
      });
    }
  }

  /** 读取显示尺寸（已按 EXIF 方向交换宽高）；图片损坏时 reject。 */
  static async sizeOf(pathOrUri: string): Promise<image.Size> {
    const file: fs.File = fs.openSync(MediaSupport.localPath(pathOrUri), fs.OpenMode.READ_ONLY);
    let source: image.ImageSource | null = null;
    try {
      source = image.createImageSource(file.fd);
      const info: image.ImageInfo = await source.getImageInfo();
      const width: number = info.size.width;
      const height: number = info.size.height;
      if (!(width > 0 && height > 0)) {
        throw new Error('corrupt image');
      }
      const angle: number = await ImageCodec.rotation(source);
      const swap: boolean = angle === 90 || angle === 270;
      const size: image.Size = { width: swap ? height : width, height: swap ? width : height };
      return size;
    } finally {
      ImageCodec.releaseSource(source);
      MediaSupport.closeQuietly(file);
    }
  }

  /**
   * 解码 → 摆正方向 → 可选缩小（最长边不超过 maxSide，0 表示不限制）→ JPEG。
   * @param quality 1..100
   */
  static async toJpeg(srcPathOrUri: string, dest: string, quality: number, maxSide: number): Promise<EncodedImage> {
    const file: fs.File = fs.openSync(MediaSupport.localPath(srcPathOrUri), fs.OpenMode.READ_ONLY);
    let source: image.ImageSource | null = null;
    let pixelMap: image.PixelMap | null = null;
    let packer: image.ImagePacker | null = null;
    let out: fs.File | null = null;
    let done: boolean = false;
    try {
      source = image.createImageSource(file.fd);
      const info: image.ImageInfo = await source.getImageInfo();
      const width: number = info.size.width;
      const height: number = info.size.height;
      if (!(width > 0 && height > 0)) {
        throw new Error('corrupt image');
      }
      const angle: number = await ImageCodec.rotation(source);

      const decode: image.DecodingOptions = { editable: true };
      const longest: number = Math.max(width, height);
      if (maxSide > 0 && longest > maxSide) {
        const scale: number = maxSide / longest;
        const target: image.Size = {
          width: Math.max(1, Math.round(width * scale)),
          height: Math.max(1, Math.round(height * scale))
        };
        decode.desiredSize = target;
      }
      pixelMap = await source.createPixelMap(decode);
      if (angle !== 0) {
        await pixelMap.rotate(angle);
      }
      const outInfo: image.ImageInfo = await pixelMap.getImageInfo();

      let q: number = Math.round(quality);
      if (!(q >= 1)) {
        q = 100;
      }
      if (q > 100) {
        q = 100;
      }
      MediaSupport.ensureParent(dest);
      out = fs.openSync(dest, fs.OpenMode.WRITE_ONLY | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      packer = image.createImagePacker();
      const options: image.PackingOption = { format: 'image/jpeg', quality: q };
      await packer.packToFile(pixelMap, out.fd, options);
      MediaSupport.closeQuietly(out);
      out = null;

      const result: EncodedImage = new EncodedImage();
      result.path = dest;
      result.width = outInfo.size.width;
      result.height = outInfo.size.height;
      result.size = MediaSupport.fileSize(dest);
      if (result.size <= 0) {
        throw new Error('jpeg encode produced an empty file');
      }
      done = true;
      return result;
    } finally {
      if (packer !== null) {
        packer.release().catch((e: BusinessError) => {
          SDLog.w(TAG, `packer release: ${SD.errMsg(e)}`);
        });
      }
      if (pixelMap !== null) {
        pixelMap.release().catch((e: BusinessError) => {
          SDLog.w(TAG, `pixelMap release: ${SD.errMsg(e)}`);
        });
      }
      ImageCodec.releaseSource(source);
      MediaSupport.closeQuietly(out);
      MediaSupport.closeQuietly(file);
      if (!done) {
        MediaSupport.remove(dest);
      }
    }
  }
}
