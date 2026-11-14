package com.sondo.app.core.http

import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.storage.Sp

/** 登录、退出与换号的代次。刷新同一账号的凭证不会改变代次。 */
object AuthSession {
    var revision: Int = 0
        private set

    val accessToken: String get() = Sp.getString(StorageKey.token)
    val refreshToken: String get() = Sp.getString(StorageKey.refreshToken)
    val isLoggedIn: Boolean get() = accessToken.isNotEmpty()

    fun isCurrent(rev: Int): Boolean = rev == revision

    fun replace(accessToken: String, refreshToken: String) {
        revision++
        write(accessToken, refreshToken)
    }

    fun clear() {
        revision++
        Sp.remove(StorageKey.token)
        Sp.remove(StorageKey.refreshToken)
    }

    /** 旧刷新响应不得恢复已退出的会话，也不得覆盖换号后的凭证。 */
    fun commitRefresh(rev: Int, previousRefreshToken: String, accessToken: String, refreshToken: String): Boolean {
        if (!isCurrent(rev) || this.refreshToken != previousRefreshToken ||
            accessToken.trim().isEmpty() || refreshToken.trim().isEmpty()
        ) return false
        write(accessToken, refreshToken)
        return true
    }

    private fun write(accessToken: String, refreshToken: String) {
        Sp.setString(StorageKey.token, accessToken)
        if (refreshToken.isEmpty()) Sp.remove(StorageKey.refreshToken) else Sp.setString(StorageKey.refreshToken, refreshToken)
    }
}
