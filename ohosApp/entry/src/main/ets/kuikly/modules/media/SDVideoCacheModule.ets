/*
 * SDVideoCacheModule —— 视频磁盘缓存（配合原生视图 SDVideoView 使用）。
 * 契约见 shared/.../nativeapi/SDMediaModules.kt；缓存规则与实现见 VideoCacheStore。
 *
 *   prefetch({url})        → {ok, localPath?} | {ok:false, error:"too_large"|"unknown_size"|"cancelled"|…}
 *   resolve({url})         → {ok:true, localPath?}        未缓存时不带 localPath
 *   cancelPrefetch({url})  → {ok:true}
 *   clear()                → {ok}
 */
import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { SD, SDParams, SDReply, SDResult } from '../../support/SDModuleSupport';
import { VideoCacheStore } from './VideoCacheStore';

export class SDVideoCacheModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME: string = 'SDVideoCacheModule';

  syncMode(): boolean {
    return false;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    const p: SDParams = SD.parse(params);
    const url: string = SD.str(p, 'url');
    const reply: SDReply = new SDReply(callback);
    switch (method) {
      case 'prefetch':
        if (url.length === 0) {
          reply.fail('url is required');
          break;
        }
        VideoCacheStore.get().prefetch(url).then((path: string) => {
          const extra: SDResult = {};
          extra['localPath'] = path;
          reply.ok(extra);
        }).catch((e: Error) => {
          reply.fail(SD.errMsg(e));
        });
        break;
      case 'resolve': {
        const path: string = url.length > 0 ? VideoCacheStore.get().resolve(url) : '';
        const extra: SDResult = {};
        if (path.length > 0) {
          extra['localPath'] = path;
        }
        reply.ok(extra);
        break;
      }
      case 'cancelPrefetch':
        if (url.length > 0) {
          VideoCacheStore.get().cancel(url);
        }
        reply.ok();
        break;
      case 'clear':
        if (VideoCacheStore.get().clear()) {
          reply.ok();
        } else {
          reply.fail('clear failed');
        }
        break;
      default:
        reply.fail(`unknown method: ${method}`);
        break;
    }
    return null;
  }
}
