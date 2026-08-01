package com.android.purebilibili.community.bilicompanion

import com.android.purebilibili.plugin.sdk.DanmakuItem
import com.android.purebilibili.plugin.sdk.DanmakuPluginApi
import com.android.purebilibili.plugin.sdk.DanmakuStyle
import com.android.purebilibili.plugin.sdk.PluginCapability
import com.android.purebilibili.plugin.sdk.PluginCapabilityManifest

class BiliCompanionPlugin : DanmakuPluginApi {
    override val capabilityManifest = PluginCapabilityManifest(
        pluginId = "com.android.purebilibili.community.bili_companion",
        displayName = "Bili-Companion：蓝雪女仆桌宠",
        version = "1.1.0",
        apiVersion = 1,
        entryClassName = "com.android.purebilibili.community.bilicompanion.BiliCompanionPlugin",
        capabilities = setOf(PluginCapability.DANMAKU_STREAM)
    )

    override fun filterDanmaku(danmaku: DanmakuItem): DanmakuItem = danmaku

    override fun styleDanmaku(danmaku: DanmakuItem): DanmakuStyle? {
        val content = danmaku.content.trim().lowercase()
        return when {
            funnyTokens.any(content::contains) -> DanmakuStyle(
                textColor = 0xFFFFC857.toInt(),
                bold = true,
                scale = 1.08f
            )
            studyTokens.any(content::contains) -> DanmakuStyle(
                textColor = 0xFF4D8C8A.toInt(),
                scale = 1.02f
            )
            else -> null
        }
    }

    private companion object {
        val funnyTokens = listOf("哈哈哈哈", "哈哈哈", "前方高能", "awsl", "2333", "笑死", "太草了")
        val studyTokens = listOf("知识", "科普", "教程", "学习", "实验", "原理", "编程", "数学", "历史")
    }
}
