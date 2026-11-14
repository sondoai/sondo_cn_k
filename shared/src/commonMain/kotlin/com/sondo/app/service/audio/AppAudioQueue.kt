package com.sondo.app.service.audio

import kotlin.random.Random

/** 不可变播放队列：每次编辑返回新队列，currentIndex 始终有效（空队列为 -1）。 */
class AppAudioQueue private constructor(val tracks: List<AppAudioTrack>, val currentIndex: Int) {

    val currentTrack: AppAudioTrack? get() = if (currentIndex < 0) null else tracks[currentIndex]
    val isEmpty: Boolean get() = tracks.isEmpty()
    val isNotEmpty: Boolean get() = tracks.isNotEmpty()
    val length: Int get() = tracks.size

    operator fun get(index: Int): AppAudioTrack = tracks[index]

    fun select(index: Int): AppAudioQueue = AppAudioQueue(tracks, normalizeIndex(tracks.size, index))

    fun append(track: AppAudioTrack): AppAudioQueue = insert(tracks.size, track)

    fun insert(index: Int, track: AppAudioTrack): AppAudioQueue {
        val next = ArrayList(tracks)
        val insertionIndex = index.coerceIn(0, next.size)
        next.add(insertionIndex, track)
        val nextCurrent = when {
            currentIndex < 0 -> 0
            currentIndex >= insertionIndex -> currentIndex + 1
            else -> currentIndex
        }
        return AppAudioQueue(next, nextCurrent)
    }

    fun replaceAt(index: Int, track: AppAudioTrack): AppAudioQueue {
        if (index < 0 || index >= tracks.size) return this
        val next = ArrayList(tracks)
        next[index] = track
        return AppAudioQueue(next, currentIndex)
    }

    fun removeAt(index: Int): AppAudioQueue {
        if (index < 0 || index >= tracks.size) return this
        val next = ArrayList(tracks)
        next.removeAt(index)
        if (next.isEmpty()) return EMPTY
        var nextCurrent = currentIndex
        if (index < currentIndex) {
            nextCurrent--
        } else if (index == currentIndex && nextCurrent >= next.size) {
            nextCurrent = next.size - 1
        }
        return AppAudioQueue(next, nextCurrent)
    }

    fun move(oldIndex: Int, newIndex: Int): AppAudioQueue {
        if (oldIndex < 0 || oldIndex >= tracks.size || tracks.size < 2) return this
        val next = ArrayList(tracks)
        val boundedIndex = newIndex.coerceIn(0, next.size - 1)
        val track = next.removeAt(oldIndex)
        next.add(boundedIndex, track)
        var nextCurrent = currentIndex
        if (currentIndex == oldIndex) {
            nextCurrent = boundedIndex
        } else if (oldIndex < currentIndex && boundedIndex >= currentIndex) {
            nextCurrent--
        } else if (oldIndex > currentIndex && boundedIndex <= currentIndex) {
            nextCurrent++
        }
        return AppAudioQueue(next, nextCurrent)
    }

    fun indexOfId(id: String): Int = tracks.indexOfFirst { it.id == id }

    fun clear(): AppAudioQueue = EMPTY

    companion object {
        val EMPTY = AppAudioQueue(emptyList(), -1)

        fun of(tracks: List<AppAudioTrack>, currentIndex: Int = 0): AppAudioQueue {
            val snapshot = ArrayList(tracks)
            return AppAudioQueue(snapshot, normalizeIndex(snapshot.size, currentIndex))
        }

        private fun normalizeIndex(length: Int, index: Int): Int {
            if (length == 0) return -1
            return index.coerceIn(0, length - 1)
        }
    }
}

/** 队列导航策略。 */
interface AppAudioPlaybackStrategy {
    val mode: AppAudioPlayMode
    fun nextIndex(queue: AppAudioQueue, random: Random): Int
    fun previousIndex(queue: AppAudioQueue, random: Random): Int

    /** 播放完成后是否重播当前轨道。 */
    val restartCurrentOnCompletion: Boolean
}

object AppAudioLoopStrategy : AppAudioPlaybackStrategy {
    override val mode = AppAudioPlayMode.loop
    override val restartCurrentOnCompletion = false

    override fun nextIndex(queue: AppAudioQueue, random: Random): Int {
        if (queue.isEmpty) return -1
        return (queue.currentIndex + 1) % queue.length
    }

    override fun previousIndex(queue: AppAudioQueue, random: Random): Int {
        if (queue.isEmpty) return -1
        return if (queue.currentIndex <= 0) queue.length - 1 else queue.currentIndex - 1
    }
}

object AppAudioSingleStrategy : AppAudioPlaybackStrategy {
    override val mode = AppAudioPlayMode.single
    override val restartCurrentOnCompletion = true
    override fun nextIndex(queue: AppAudioQueue, random: Random): Int = AppAudioLoopStrategy.nextIndex(queue, random)
    override fun previousIndex(queue: AppAudioQueue, random: Random): Int =
        AppAudioLoopStrategy.previousIndex(queue, random)
}

object AppAudioRandomStrategy : AppAudioPlaybackStrategy {
    override val mode = AppAudioPlayMode.random
    override val restartCurrentOnCompletion = false
    override fun nextIndex(queue: AppAudioQueue, random: Random): Int = randomIndex(queue, random)
    override fun previousIndex(queue: AppAudioQueue, random: Random): Int = randomIndex(queue, random)

    /** 与当前不同的随机下标；无历史。 */
    private fun randomIndex(queue: AppAudioQueue, random: Random): Int {
        if (queue.isEmpty) return -1
        if (queue.length <= 1) return queue.currentIndex
        var index: Int
        do {
            index = random.nextInt(queue.length)
        } while (index == queue.currentIndex)
        return index
    }
}

object AppAudioPlaybackStrategyFactory {
    fun create(mode: AppAudioPlayMode): AppAudioPlaybackStrategy = when (mode) {
        AppAudioPlayMode.loop -> AppAudioLoopStrategy
        AppAudioPlayMode.single -> AppAudioSingleStrategy
        AppAudioPlayMode.random -> AppAudioRandomStrategy
    }
}
