package com.sondo.music.video.ai.wxapi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.sondo.music.video.ai.sdk.auth.WechatManager

/**
 * 微信回跳入口。类名和包路径（`<applicationId>.wxapi.WXEntryActivity`）是微信 SDK 写死的约定，不能改。
 *
 * 只做一件事：把回跳 Intent 交给 [WechatManager] 解析成 `wechatAuthResp` 事件，然后立刻关闭。
 * 页面透明且不带转场，用户看到的是从微信直接回到登录页。
 * 进程在微信里被系统回收后回跳时 IWXAPI 还没重建，这次授权随旧页面一起作废，直接关闭即可。
 */
class WXEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forward(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        forward(intent)
    }

    private fun forward(intent: Intent?) {
        try {
            WechatManager.handleIntent(intent)
        } finally {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
