package com.android.purebilibili.feature.plugin.bilicompanion

import com.android.purebilibili.data.model.response.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.util.LinkedHashMap

internal enum class CompanionMood {
    PARTY,
    STUDY,
    BORED
}

internal data class CompanionDanmakuSignal(
    val eventCount: Int,
    val funnyCount: Int,
    val studyCount: Int,
    val eatingCount: Int,
    val playing: Boolean = true
)

internal data class BiliCompanionState(
    val mood: CompanionMood = CompanionMood.BORED,
    val experience: Int = 0,
    val fullness: Float = 0.08f,
    val densityPerMinute: Int = 0,
    val isEating: Boolean = false,
    val isCompact: Boolean = false,
    val isFullscreen: Boolean = false,
    val fingerFollow: Boolean = false,
    val scale: Float = 1f,
    val speech: String = "陪你看视频",
    val speechBvid: String? = null,
    val overlayEnabled: Boolean = true,
    val compactOnFullscreen: Boolean = true,
    val uploadBubbleEnabled: Boolean = true
)

internal object BiliCompanionMoodPolicy {
    private val funnyTokens = listOf(
        "哈哈哈哈", "哈哈哈", "前方高能", "awsl", "2333", "笑死", "蚌埠住了", "太草了"
    )
    private val studyTokens = listOf(
        "知识", "科普", "教程", "学习", "实验", "原理", "考试", "编程", "数学", "历史", "论文"
    )

    fun signalFor(content: String): CompanionDanmakuSignal {
        val normalized = content.trim().lowercase()
        val funny = if (funnyTokens.any(normalized::contains)) 1 else 0
        val study = if (studyTokens.any(normalized::contains)) 1 else 0
        val eating = if (funny == 1 || normalized.contains("666") || normalized.contains("好耶")) 1 else 0
        return CompanionDanmakuSignal(
            eventCount = 1,
            funnyCount = funny,
            studyCount = study,
            eatingCount = eating
        )
    }

    fun resolveMood(signal: CompanionDanmakuSignal): CompanionMood {
        if (!signal.playing || signal.eventCount <= 0) return CompanionMood.BORED
        if (signal.funnyCount >= 1 || signal.eventCount >= 16) return CompanionMood.PARTY
        if (signal.studyCount >= 1) return CompanionMood.STUDY
        return if (signal.eventCount < 2) CompanionMood.BORED else CompanionMood.STUDY
    }
}

@Serializable
internal data class BiliCompanionConfig(
    val overlayEnabled: Boolean = true,
    val compactOnFullscreen: Boolean = true,
    val showUploadBubble: Boolean = true
)

internal data class CompanionUploadNotice(
    val bvid: String,
    val title: String,
    val authorName: String
)

internal object BiliCompanionRuntime {
    private const val MAX_REMEMBERED_BVIDS = 240
    private val _state = MutableStateFlow(BiliCompanionState())
    val state: StateFlow<BiliCompanionState> = _state.asStateFlow()

    private val seenFollowedVideos = object : LinkedHashMap<String, Long>(MAX_REMEMBERED_BVIDS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MAX_REMEMBERED_BVIDS
    }
    private var followFeedPrimed = false

    fun acceptDanmakuBatch(lastContent: String, signal: CompanionDanmakuSignal) {
        val mood = BiliCompanionMoodPolicy.resolveMood(signal)
        val food = BiliCompanionMoodPolicy.signalFor(lastContent)
        _state.update { old ->
            val nextFullness = (old.fullness + signal.eatingCount * 0.035f).coerceIn(0.02f, 1f)
            old.copy(
                mood = mood,
                experience = (old.experience + signal.eventCount * 2 + signal.eatingCount * 5).coerceAtMost(9999),
                fullness = nextFullness,
                densityPerMinute = (signal.eventCount * 5).coerceAtMost(99),
                isEating = food.eatingCount > 0,
                speech = when {
                    food.eatingCount > 0 -> "这条弹幕好吃！"
                    mood == CompanionMood.PARTY -> "弹幕热闹起来啦！"
                    mood == CompanionMood.STUDY -> "认真记笔记中"
                    else -> "陪你看视频"
                },
                speechBvid = null
            )
        }
    }

    fun onFollowFeedLoaded(videos: List<VideoItem>) {
        val notice = synchronized(seenFollowedVideos) {
            val newVideos = videos.filter { it.bvid.isNotBlank() && seenFollowedVideos[it.bvid] == null }
            newVideos.forEach { seenFollowedVideos[it.bvid] = System.currentTimeMillis() }
            if (!followFeedPrimed) {
                followFeedPrimed = true
                null
            } else {
                newVideos.firstOrNull()
            }
        } ?: return

        if (!_state.value.uploadBubbleEnabled) return

        _state.update { old ->
            old.copy(
                speech = "${notice.owner.name.ifBlank { "关注的UP主" }}更新啦：${notice.title.take(12)}",
                speechBvid = notice.bvid
            )
        }
    }

    fun markIdle() {
        _state.update { old ->
            old.copy(
                mood = CompanionMood.BORED,
                densityPerMinute = 0,
                isEating = false,
                speech = if (old.speechBvid == null) "弹幕安静啦，眯一会儿" else old.speech
            )
        }
    }

    fun clearSpeech() {
        _state.update { it.copy(speech = "陪你看视频", speechBvid = null) }
    }

    fun setOverlayEnabled(enabled: Boolean) {
        _state.update { it.copy(overlayEnabled = enabled) }
    }

    fun setCompactOnFullscreen(enabled: Boolean) {
        _state.update { it.copy(compactOnFullscreen = enabled) }
    }

    fun setUploadBubbleEnabled(enabled: Boolean) {
        _state.update { it.copy(uploadBubbleEnabled = enabled) }
    }

    fun setCompact(compact: Boolean) {
        _state.update { it.copy(isCompact = compact) }
    }

    fun setFullscreen(fullscreen: Boolean, compactOnFullscreen: Boolean = true) {
        _state.update { old ->
            old.copy(
                isFullscreen = fullscreen,
                isCompact = if (fullscreen && compactOnFullscreen) true else old.isCompact
            )
        }
    }

    fun setFingerFollow(enabled: Boolean) {
        _state.update { it.copy(fingerFollow = enabled) }
    }

    fun setScale(scale: Float) {
        _state.update { it.copy(scale = scale.coerceIn(0.72f, 1.6f)) }
    }
}
