/*
 * SDMediaPickerModule —— 相册 / 相机 / 文件选择与保存。契约见 shared/.../nativeapi/SDMediaModules.kt。
 * 假定 API 12（HarmonyOS NEXT 5.0）。
 *
 * 全部走系统 Picker / 系统授权弹窗，应用本身不需要申请任何媒体权限：
 *   pickImage(gallery) / pickImages / pickVideo   photoAccessHelper.PhotoViewPicker
 *   pickImage(camera)                             cameraPicker.pick（照片直接落到应用沙箱，不进相册）
 *   pickAudioFile / saveFileAs                    picker.DocumentViewPicker（select / save）
 *   saveToGallery                                 photoAccessHelper.showAssetsCreationDialog（系统弹窗确认后返回可写 URI）
 * 选中的文件都会拷贝 / 重编码到 <cacheDir>/sd_pick 下，返回沙箱绝对路径。
 * 用户取消统一返回 {ok:false, cancelled:true}。
 */
import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { common } from '@kit.AbilityKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { camera, cameraPicker } from '@kit.CameraKit';
import { image } from '@kit.ImageKit';
import { fileIo as fs, fileUri, picker } from '@kit.CoreFileKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { SD, SDLog, SDParams, SDReply, SDResult } from '../../support/SDModuleSupport';
import { MediaSupport } from './MediaSupport';
import { EncodedImage, ImageCodec } from './ImageCodec';
import { MediaInfo, MediaProbe } from './MediaProbe';

const TAG: string = 'SDMediaPicker';
const PICK_DIR: string = 'sd_pick';
const DEFAULT_AUDIO_EXTENSIONS: Array<string> = ['mp3', 'm4a', 'aac', 'wav', 'flac'];
/** 媒体库标题里不允许出现的字符。 */
const ILLEGAL_TITLE_CHARS: string = '.\\/:*?"\'`<>|{}[]';

export class SDMediaPickerModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME: string = 'SDMediaPickerModule';

  syncMode(): boolean {
    return false;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    const p: SDParams = SD.parse(params);
    const reply: SDReply = new SDReply(callback);
    switch (method) {
      case 'pickImage':
        this.run(reply, this.pickImage(p), SD.str(p, 'source') === 'camera' ? 'camera_access_denied' :
          'photo_access_denied');
        break;
      case 'pickImages':
        this.run(reply, this.pickImages(p), 'photo_access_denied');
        break;
      case 'pickAudioFile':
        this.run(reply, this.pickAudioFile(p), 'file_access_denied');
        break;
      case 'pickVideo':
        this.run(reply, this.pickVideo(), 'photo_access_denied');
        break;
      case 'saveFileAs':
        this.run(reply, this.saveFileAs(p), 'file_access_denied');
        break;
      case 'saveToGallery':
        this.run(reply, this.saveToGallery(p), 'photo_access_denied');
        break;
      case 'getImageSize':
        this.run(reply, this.getImageSize(p), 'file_access_denied');
        break;
      case 'compressImage':
        this.run(reply, this.compressImage(p), 'file_access_denied');
        break;
      default:
        reply.fail(`unknown method: ${method}`);
        break;
    }
    return null;
  }

  /** 统一收尾：成功回结果；权限类错误（201 / 13900012）映射成契约里的错误码，其余回错误文本。 */
  private run(reply: SDReply, work: Promise<SDResult>, deniedCode: string): void {
    work.then((result: SDResult) => {
      reply.send(result);
    }).catch((e: BusinessError) => {
      const code: number = SD.errCode(e);
      if (code === 201 || code === 13900012) {
        reply.fail(deniedCode, code);
      } else {
        SDLog.w(TAG, `picker failed: ${SD.errMsg(e)}`);
        reply.fail(SD.errMsg(e), code !== 0 ? code : undefined);
      }
    });
  }

  private static cancelled(): SDResult {
    const extra: SDResult = {};
    extra['cancelled'] = true;
    return SD.fail('cancelled', undefined, extra);
  }

  private static context(): common.UIAbilityContext {
    const ctx: common.UIAbilityContext | null = MediaSupport.context();
    if (ctx === null) {
      throw new Error('UIAbilityContext unavailable');
    }
    return ctx;
  }

  private static quality(p: SDParams): number {
    const q: number = SD.int(p, 'quality', 100);
    return q >= 1 && q <= 100 ? q : 100;
  }

  private static imageResult(img: EncodedImage): SDResult {
    const out: SDResult = {};
    out['path'] = img.path;
    out['width'] = img.width;
    out['height'] = img.height;
    out['size'] = img.size;
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // 图片 / 视频
  // ---------------------------------------------------------------------------------------------

  /** 系统相册选择器。返回选中项的媒体库 URI；用户取消返回空数组。 */
  private async selectFromGallery(mime: photoAccessHelper.PhotoViewMIMETypes, maxCount: number): Promise<Array<string>> {
    const options: photoAccessHelper.PhotoSelectOptions = new photoAccessHelper.PhotoSelectOptions();
    options.MIMEType = mime;
    options.maxSelectNumber = maxCount;
    const viewPicker: photoAccessHelper.PhotoViewPicker = new photoAccessHelper.PhotoViewPicker();
    const result: photoAccessHelper.PhotoSelectResult = await viewPicker.select(options);
    const uris: Array<string> | undefined = result.photoUris;
    return uris === undefined || uris === null ? [] : uris;
  }

  /** 系统相机拍照，照片写入应用沙箱。返回沙箱路径；用户取消返回空串。 */
  private async takePhoto(): Promise<string> {
    const ctx: common.UIAbilityContext = SDMediaPickerModule.context();
    const path: string = MediaSupport.tempFile(PICK_DIR, 'camera', 'jpg');
    // saveUri 指向的文件必须已经存在。
    MediaSupport.closeQuietly(fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC));
    const profile: cameraPicker.PickerProfile = {
      cameraPosition: camera.CameraPosition.CAMERA_POSITION_BACK,
      saveUri: fileUri.getUriFromPath(path)
    };
    let ok: boolean = false;
    try {
      const result: cameraPicker.PickerResult = await cameraPicker.pick(ctx, [cameraPicker.PickerMediaType.PHOTO],
        profile);
      ok = result.resultCode === 0 && MediaSupport.fileSize(path) > 0;
      if (!ok && result.resultCode === 0 && result.resultUri.length > 0 &&
        MediaSupport.isProviderUri(result.resultUri)) {
        // 个别系统版本忽略 saveUri，把照片存进了媒体库：改用返回的 URI。
        await MediaSupport.copyToSandbox(result.resultUri, path);
        ok = MediaSupport.fileSize(path) > 0;
      }
    } finally {
      if (!ok) {
        MediaSupport.remove(path);
      }
    }
    return ok ? path : '';
  }

  private async pickImage(p: SDParams): Promise<SDResult> {
    const quality: number = SDMediaPickerModule.quality(p);
    const maxSide: number = Math.max(0, SD.int(p, 'maxSide', 0));
    let source: string = '';
    let fromCamera: boolean = false;
    if (SD.str(p, 'source', 'gallery') === 'camera') {
      source = await this.takePhoto();
      fromCamera = true;
    } else {
      const uris: Array<string> = await this.selectFromGallery(photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE, 1);
      source = uris.length > 0 ? uris[0] : '';
    }
    if (source.length === 0) {
      return SDMediaPickerModule.cancelled();
    }
    try {
      const img: EncodedImage = await ImageCodec.toJpeg(source, MediaSupport.tempFile(PICK_DIR, 'img', 'jpg'), quality,
        maxSide);
      return SD.ok(SDMediaPickerModule.imageResult(img));
    } finally {
      if (fromCamera) {
        MediaSupport.remove(source); // 相机原图只是中间文件
      }
    }
  }

  private async pickImages(p: SDParams): Promise<SDResult> {
    const quality: number = SDMediaPickerModule.quality(p);
    const maxSide: number = Math.max(0, SD.int(p, 'maxSide', 0));
    let maxCount: number = SD.int(p, 'maxCount', 9);
    if (maxCount < 1) {
      maxCount = 1;
    }
    if (maxCount > 500) {
      maxCount = 500;
    }
    const uris: Array<string> = await this.selectFromGallery(photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE,
      maxCount);
    if (uris.length === 0) {
      return SDMediaPickerModule.cancelled();
    }
    const items: Array<SDResult> = [];
    let lastError: string = '';
    for (let i = 0; i < uris.length; i++) {
      try {
        const img: EncodedImage = await ImageCodec.toJpeg(uris[i], MediaSupport.tempFile(PICK_DIR, 'img', 'jpg'),
          quality, maxSide);
        items.push(SDMediaPickerModule.imageResult(img));
      } catch (e) {
        lastError = SD.errMsg(e as Object);
        SDLog.w(TAG, `skip unreadable image: ${lastError}`);
      }
    }
    if (items.length === 0) {
      return SD.fail(lastError.length > 0 ? lastError : 'no readable image');
    }
    const out: SDResult = {};
    out['items'] = items;
    return SD.ok(out);
  }

  private async pickVideo(): Promise<SDResult> {
    const uris: Array<string> = await this.selectFromGallery(photoAccessHelper.PhotoViewMIMETypes.VIDEO_TYPE, 1);
    if (uris.length === 0) {
      return SDMediaPickerModule.cancelled();
    }
    let ext: string = MediaSupport.extensionOf(MediaSupport.fileNameOf(uris[0]));
    if (ext.length === 0) {
      ext = 'mp4';
    }
    const dest: string = MediaSupport.tempFile(PICK_DIR, 'video', ext);
    const size: number = await MediaSupport.copyToSandbox(uris[0], dest);
    if (size <= 0) {
      MediaSupport.remove(dest);
      return SD.fail('copy failed');
    }
    const out: SDResult = {};
    out['path'] = dest;
    out['size'] = size;
    out['durationMs'] = 0;
    out['width'] = 0;
    out['height'] = 0;
    try {
      const info: MediaInfo = await MediaProbe.probe(dest);
      out['durationMs'] = info.durationMs;
      out['width'] = info.width;
      out['height'] = info.height;
    } catch (e) {
      SDLog.w(TAG, `probe picked video failed: ${SD.errMsg(e as Object)}`);
    }
    return SD.ok(out);
  }

  private async getImageSize(p: SDParams): Promise<SDResult> {
    const path: string = SD.str(p, 'path');
    if (path.length === 0) {
      return SD.fail('path is required');
    }
    const size: image.Size = await ImageCodec.sizeOf(path);
    const out: SDResult = {};
    out['width'] = size.width;
    out['height'] = size.height;
    return SD.ok(out);
  }

  private async compressImage(p: SDParams): Promise<SDResult> {
    const path: string = SD.str(p, 'path');
    if (path.length === 0) {
      return SD.fail('path is required');
    }
    const img: EncodedImage = await ImageCodec.toJpeg(path, MediaSupport.tempFile(PICK_DIR, 'img', 'jpg'),
      SDMediaPickerModule.quality(p), Math.max(0, SD.int(p, 'maxSide', 0)));
    return SD.ok(SDMediaPickerModule.imageResult(img));
  }

  // ---------------------------------------------------------------------------------------------
  // 文件
  // ---------------------------------------------------------------------------------------------

  private async pickAudioFile(p: SDParams): Promise<SDResult> {
    let extensions: Array<string> = SD.strList(p, 'extensions');
    if (extensions.length === 0) {
      extensions = DEFAULT_AUDIO_EXTENSIONS;
    }
    const suffixes: Array<string> = [];
    for (let i = 0; i < extensions.length; i++) {
      let ext: string = extensions[i].trim().toLowerCase();
      while (ext.startsWith('.')) {
        ext = ext.substring(1);
      }
      if (ext.length > 0) {
        suffixes.push(`.${ext}`);
      }
    }
    const options: picker.DocumentSelectOptions = new picker.DocumentSelectOptions();
    options.maxSelectNumber = 1;
    if (suffixes.length > 0) {
      // 「描述|后缀1,后缀2」：一个筛选项里列出全部允许的后缀。
      options.fileSuffixFilters = [`音频文件|${suffixes.join(',')}`];
    }
    const documentPicker: picker.DocumentViewPicker = new picker.DocumentViewPicker(SDMediaPickerModule.context());
    const uris: Array<string> = await documentPicker.select(options);
    if (uris === undefined || uris === null || uris.length === 0) {
      return SDMediaPickerModule.cancelled();
    }
    const name: string = MediaSupport.fileNameOf(uris[0]);
    const extension: string = MediaSupport.extensionOf(name);
    const dest: string = MediaSupport.tempFile(PICK_DIR, 'audio', extension);
    const size: number = await MediaSupport.copyToSandbox(uris[0], dest);
    if (size < 0) {
      MediaSupport.remove(dest);
      return SD.fail('copy failed');
    }
    const out: SDResult = {};
    out['path'] = dest;
    out['name'] = name;
    out['size'] = size;
    out['extension'] = extension;
    return SD.ok(out);
  }

  private async saveFileAs(p: SDParams): Promise<SDResult> {
    const srcPath: string = MediaSupport.localPath(SD.str(p, 'srcPath'));
    const fileName: string = SD.str(p, 'fileName');
    if (srcPath.length === 0 || fileName.length === 0) {
      return SD.fail('srcPath and fileName are required');
    }
    if (!MediaSupport.exists(srcPath)) {
      return SD.fail('source file not found');
    }
    const options: picker.DocumentSaveOptions = new picker.DocumentSaveOptions();
    options.newFileNames = [fileName];
    const documentPicker: picker.DocumentViewPicker = new picker.DocumentViewPicker(SDMediaPickerModule.context());
    const uris: Array<string> = await documentPicker.save(options);
    if (uris === undefined || uris === null || uris.length === 0) {
      return SDMediaPickerModule.cancelled();
    }
    await MediaSupport.copyToUri(srcPath, uris[0]);
    const out: SDResult = {};
    out['savedPath'] = uris[0];
    return SD.ok(out);
  }

  private static sanitizeTitle(name: string): string {
    let title: string = '';
    for (let i = 0; i < name.length; i++) {
      const ch: string = name.charAt(i);
      if (ILLEGAL_TITLE_CHARS.indexOf(ch) < 0) {
        title += ch;
      }
    }
    title = title.trim();
    return title.length > 200 ? title.substring(0, 200) : title;
  }

  private async saveToGallery(p: SDParams): Promise<SDResult> {
    const path: string = MediaSupport.localPath(SD.str(p, 'path'));
    const isVideo: boolean = SD.str(p, 'type', 'image') === 'video';
    const fileName: string = SD.str(p, 'fileName');
    if (path.length === 0) {
      return SD.fail('path is required');
    }
    if (MediaSupport.isHttp(path) || !MediaSupport.exists(path)) {
      return SD.fail('source file not found (download it first)');
    }
    let extension: string = MediaSupport.extensionOf(fileName);
    if (extension.length === 0) {
      extension = MediaSupport.extensionOf(path);
    }
    if (extension.length === 0) {
      extension = isVideo ? 'mp4' : 'jpg';
    }
    const config: photoAccessHelper.PhotoCreationConfig = {
      fileNameExtension: extension,
      photoType: isVideo ? photoAccessHelper.PhotoType.VIDEO : photoAccessHelper.PhotoType.IMAGE
    };
    if (fileName.length > 0) {
      const dot: number = fileName.lastIndexOf('.');
      const title: string = SDMediaPickerModule.sanitizeTitle(dot > 0 ? fileName.substring(0, dot) : fileName);
      if (title.length > 0) {
        config.title = title;
      }
    }
    const helper: photoAccessHelper.PhotoAccessHelper =
      photoAccessHelper.getPhotoAccessHelper(SDMediaPickerModule.context());
    // 系统弹窗让用户确认保存；同意后返回一个已创建好、可写的媒体库 URI。
    const uris: Array<string> = await helper.showAssetsCreationDialog([fileUri.getUriFromPath(path)], [config]);
    if (uris === undefined || uris === null || uris.length === 0 || !uris[0].startsWith('file://')) {
      const extra: SDResult = {};
      extra['cancelled'] = true;
      return SD.fail('photo_access_denied', undefined, extra);
    }
    await MediaSupport.copyToUri(path, uris[0]);
    const out: SDResult = {};
    out['uri'] = uris[0];
    return SD.ok(out);
  }
}
