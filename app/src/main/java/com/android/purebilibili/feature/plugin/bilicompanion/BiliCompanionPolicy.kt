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
    val specialReaction: String? = null,
    val playing: Boolean = true
)

internal data class BiliCompanionState(
    val mood: CompanionMood = CompanionMood.BORED,
    val experience: Int = 0,
    val affinity: Int = 0,
    val fullness: Float = 0.08f,
    val densityPerMinute: Int = 0,
    val danmakuCombo: Int = 0,
    val petTapCombo: Int = 0,
    val isEating: Boolean = false,
    val isCompact: Boolean = false,
    val isFullscreen: Boolean = false,
    val fingerFollow: Boolean = false,
    val scale: Float = 1f,
    val speech: String = "陪你看视频",
    val speechBvid: String? = null,
    val reactionText: String? = null,
    val reactionStartedAtMs: Long = 0L,
    val overlayEnabled: Boolean = true,
    val compactOnFullscreen: Boolean = true,
    val uploadBubbleEnabled: Boolean = true,
    val watchReminderEnabled: Boolean = true,
    val watchReminderMinutes: Int = 45
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
            eatingCount = eating,
            specialReaction = specialReactionFor(normalized, funny > 0, study > 0, eating > 0)
        )
    }

    private fun specialReactionFor(
        normalized: String,
        funny: Boolean,
        study: Boolean,
        eating: Boolean
    ): String? = when {
        normalized.contains("三连") -> "三连收到！"
        normalized.contains("投币") -> "投币加成！"
        normalized.contains("收藏") -> "收藏成功！"
        normalized.contains("生日快乐") -> "生日快乐！"
        normalized.contains("早安") -> "早安，出发！"
        normalized.contains("晚安") -> "晚安，做个好梦"
        normalized.contains("可爱") || normalized.contains("萌") -> "被夸得脸红啦！"
        normalized.contains("前方高能") -> "前方高能！"
        eating -> "咔嚓！"
        funny -> "笑到起飞！"
        study -> "知识点记下了"
        else -> null
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
    val showUploadBubble: Boolean = true,
    val aiEnabled: Boolean = false,
    val aiProvider: BiliCompanionAiProvider = BiliCompanionAiProvider.OPENAI_COMPATIBLE,
    val aiBaseUrl: String = "https://api.openai.com/v1",
    val aiEndpointPath: String = "chat/completions",
    val aiModel: String = "gpt-4o-mini",
    val aiCommentQuery: String = "最有信息量的评论",
    val watchReminderEnabled: Boolean = true,
    val watchReminderMinutes: Int = 45
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
    private var lastDanmakuBatchAtMs = 0L
    private var lastPetTapAtMs = 0L
    private var petTapCombo = 0
    private var petReplyIndex = 0
    private var watchSessionStartedAtMs = 0L
    private var watchReminderShown = false

    private val petReplies = listOf(
        "摸摸头成功！好感度+1",
        "收到鼓励，继续巡游！",
        "今天也要陪你看视频～",
        "再摸一下就要害羞啦！"
    )

    fun acceptDanmakuBatch(lastContent: String, signal: CompanionDanmakuSignal) = synchronized(this) {
        val now = System.currentTimeMillis()
        val combo = if (now - lastDanmakuBatchAtMs <= 2_400L) {
            (_state.value.danmakuCombo + 1).coerceAtMost(99)
        } else {
            1
        }
        lastDanmakuBatchAtMs = now
        val mood = BiliCompanionMoodPolicy.resolveMood(signal)
        val food = BiliCompanionMoodPolicy.signalFor(lastContent)
        _state.update { old ->
            val nextFullness = (old.fullness + signal.eatingCount * 0.035f).coerceIn(0.02f, 1f)
            old.copy(
                mood = mood,
                experience = (old.experience + signal.eventCount * 2 + signal.eatingCount * 5).coerceAtMost(9999),
                affinity = (old.affinity + signal.eatingCount + if (signal.specialReaction != null) 1 else 0).coerceAtMost(999),
                fullness = nextFullness,
                densityPerMinute = (signal.eventCount * 5).coerceAtMost(99),
                danmakuCombo = combo,
                isEating = food.eatingCount > 0,
                speech = when {
                    food.eatingCount > 0 -> "这条弹幕好吃！"
                    mood == CompanionMood.PARTY -> "弹幕热闹起来啦！"
                    mood == CompanionMood.STUDY -> "认真记笔记中"
                    else -> "陪你看视频"
                },
                speechBvid = null,
                reactionText = signal.specialReaction,
                reactionStartedAtMs = if (signal.specialReaction != null) now else 0L
            )
        }
    }

    fun onPetTapped(): String = synchronized(this) {
        val now = System.currentTimeMillis()
        petTapCombo = if (now - lastPetTapAtMs <= 2_500L) {
            (petTapCombo + 1).coerceAtMost(9)
        } else {
            1
        }
        lastPetTapAtMs = now
        val response = if (petTapCombo >= 3) {
            "连续摸头${petTapCombo}次！好感度+1"
        } else {
            petReplies[petReplyIndex++ % petReplies.size]
        }
        _state.update { old ->
            old.copy(
                experience = (old.experience + 3).coerceAtMost(9999),
                affinity = (old.affinity + 1).coerceAtMost(999),
                petTapCombo = petTapCombo,
                speech = response,
                speechBvid = null,
                reactionText = "摸摸头",
                reactionStartedAtMs = now
            )
        }
        response
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
                speechBvid = notice.bvid,
                reactionText = "新视频！",
                reactionStartedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun markIdle() {
        _state.update { old ->
            if (old.reactionText != null && System.currentTimeMillis() - old.reactionStartedAtMs < 3_000L) {
                return@update old
            }
            old.copy(
                mood = CompanionMood.BORED,
                densityPerMinute = 0,
                danmakuCombo = 0,
                isEating = false,
                reactionText = null,
                reactionStartedAtMs = 0L,
                speech = if (old.speechBvid == null) "弹幕安静啦，眯一会儿" else old.speech
            )
        }
    }

    fun clearSpeech() {
        _state.update { it.copy(speech = "陪你看视频", speechBvid = null, reactionText = null) }
    }

    fun showAssistantMessage(message: String, reaction: String? = "AI 助手") {
        val now = System.currentTimeMillis()
        _state.update {
            it.copy(
                speech = message.take(120),
                speechBvid = null,
                reactionText = reaction,
                reactionStartedAtMs = now
            )
        }
    }

    fun noteWatchTick() {
        val now = System.currentTimeMillis()
        if (watchSessionStartedAtMs == 0L) watchSessionStartedAtMs = now
        val reminderMs = _state.value.watchReminderMinutes.coerceIn(5, 240) * 60_000L
        if (_state.value.watchReminderEnabled && !watchReminderShown && now - watchSessionStartedAtMs >= reminderMs) {
            watchReminderShown = true
            _state.update {
                it.copy(
                    speech = "已经看了${it.watchReminderMinutes}分钟，起来活动一下吧",
                    speechBvid = null,
                    reactionText = "休息提醒",
                    reactionStartedAtMs = now
                )
            }
        }
    }

    fun beginWatchSession() {
        watchSessionStartedAtMs = System.currentTimeMillis()
        watchReminderShown = false
    }

    fun endWatchSession() {
        watchSessionStartedAtMs = 0L
        watchReminderShown = false
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

    fun setWatchReminder(enabled: Boolean, minutes: Int) {
        watchReminderShown = false
        _state.update {
            it.copy(
                watchReminderEnabled = enabled,
                watchReminderMinutes = minutes.coerceIn(5, 240)
            )
        }
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
