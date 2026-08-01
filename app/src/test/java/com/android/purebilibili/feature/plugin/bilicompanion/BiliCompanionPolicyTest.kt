package com.android.purebilibili.feature.plugin.bilicompanion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BiliCompanionPolicyTest {
    @Test
    fun funnyDanmakuIsRecognizedAsFoodAndPartySignal() {
        val signal = BiliCompanionMoodPolicy.signalFor("前方高能，哈哈哈哈")

        assertEquals(1, signal.funnyCount)
        assertEquals(1, signal.eatingCount)
        assertEquals(
            CompanionMood.PARTY,
            BiliCompanionMoodPolicy.resolveMood(signal)
        )
    }

    @Test
    fun studyDanmakuEntersQuietNoteTakingMode() {
        val signal = BiliCompanionMoodPolicy.signalFor("这个实验的原理是什么")

        assertEquals(1, signal.studyCount)
        assertEquals(
            CompanionMood.STUDY,
            BiliCompanionMoodPolicy.resolveMood(signal)
        )
    }

    @Test
    fun emptyOrPausedStreamEntersBoredMode() {
        assertEquals(
            CompanionMood.BORED,
            BiliCompanionMoodPolicy.resolveMood(
                CompanionDanmakuSignal(
                    eventCount = 0,
                    funnyCount = 0,
                    studyCount = 0,
                    eatingCount = 0
                )
            )
        )
        assertEquals(
            CompanionMood.BORED,
            BiliCompanionMoodPolicy.resolveMood(
                CompanionDanmakuSignal(
                    eventCount = 12,
                    funnyCount = 0,
                    studyCount = 0,
                    eatingCount = 0,
                    playing = false
                )
            )
        )
    }

    @Test
    fun denseStreamEntersPartyModeWithoutKeyword() {
        val mood = BiliCompanionMoodPolicy.resolveMood(
            CompanionDanmakuSignal(
                eventCount = 16,
                funnyCount = 0,
                studyCount = 0,
                eatingCount = 0
            )
        )

        assertEquals(CompanionMood.PARTY, mood)
        assertTrue(BiliCompanionConfig().compactOnFullscreen)
    }

    @Test
    fun interactionKeywordsProduceCompanionEasterEggs() {
        assertEquals("三连收到！", BiliCompanionMoodPolicy.signalFor("已三连支持").specialReaction)
        assertEquals("早安，出发！", BiliCompanionMoodPolicy.signalFor("早安桌宠").specialReaction)
        assertEquals("知识点记下了", BiliCompanionMoodPolicy.signalFor("这个知识点讲得清楚").specialReaction)
    }

    @Test
    fun aiAndWatchReminderSettingsHaveConservativeDefaults() {
        val config = BiliCompanionConfig()

        assertEquals(false, config.aiEnabled)
        assertEquals(BiliCompanionAiProvider.OPENAI_COMPATIBLE, config.aiProvider)
        assertEquals("chat/completions", config.aiEndpointPath)
        assertTrue(config.watchReminderEnabled)
        assertEquals(45, config.watchReminderMinutes)
    }
}
