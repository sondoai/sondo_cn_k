package com.sondo.app.data.models

/** 播放模式（Dart: lib/data/models/play_mode.dart `PlayMode`）。 */
enum class PlayMode {
    /** 列表循环 */
    loop,

    /** 单曲循环 */
    single,

    /** 随机播放 */
    random;

    /** 播放模式名称（Dart 扩展的 `name`；Kotlin 枚举的 name 已被占用）。 */
    val displayName: String
        get() = when (this) {
            loop -> "列表循环"
            single -> "单曲循环"
            random -> "随机播放"
        }

    /** 循环切换：列表循环 → 单曲循环 → 随机播放 → 列表循环 */
    val next: PlayMode
        get() = when (this) {
            loop -> single
            single -> random
            random -> loop
        }
}
