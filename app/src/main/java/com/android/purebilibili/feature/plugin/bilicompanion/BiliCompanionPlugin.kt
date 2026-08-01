package com.android.purebilibili.feature.plugin.bilicompanion

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.purebilibili.core.plugin.DanmakuItem
import com.android.purebilibili.core.plugin.DanmakuPlugin
import com.android.purebilibili.core.plugin.DanmakuStyle
import com.android.purebilibili.core.plugin.PluginCapability
import com.android.purebilibili.core.plugin.PluginCapabilityManifest
import com.android.purebilibili.core.plugin.PluginManager
import com.android.purebilibili.core.plugin.PluginStore
import com.android.purebilibili.core.ui.components.AppSwitchPreference
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.util.Logger
import io.github.alexzhirkevich.cupertino.icons.CupertinoIcons
import io.github.alexzhirkevich.cupertino.icons.outlined.Sparkles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "BiliCompanion"
private const val DANMAKU_SAMPLE_MS = 180L

@OptIn(FlowPreview::class)
class BiliCompanionPlugin : DanmakuPlugin {
    override val id: String = "bili_companion"
    override val name: String = "Bili-Companion"
    override val description: String = "应用内嵌入式弹幕互动电子桌宠"
    override val version: String = "1.0.0"
    override val author: String = "BiliPai项目组"
    override val capabilityManifest: PluginCapabilityManifest = PluginCapabilityManifest(
        pluginId = id,
        displayName = name,
        version = version,
        apiVersion = 1,
        entryClassName = this::class.java.name,
        capabilities = setOf(
            PluginCapability.DANMAKU_STREAM,
            PluginCapability.DANMAKU_MUTATION,
            PluginCapability.PLAYER_STATE,
            PluginCapability.PLUGIN_STORAGE
        )
    )

    private val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val danmakuEvents = MutableSharedFlow<DanmakuItem>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val pendingEventCount = AtomicInteger(0)
    private val lastDanmakuAtMs = AtomicLong(0L)
    private var processingJob: Job? = null
    private var idleJob: Job? = null
    private var config = BiliCompanionConfig()

    override suspend fun onEnable() {
        config = loadConfig()
        BiliCompanionRuntime.setOverlayEnabled(config.overlayEnabled)
        BiliCompanionRuntime.setCompactOnFullscreen(config.compactOnFullscreen)
        BiliCompanionRuntime.setUploadBubbleEnabled(config.showUploadBubble)
        processingJob?.cancel()
        idleJob?.cancel()
        processingJob = pluginScope.launch {
            danmakuEvents
                .sample(DANMAKU_SAMPLE_MS)
                .collectLatest { lastItem ->
                    val eventCount = pendingEventCount.getAndSet(0).coerceAtLeast(1)
                    val lastSignal = BiliCompanionMoodPolicy.signalFor(lastItem.content)
                    BiliCompanionRuntime.acceptDanmakuBatch(
                        lastContent = lastItem.content,
                        signal = CompanionDanmakuSignal(
                            eventCount = eventCount,
                            funnyCount = lastSignal.funnyCount,
                            studyCount = lastSignal.studyCount,
                            eatingCount = lastSignal.eatingCount
                        )
                    )
                }
        }
        idleJob = pluginScope.launch {
            while (isActive) {
                delay(1_200L)
                val lastEvent = lastDanmakuAtMs.get()
                if (lastEvent > 0L && System.currentTimeMillis() - lastEvent > 1_100L) {
                    BiliCompanionRuntime.markIdle()
                }
            }
        }
        Logger.d(TAG, "Bili-Companion已启用，弹幕采样间隔=${DANMAKU_SAMPLE_MS}毫秒")
    }

    override suspend fun onDisable() {
        processingJob?.cancel()
        processingJob = null
        idleJob?.cancel()
        idleJob = null
        pendingEventCount.set(0)
        BiliCompanionRuntime.setOverlayEnabled(false)
        Logger.d(TAG, "Bili-Companion已停用")
    }

    override fun filterDanmaku(danmaku: DanmakuItem): DanmakuItem? {
        lastDanmakuAtMs.set(System.currentTimeMillis())
        pendingEventCount.incrementAndGet()
        danmakuEvents.tryEmit(danmaku)
        return danmaku
    }

    override fun styleDanmaku(danmaku: DanmakuItem): DanmakuStyle? {
        val signal = BiliCompanionMoodPolicy.signalFor(danmaku.content)
        return if (signal.eatingCount > 0) {
            DanmakuStyle(
                textColor = Color(0xFFFFD166),
                bold = true,
                scale = 1.04f
            )
        } else {
            null
        }
    }

    @Composable
    override fun SettingsContent() {
        val configState = remember { kotlinx.coroutines.flow.MutableStateFlow(config) }
        val snapshot by configState.collectAsStateWithLifecycle()
        var uiConfig by remember { mutableStateOf(snapshot) }

        LaunchedEffect(Unit) {
            val loaded = loadConfig()
            config = loaded
            uiConfig = loaded
            configState.value = loaded
            BiliCompanionRuntime.setOverlayEnabled(loaded.overlayEnabled)
            BiliCompanionRuntime.setCompactOnFullscreen(loaded.compactOnFullscreen)
            BiliCompanionRuntime.setUploadBubbleEnabled(loaded.showUploadBubble)
        }

        fun commit(next: BiliCompanionConfig) {
            config = next
            uiConfig = next
            configState.value = next
            BiliCompanionRuntime.setOverlayEnabled(next.overlayEnabled)
            BiliCompanionRuntime.setCompactOnFullscreen(next.compactOnFullscreen)
            BiliCompanionRuntime.setUploadBubbleEnabled(next.showUploadBubble)
            pluginScope.launch(Dispatchers.IO) {
                runCatching {
                    PluginStore.setConfigJson(
                        PluginManager.getContext(),
                        id,
                        Json.encodeToString(next)
                    )
                }.onFailure { Logger.w(TAG, "保存Bili-Companion配置失败", it) }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            AppSwitchPreference(
                icon = CupertinoIcons.Default.Sparkles,
                title = "显示电子桌宠",
                subtitle = "只在BiliPai应用页面内显示，不会出现在系统桌面",
                checked = uiConfig.overlayEnabled,
                onCheckedChange = { commit(uiConfig.copy(overlayEnabled = it)) }
            )
            AppSwitchPreference(
                icon = CupertinoIcons.Default.Sparkles,
                title = "全屏时收起",
                subtitle = "视频全屏或进入画中画时缩成小气泡",
                checked = uiConfig.compactOnFullscreen,
                onCheckedChange = { commit(uiConfig.copy(compactOnFullscreen = it)) }
            )
            AppSwitchPreference(
                icon = CupertinoIcons.Default.Sparkles,
                title = "关注更新提示",
                subtitle = "关注动态出现新视频时显示可点击的中文气泡",
                checked = uiConfig.showUploadBubble,
                onCheckedChange = { commit(uiConfig.copy(showUploadBubble = it)) }
            )
            AppText(
                text = "弹幕会在后台采样处理，电子桌宠只消费自己的触摸区域，其他区域继续交给页面操作。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }

    private suspend fun loadConfig(): BiliCompanionConfig {
        return runCatching {
            PluginStore.getConfigJson(PluginManager.getContext(), id)
                ?.let { Json.decodeFromString<BiliCompanionConfig>(it) }
                ?: BiliCompanionConfig()
        }.getOrDefault(BiliCompanionConfig())
    }
}
