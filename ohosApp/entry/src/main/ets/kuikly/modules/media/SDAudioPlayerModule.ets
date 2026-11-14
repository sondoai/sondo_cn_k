/*
 * SDAudioPlayerModule —— 全局音频播放器的 Kuikly 模块入口。
 * 契约见 shared/.../nativeapi/SDMediaModules.kt；真正的播放器是进程内单例 AudioPlayerManager，
 * 本模块每个页面一个实例，只负责参数解析和把页面的常驻回调挂到广播器上。
 *
 * getState 是同步方法：Kuikly 线程会阻塞等待主线程返回，这里只读内存里的快照，不做任何耗时操作。
 */
import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { SD, SDParams, SDReply, SDResult } from '../../support/SDModuleSupport';
import { AudioPlayerManager } from './AudioPlayerManager';

export class SDAudioPlayerModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME: string = 'SDAudioPlayerModule';

  syncMode(): boolean {
    return true;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    const manager: AudioPlayerManager = AudioPlayerManager.get();
    const p: SDParams = SD.parse(params);
    switch (method) {
      case 'setSource':
        manager.setSource(p, new SDReply(callback));
        return null;
      case 'play':
        manager.play();
        break;
      case 'pause':
        manager.pause();
        break;
      case 'stop':
        manager.stop();
        break;
      case 'seek':
        manager.seek(SD.num(p, 'positionMs', 0));
        break;
      case 'setVolume':
        manager.setVolume(SD.num(p, 'volume', 1));
        break;
      case 'setSpeed':
        manager.setSpeed(SD.num(p, 'speed', 1));
        break;
      case 'setLoopOne':
        manager.setLoopOne(SD.bool(p, 'on', false));
        break;
      case 'getState':
        return SD.json(manager.snapshot());
      case 'setSessionActive': {
        const active: boolean = manager.setSessionActive(SD.bool(p, 'active', true));
        const result: SDResult = active ? SD.ok() : SD.fail('audio focus request failed');
        SD.send(callback, result);
        return null;
      }
      case 'release':
        manager.release();
        break;
      case 'addEventListener':
        if (callback !== null) {
          manager.events.add(this, callback);
          // 新页面注册后立即同步一次当前状态，避免等到下一次状态变化。
          manager.events.emitTo(this, manager.stateEvent());
        }
        return null;
      default:
        SD.send(callback, SD.fail(`unknown method: ${method}`));
        return null;
    }
    SD.send(callback, SD.ok());
    return null;
  }

  onDestroy(): void {
    AudioPlayerManager.get().events.removeOwner(this);
  }
}
