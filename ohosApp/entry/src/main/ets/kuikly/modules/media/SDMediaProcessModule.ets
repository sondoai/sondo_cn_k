/*
 * SDMediaProcessModule —— 媒体处理（Flutter 侧 ffmpeg-kit / just_waveform 的鸿蒙实现，不带 ffmpeg）。
 * 契约见 shared/.../nativeapi/SDMediaModules.kt。假定 API 12（HarmonyOS NEXT 5.0）。
 *
 * 各操作的实现方式与真实程度
 * ---------------------------------------------------------------------------------------------
 * probe              真实。media.AVMetadataExtractor（仅本地文件）；失败时用纯 ArkTS 解析兜底
 *                    （MP4 采样表 / WAV 头），MP4 另外补 fps。
 * videoFrame         真实。media.AVImageGenerator.fetchFrameByTime + image.ImagePacker → JPEG。
 * remuxMp3WithXing   真实。纯 ArkTS 逐帧重写（codec/Mp3Format.ets），TaskPool 执行；
 *                    等价于 `ffmpeg -map 0:a:0 -c:a copy -write_xing 1`，解析失败返回 ok=false。
 * trimAudio          真实（无损、帧级精度）。MP3 / WAV / ADTS-AAC / M4A(MP4) 按帧或采样裁剪，输出容器与输入相同；
 *                    FLAC / OGG 等需要解码器的格式返回 error:"unsupported…"。
 * muxVideoWithAudio  真实，分三级：
 *                    1) 音频本身是 AAC-in-MP4（m4a/mp4）：纯 ArkTS 重封装（codec/Mp4Format + Mp4Writer），
 *                       视频轨与音频轨都是流拷贝，按 audioStartMs / audioDurationMs 截取并做 -shortest；
 *                    2) 其它音频（MP3 / WAV / FLAC…）：先用 media.AVTranscoder 转成 AAC（MP4 容器，192kbps），再走 1)；
 *                    3) AVTranscoder 失败（例如该机型不支持纯音频源）且音频是 MP3：把 MP3 帧直接封进 MP4
 *                       （mp4a + esds objectType 0x6B，done 事件带 audioCodec:"mp3"）。鸿蒙 / Android 播放器可播，
 *                       苹果系播放器会忽略这条音轨——这是降级结果，不是契约里的「音频转 AAC」。
 *                    视频只支持非分片 MP4。
 * extractWaveform    WAV：真实 PCM 峰值。
 *                    MP3：近似值（done 事件带 approximate:true）——ArkTS 没有任何能拿到 PCM 的解码接口，
 *                         这里用 Layer III side info 里的 global_gain 估算相对响度包络（见 Mp3Format.gainEnvelope）。
 *                    其它压缩格式（AAC / FLAC…）：{event:"done", ok:false, error:"unsupported"}，由 shared 侧画平直波形。
 * cancel             真实。TaskPool 任务在下一个检查点中止并删除半成品；AVTranscoder 调 cancel()。
 *
 * 要把上面的「近似 / 降级 / unsupported」补成完整实现，需要一个 C++ NAPI 模块（libsondo_media.so）：
 *   - 解封装：OH_AVSource_CreateWithFD → OH_AVDemuxer_CreateWithSource → OH_AVDemuxer_SelectTrackByID →
 *             OH_AVDemuxer_SeekToTime(SEEK_MODE_CLOSEST_SYNC) → 循环 OH_AVDemuxer_ReadSampleBuffer；
 *   - 解码：OH_AudioCodec_CreateByMime(mime, false) + OH_AudioCodec_RegisterCallback / Configure / Prepare / Start，
 *           把样本 OH_AudioCodec_PushInputBuffer，在 onNewOutputBuffer 里拿 S16LE PCM；
 *           波形 = 每 sampleRate / samplesPerSecond 个采样取 max(|pcm|) / 32768；
 *   - 编码：OH_AudioCodec_CreateByMime(OH_AVCODEC_MIMETYPE_AUDIO_AAC, true)，
 *           配置 OH_MD_KEY_AUD_SAMPLE_RATE / OH_MD_KEY_AUD_CHANNEL_COUNT / OH_MD_KEY_BITRATE / OH_MD_KEY_AAC_IS_ADTS=0；
 *   - 封装：OH_AVMuxer_Create(fd, AV_OUTPUT_FORMAT_MPEG_4) → OH_AVMuxer_AddTrack（视频轨直接用解封装得到的
 *           OH_AVFormat，含 codec_config；音频轨用编码器输出格式）→ OH_AVMuxer_Start →
 *           OH_AVMuxer_WriteSampleBuffer（按 pts 交错，写到 min(视频时长, 音频时长) 为止）→ OH_AVMuxer_Stop；
 *   - FLAC 等格式的裁剪 = 解封装 + 解码 + 重新编码，同样走上面这条链路。
 *   需要链接 libnative_media_avsource.so / libnative_media_avdemuxer.so / libnative_media_acodec.so /
 *   libnative_media_avmuxer.so / libnative_media_core.so，并在工作线程里跑。
 */
import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { media } from '@kit.MediaKit';
import { image } from '@kit.ImageKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { taskpool } from '@kit.ArkTS';
import { BusinessError } from '@kit.BasicServicesKit';
import { SD, SDLog, SDParams, SDReply, SDResult } from '../../support/SDModuleSupport';
import { MediaSupport } from './MediaSupport';
import { MediaInfo, MediaProbe } from './MediaProbe';
import { sdInspectTask, sdMuxTask, sdRemuxMp3Task, sdTrimAudioTask, sdWaveformTask } from './MediaTasks';

const TAG: string = 'SDMediaProcess';
const TEMP_DIR: string = 'sd_process';
const AAC_BITRATE: number = 192000;

class ProcessJob {
  taskId: string;
  owner: Object;
  private callback: KuiklyRenderCallback | null;
  task: taskpool.Task | null = null;
  /** 中止当前的 AVTranscoder 阶段（没有在转码时为 null）。 */
  abortTranscode: (() => void) | null = null;
  cancelled: boolean = false;
  finished: boolean = false;
  private lastProgress: number = -1;

  constructor(taskId: string, owner: Object, callback: KuiklyRenderCallback | null) {
    this.taskId = taskId;
    this.owner = owner;
    this.callback = callback;
  }

  progress(value: number): void {
    if (this.finished || this.cancelled) {
      return;
    }
    let v: number = value;
    if (!(v >= 0)) {
      v = 0;
    }
    if (v > 1) {
      v = 1;
    }
    if (v < this.lastProgress + 0.01 && v < 1) {
      return;
    }
    this.lastProgress = v;
    const extra: SDResult = {};
    extra['taskId'] = this.taskId;
    extra['progress'] = Math.round(v * 1000) / 1000;
    SD.send(this.callback, SD.event('progress', extra));
  }

  /** 结束事件，只发一次。 */
  done(result: SDResult): void {
    if (this.finished) {
      return;
    }
    this.finished = true;
    const event: SDResult = SD.event('done', result);
    event['taskId'] = this.taskId;
    const cb: KuiklyRenderCallback | null = this.callback;
    this.callback = null;
    SD.send(cb, event);
  }

  /** 页面已销毁：不再回调。 */
  detach(): void {
    this.callback = null;
  }
}

export class SDMediaProcessModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME: string = 'SDMediaProcessModule';
  /** taskId → 任务。静态：任何页面都可以 cancel 别的页面发起的任务。 */
  private static jobs: Map<string, ProcessJob> = new Map();
  private static anonymous: number = 0;

  syncMode(): boolean {
    return false;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    const p: SDParams = SD.parse(params);
    switch (method) {
      case 'probe':
        this.probe(p, new SDReply(callback));
        break;
      case 'videoFrame':
        this.videoFrame(p, new SDReply(callback));
        break;
      case 'remuxMp3WithXing':
        this.remuxMp3(p, new SDReply(callback));
        break;
      case 'muxVideoWithAudio':
        this.startJob(p, callback, (job: ProcessJob): Promise<SDResult> => this.runMux(job, p));
        break;
      case 'trimAudio':
        this.startJob(p, callback, (job: ProcessJob): Promise<SDResult> => this.runTrim(job, p));
        break;
      case 'extractWaveform':
        this.startJob(p, callback, (job: ProcessJob): Promise<SDResult> => this.runWaveform(job, p));
        break;
      case 'cancel':
        SDMediaProcessModule.cancel(SD.str(p, 'taskId'));
        SD.send(callback, SD.ok());
        break;
      default:
        SD.send(callback, SD.fail(`unknown method: ${method}`));
        break;
    }
    return null;
  }

  onDestroy(): void {
    // 页面销毁后回调已失效：取消它名下还在跑的任务，释放 CPU / 临时文件。
    const mine: Array<ProcessJob> = [];
    SDMediaProcessModule.jobs.forEach((job: ProcessJob) => {
      if (job.owner === this) {
        mine.push(job);
      }
    });
    for (let i = 0; i < mine.length; i++) {
      mine[i].detach();
      SDMediaProcessModule.cancel(mine[i].taskId);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 一次性方法
  // ---------------------------------------------------------------------------------------------

  private probe(p: SDParams, reply: SDReply): void {
    const path: string = SD.str(p, 'path');
    if (path.length === 0) {
      reply.fail('path is required');
      return;
    }
    if (MediaSupport.isHttp(path)) {
      reply.fail('unsupported: probe needs a local file');
      return;
    }
    const local: string = MediaSupport.localPath(path);
    this.probeLocal(local).then((result: SDResult) => {
      reply.send(result);
    }).catch((e: BusinessError) => {
      reply.fail(SD.errMsg(e));
    });
  }

  private async probeLocal(local: string): Promise<SDResult> {
    const out: SDResult = {};
    let systemError: string = '';
    let info: MediaInfo | null = null;
    try {
      info = await MediaProbe.probe(local);
    } catch (e) {
      systemError = SD.errMsg(e as Object);
    }
    // 纯解析兜底 / 补充（MP4 的 fps；系统解析失败时的时长）。
    let inspected: SDParams = {};
    try {
      inspected = SD.parseJson((await taskpool.execute(sdInspectTask, local)) as string);
    } catch (e) {
      SDLog.w(TAG, `inspect failed: ${SD.errMsg(e as Object)}`);
    }
    const inspectedOk: boolean = SD.bool(inspected, 'ok');
    if (info !== null && (info.durationMs > 0 || !inspectedOk)) {
      out['durationMs'] = info.durationMs;
      out['hasVideo'] = info.hasVideo;
      out['hasAudio'] = info.hasAudio;
      if (info.hasVideo && info.width > 0 && info.height > 0) {
        out['width'] = info.width;
        out['height'] = info.height;
      }
      if (info.format.length > 0) {
        out['format'] = info.format;
      }
    } else if (inspectedOk) {
      out['durationMs'] = SD.num(inspected, 'durationMs');
      out['hasVideo'] = SD.bool(inspected, 'hasVideo');
      out['hasAudio'] = SD.bool(inspected, 'hasAudio');
      if (SD.has(inspected, 'width')) {
        out['width'] = SD.num(inspected, 'width');
        out['height'] = SD.num(inspected, 'height');
      }
      out['format'] = SD.str(inspected, 'kind');
    } else {
      return SD.fail(systemError.length > 0 ? systemError : SD.str(inspected, 'error', 'probe failed'));
    }
    if (inspectedOk && SD.has(inspected, 'fps')) {
      out['fps'] = SD.num(inspected, 'fps');
    }
    return SD.ok(out);
  }

  private videoFrame(p: SDParams, reply: SDReply): void {
    const videoPath: string = SD.str(p, 'videoPath');
    const dest: string = MediaSupport.localPath(SD.str(p, 'dest'));
    const positionMs: number = Math.max(0, SD.num(p, 'positionMs'));
    if (videoPath.length === 0 || dest.length === 0) {
      reply.fail('videoPath and dest are required');
      return;
    }
    if (MediaSupport.isHttp(videoPath)) {
      reply.fail('unsupported: videoFrame needs a local file');
      return;
    }
    this.grabFrame(MediaSupport.localPath(videoPath), positionMs, dest).then((result: SDResult) => {
      reply.send(result);
    }).catch((e: BusinessError) => {
      MediaSupport.remove(dest);
      reply.fail(SD.errMsg(e));
    });
  }

  private async grabFrame(videoPath: string, positionMs: number, dest: string): Promise<SDResult> {
    const src: fs.File = fs.openSync(videoPath, fs.OpenMode.READ_ONLY);
    let generator: media.AVImageGenerator | null = null;
    let pixelMap: image.PixelMap | null = null;
    let packer: image.ImagePacker | null = null;
    let out: fs.File | null = null;
    try {
      const size: number = fs.statSync(src.fd).size;
      generator = await media.createAVImageGenerator();
      const source: media.AVFileDescriptor = { fd: src.fd, offset: 0, length: size };
      generator.fdSrc = source;
      const frameSize: media.PixelMapParams = {};
      pixelMap = await generator.fetchFrameByTime(positionMs * 1000, media.AVImageQueryOptions.AV_IMAGE_QUERY_CLOSEST,
        frameSize);
      const info: image.ImageInfo = await pixelMap.getImageInfo();
      MediaSupport.ensureParent(dest);
      out = fs.openSync(dest, fs.OpenMode.WRITE_ONLY | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      packer = image.createImagePacker();
      const options: image.PackingOption = { format: 'image/jpeg', quality: 95 };
      await packer.packToFile(pixelMap, out.fd, options);
      const result: SDResult = {};
      result['path'] = dest;
      result['width'] = info.size.width;
      result['height'] = info.size.height;
      return SD.ok(result);
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
      if (generator !== null) {
        generator.release().catch((e: BusinessError) => {
          SDLog.w(TAG, `generator release: ${SD.errMsg(e)}`);
        });
      }
      MediaSupport.closeQuietly(out);
      MediaSupport.closeQuietly(src);
    }
  }

  private remuxMp3(p: SDParams, reply: SDReply): void {
    const src: string = MediaSupport.localPath(SD.str(p, 'src'));
    const dest: string = MediaSupport.localPath(SD.str(p, 'dest'));
    if (src.length === 0 || dest.length === 0 || src === dest) {
      reply.fail('src and dest are required and must differ');
      return;
    }
    taskpool.execute(new taskpool.Task(sdRemuxMp3Task, src, dest)).then((res: Object) => {
      reply.send(SD.parseJson(res as string));
    }).catch((e: BusinessError) => {
      reply.fail(SD.errMsg(e));
    });
  }

  // ---------------------------------------------------------------------------------------------
  // 带 taskId 的长任务
  // ---------------------------------------------------------------------------------------------

  private startJob(p: SDParams, callback: KuiklyRenderCallback | null,
    run: (job: ProcessJob) => Promise<SDResult>): void {
    let taskId: string = SD.str(p, 'taskId');
    if (taskId.length === 0) {
      SDMediaProcessModule.anonymous += 1;
      taskId = `sd_task_${SDMediaProcessModule.anonymous}`;
    }
    const job: ProcessJob = new ProcessJob(taskId, this, callback);
    if (SDMediaProcessModule.jobs.has(taskId)) {
      job.done(SD.fail('duplicate taskId'));
      return;
    }
    SDMediaProcessModule.jobs.set(taskId, job);
    run(job).then((result: SDResult) => {
      SDMediaProcessModule.jobs.delete(taskId);
      job.done(job.cancelled ? SDMediaProcessModule.cancelledResult() : result);
    }).catch((e: BusinessError) => {
      SDMediaProcessModule.jobs.delete(taskId);
      job.done(job.cancelled ? SDMediaProcessModule.cancelledResult() : SD.fail(SD.errMsg(e)));
    });
  }

  private static cancelledResult(): SDResult {
    const extra: SDResult = {};
    extra['cancelled'] = true;
    return SD.fail('cancelled', undefined, extra);
  }

  private static cancel(taskId: string): void {
    const job: ProcessJob | undefined = SDMediaProcessModule.jobs.get(taskId);
    if (job === undefined || job.finished) {
      return;
    }
    job.cancelled = true;
    const task: taskpool.Task | null = job.task;
    if (task !== null) {
      try {
        taskpool.cancel(task);
      } catch (e) {
        // 任务刚好结束 / 还没入队：忽略，run() 的收尾逻辑会按 cancelled 处理。
        SDLog.d(TAG, `cancel task: ${SD.errMsg(e as Object)}`);
      }
    }
    const abort: (() => void) | null = job.abortTranscode;
    if (abort !== null) {
      abort();
    }
  }

  /** 在 TaskPool 里跑一个任务，把 0..1 的进度映射到 [from, to] 上报。任务异常时返回 {ok:false}。 */
  private runTask(job: ProcessJob, task: taskpool.Task, from: number, to: number): Promise<SDParams> {
    if (job.cancelled) {
      return Promise.resolve(SDMediaProcessModule.cancelledResult());
    }
    job.task = task;
    task.onReceiveData((value: number): void => {
      job.progress(from + (to - from) * value);
    });
    return taskpool.execute(task).then((res: Object): SDParams => {
      job.task = null;
      return SD.parseJson(res as string);
    }).catch((e: BusinessError): SDParams => {
      job.task = null;
      return job.cancelled ? SDMediaProcessModule.cancelledResult() : SD.fail(SD.errMsg(e));
    });
  }

  private async runTrim(job: ProcessJob, p: SDParams): Promise<SDResult> {
    const src: string = MediaSupport.localPath(SD.str(p, 'src'));
    const dest: string = MediaSupport.localPath(SD.str(p, 'dest'));
    const startMs: number = Math.max(0, SD.num(p, 'startMs'));
    const endMs: number = SD.num(p, 'endMs');
    if (src.length === 0 || dest.length === 0 || src === dest) {
      return SD.fail('src and dest are required and must differ');
    }
    return this.runTask(job, new taskpool.Task(sdTrimAudioTask, src, dest, startMs, endMs), 0, 1);
  }

  private async runWaveform(job: ProcessJob, p: SDParams): Promise<SDResult> {
    const audioPath: string = MediaSupport.localPath(SD.str(p, 'audioPath'));
    let rate: number = SD.num(p, 'samplesPerSecond', 20);
    if (rate < 8) {
      rate = 8;
    }
    if (rate > 40) {
      rate = 40;
    }
    if (audioPath.length === 0) {
      return SD.fail('audioPath is required');
    }
    const result: SDParams = await this.runTask(job, new taskpool.Task(sdWaveformTask, audioPath, rate), 0, 1);
    if (!SD.bool(result, 'ok') && SD.str(result, 'error').startsWith('unsupported')) {
      // 契约：没有解码路径时 error 固定为 "unsupported"，shared 侧据此画平直波形；细节放在 detail。
      const extra: SDResult = {};
      extra['detail'] = SD.str(result, 'error');
      return SD.fail('unsupported', undefined, extra);
    }
    return result;
  }

  private async runMux(job: ProcessJob, p: SDParams): Promise<SDResult> {
    const videoPath: string = MediaSupport.localPath(SD.str(p, 'videoPath'));
    const audioPath: string = MediaSupport.localPath(SD.str(p, 'audioPath'));
    const dest: string = MediaSupport.localPath(SD.str(p, 'dest'));
    const startMs: number = Math.max(0, SD.num(p, 'audioStartMs'));
    const durationMs: number = Math.max(0, SD.num(p, 'audioDurationMs'));
    if (videoPath.length === 0 || audioPath.length === 0 || dest.length === 0) {
      return SD.fail('videoPath, audioPath and dest are required');
    }
    if (MediaSupport.isHttp(videoPath) || MediaSupport.isHttp(audioPath)) {
      return SD.fail('unsupported: inputs must be local files');
    }
    if (dest === videoPath || dest === audioPath) {
      return SD.fail('dest must differ from the inputs');
    }

    // 1) 音频已经是 AAC-in-MP4：直接流拷贝。
    const direct: SDParams = await this.runTask(job,
      new taskpool.Task(sdMuxTask, videoPath, audioPath, startMs, durationMs, dest, false), 0, 1);
    if (SD.bool(direct, 'ok') || !SD.bool(direct, 'needTranscode') || job.cancelled) {
      return direct;
    }

    // 2) 先转 AAC 再混流。
    const kind: string = SD.str(direct, 'kind');
    let transcodeError: string = '';
    let temp: string = '';
    try {
      temp = MediaSupport.tempFile(TEMP_DIR, 'aac', 'm4a');
      await this.transcodeToAac(job, audioPath, temp);
      const muxed: SDParams = await this.runTask(job,
        new taskpool.Task(sdMuxTask, videoPath, temp, startMs, durationMs, dest, false), 0.7, 1);
      if (SD.bool(muxed, 'ok') || job.cancelled) {
        return muxed;
      }
      transcodeError = SD.str(muxed, 'error', 'mux after transcode failed');
    } catch (e) {
      transcodeError = SD.errMsg(e as Object);
    } finally {
      MediaSupport.remove(temp);
    }
    if (job.cancelled) {
      return SDMediaProcessModule.cancelledResult();
    }
    SDLog.w(TAG, `AAC transcode path failed (${kind}): ${transcodeError}`);

    // 3) 降级：MP3 帧直通。
    if (kind === 'mp3') {
      const passthrough: SDParams = await this.runTask(job,
        new taskpool.Task(sdMuxTask, videoPath, audioPath, startMs, durationMs, dest, true), 0.7, 1);
      if (SD.bool(passthrough, 'ok')) {
        passthrough['degraded'] = true;
        passthrough['detail'] = `AAC transcode unavailable: ${transcodeError}`;
      }
      return passthrough;
    }
    const extra: SDResult = {};
    extra['detail'] = transcodeError;
    return SD.fail(`unsupported: cannot convert '${kind}' audio to AAC on this device`, undefined, extra);
  }

  /**
   * media.AVTranscoder：任意系统可解码的音频 → AAC（MP4 容器）。进度映射到 0..0.7。
   * 取消时 reject(Error('cancelled'))。
   */
  private async transcodeToAac(job: ProcessJob, srcPath: string, destPath: string): Promise<void> {
    const src: fs.File = fs.openSync(srcPath, fs.OpenMode.READ_ONLY);
    let dst: fs.File | null = null;
    let transcoder: media.AVTranscoder | null = null;
    try {
      const size: number = fs.statSync(src.fd).size;
      dst = fs.openSync(destPath, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      transcoder = await media.createAVTranscoder();
      const active: media.AVTranscoder = transcoder;
      const dstFd: number = dst.fd;
      await new Promise<void>((resolve, reject) => {
        let settled: boolean = false;
        const finish = (error: Error | null): void => {
          if (settled) {
            return;
          }
          settled = true;
          job.abortTranscode = null;
          if (error === null) {
            resolve();
          } else {
            reject(error);
          }
        };
        job.abortTranscode = (): void => {
          active.cancel().catch((e: BusinessError) => {
            SDLog.w(TAG, `transcoder cancel: ${SD.errMsg(e)}`);
          });
          finish(new Error('cancelled'));
        };
        active.on('complete', () => {
          finish(null);
        });
        active.on('error', (err: BusinessError) => {
          finish(new Error(SD.errMsg(err)));
        });
        active.on('progressUpdate', (percent: number) => {
          job.progress(0.7 * percent / 100);
        });
        const source: media.AVFileDescriptor = { fd: src.fd, offset: 0, length: size };
        const config: media.AVTranscoderConfig = {
          audioBitrate: AAC_BITRATE,
          audioCodec: media.CodecMimeType.AUDIO_AAC,
          fileFormat: media.ContainerFormatType.CFT_MPEG_4
        };
        try {
          active.fdSrc = source;
          active.fdDst = dstFd;
        } catch (e) {
          finish(new Error(SD.errMsg(e as Object)));
          return;
        }
        active.prepare(config).then((): Promise<void> => {
          return active.start();
        }).catch((e: BusinessError) => {
          finish(new Error(SD.errMsg(e)));
        });
        if (job.cancelled) {
          finish(new Error('cancelled'));
        }
      });
    } finally {
      job.abortTranscode = null;
      if (transcoder !== null) {
        try {
          await transcoder.release();
        } catch (e) {
          SDLog.w(TAG, `transcoder release: ${SD.errMsg(e as Object)}`);
        }
      }
      MediaSupport.closeQuietly(dst);
      MediaSupport.closeQuietly(src);
    }
  }
}
