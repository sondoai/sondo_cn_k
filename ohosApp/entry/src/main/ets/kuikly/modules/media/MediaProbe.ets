/*
 * 媒体信息探测（@kit.MediaKit 的 AVMetadataExtractor，API 11+）。
 * 只支持本地文件（沙箱路径 / file URI）——AVMetadataExtractor 没有 url 入口。
 */
import { media } from '@kit.MediaKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { SD, SDLog } from '../../support/SDModuleSupport';
import { MediaSupport } from './MediaSupport';

const TAG: string = 'MediaProbe';

export class MediaInfo {
  durationMs: number = 0;
  hasVideo: boolean = false;
  hasAudio: boolean = false;
  /** 已按旋转角度校正后的显示宽高；没有视频时为 0。 */
  width: number = 0;
  height: number = 0;
  /** 容器 MIME，如 "video/mp4"、"audio/mpeg"；取不到为空串。 */
  format: string = '';
  size: number = 0;
}

export class MediaProbe {
  private static flag(value: string | undefined): boolean {
    if (value === undefined || value === null) {
      return false;
    }
    const v: string = value.toLowerCase();
    return v.length > 0 && v !== 'no' && v !== 'false' && v !== '0';
  }

  private static int(value: string | undefined): number {
    if (value === undefined || value === null || value.length === 0) {
      return 0;
    }
    const n: number = Number(value);
    return Number.isFinite(n) ? Math.round(n) : 0;
  }

  /** 失败时 reject（Error / BusinessError）。 */
  static async probe(pathOrUri: string): Promise<MediaInfo> {
    const file: fs.File = fs.openSync(MediaSupport.localPath(pathOrUri), fs.OpenMode.READ_ONLY);
    let extractor: media.AVMetadataExtractor | null = null;
    try {
      const size: number = fs.statSync(file.fd).size;
      if (size <= 0) {
        throw new Error('empty file');
      }
      extractor = await media.createAVMetadataExtractor();
      const source: media.AVFileDescriptor = { fd: file.fd, offset: 0, length: size };
      extractor.fdSrc = source;
      const meta: media.AVMetadata = await extractor.fetchMetadata();
      const info: MediaInfo = new MediaInfo();
      info.size = size;
      info.durationMs = MediaProbe.int(meta.duration);
      info.hasAudio = MediaProbe.flag(meta.hasAudio);
      info.hasVideo = MediaProbe.flag(meta.hasVideo);
      const mime: string | undefined = meta.mimeType;
      info.format = mime === undefined || mime === null ? '' : mime;
      if (info.hasVideo) {
        const w: number = MediaProbe.int(meta.videoWidth);
        const h: number = MediaProbe.int(meta.videoHeight);
        const rotation: number = ((MediaProbe.int(meta.videoOrientation) % 360) + 360) % 360;
        const swap: boolean = rotation === 90 || rotation === 270;
        info.width = swap ? h : w;
        info.height = swap ? w : h;
      }
      return info;
    } finally {
      if (extractor !== null) {
        try {
          await extractor.release();
        } catch (e) {
          SDLog.w(TAG, `release failed: ${SD.errMsg(e as Object)}`);
        }
      }
      MediaSupport.closeQuietly(file);
    }
  }
}
