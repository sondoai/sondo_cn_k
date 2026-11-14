/*
 * 全局音频播放器（进程内单例）。SDAudioPlayerModule 的每个页面实例都只是它的薄封装，
 * 所以播放可以跨页面持续；事件通过 EventBroadcaster 广播给所有已注册页面。
 *
 * 实现：@kit.MediaKit 的 media.AVPlayer（假定 API 12）。
 *  - 每次 setSource 都新建一个 AVPlayer、释放旧的（release 可以在任何状态调用，
 *    比 reset 后复用更不容易踩状态机的坑）；所有异步步骤带 generation，过期的回调一律丢弃。
 *  - 音源：http(s) → url（带 headers 时用 createMediaSourceWithUrl + setMediaSource）；本地文件 → fdSrc。
 *  - 片段播放（clipStartMs / clipEndMs）：语义与 just_audio 的 ClippingAudioSource 一致——
 *    对外的 position / duration 都是「相对片段起点」的值；内部用 seek + 位置看门狗实现。
 *  - loopOne：AVPlayer.loop；带片段时由看门狗在片段终点 seek 回起点。
 *  - 状态：state ∈ idle / loading / buffering / ready / completed；playing 表示「播放意图」
 *    （与 just_audio 的 playing 一致：缓冲中也为 true；播完、暂停、被系统打断后为 false）。
 *  - 系统打断（audioInterrupt）只上报不处理：系统强制暂停后不会自动恢复，由 Kuikly 侧决定。
 *    例外：系统强制的压低音量（DUCK，forceType = FORCE）已由系统完成，不再上报，避免重复压低。
 *  - 拔出耳机 / 蓝牙断开：audioOutputDeviceChangeWithInfo 的 REASON_OLD_DEVICE_UNAVAILABLE → becomingNoisy。
 *  - 不做后台播放：没有 AVSession，也不申请长时任务。
 */
import { media } from '@kit.MediaKit';
import { audio } from '@kit.AudioKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { EventBroadcaster, SD, SDLog, SDParams, SDReply, SDResult } from '../../support/SDModuleSupport';
import { AudioOutputCoordinator, AudioOutputParticipant } from './AudioOutputCoordinator';
import { MediaSupport } from './MediaSupport';

const TAG: string = 'AudioPlayerManager';
const POSITION_INTERVAL_MS: number = 200;
const WATCHDOG_INTERVAL_MS: number = 40;
const PREPARE_TIMEOUT_MS: number = 45000;
/** 片段终点的提前量：看门狗 / timeUpdate 的粒度有限，略微提前触发以免越界播放。 */
const CLIP_END_SLACK_MS: number = 20;

const PHASE_IDLE: string = 'idle';
const PHASE_LOADING: string = 'loading';
const PHASE_BUFFERING: string = 'buffering';
const PHASE_READY: string = 'ready';
const PHASE_COMPLETED: string = 'completed';

/** AVPlayer 通用辅助（音频管理器和 SDVideoView 共用）。 */
export class AVPlayerSupport {
  /** 把任意倍速映射到最接近的 PlaybackSpeed 枚举（API 12 可用的档位：0.25 ~ 2.0）。 */
  static nearestSpeed(speed: number): media.PlaybackSpeed {
    const values: Array<number> = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];
    const enums: Array<media.PlaybackSpeed> = [
      media.PlaybackSpeed.SPEED_FORWARD_0_25_X,
      media.PlaybackSpeed.SPEED_FORWARD_0_50_X,
      media.PlaybackSpeed.SPEED_FORWARD_0_75_X,
      media.PlaybackSpeed.SPEED_FORWARD_1_00_X,
      media.PlaybackSpeed.SPEED_FORWARD_1_25_X,
      media.PlaybackSpeed.SPEED_FORWARD_1_50_X,
      media.PlaybackSpeed.SPEED_FORWARD_1_75_X,
      media.PlaybackSpeed.SPEED_FORWARD_2_00_X
    ];
    let best: number = 3;
    let bestDiff: number = Number.MAX_VALUE;
    const target: number = Number.isFinite(speed) && speed > 0 ? speed : 1;
    for (let i = 0; i < values.length; i++) {
      const diff: number = Math.abs(values[i] - target);
      if (diff < bestDiff) {
        bestDiff = diff;
        best = i;
      }
    }
    return enums[best];
  }

  /** 释放播放器，吞掉所有异常。 */
  static async releaseQuietly(player: media.AVPlayer | null): Promise<void> {
    if (player === null) {
      return;
    }
    try {
      await player.release();
    } catch (e) {
      SDLog.w(TAG, `release failed: ${SD.errMsg(e as Object)}`);
    }
  }

  /** 把本地地址解析成可打开的路径：沙箱路径 / file URI / shared assets 相对路径。 */
  static resolveLocal(uri: string): string {
    if (uri.startsWith('/') || uri.startsWith('file://') || uri.startsWith('datashare://')) {
      return MediaSupport.localPath(uri);
    }
    const asset: string = MediaSupport.assetPath(uri);
    return asset.length > 0 ? asset : uri;
  }
}

class AudioSourceRequest {
  uri: string = '';
  headers: Record<string, string> = {};
  clipStartMs: number = 0;
  /** 0 表示没有片段终点。 */
  clipEndMs: number = 0;
  loopOne: boolean = false;
  autoPlay: boolean = true;
}

export class AudioPlayerManager implements AudioOutputParticipant {
  private static instance: AudioPlayerManager | null = null;

  static get(): AudioPlayerManager {
    if (AudioPlayerManager.instance === null) {
      AudioPlayerManager.instance = new AudioPlayerManager();
    }
    return AudioPlayerManager.instance;
  }

  /** 事件广播器：SDAudioPlayerModule.addEventListener 把页面的常驻回调挂在这里。 */
  readonly events: EventBroadcaster = new EventBroadcaster();

  private player: media.AVPlayer | null = null;
  private file: fs.File | null = null;
  private request: AudioSourceRequest | null = null;
  /** 每次换源 / 停止 / 释放自增；异步回调里与之比较，过期即丢弃。 */
  private generation: number = 0;
  /** 串行化「释放旧播放器 → 创建新播放器」。 */
  private chain: Promise<void> = Promise.resolve();

  private phase: string = PHASE_IDLE;
  private avState: string = 'idle';
  private prepared: boolean = false;
  private playIntent: boolean = false;
  private durationRaw: number = 0;
  private positionRaw: number = 0;
  private bufferedRaw: number = 0;
  private volume: number = 1;
  private speed: number = 1;
  private loopOne: boolean = false;
  private clipStart: number = 0;
  private clipEnd: number = 0;
  /** prepare 完成前收到的 seek（原始时间轴）。 */
  private pendingSeekRaw: number = -1;
  /** 从 completed 状态重新 play 后需要回到的位置（原始时间轴）。 */
  private restartSeekRaw: number = -1;
  private clipEndHandled: boolean = false;

  private prepareReply: SDReply | null = null;
  private prepareTimer: number = -1;
  private watchdogTimer: number = -1;
  private lastPositionEmit: number = 0;
  private lastStateKey: string = '';
  /** 被应用内其它有声播放器抢占而暂停：期间忽略系统打断事件，避免 Kuikly 侧误恢复。 */
  private handoffPaused: boolean = false;
  /** 最近一次「非用户发起」的暂停时间（用于给 interruption 事件补 wasPlaying）。 */
  private systemPausedAt: number = 0;
  private userPauseRequested: boolean = false;

  private constructor() {
    AudioOutputCoordinator.attach(this);
  }

  // ---------------------------------------------------------------------------------------------
  // AudioOutputParticipant
  // ---------------------------------------------------------------------------------------------

  isAudiblePlaying(): boolean {
    return this.playIntent && this.volume > 0;
  }

  pauseForHandoff(): void {
    this.pause();
    this.handoffPaused = true;
  }

  // ---------------------------------------------------------------------------------------------
  // 对外接口（全部在主线程调用）
  // ---------------------------------------------------------------------------------------------

  /** params: {uri, headers?, clipStartMs?, clipEndMs?, loopOne, autoPlay, preload?} → {ok, durationMs?} */
  setSource(p: SDParams, reply: SDReply): void {
    const uri: string = SD.str(p, 'uri');
    if (uri.length === 0) {
      reply.fail('uri is required');
      return;
    }
    const request: AudioSourceRequest = new AudioSourceRequest();
    request.uri = uri;
    request.headers = SD.strMap(p, 'headers');
    request.clipStartMs = Math.max(0, SD.num(p, 'clipStartMs', 0));
    request.clipEndMs = Math.max(0, SD.num(p, 'clipEndMs', 0));
    request.loopOne = SD.bool(p, 'loopOne', false);
    request.autoPlay = SD.bool(p, 'autoPlay', true);
    if (request.clipEndMs > 0 && request.clipEndMs <= request.clipStartMs) {
      reply.fail('invalid clip: clipEndMs must be greater than clipStartMs');
      return;
    }
    if (SD.bool(p, 'preload', true)) {
      this.open(request, reply);
    } else {
      // preload=false（直播 AAC 等）：不等 prepared，设置完音源就回调，缓冲交给播放阶段。
      this.open(request, null);
      reply.ok();
    }
  }

  play(): void {
    this.playIntent = true;
    this.handoffPaused = false;
    this.userPauseRequested = false;
    if (this.phase === PHASE_IDLE) {
      // stop() / 出错之后再次 play：重新加载上一个音源。
      const last: AudioSourceRequest | null = this.request;
      if (last !== null) {
        last.autoPlay = true;
        this.open(last, null);
      } else {
        this.playIntent = false;
      }
      this.emitState();
      return;
    }
    if (this.prepared) {
      this.startPlayback();
    }
    this.emitState();
  }

  pause(): void {
    this.playIntent = false;
    this.userPauseRequested = true;
    const player: media.AVPlayer | null = this.player;
    if (player !== null && this.prepared && this.avState === 'playing') {
      player.pause().catch((e: BusinessError) => {
        SDLog.w(TAG, `pause failed: ${SD.errMsg(e)}`);
      });
    }
    this.stopWatchdog();
    this.emitState();
  }

  /** 停止并释放解码资源；保留音源信息，之后 play() 会重新加载。 */
  stop(): void {
    this.generation += 1;
    this.failPrepare('stopped');
    this.resetPlaybackState();
    this.phase = PHASE_IDLE;
    this.enqueueDispose();
    this.emitState();
    this.emitPosition(true);
  }

  seek(positionMs: number): void {
    let raw: number = this.clipStart + Math.max(0, Number.isFinite(positionMs) ? positionMs : 0);
    const end: number = this.effectiveEndRaw();
    if (end > 0 && raw > end) {
      raw = end;
    }
    this.positionRaw = raw;
    this.clipEndHandled = false;
    const player: media.AVPlayer | null = this.player;
    if (player === null || !this.prepared) {
      this.pendingSeekRaw = raw;
      this.emitPosition(true);
      return;
    }
    if (this.phase === PHASE_COMPLETED) {
      // 播完后拖动：回到「就绪」状态；真正的 completed 状态下 play() 会从头开始，记下要回到的位置。
      this.phase = PHASE_READY;
      if (this.avState === 'completed') {
        this.restartSeekRaw = raw;
      }
    }
    this.seekPlayer(player, raw);
    this.emitPosition(true);
    this.emitState();
  }

  setVolume(volume: number): void {
    let v: number = Number.isFinite(volume) ? volume : 1;
    if (v < 0) {
      v = 0;
    }
    if (v > 1) {
      v = 1;
    }
    const wasSilent: boolean = this.volume <= 0;
    this.volume = v;
    const player: media.AVPlayer | null = this.player;
    if (player !== null && this.prepared) {
      this.applyVolume(player);
    }
    if (wasSilent && v > 0 && this.playIntent) {
      AudioOutputCoordinator.claim(this);
    }
  }

  setSpeed(speed: number): void {
    this.speed = Number.isFinite(speed) && speed > 0 ? speed : 1;
    const player: media.AVPlayer | null = this.player;
    if (player !== null && this.prepared) {
      this.applySpeed(player);
    }
  }

  setLoopOne(on: boolean): void {
    this.loopOne = on;
    const request: AudioSourceRequest | null = this.request;
    if (request !== null) {
      request.loopOne = on;
    }
    const player: media.AVPlayer | null = this.player;
    if (player !== null && this.prepared) {
      this.applyLoop(player);
    }
  }

  /**
   * 音频焦点：鸿蒙上由 AVPlayer 在 play / pause / release 时隐式申请和归还，
   * 没有与 AVAudioSession.setActive 对应的独立开关，所以这里恒返回成功。
   */
  setSessionActive(active: boolean): boolean {
    return true;
  }

  /** 释放播放器并清空音源。 */
  release(): void {
    this.generation += 1;
    this.failPrepare('released');
    this.request = null;
    this.resetPlaybackState();
    this.clipStart = 0;
    this.clipEnd = 0;
    this.phase = PHASE_IDLE;
    this.enqueueDispose();
    this.emitState();
  }

  /** 同步快照：{playing, positionMs, bufferedMs, durationMs, state} */
  snapshot(): SDResult {
    const player: media.AVPlayer | null = this.player;
    if (player !== null && this.prepared && this.avState === 'playing') {
      // timeUpdate 约 100ms 一次；同步查询时取一次实时值。
      try {
        const now: number = player.currentTime;
        if (now >= 0) {
          this.positionRaw = now;
        }
      } catch (e) {
        // 保留上一次的位置
      }
    }
    const out: SDResult = {};
    out['playing'] = this.playIntent;
    out['positionMs'] = this.positionMs();
    out['bufferedMs'] = this.bufferedMs();
    out['durationMs'] = this.durationMs();
    out['state'] = this.phase;
    return out;
  }

  /** 给刚注册监听的页面补发一次当前状态。 */
  stateEvent(): SDResult {
    const extra: SDResult = {};
    extra['state'] = this.phase;
    extra['playing'] = this.playIntent;
    return SD.event('state', extra);
  }

  // ---------------------------------------------------------------------------------------------
  // 加载
  // ---------------------------------------------------------------------------------------------

  private open(request: AudioSourceRequest, reply: SDReply | null): void {
    this.generation += 1;
    const gen: number = this.generation;
    this.failPrepare('superseded');
    this.request = request;
    this.resetPlaybackState();
    this.loopOne = request.loopOne;
    this.clipStart = request.clipStartMs;
    this.clipEnd = request.clipEndMs;
    this.positionRaw = this.clipStart;
    this.playIntent = request.autoPlay;
    this.phase = PHASE_LOADING;
    this.prepareReply = reply;
    this.emitState();
    this.enqueue(async (): Promise<void> => {
      await this.disposePlayer();
      if (gen !== this.generation) {
        return;
      }
      await this.createPlayer(gen, request);
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

  private async createPlayer(gen: number, request: AudioSourceRequest): Promise<void> {
    let player: media.AVPlayer | null = null;
    try {
      player = await media.createAVPlayer();
    } catch (e) {
      if (gen === this.generation) {
        this.fatal(gen, `createAVPlayer failed: ${SD.errMsg(e as Object)}`);
      }
      return;
    }
    if (gen !== this.generation) {
      await AVPlayerSupport.releaseQuietly(player);
      return;
    }
    this.player = player;
    this.avState = 'idle';
    this.bind(player, gen);
    this.prepareTimer = setTimeout(() => {
      this.prepareTimer = -1;
      if (gen === this.generation && !this.prepared) {
        this.fatal(gen, 'prepare timeout');
      }
    }, PREPARE_TIMEOUT_MS);
    try {
      if (MediaSupport.isHttp(request.uri)) {
        if (Object.keys(request.headers).length > 0) {
          await player.setMediaSource(media.createMediaSourceWithUrl(request.uri, request.headers));
        } else {
          player.url = request.uri;
        }
      } else {
        const file: fs.File = fs.openSync(AVPlayerSupport.resolveLocal(request.uri), fs.OpenMode.READ_ONLY);
        this.file = file;
        const size: number = fs.statSync(file.fd).size;
        const source: media.AVFileDescriptor = { fd: file.fd, offset: 0, length: size };
        player.fdSrc = source;
      }
    } catch (e) {
      if (gen === this.generation) {
        this.fatal(gen, `open source failed: ${SD.errMsg(e as Object)}`);
      }
    }
  }

  private bind(player: media.AVPlayer, gen: number): void {
    player.on('stateChange', (state: string, reason: media.StateChangeReason) => {
      if (gen !== this.generation) {
        return;
      }
      this.onStateChange(player, gen, state, reason);
    });
    player.on('error', (err: BusinessError) => {
      if (gen !== this.generation) {
        return;
      }
      this.fatal(gen, SD.errMsg(err));
    });
    player.on('durationUpdate', (duration: number) => {
      if (gen !== this.generation || !(duration > 0)) {
        return;
      }
      if (duration !== this.durationRaw) {
        this.durationRaw = duration;
        this.emitDuration();
      }
    });
    player.on('timeUpdate', (time: number) => {
      if (gen !== this.generation || !this.prepared || this.phase === PHASE_COMPLETED) {
        return;
      }
      this.positionRaw = time;
      this.checkClipEnd(player);
      this.emitPosition(false);
    });
    player.on('seekDone', (time: number) => {
      if (gen !== this.generation) {
        return;
      }
      if (this.phase !== PHASE_COMPLETED) {
        this.positionRaw = time;
        this.clipEndHandled = false;
      }
      this.emitPosition(true);
    });
    player.on('bufferingUpdate', (infoType: media.BufferingInfoType, value: number) => {
      if (gen !== this.generation) {
        return;
      }
      this.onBuffering(infoType, value);
    });
    player.on('endOfStream', () => {
      if (gen !== this.generation) {
        return;
      }
      // loop = true 时播放器自己回到 0 继续播；带片段起点时要回到片段起点。
      if (this.loopOne && this.clipStart > 0) {
        this.seekPlayer(player, this.clipStart);
      }
    });
    player.on('audioInterrupt', (info: audio.InterruptEvent) => {
      if (gen !== this.generation) {
        return;
      }
      this.onInterrupt(info);
    });
    player.on('audioOutputDeviceChangeWithInfo', (info: audio.AudioStreamDeviceChangeInfo) => {
      if (gen !== this.generation) {
        return;
      }
      if (info.changeReason === audio.AudioStreamDeviceChangeReason.REASON_OLD_DEVICE_UNAVAILABLE) {
        this.events.emit(SD.event('becomingNoisy'));
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
      player.off('endOfStream');
      player.off('audioInterrupt');
      player.off('audioOutputDeviceChangeWithInfo');
    } catch (e) {
      SDLog.w(TAG, `off failed: ${SD.errMsg(e as Object)}`);
    }
  }

  private async disposePlayer(): Promise<void> {
    const player: media.AVPlayer | null = this.player;
    const file: fs.File | null = this.file;
    this.player = null;
    this.file = null;
    this.prepared = false;
    this.avState = 'idle';
    this.stopWatchdog();
    this.clearPrepareTimer();
    if (player !== null) {
      this.unbind(player);
      await AVPlayerSupport.releaseQuietly(player);
    }
    MediaSupport.closeQuietly(file);
  }

  // ---------------------------------------------------------------------------------------------
  // AVPlayer 事件
  // ---------------------------------------------------------------------------------------------

  private onStateChange(player: media.AVPlayer, gen: number, state: string, reason: media.StateChangeReason): void {
    this.avState = state;
    switch (state) {
      case 'initialized':
        try {
          const info: audio.AudioRendererInfo = { usage: audio.StreamUsage.STREAM_USAGE_MUSIC, rendererFlags: 0 };
          player.audioRendererInfo = info;
        } catch (e) {
          SDLog.w(TAG, `audioRendererInfo: ${SD.errMsg(e as Object)}`);
        }
        player.prepare().catch((e: BusinessError) => {
          if (gen === this.generation) {
            this.fatal(gen, `prepare failed: ${SD.errMsg(e)}`);
          }
        });
        break;
      case 'prepared':
        if (!this.prepared) {
          this.onPrepared(player);
        }
        break;
      case 'playing':
        if (!this.playIntent) {
          // play() 发出后、进入 playing 之前用户又点了暂停：此时才能真正暂停。
          this.userPauseRequested = true;
          player.pause().catch((e: BusinessError) => {
            SDLog.w(TAG, `late pause failed: ${SD.errMsg(e)}`);
          });
          break;
        }
        if (this.phase === PHASE_COMPLETED || this.phase === PHASE_LOADING) {
          this.phase = PHASE_READY;
        }
        if (this.restartSeekRaw >= 0) {
          const target: number = this.restartSeekRaw;
          this.restartSeekRaw = -1;
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
        this.startWatchdog(player, gen);
        this.emitState();
        break;
      case 'paused':
        if (!this.userPauseRequested && reason === media.StateChangeReason.BACKGROUND) {
          this.systemPausedAt = Date.now();
        }
        this.userPauseRequested = false;
        this.playIntent = false;
        this.stopWatchdog();
        this.emitState();
        this.emitPosition(true);
        break;
      case 'completed':
        this.onCompleted();
        break;
      case 'stopped':
        this.playIntent = false;
        this.stopWatchdog();
        this.emitState();
        break;
      default:
        break;
    }
  }

  private onPrepared(player: media.AVPlayer): void {
    this.prepared = true;
    this.clearPrepareTimer();
    let duration: number = 0;
    try {
      duration = player.duration;
    } catch (e) {
      duration = 0;
    }
    this.durationRaw = duration > 0 ? duration : 0;
    const request: AudioSourceRequest | null = this.request;
    if (request !== null && !MediaSupport.isHttp(request.uri)) {
      this.bufferedRaw = this.durationRaw; // 本地文件：视为已全部缓冲
    }
    this.applyVolume(player);
    this.applyLoop(player);
    if (this.speed !== 1) {
      this.applySpeed(player);
    }
    let start: number = this.pendingSeekRaw >= 0 ? this.pendingSeekRaw : this.clipStart;
    this.pendingSeekRaw = -1;
    if (this.durationRaw > 0 && start > this.durationRaw) {
      start = this.durationRaw;
    }
    this.positionRaw = start;
    if (start > 0) {
      this.seekPlayer(player, start);
    }
    this.phase = PHASE_READY;
    this.emitDuration();
    this.emitState();
    this.emitPosition(true);

    const reply: SDReply | null = this.prepareReply;
    this.prepareReply = null;
    if (reply !== null) {
      const extra: SDResult = {};
      extra['durationMs'] = this.durationMs();
      reply.ok(extra);
    }
    if (this.playIntent) {
      this.startPlayback();
    }
  }

  private onCompleted(): void {
    this.stopWatchdog();
    this.playIntent = false;
    this.phase = PHASE_COMPLETED;
    const end: number = this.effectiveEndRaw();
    if (end > 0) {
      this.positionRaw = end;
    }
    this.emitPosition(true);
    this.emitState();
  }

  private onBuffering(infoType: media.BufferingInfoType, value: number): void {
    switch (infoType) {
      case media.BufferingInfoType.BUFFERING_START:
        if (this.phase === PHASE_READY) {
          this.phase = PHASE_BUFFERING;
          this.emitState();
        }
        break;
      case media.BufferingInfoType.BUFFERING_END:
        if (this.phase === PHASE_BUFFERING) {
          this.phase = PHASE_READY;
          this.emitState();
        }
        break;
      case media.BufferingInfoType.CACHED_DURATION: {
        // value：当前播放位置之后已缓存的时长（毫秒）。
        let buffered: number = this.positionRaw + (value > 0 ? value : 0);
        if (this.durationRaw > 0 && buffered > this.durationRaw) {
          buffered = this.durationRaw;
        }
        this.bufferedRaw = buffered;
        break;
      }
      case media.BufferingInfoType.BUFFERING_PERCENT:
        if (value >= 100 && this.durationRaw > 0) {
          this.bufferedRaw = this.durationRaw;
        }
        break;
      default:
        break;
    }
  }

  private onInterrupt(info: audio.InterruptEvent): void {
    if (this.handoffPaused) {
      return;
    }
    const forced: boolean = info.forceType === audio.InterruptForceType.INTERRUPT_FORCE;
    let begin: boolean = true;
    let type: string = 'unknown';
    switch (info.hintType) {
      case audio.InterruptHint.INTERRUPT_HINT_PAUSE:
        type = 'pause';
        break;
      case audio.InterruptHint.INTERRUPT_HINT_STOP:
        type = 'unknown'; // 永久失去焦点，与 Android 的 AUDIOFOCUS_LOSS 对应
        break;
      case audio.InterruptHint.INTERRUPT_HINT_RESUME:
        begin = false;
        type = 'pause';
        break;
      case audio.InterruptHint.INTERRUPT_HINT_DUCK:
        if (forced) {
          return;
        }
        type = 'duck';
        break;
      case audio.InterruptHint.INTERRUPT_HINT_UNDUCK:
        if (forced) {
          return;
        }
        begin = false;
        type = 'duck';
        break;
      default:
        return;
    }
    const extra: SDResult = {};
    extra['begin'] = begin;
    extra['type'] = type;
    extra['forced'] = forced;
    // stateChange(paused) 可能先于本事件到达，补一个「打断前是否在播放」供 Kuikly 侧判断是否要恢复。
    extra['wasPlaying'] = this.playIntent || (Date.now() - this.systemPausedAt < 1500);
    this.events.emit(SD.event('interruption', extra));
  }

  /** 加载 / 播放出错：结束挂起的 setSource、上报 error、回到 idle 并释放播放器。 */
  private fatal(gen: number, message: string): void {
    if (gen !== this.generation) {
      return;
    }
    SDLog.e(TAG, message);
    this.generation += 1;
    this.failPrepare(message);
    const extra: SDResult = {};
    extra['msg'] = message;
    this.events.emit(SD.event('error', extra));
    this.resetPlaybackState();
    this.phase = PHASE_IDLE;
    this.enqueueDispose();
    this.emitState();
  }

  // ---------------------------------------------------------------------------------------------
  // 播放控制细节
  // ---------------------------------------------------------------------------------------------

  private startPlayback(): void {
    const player: media.AVPlayer | null = this.player;
    if (player === null || !this.prepared) {
      return;
    }
    if (this.volume > 0) {
      AudioOutputCoordinator.claim(this);
    }
    if (this.phase === PHASE_COMPLETED) {
      this.phase = PHASE_READY;
      this.clipEndHandled = false;
      this.positionRaw = this.clipStart;
      if (this.avState === 'completed') {
        // 真正播完：play() 会从 0 开始，带片段起点时进入 playing 后再 seek。
        this.restartSeekRaw = this.clipStart > 0 ? this.clipStart : -1;
      } else {
        // 片段终点处的「虚拟播完」（播放器其实是 paused）：先回到片段起点。
        this.seekPlayer(player, this.clipStart);
      }
    }
    if (this.avState === 'playing') {
      return;
    }
    const gen: number = this.generation;
    player.play().catch((e: BusinessError) => {
      if (gen === this.generation) {
        this.fatal(gen, `play failed: ${SD.errMsg(e)}`);
      }
    });
  }

  private seekPlayer(player: media.AVPlayer, raw: number): void {
    try {
      player.seek(Math.max(0, Math.round(raw)), media.SeekMode.SEEK_CLOSEST);
    } catch (e) {
      SDLog.w(TAG, `seek failed: ${SD.errMsg(e as Object)}`);
    }
  }

  private applyVolume(player: media.AVPlayer): void {
    try {
      player.setVolume(this.volume);
    } catch (e) {
      SDLog.w(TAG, `setVolume failed: ${SD.errMsg(e as Object)}`);
    }
  }

  private applySpeed(player: media.AVPlayer): void {
    try {
      player.setSpeed(AVPlayerSupport.nearestSpeed(this.speed));
    } catch (e) {
      SDLog.w(TAG, `setSpeed failed: ${SD.errMsg(e as Object)}`);
    }
  }

  private applyLoop(player: media.AVPlayer): void {
    try {
      player.loop = this.loopOne;
    } catch (e) {
      SDLog.w(TAG, `set loop failed: ${SD.errMsg(e as Object)}`);
    }
  }

  /** 片段终点看门狗：循环则回到片段起点，否则暂停并报告 completed。 */
  private checkClipEnd(player: media.AVPlayer): void {
    if (this.clipEnd <= 0 || this.clipEndHandled || this.phase === PHASE_COMPLETED) {
      return;
    }
    if (this.positionRaw < this.clipEnd - CLIP_END_SLACK_MS) {
      return;
    }
    this.clipEndHandled = true; // seekDone 之后才会重新开放
    if (this.loopOne) {
      this.positionRaw = this.clipStart;
      this.seekPlayer(player, this.clipStart);
      return;
    }
    this.userPauseRequested = true;
    player.pause().catch((e: BusinessError) => {
      SDLog.w(TAG, `pause at clip end failed: ${SD.errMsg(e)}`);
    });
    this.onCompleted();
  }

  private startWatchdog(player: media.AVPlayer, gen: number): void {
    this.stopWatchdog();
    if (this.clipEnd <= 0) {
      return;
    }
    this.watchdogTimer = setInterval(() => {
      if (gen !== this.generation || this.avState !== 'playing') {
        return;
      }
      try {
        const now: number = player.currentTime;
        if (now >= 0) {
          this.positionRaw = now;
        }
      } catch (e) {
        return;
      }
      this.checkClipEnd(player);
    }, WATCHDOG_INTERVAL_MS);
  }

  private stopWatchdog(): void {
    if (this.watchdogTimer >= 0) {
      clearInterval(this.watchdogTimer);
      this.watchdogTimer = -1;
    }
  }

  private clearPrepareTimer(): void {
    if (this.prepareTimer >= 0) {
      clearTimeout(this.prepareTimer);
      this.prepareTimer = -1;
    }
  }

  private failPrepare(reason: string): void {
    const reply: SDReply | null = this.prepareReply;
    this.prepareReply = null;
    if (reply !== null) {
      reply.fail(reason);
    }
  }

  private resetPlaybackState(): void {
    this.playIntent = false;
    this.durationRaw = 0;
    this.positionRaw = this.clipStart;
    this.bufferedRaw = 0;
    this.pendingSeekRaw = -1;
    this.restartSeekRaw = -1;
    this.clipEndHandled = false;
    this.handoffPaused = false;
    this.userPauseRequested = false;
    this.stopWatchdog();
    this.clearPrepareTimer();
  }

  // ---------------------------------------------------------------------------------------------
  // 片段相对时间 & 事件
  // ---------------------------------------------------------------------------------------------

  /** 片段在原始时间轴上的终点；时长未知且没有 clipEnd 时为 0。 */
  private effectiveEndRaw(): number {
    if (this.clipEnd > 0) {
      return this.durationRaw > 0 ? Math.min(this.clipEnd, this.durationRaw) : this.clipEnd;
    }
    return this.durationRaw;
  }

  private durationMs(): number {
    const end: number = this.effectiveEndRaw();
    return end > this.clipStart ? Math.round(end - this.clipStart) : 0;
  }

  private positionMs(): number {
    let pos: number = this.positionRaw - this.clipStart;
    const duration: number = this.durationMs();
    if (duration > 0 && pos > duration) {
      pos = duration;
    }
    return pos > 0 ? Math.round(pos) : 0;
  }

  private bufferedMs(): number {
    let buffered: number = this.bufferedRaw - this.clipStart;
    const duration: number = this.durationMs();
    if (duration > 0 && buffered > duration) {
      buffered = duration;
    }
    return buffered > 0 ? Math.round(buffered) : 0;
  }

  private emitState(): void {
    const key: string = `${this.phase}|${this.playIntent}`;
    if (key === this.lastStateKey) {
      return;
    }
    this.lastStateKey = key;
    this.events.emit(this.stateEvent());
  }

  private emitDuration(): void {
    const extra: SDResult = {};
    extra['durationMs'] = this.durationMs();
    this.events.emit(SD.event('duration', extra));
  }

  private emitPosition(force: boolean): void {
    const now: number = Date.now();
    if (!force && now - this.lastPositionEmit < POSITION_INTERVAL_MS) {
      return;
    }
    this.lastPositionEmit = now;
    const extra: SDResult = {};
    extra['positionMs'] = this.positionMs();
    extra['bufferedMs'] = this.bufferedMs();
    extra['durationMs'] = this.durationMs();
    this.events.emit(SD.event('position', extra));
  }
}
