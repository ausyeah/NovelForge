package com.novelforge.app.presentation.project

import com.novelforge.app.domain.model.CreativeConfig
import com.novelforge.app.domain.model.QuestData
import com.novelforge.app.domain.model.ThrillFrequency
import com.novelforge.app.domain.model.Tone
import com.novelforge.app.domain.model.WritingStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreativeSetupViewModelTest {
    private val completeConfig = CreativeConfig(
        genreTags = listOf("悬疑"),
        chapterCount = 6,
        targetLength = 3_000
    )
    private val completeQuestData = QuestData(
        answers = mapOf(
            "premise" to "一座城市每天都会忘记一件事",
            "protagonist" to "记录员周岚",
            "conflict" to "她必须在自己被遗忘前找出原因"
        )
    )

    @Test
    fun completeSetup_isValid() {
        assertNull(validateCreativeSetup(completeConfig, completeQuestData))
    }

    @Test
    fun blankCoreAnswer_isRejected() {
        val error = validateCreativeSetup(
            completeConfig,
            completeQuestData.copy(
                answers = completeQuestData.answers + ("conflict" to " ")
            )
        )

        assertEquals("请填写核心冲突", error)
    }

    @Test
    fun missingGenre_isRejected() {
        val error = validateCreativeSetup(
            completeConfig.copy(genreTags = emptyList()),
            completeQuestData
        )

        assertEquals("请至少选择一个题材标签", error)
    }

    @Test
    fun customCreativeOptions_areValidWhenTextIsProvided() {
        val config = completeConfig.copy(
            writingStyle = WritingStyle.CUSTOM,
            customWritingStyle = "冷峻克制",
            tone = Tone.CUSTOM,
            customTone = "压迫感逐步增强",
            thrillFrequency = ThrillFrequency.CUSTOM,
            customThrillFrequency = "每 5 章一次大高潮",
            chapterCount = 150,
            targetLength = 10_000
        )

        assertNull(validateCreativeSetup(config, completeQuestData))
    }

    @Test
    fun targetLengthAboveRecommendedRange_isStillValidAtUpperBoundary() {
        assertNull(
            validateCreativeSetup(
                completeConfig.copy(targetLength = 8_001),
                completeQuestData
            )
        )
    }

    @Test
    fun targetLengthAboveHardLimit_isRejected() {
        val error = validateCreativeSetup(
            completeConfig.copy(targetLength = 10_001),
            completeQuestData
        )

        assertEquals("每章目标字数必须在 500 到 10000 之间", error)
    }

    @Test
    fun blankCustomStyle_isRejected() {
        val error = validateCreativeSetup(
            completeConfig.copy(
                writingStyle = WritingStyle.CUSTOM,
                customWritingStyle = "  "
            ),
            completeQuestData
        )

        assertEquals("请填写自定义叙事风格", error)
    }
}
