package com.sondo.app.service

/** 作品库 Tab 刷新回调。 */
typealias LibraryTabRefreshFn = suspend (isCreate: Boolean) -> Unit

/**
 * 作品库 Tab 刷新桥：由作品库页面注册回调，其它模块只依赖本 Service。
 * 回调在调用方协程里执行；作品库页面销毁时必须 [unbind]。
 */
object LibraryTabRefreshService {
    private var refreshMvFn: LibraryTabRefreshFn? = null
    private var refreshSongFn: LibraryTabRefreshFn? = null

    fun init() {}

    fun bind(refreshMv: LibraryTabRefreshFn, refreshSong: LibraryTabRefreshFn) {
        refreshMvFn = refreshMv
        refreshSongFn = refreshSong
    }

    fun unbind() {
        refreshMvFn = null
        refreshSongFn = null
    }

    val isBound: Boolean get() = refreshMvFn != null || refreshSongFn != null

    suspend fun refreshMv(isCreate: Boolean = false) {
        refreshMvFn?.invoke(isCreate)
    }

    suspend fun refreshSong(isCreate: Boolean = false) {
        refreshSongFn?.invoke(isCreate)
    }
}
