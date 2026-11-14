/*
 * TaskPool 入口函数。@Concurrent 函数体内只能使用入参、局部变量和 import 进来的符号，
 * 所以这里每个函数都只是转调 MediaTaskImpl 的静态方法。
 *
 * 用法（主线程）：
 *   const task = new taskpool.Task(sdMuxTask, video, audio, startMs, durationMs, dest, false);
 *   task.onReceiveData((progress: number) => { ... });      // 0..1
 *   const json = (await taskpool.execute(task)) as string;  // {ok, ...}
 *   taskpool.cancel(task);                                   // 任务内部会在下一个检查点中止
 */
import { MediaTaskImpl } from './MediaTaskImpl';

@Concurrent
export function sdRemuxMp3Task(src: string, dest: string): string {
  return MediaTaskImpl.remuxMp3(src, dest);
}

@Concurrent
export function sdTrimAudioTask(src: string, dest: string, startMs: number, endMs: number): string {
  return MediaTaskImpl.trimAudio(src, dest, startMs, endMs);
}

@Concurrent
export function sdMuxTask(videoPath: string, audioPath: string, audioStartMs: number, audioDurationMs: number,
  dest: string, allowMp3Passthrough: boolean): string {
  return MediaTaskImpl.mux(videoPath, audioPath, audioStartMs, audioDurationMs, dest, allowMp3Passthrough);
}

@Concurrent
export function sdWaveformTask(audioPath: string, samplesPerSecond: number): string {
  return MediaTaskImpl.waveform(audioPath, samplesPerSecond);
}

@Concurrent
export function sdInspectTask(path: string): string {
  return MediaTaskImpl.inspect(path);
}
