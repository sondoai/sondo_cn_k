/*
 * SDVideoView —— 视频播放视图（media.AVPlayer + XComponent SURFACE）。
 * 契约见 shared/.../nativeapi/SDNativeViews.kt。假定 API 12（HarmonyOS NEXT 5.0）。
 *
 * 结构：SDVideoView（Kuikly 渲染视图，持有播放器与全部逻辑）+ 文件末尾的 SDVideoComponent
 * （ArkUI 组件，只负责提供 Surface 和按 fit 排版）。两者通过 SDVideoUiBridge 通信。
 *
 * 要点
 *  - 每次换源新建一个 AVPlayer（释放旧的）；所有异步步骤带 generation，过期回调一律丢弃。
 *  - 属性是逐个到达的，所以换源后延迟一拍（setTimeout 0）再加载，保证拿到完整属性。
 *  - Surface 就绪之前不创建播放器；Surface 销毁 / 视图销毁时立刻释放播放器（滚动列表里不泄漏）。
 *  - 缓存（cache = 1 且是 http(s) 地址）：已完整缓存则播放本地文件，否则在线播放并在后台预取
 *    （规则见 VideoCacheStore：≤ 20MB、300 个、69 天）。
 *  - fit：cover → VIDEO_SCALE_TYPE_FIT_CROP；fill → VIDEO_SCALE_TYPE_FIT（拉伸铺满）；
 *         contain → 把 Surface 排版成视频宽高比（居中）+ VIDEO_SCALE_TYPE_FIT。
 *  - 应用退后台时暂停正在播放的视频，回前台时恢复（期间有任何显式 play / pause / 换源则不恢复）。
 *
 * 音频互斥（与 Flutter 参考工程一致：有声播放器之间互斥，静音播放器不参与）
 *  - mixWithOthers = 0 且有声（未静音、音量 > 0）的视频开始播放时，通过 AudioOutputCoordinator
 *    暂停全局音频播放器（AudioPlayerManager）和其它独占视频；反过来全局音频开始播放时也会暂停这类视频。
 *  - mixWithOthers = 1 或静音的视频不参与应用内互斥。
 *  - 系统层面：mixWithOthers = 0 → audioInterruptMode = INDEPENDENT_MODE（独立焦点）；
 *    mixWithOthers = 1 → SHARE_MODE（与应用内其它播放器共享焦点，可同时出声）。
 *    加载时就是静音的视频用 PlaybackStrategy.mutedMediaType 关闭音轨，避免抢占别的应用的音频焦点；
 *    之后若被取消静音，会保留进度重新加载一次以恢复音轨。
 */
import { KRAny, KRRecord, KuiklyRenderBaseView, KuiklyRenderCallback } from '@kuikly-open/render';
import { ComponentContent, UIContext } from '@kit.ArkUI';
import { media } from '@kit.MediaKit';
import { audio } from '@kit.AudioKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { SD, SDLog } from '../support/SDModuleSupport';
import { AppEvent, AppEventListener, AppEvents } from '../support/AppEvents';
import { AudioOutputCoordinator, AudioOutputParticipant } from '../modules/media/AudioOutputCoordinator';
import { AVPlayerSupport } from '../modules/media/AudioPlayerManager';
import { MediaSupport } from '../modules/media/MediaSupport';
import { VideoCacheStore } from '../modules/media/VideoCacheStore';

const TAG: string = 'SDVideoView';

const STATE_IDLE: string = 'idle';
const STATE_LOADING: string = 'loading';
const STATE_READY: string = 'ready';
const STATE_BUFFERING: string = 'buffering';
const STATE_PLAYING: string = 'playing';
const STATE_PAUSED: string = 'paused';
const STATE_COMPLETED: string = 'completed';
const STATE_ERROR: string = 'error';

/** 视图 → ArkUI 组件的通知通道（组件在 aboutToAppear 里填入回调）。 */
export class SDVideoUiBridge {
  onFit: (fit: string) => void = (fit: string): void => {
  };
  onVideoSize: (width: number, height: number) => void = (width: number, height: number): void => {
  };
  onFirstFrame: (shown: boolean) => void = (shown: boolean): void => {
  };
}

export class SDVideoView extends KuiklyRenderBaseView implements AudioOutputParticipant {
  static readonly VIEW_NAME: string = 'SDVideoView';

  // ---- 属性 ----
  src: string = '';
  autoPlay: boolean = false;
  loop: boolean = false;
  muted: boolean = false;
  volume: number = 1;
  speed: number = 1;
  fit: string = 'contain';
  mixWithOthers: boolean = false;
  cache: boolean = true;
  progressIntervalMs: number = 250;

  // ---- 事件 ----
  private stateCallback: KuiklyRenderCallback | null = null;
  private progressCallback: KuiklyRenderCallback | null = null;
  private firstFrameCallback: KuiklyRenderCallback | null = null;

  // ---- 运行时 ----
  videoWidth: number = 0;
  videoHeight: number = 0;
  private ui: SDVideoUiBridge | null = null;
  private surfaceId: string = '';
  private player: media.AVPlayer | null = null;
  private file: fs.File | null = null;
  private generation: number = 0;
  private chain: Promise<void> = Promise.resolve();
  private loadTimer: number = -1;

  private state: string = STATE_IDLE;
  private avState: string = 'idle';
  private prepared: boolean = false;
  private playIntent: boolean = false;
  private buffering: boolean = false;
  private durationMs: number = 0;
  private positionMs: number = 0;
  private bufferedMs: number = 0;
  private errorMessage: string = '';
  private localSource: boolean = false;
  firstFrameSent: boolean = false;
  private pendingSeekMs: number = -1;
  private restartSeekMs: number = -1;
  private lastProgressEmit: number = 0;
  private lastStateKey: string = '';
  /** 本次加载用 mutedMediaType 关掉了音轨。 */
  private audioTrackDisabled: boolean = false;
  /** 显式 release() 之后，直到下一次 play() / 换源之前不自动加载。 */
  private releasedByCaller: boolean = false;
  private pausedByBackground: boolean = false;
  private destroyed: boolean = false;

  private backgroundListener: AppEventListener = (event: AppEvent): void => {
    if (this.destroyed || !this.playIntent) {
      return;
    }
    this.pauseInternal();
    this.pausedByBackground = true;
  };
  private foregroundListener: AppEventListener = (event: AppEvent): void => {
    if (this.destroyed || !this.pausedByBackground) {
      return;
    }
    this.pausedByBackground = false;
    this.playInternal();
  };

  init(instanceId: string, viewTag: number, viewType: string): void {
    super.init(instanceId, viewTag, viewType);
    AppEvents.on(AppEvents.BACKGROUND, this.backgroundListener);
    AppEvents.on(AppEvents.FOREGROUND, this.foregroundListener);
    AudioOutputCoordinator.attach(this);
  }

  // ---------------------------------------------------------------------------------------------
  // Kuikly 渲染视图接口
  // ---------------------------------------------------------------------------------------------

  createArkUIView(): ComponentContent<KuiklyRenderBaseView> {
    const uiContext: UIContext = this.getUIContext() as UIContext;
    return new ComponentContent<KuiklyRenderBaseView>(uiContext,
      wrapBuilder<[KuiklyRenderBaseView]>(buildSDVideoView), this);
  }

  setProp(propKey: string, propValue: KRAny | KuiklyRenderCallback): boolean {
    switch (propKey) {
      case 'src':
        this.setSrc(SDVideoView.text(propValue));
        return true;
      case 'autoPlay':
        this.autoPlay = SDVideoView.flag(propValue);
        return true;
      case 'loop':
        this.loop = SDVideoView.flag(propValue);
        this.applyLoop();
        return true;
      case 'muted':
        this.setAudio(SDVideoView.flag(propValue), this.volume);
        return true;
      case 'volume':
        this.setAudio(this.muted, SDVideoView.clamp(SDVideoView.num(propValue, 1), 0, 1));
        return true;
      case 'speed':
        this.speed = SDVideoView.clamp(SDVideoView.num(propValue, 1), 0.25, 4);
        this.applySpeed();
        return true;
      case 'fit': {
        const fit: string = SDVideoView.text(propValue);
        this.fit = fit === 'cover' || fit === 'fill' ? fit : 'contain';
        this.applyScaleType();
        const ui: SDVideoUiBridge | null = this.ui;
        if (ui !== null) {
          ui.onFit(this.fit);
        }
        return true;
      }
      case 'mixWithOthers':
        this.mixWithOthers = SDVideoView.flag(propValue);
        this.applyInterruptMode();
        return true;
      case 'cache':
        this.cache = SDVideoView.flag(propValue);
        return true;
      case 'progressIntervalMs': {
        const interval: number = SDVideoView.num(propValue, 250);
        this.progressIntervalMs = interval >= 50 ? interval : 50;
        return true;
      }
      case 'sdState':
        this.stateCallback = propValue as KuiklyRenderCallback;
        this.lastStateKey = '';
        this.emitState();
        return true;
      case 'sdProgress':
        this.progressCallback = propValue as KuiklyRenderCallback;
        return true;
      case 'sdFirstFrame':
        this.firstFrameCallback = propValue as KuiklyRenderCallback;
        return true;
      default:
        return super.setProp(propKey, propValue);
    }
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): void {
    switch (method) {
      case 'play':
        this.pausedByBackground = false;
        this.playInternal();
        break;
      case 'pause':
        this.pausedByBackground = false;
        this.pauseInternal();
        break;
      case 'stop':
        this.pausedByBackground = false;
        this.stopInternal();
        break;
      case 'seekTo':
        this.seekTo(SD.num(SD.parse(params), 'positionMs', 0));
        break;
      case 'release':
        this.pausedByBackground = false;
        this.releaseInternal();
        break;
      default:
        super.call(method, params, callback);
        break;
    }
  }

  onDestroy(): void {
    this.destroyed = true;
    this.generation += 1;
    this.cancelLoadTimer();
    AppEvents.off(AppEvents.BACKGROUND, this.backgroundListener);
    AppEvents.off(AppEvents.FOREGROUND, this.foregroundListener);
    AudioOutputCoordinator.detach(this);
    this.stateCallback = null;
    this.progressCallback = null;
    this.firstFrameCallback = null;
    this.ui = null;
    this.enqueueDispose();
  }

  // ---------------------------------------------------------------------------------------------
  // ArkUI 组件回调
  // ---------------------------------------------------------------------------------------------

  attachUi(bridge: SDVideoUiBridge): void {
    this.ui = bridge;
  }

  detachUi(bridge: SDVideoUiBridge): void {
    if (this.ui === bridge) {
      this.ui = null;
    }
  }

  onSurfaceReady(surfaceId: string): void {
    if (this.destroyed || surfaceId.length === 0 || surfaceId === this.surfaceId) {
      return;
    }
    this.surfaceId = surfaceId;
    if (this.src.length > 0 && !this.releasedByCaller) {
      this.scheduleLoad();
    }
  }

  onSurfaceDestroyed(): void {
    this.surfaceId = '';
    if (this.destroyed) {
      return;
    }
    // Surface 没了：播放器必须释放；组件重新出现时会用当前属性重新加载。
    this.generation += 1;
    this.cancelLoadTimer();
    this.resetRuntime();
    this.enqueueDispose();
    this.setState(this.src.length > 0 ? STATE_LOADING : STATE_IDLE);
  }

  // ---------------------------------------------------------------------------------------------
  // AudioOutputParticipant
  // ---------------------------------------------------------------------------------------------

  isAudiblePlaying(): boolean {
    return !this.destroyed && !this.mixWithOthers && !this.isSilent() && this.playIntent;
  }

  pauseForHandoff(): void {
    this.pausedByBackground = false;
    this.pauseInternal();
  }

  // ---------------------------------------------------------------------------------------------
  // 属性处理
  // ---------------------------------------------------------------------------------------------

  private static text(value: KRAny | KuiklyRenderCallback): string {
    if (typeof value === 'string') {
      return value as string;
    }
    if (typeof value === 'number') {
      return `${value}`;
    }
    return '';
  }

  private static num(value: KRAny | KuiklyRenderCallback, fallback: number): number {
    if (typeof value === 'number') {
      return Number.isFinite(value as number) ? value as number : fallback;
    }
    if (typeof value === 'string' && (value as string).length > 0) {
      const n: number = Number(value as string);
      return Number.isFinite(n) ? n : fallback;
    }
    if (typeof value === 'boolean') {
      return (value as boolean) ? 1 : 0;
    }
    return fallback;
  }

  private static flag(value: KRAny | KuiklyRenderCallback): boolean {
    if (typeof value === 'boolean') {
      return value as boolean;
    }
    if (typeof value === 'string') {
      return value === '1' || value === 'true';
    }
    return SDVideoView.num(value, 0) !== 0;
  }

  private static clamp(value: number, min: number, max: number): number {
    if (value < min) {
      return min;
    }
    return value > max ? max : value;
  }

  private isSilent(): boolean {
    return this.muted || this.volume <= 0;
  }

  private setSrc(src: string): void {
    if (src === this.src) {
      return;
    }
    this.src = src;
    this.releasedByCaller = false;
    this.pausedByBackground = false;
    // 新音源是否自动播放只看 autoPlay 属性，不继承上一个音源的播放状态。
    this.playIntent = false;
    this.pendingSeekMs = -1;
    this.scheduleLoad();
  }

  private setAudio(muted: boolean, volume: number): void {
    const wasSilent: boolean = this.isSilent();
    this.muted = muted;
    this.volume = volume;
    const silent: boolean = this.isSilent();
    const player: media.AVPlayer | null = this.player;
    if (player === null || !this.prepared) {
      return;
    }
    if (wasSilent && !silent && this.audioTrackDisabled) {
      // 音轨在加载时被关掉了：保留进度重新加载，恢复音轨。
      this.reloadKeepingPosition();
      return;
    }
    this.applyVolume(player);
    if (wasSilent !== silent) {
      player.setMediaMuted(media.MediaType.MEDIA_TYPE_AUD, silent).catch((e: BusinessError) => {
        SDLog.d(TAG, `setMediaMuted: ${SD.errMsg(e)}`);
      });
      if (!silent && this.playIntent && !this.mixWithOthers) {
        AudioOutputCoordinator.claim(this);
      }
    }
  }

  private applyVolume(player: media.AVPlayer): void {
    try {
      player.setVolume(this.isSilent() ? 0 : this.volume);
    } catch (e) {
      SDLog.w(TAG, `setVolume: ${SD.errMsg(e as Object)}`);
    }
  }

  private applyLoop(): void {
    const player: media.AVPlayer | null = this.player;
    if (player === null || !this.prepared) {
      return;
    }
    try {
      player.loop = this.loop;
    } catch (e) {
      SDLog.w(TAG, `loop: ${SD.errMsg(e as Object)}`);
    }
  }

  private applySpeed(): void {
    const player: media.AVPlayer | null = this.player;
    if (player === null || !this.prepared) {
      return;
    }
    try {
      player.setSpeed(AVPlayerSupport.nearestSpeed(this.speed));
    } catch (e) {
      SDLog.w(TAG, `setSpeed: ${SD.errMsg(e as Object)}`);
    }
  }

  private applyScaleType(): void {
    const player: media.AVPlayer | null = this.player;
    if (player === null || !this.prepared) {
      return;
    }
    try {
      player.videoScaleType = this.fit === 'cover' ? media.VideoScaleType.VIDEO_SCALE_TYPE_FIT_CROP :
        media.VideoScaleType.VIDEO_SCALE_TYPE_FIT;
    } catch (e) {
      SDLog.w(TAG, `videoScaleType: ${SD.errMsg(e as Object)}`);
    }
  }

  private applyInterruptMode(): void {
    const player: media.AVPlayer | null = this.player;
    if (player === null || !this.prepared) {
      return;
    }
    try {
      player.audioInterruptMode = this.mixWithOthers ? audio.InterruptMode.SHARE_MODE :
        audio.InterruptMode.INDEPENDENT_MODE;
    } catch (e) {
      SDLog.w(TAG, `audioInterruptMode: ${SD.errMsg(e as Object)}`);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 播放控制
  // ---------------------------------------------------------------------------------------------

  private playInternal(): void {
    if (this.destroyed) {
      return;
    }
    this.playIntent = true;
    if (this.releasedByCaller) {
      // release() 之后再次 play：重新加载并自动播放。
      this.releasedByCaller = false;
      this.load(true, -1);
      return;
    }
    if (this.state === STATE_ERROR && this.src.length > 0) {
      this.load(true, -1); // 出错后重试
      return;
    }
    if (this.prepared) {
      this.startPlayback();
    }
  }

  private pauseInternal(): void {
    this.playIntent = false;
    const player: media.AVPlayer | null = this.player;
    if (player === null || !this.prepared) {
      return;
    }
    if (this.avState === 'playing') {
      player.pause().catch((e: BusinessError) => {
        SDLog.w(TAG, `pause: ${SD.errMsg(e)}`);
      });
    } else if (this.state === STATE_BUFFERING || this.state === STATE_PLAYING) {
      this.setState(STATE_PAUSED);
    }
  }

  /** 停止：暂停并回到开头（播放器保持就绪，可直接再次 play）。 */
  private stopInternal(): void {
    this.playIntent = false;
    const player: media.AVPlayer | null = this.player;
    if (player === null || !this.prepared) {
      this.pendingSeekMs = -1;
      return;
    }
    if (this.avState === 'playing') {
      player.pause().catch((e: BusinessError) => {
        SDLog.w(TAG, `stop/pause: ${SD.errMsg(e)}`);
      });
    }
    this.restartSeekMs = -1;
    this.seekPlayer(player, 0);
    this.positionMs = 0;
    this.setState(STATE_READY);
    this.emitProgress(true);
  }

  private seekTo(positionMs: number): void {
    let target: number = Number.isFinite(positionMs) && positionMs > 0 ? positionMs : 0;
    if (this.durationMs > 0 && target > this.durationMs) {
      target = this.durationMs;
    }
    const player: media.AVPlayer | null = this.player;
    if (player === null || !this.prepared) {
      this.pendingSeekMs = target;
      return;
    }
    if (this.avState === 'completed') {
      this.restartSeekMs = target; // completed 状态下 play() 会从头开始，进入 playing 后再校正
    }
    this.positionMs = target;
    this.seekPlayer(player, target);
    this.emitProgress(true);
  }

  private releaseInternal(): void {
    this.generation += 1;
    this.cancelLoadTimer();
    this.releasedByCaller = true;
    this.resetRuntime();
    this.enqueueDispose();
    this.setState(STATE_IDLE);
  }

  private startPlayback(): void {
    const player: media.AVPlayer | null = this.player;
    if (player === null || !this.prepared || this.avState === 'playing') {
      return;
    }
    if (!this.mixWithOthers && !this.isSilent()) {
      AudioOutputCoordinator.claim(this);
    }
    const gen: number = this.generation;
    player.play().catch((e: BusinessError) => {
      if (gen === this.generation) {
        this.fail(gen, `play failed: ${SD.errMsg(e)}`);
      }
    });
  }

  private seekPlayer(player: media.AVPlayer, positionMs: number): void {
    try {
      player.seek(Math.max(0, Math.round(positionMs)), media.SeekMode.SEEK_CLOSEST);
    } catch (e) {
      SDLog.w(TAG, `seek: ${SD.errMsg(e as Object)}`);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 加载
  // ---------------------------------------------------------------------------------------------

  private scheduleLoad(): void {
    if (this.destroyed || this.loadTimer >= 0) {
      return;
    }
    this.loadTimer = setTimeout(() => {
      this.loadTimer = -1;
      if (!this.destroyed) {
        // playIntent / pendingSeekMs：Surface 就绪前收到的 play() / seekTo() 在这里兑现。
        this.load(this.autoPlay || this.playIntent, this.pendingSeekMs);
      }
    }, 0);
  }

  private cancelLoadTimer(): void {
    if (this.loadTimer >= 0) {
      clearTimeout(this.loadTimer);
      this.loadTimer = -1;
    }
  }

  private reloadKeepingPosition(): void {
    this.load(this.playIntent, this.positionMs);
  }

  private load(play: boolean, startMs: number): void {
    this.cancelLoadTimer();
    this.generation += 1;
    const gen: number = this.generation;
    this.resetRuntime();
    if (this.src.length === 0) {
      this.enqueueDispose();
      this.setState(STATE_IDLE);
      return;
    }
    this.playIntent = play;
    this.pendingSeekMs = startMs;
    this.setState(STATE_LOADING);
    if (this.surfaceId.length === 0) {
      // Surface 还没好：onSurfaceReady 会再触发一次加载。
      this.enqueueDispose();
      return;
    }
    const src: string = this.src;
    this.enqueue(async (): Promise<void> => {
      await this.disposePlayer();
      if (gen !== this.generation) {
        return;
      }
      await this.createPlayer(gen, src);
    });
  }

  private enqueue(task: () => Promise<void>): void {
    this.chain = this.chain.then(task).catch((e: BusinessError) => {
      SDLog.e(TAG, `queued task failed: ${SD.errMsg(e)}`);
    });
  }

  private enqueueDispose(): void {
    this.enqueue(async (): Promise<void> => {
      await this.disposePlayer();
    });
  }

  private resetRuntime(): void {
    this.prepared = false;
    this.playIntent = false;
    this.buffering = false;
    this.durationMs = 0;
    this.positionMs = 0;
    this.bufferedMs = 0;
    this.errorMessage = '';
    this.firstFrameSent = false;
    this.notifyFirstFrame(false);
    this.pendingSeekMs = -1;
    this.restartSeekMs = -1;
    this.audioTrackDisabled = false;
    this.localSource = false;
  }

  private async disposePlayer(): Promise<void> {
    const player: media.AVPlayer | null = this.player;
    const file: fs.File | null = this.file;
    this.player = null;
    this.file = null;
    this.avState = 'idle';
    if (player !== null) {
      this.unbind(player);
      await AVPlayerSupport.releaseQuietly(player);
    }
    MediaSupport.closeQuietly(file);
  }

  private async createPlayer(gen: number, src: string): Promise<void> {
    // 解析音源：缓存命中 → 本地文件；否则在线播放 + 后台预取。
    let localPath: string = '';
    if (MediaSupport.isHttp(src)) {
      if (this.cache) {
        localPath = VideoCacheStore.get().resolve(src);
        if (localPath.length === 0) {
          VideoCacheStore.get().prefetch(src).catch((e: Error) => {
            SDLog.d(TAG, `prefetch skipped: ${SD.errMsg(e)}`);
          });
        }
      }
    } else {
      localPath = AVPlayerSupport.resolveLocal(src);
    }

    let player: media.AVPlayer | null = null;
    try {
      player = await media.createAVPlayer();
    } catch (e) {
      this.fail(gen, `createAVPlayer failed: ${SD.errMsg(e as Object)}`);
      return;
    }
    if (gen !== this.generation) {
      await AVPlayerSupport.releaseQuietly(player);
      return;
    }
    this.player = player;
    this.avState = 'idle';
    this.localSource = localPath.length > 0;
    this.bind(player, gen);
    try {
      if (localPath.length > 0) {
        const file: fs.File = fs.openSync(localPath, fs.OpenMode.READ_ONLY);
        this.file = file;
        const size: number = fs.statSync(file.fd).size;
        const source: media.AVFileDescriptor = { fd: file.fd, offset: 0, length: size };
        player.fdSrc = source;
      } else {
        player.url = src;
      }
    } catch (e) {
      this.fail(gen, `open source failed: ${SD.errMsg(e as Object)}`);
    }
  }

  private bind(player: media.AVPlayer, gen: number): void {
    player.on('stateChange', (state: string, reason: media.StateChangeReason) => {
      if (gen === this.generation) {
        this.onStateChange(player, gen, state);
      }
    });
    player.on('error', (err: BusinessError) => {
      if (gen === this.generation) {
        this.fail(gen, SD.errMsg(err));
      }
    });
    player.on('durationUpdate', (duration: number) => {
      if (gen === this.generation && duration > 0 && duration !== this.durationMs) {
        this.durationMs = duration;
        if (this.localSource) {
          this.bufferedMs = duration;
        }
        this.emitState();
      }
    });
    player.on('timeUpdate', (time: number) => {
      if (gen === this.generation && this.prepared) {
        this.positionMs = time;
        this.emitProgress(false);
      }
    });
    player.on('seekDone', (time: number) => {
      if (gen === this.generation) {
        this.positionMs = time;
        this.emitProgress(true);
      }
    });
    player.on('bufferingUpdate', (infoType: media.BufferingInfoType, value: number) => {
      if (gen === this.generation) {
        this.onBuffering(infoType, value);
      }
    });
    player.on('startRenderFrame', () => {
      if (gen !== this.generation || this.firstFrameSent) {
        return;
      }
      this.firstFrameSent = true;
      this.notifyFirstFrame(true);
      const callback: KuiklyRenderCallback | null = this.firstFrameCallback;
      if (callback !== null) {
        const data: KRRecord = {};
        callback(data);
      }
    });
    player.on('videoSizeChange', (width: number, height: number) => {
      if (gen === this.generation) {
        this.updateVideoSize(width, height);
      }
    });
  }

  private unbind(player: media.AVPlayer): void {
    try {
      player.off('stateChange');
      player.off('error');
      player.off('durationUpdate');
      player.off('timeUpdate');
      player.off('seekDone');
      player.off('bufferingUpdate');
      player.off('startRenderFrame');
      player.off('videoSizeChange');
    } catch (e) {
      SDLog.w(TAG, `off failed: ${SD.errMsg(e as Object)}`);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // AVPlayer 事件
  // ---------------------------------------------------------------------------------------------

  private onStateChange(player: media.AVPlayer, gen: number, state: string): void {
    this.avState = state;
    switch (state) {
      case 'initialized':
        this.onInitialized(player, gen);
        break;
      case 'prepared':
        if (!this.prepared) {
          this.onPrepared(player);
        }
        break;
      case 'playing':
        if (!this.playIntent) {
          // play() 发出后、进入 playing 之前又被暂停。
          player.pause().catch((e: BusinessError) => {
            SDLog.w(TAG, `late pause: ${SD.errMsg(e)}`);
          });
          break;
        }
        if (this.restartSeekMs >= 0) {
          const target: number = this.restartSeekMs;
          this.restartSeekMs = -1;
          let current: number = 0;
          try {
            current = player.currentTime;
          } catch (e) {
            current = 0;
          }
          if (Math.abs(current - target) > 1000) {
            this.seekPlayer(player, target);
          }
        }
        this.setState(this.buffering ? STATE_BUFFERING : STATE_PLAYING);
        break;
      case 'paused':
        // 用户暂停、被应用内其它播放器抢占、或被系统打断（不自动恢复）。
        this.playIntent = false;
        this.setState(STATE_PAUSED);
        this.emitProgress(true);
        break;
      case 'completed':
        this.playIntent = false;
        if (this.durationMs > 0) {
          this.positionMs = this.durationMs;
        }
        this.emitProgress(true);
        this.setState(STATE_COMPLETED);
        break;
      case 'stopped':
        this.playIntent = false;
        this.setState(STATE_IDLE);
        break;
      default:
        break;
    }
  }

  private onInitialized(player: media.AVPlayer, gen: number): void {
    try {
      player.surfaceId = this.surfaceId;
      const info: audio.AudioRendererInfo = { usage: audio.StreamUsage.STREAM_USAGE_MOVIE, rendererFlags: 0 };
      player.audioRendererInfo = info;
    } catch (e) {
      SDLog.w(TAG, `configure player: ${SD.errMsg(e as Object)}`);
    }
    const prepare = (): void => {
      if (gen !== this.generation) {
        return;
      }
      player.prepare().catch((e: BusinessError) => {
        this.fail(gen, `prepare failed: ${SD.errMsg(e)}`);
      });
    };
    if (this.isSilent()) {
      // 静音起播：不渲染音轨，不去抢别的应用的音频焦点。
      const strategy: media.PlaybackStrategy = { mutedMediaType: media.MediaType.MEDIA_TYPE_AUD };
      player.setPlaybackStrategy(strategy).then(() => {
        if (gen === this.generation) {
          this.audioTrackDisabled = true;
        }
        prepare();
      }).catch((e: BusinessError) => {
        SDLog.d(TAG, `setPlaybackStrategy: ${SD.errMsg(e)}`);
        prepare();
      });
    } else {
      prepare();
    }
  }

  private onPrepared(player: media.AVPlayer): void {
    this.prepared = true;
    let duration: number = 0;
    let width: number = 0;
    let height: number = 0;
    try {
      duration = player.duration;
      width = player.width;
      height = player.height;
    } catch (e) {
      SDLog.w(TAG, `read player info: ${SD.errMsg(e as Object)}`);
    }
    this.durationMs = duration > 0 ? duration : 0;
    if (this.localSource) {
      this.bufferedMs = this.durationMs;
    }
    this.applyVolume(player);
    this.applyLoop();
    if (this.speed !== 1) {
      this.applySpeed();
    }
    this.applyScaleType();
    this.applyInterruptMode();
    if (width > 0 && height > 0) {
      this.updateVideoSize(width, height);
    }
    if (this.pendingSeekMs > 0) {
      this.positionMs = this.pendingSeekMs;
      this.seekPlayer(player, this.pendingSeekMs);
    }
    this.pendingSeekMs = -1;
    this.setState(STATE_READY);
    if (this.playIntent) {
      this.startPlayback();
    }
  }

  private onBuffering(infoType: media.BufferingInfoType, value: number): void {
    switch (infoType) {
      case media.BufferingInfoType.BUFFERING_START:
        this.buffering = true;
        if (this.state === STATE_PLAYING) {
          this.setState(STATE_BUFFERING);
        }
        break;
      case media.BufferingInfoType.BUFFERING_END:
        this.buffering = false;
        if (this.state === STATE_BUFFERING) {
          this.setState(this.avState === 'playing' ? STATE_PLAYING : STATE_PAUSED);
        }
        break;
      case media.BufferingInfoType.CACHED_DURATION: {
        let buffered: number = this.positionMs + (value > 0 ? value : 0);
        if (this.durationMs > 0 && buffered > this.durationMs) {
          buffered = this.durationMs;
        }
        this.bufferedMs = buffered;
        break;
      }
      case media.BufferingInfoType.BUFFERING_PERCENT:
        if (value >= 100 && this.durationMs > 0) {
          this.bufferedMs = this.durationMs;
        }
        break;
      default:
        break;
    }
  }

  // 出首帧前 Surface 是黑底，会盖住业务层垫在下面的封面图：首帧前让组件把 Surface 设为透明
  private notifyFirstFrame(shown: boolean): void {
    const ui: SDVideoUiBridge | null = this.ui;
    if (ui !== null) {
      ui.onFirstFrame(shown);
    }
  }

  private updateVideoSize(width: number, height: number): void {
    if (!(width > 0 && height > 0) || (width === this.videoWidth && height === this.videoHeight)) {
      return;
    }
    this.videoWidth = width;
    this.videoHeight = height;
    const ui: SDVideoUiBridge | null = this.ui;
    if (ui !== null) {
      ui.onVideoSize(width, height);
    }
    this.emitState();
  }

  private fail(gen: number, message: string): void {
    if (gen !== this.generation) {
      return;
    }
    SDLog.e(TAG, message);
    this.generation += 1;
    this.resetRuntime();
    this.errorMessage = message;
    this.enqueueDispose();
    this.setState(STATE_ERROR);
  }

  // ---------------------------------------------------------------------------------------------
  // 事件上报
  // ---------------------------------------------------------------------------------------------

  private setState(state: string): void {
    this.state = state;
    this.emitState();
  }

  private emitState(): void {
    const callback: KuiklyRenderCallback | null = this.stateCallback;
    if (callback === null) {
      return;
    }
    const key: string = `${this.state}|${this.durationMs}|${this.videoWidth}|${this.videoHeight}|${this.errorMessage}`;
    if (key === this.lastStateKey) {
      return;
    }
    this.lastStateKey = key;
    const data: KRRecord = {};
    data['state'] = this.state;
    data['durationMs'] = Math.round(this.durationMs);
    data['width'] = this.videoWidth;
    data['height'] = this.videoHeight;
    if (this.state === STATE_ERROR) {
      data['error'] = this.errorMessage;
    }
    callback(data);
  }

  private emitProgress(force: boolean): void {
    const callback: KuiklyRenderCallback | null = this.progressCallback;
    if (callback === null) {
      return;
    }
    const now: number = Date.now();
    if (!force && now - this.lastProgressEmit < this.progressIntervalMs) {
      return;
    }
    this.lastProgressEmit = now;
    const data: KRRecord = {};
    data['positionMs'] = Math.round(this.positionMs);
    data['durationMs'] = Math.round(this.durationMs);
    data['bufferedMs'] = Math.round(this.bufferedMs);
    callback(data);
  }
}

// ==== ArkUI ====

@Component
struct SDVideoComponent {
  host: SDVideoView | null = null;
  @State fit: string = 'contain';
  @State videoWidth: number = 0;
  @State videoHeight: number = 0;
  @State boxWidth: number = 0;
  @State boxHeight: number = 0;
  @State surfaceShown: boolean = false;
  private controller: XComponentController = new XComponentController();
  private bridge: SDVideoUiBridge = new SDVideoUiBridge();

  aboutToAppear(): void {
    const host: SDVideoView | null = this.host;
    if (host === null) {
      return;
    }
    this.fit = host.fit;
    this.videoWidth = host.videoWidth;
    this.videoHeight = host.videoHeight;
    this.surfaceShown = host.firstFrameSent;
    this.bridge.onFirstFrame = (shown: boolean): void => {
      this.surfaceShown = shown;
    };
    this.bridge.onFit = (fit: string): void => {
      this.fit = fit;
    };
    this.bridge.onVideoSize = (width: number, height: number): void => {
      this.videoWidth = width;
      this.videoHeight = height;
    };
    host.attachUi(this.bridge);
  }

  aboutToDisappear(): void {
    const host: SDVideoView | null = this.host;
    if (host !== null) {
      host.detachUi(this.bridge);
    }
  }

  /** contain：按视频宽高比在容器内居中；cover / fill：铺满，由 videoScaleType 决定裁剪还是拉伸。 */
  private containScale(): number {
    if (this.fit !== 'contain' || this.videoWidth <= 0 || this.videoHeight <= 0 || this.boxWidth <= 0 ||
      this.boxHeight <= 0) {
      return 0;
    }
    return Math.min(this.boxWidth / this.videoWidth, this.boxHeight / this.videoHeight);
  }

  private surfaceWidth(): Length {
    const scale: number = this.containScale();
    return scale > 0 ? this.videoWidth * scale : '100%';
  }

  private surfaceHeight(): Length {
    const scale: number = this.containScale();
    return scale > 0 ? this.videoHeight * scale : '100%';
  }

  build() {
    Stack({ alignContent: Alignment.Center }) {
      XComponent({ type: XComponentType.SURFACE, controller: this.controller })
        .width(this.surfaceWidth())
        .height(this.surfaceHeight())
        .opacity(this.surfaceShown ? 1 : 0)
        .onLoad(() => {
          const host: SDVideoView | null = this.host;
          if (host !== null) {
            host.onSurfaceReady(this.controller.getXComponentSurfaceId());
          }
        })
        .onDestroy(() => {
          const host: SDVideoView | null = this.host;
          if (host !== null) {
            host.onSurfaceDestroyed();
          }
        })
    }
    .width('100%')
    .height('100%')
    .clip(true)
    .onAreaChange((oldValue: Area, newValue: Area) => {
      this.boxWidth = newValue.width as number;
      this.boxHeight = newValue.height as number;
    })
  }
}

@Builder
function buildSDVideoView(view: KuiklyRenderBaseView) {
  SDVideoComponent({ host: view as SDVideoView })
}
