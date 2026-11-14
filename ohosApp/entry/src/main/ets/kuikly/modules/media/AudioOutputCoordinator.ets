/*
 * 应用内「有声输出」互斥（对应 Flutter 参考工程的 AppAudioOutputCoordinator）。
 *
 * 系统音频焦点只管「本应用 vs 其它应用」；应用内部的全局音频播放器（AudioPlayerManager）
 * 和独占模式的视频（SDVideoView，mixWithOthers = 0 且有声）之间由这里协调：
 * 谁开始有声播放，谁先把其它正在有声播放的参与者暂停掉。
 * 静音视频、mixWithOthers = 1 的视频不参与（既不会被暂停，也不会暂停别人）。
 */
export interface AudioOutputParticipant {
  /** 当前是否正在（或即将）有声播放。 */
  isAudiblePlaying(): boolean;

  /** 被别的参与者抢占：暂停，不要自动恢复。 */
  pauseForHandoff(): void;
}

export class AudioOutputCoordinator {
  private static participants: Array<AudioOutputParticipant> = [];

  static attach(participant: AudioOutputParticipant): void {
    if (AudioOutputCoordinator.participants.indexOf(participant) < 0) {
      AudioOutputCoordinator.participants.push(participant);
    }
  }

  static detach(participant: AudioOutputParticipant): void {
    const index: number = AudioOutputCoordinator.participants.indexOf(participant);
    if (index >= 0) {
      AudioOutputCoordinator.participants.splice(index, 1);
    }
  }

  /** owner 即将开始有声播放：暂停其它所有正在有声播放的参与者。 */
  static claim(owner: AudioOutputParticipant): void {
    const snapshot: Array<AudioOutputParticipant> = AudioOutputCoordinator.participants.slice();
    for (let i = 0; i < snapshot.length; i++) {
      const other: AudioOutputParticipant = snapshot[i];
      if (other === owner) {
        continue;
      }
      let audible: boolean = false;
      try {
        audible = other.isAudiblePlaying();
      } catch (e) {
        audible = false;
      }
      if (audible) {
        try {
          other.pauseForHandoff();
        } catch (e) {
          // 单个参与者出错不影响其它参与者
        }
      }
    }
  }
}
