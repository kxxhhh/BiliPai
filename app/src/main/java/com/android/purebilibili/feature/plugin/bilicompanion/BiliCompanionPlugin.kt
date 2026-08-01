package com.android.purebilibili.feature.plugin.bilicompanion

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.purebilibili.core.plugin.DanmakuItem
import com.android.purebilibili.core.plugin.DanmakuPlugin
import com.android.purebilibili.core.plugin.DanmakuStyle
import com.android.purebilibili.core.plugin.PluginCapability
import com.android.purebilibili.core.plugin.PluginCapabilityManifest
import com.android.purebilibili.core.plugin.PluginManager
import com.android.purebilibili.core.plugin.PluginStore
import com.android.purebilibili.core.ui.components.AppButton
import com.android.purebilibili.core.ui.components.AppDropdownMenu
import com.android.purebilibili.core.ui.components.AppDropdownMenuItem
import com.android.purebilibili.core.ui.components.AppOutlinedTextField
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
import kotlinx.coroutines.withContext
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
            PluginCapability.PLUGIN_STORAGE,
            PluginCapability.NETWORK
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
        BiliCompanionRuntime.setWatchReminder(config.watchReminderEnabled, config.watchReminderMinutes)
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
                            eatingCount = lastSignal.eatingCount,
                            specialReaction = lastSignal.specialReaction
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
        var providerMenuExpanded by remember { mutableStateOf(false) }
        var apiKeyInput by remember { mutableStateOf("") }
        var apiKeySaved by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            val loaded = loadConfig()
            config = loaded
            uiConfig = loaded
            configState.value = loaded
            BiliCompanionRuntime.setOverlayEnabled(loaded.overlayEnabled)
            BiliCompanionRuntime.setCompactOnFullscreen(loaded.compactOnFullscreen)
            BiliCompanionRuntime.setUploadBubbleEnabled(loaded.showUploadBubble)
            BiliCompanionRuntime.setWatchReminder(loaded.watchReminderEnabled, loaded.watchReminderMinutes)
            apiKeySaved = withContext(Dispatchers.IO) {
                BiliCompanionAiSecretStore.read(PluginManager.getContext()).isNotBlank()
            }
        }

        fun commit(next: BiliCompanionConfig) {
            config = next
            uiConfig = next
            configState.value = next
            BiliCompanionRuntime.setOverlayEnabled(next.overlayEnabled)
            BiliCompanionRuntime.setCompactOnFullscreen(next.compactOnFullscreen)
            BiliCompanionRuntime.setUploadBubbleEnabled(next.showUploadBubble)
            BiliCompanionRuntime.setWatchReminder(next.watchReminderEnabled, next.watchReminderMinutes)
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
            AppSwitchPreference(
                icon = CupertinoIcons.Default.Sparkles,
                title = "AI 桌宠助手",
                subtitle = "支持 OpenAI 兼容中转站，可翻译标题、总结视频资料和查找评论",
                checked = uiConfig.aiEnabled,
                onCheckedChange = { commit(uiConfig.copy(aiEnabled = it)) }
            )
            if (uiConfig.aiEnabled) {
                Spacer(modifier = Modifier.height(4.dp))
                AppText(
                    text = "AI 供应商",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentSize(Alignment.TopStart)
                ) {
                    AppButton(onClick = { providerMenuExpanded = true }) {
                        AppText(resolveProviderLabel(uiConfig.aiProvider))
                    }
                    AppDropdownMenu(
                        expanded = providerMenuExpanded,
                        onDismissRequest = { providerMenuExpanded = false }
                    ) {
                        BiliCompanionAiProvider.entries.forEach { provider ->
                            AppDropdownMenuItem(
                                text = { AppText(resolveProviderLabel(provider)) },
                                onClick = {
                                    providerMenuExpanded = false
                                    commit(uiConfig.copy(aiProvider = provider))
                                }
                            )
                        }
                    }
                }
                AppOutlinedTextField(
                    value = uiConfig.aiBaseUrl,
                    onValueChange = { commit(uiConfig.copy(aiBaseUrl = it)) },
                    label = { AppText("AI 基础地址") },
                    placeholder = { AppText("例如 https://你的中转站/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                AppOutlinedTextField(
                    value = uiConfig.aiEndpointPath,
                    onValueChange = { commit(uiConfig.copy(aiEndpointPath = it)) },
                    label = { AppText("接口路径") },
                    placeholder = { AppText("chat/completions") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                AppOutlinedTextField(
                    value = uiConfig.aiModel,
                    onValueChange = { commit(uiConfig.copy(aiModel = it)) },
                    label = { AppText("模型名称") },
                    placeholder = { AppText("例如 gpt-4o-mini、deepseek-chat") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                AppOutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { AppText("接口密钥") },
                    placeholder = { AppText(if (apiKeySaved) "已保存 Key，输入新值可替换" else "仅保存在本机") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                AppButton(
                    enabled = apiKeyInput.isNotBlank(),
                    onClick = {
                        val value = apiKeyInput.trim()
                        pluginScope.launch(Dispatchers.IO) {
                            runCatching {
                                BiliCompanionAiSecretStore.write(PluginManager.getContext(), value)
                            }.onSuccess {
                                withContext(Dispatchers.Main) {
                                    apiKeySaved = true
                                    apiKeyInput = ""
                                }
                            }.onFailure {
                                Logger.w(TAG, "保存 AI API Key 失败", it)
                            }
                        }
                    }
                ) { AppText("保存 API Key") }
                AppText(
                    text = "OpenAI 兼容模式可填写 ToolCode 等中转站的 Base URL、接口路径和模型名。API Key 使用 Android Keystore 加密，不写入插件配置 JSON。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                AppOutlinedTextField(
                    value = uiConfig.aiCommentQuery,
                    onValueChange = { commit(uiConfig.copy(aiCommentQuery = it)) },
                    label = { AppText("评论查找偏好") },
                    placeholder = { AppText("例如 最好笑、最有信息量、指出错误") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            AppSwitchPreference(
                icon = CupertinoIcons.Default.Sparkles,
                title = "观看时长提醒",
                subtitle = "连续观看一段时间后由桌宠提醒休息",
                checked = uiConfig.watchReminderEnabled,
                onCheckedChange = { commit(uiConfig.copy(watchReminderEnabled = it)) }
            )
            if (uiConfig.watchReminderEnabled) {
                AppOutlinedTextField(
                    value = uiConfig.watchReminderMinutes.toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.let { commit(uiConfig.copy(watchReminderMinutes = it.coerceIn(5, 240))) }
                    },
                    label = { AppText("提醒分钟数（5-240）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            AppText(
                text = "弹幕会在后台采样处理，电子桌宠只消费自己的触摸区域，其他区域继续交给页面操作。AI 只在你主动点击助手功能时发送当前视频资料。",
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

    private fun resolveProviderLabel(provider: BiliCompanionAiProvider): String = when (provider) {
        BiliCompanionAiProvider.OPENAI_COMPATIBLE -> "OpenAI 兼容（含中转站）"
        BiliCompanionAiProvider.ANTHROPIC -> "Anthropic 消息接口"
        BiliCompanionAiProvider.GEMINI -> "谷歌 Gemini 接口"
    }
}
