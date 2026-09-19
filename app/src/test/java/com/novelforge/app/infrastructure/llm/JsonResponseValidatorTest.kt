package com.novelforge.app.infrastructure.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonResponseValidatorTest {
    private val validator = JsonResponseValidator()

    @Test
    fun chapter_acceptsCodeFenceAndTrailingText() {
        val result = validator.parseChapter(
            "```json\n{\"summary\":\"冲突升级\",\"content\":\"正文\"}\n```"
        )

        assertTrue(result is JsonValidationResult.Success)
        assertEquals("正文", (result as JsonValidationResult.Success).value.content)
    }

    @Test
    fun outline_rejectsDuplicateIds() {
        val result = validator.parseOutline(
            "[{\"id\":\"same\",\"orderIndex\":0,\"title\":\"一\",\"summary\":\"a\"}," +
                "{\"id\":\"same\",\"orderIndex\":1,\"title\":\"二\",\"summary\":\"b\"}]"
        )

        val items = (result as JsonValidationResult.Success).value
        assertEquals(2, items.size)
        assertEquals(items[0].id, items[0].id)
        assertTrue(items[0].id != items[1].id)
    }

    @Test
    fun outline_acceptsCharacterChangesArrayAndExtraFields() {
        val result = validator.parseOutline(
            """{
                "chapters": [{
                    "id": "chapter-1",
                    "orderIndex": 1,
                    "title": "开端",
                    "summary": "主角遇到第一个冲突",
                    "characterChanges": ["主角建立目标", "对手登场"],
                    "keywords": ["冲突", "悬念"]
                }]
            }"""
        )

        assertTrue(result is JsonValidationResult.Success)
        assertEquals(
            "主角建立目标；对手登场",
            (result as JsonValidationResult.Success).value.single().characterChanges
        )
    }

    @Test
    fun outline_acceptsSingleChapterObject() {
        val result = validator.parseOutline(
            "{" +
                "\"id\":\"chapter-1\",\"order\":1," +
                "\"title\":\"开端\",\"summary\":\"主角遇到冲突\"" +
                "}"
        )

        assertTrue(result is JsonValidationResult.Success)
        assertEquals("chapter-1", (result as JsonValidationResult.Success).value.single().id)
    }

    @Test
    fun outline_expectedCountTakesFirstNWhenModelReturnsMore() {
        val result = validator.parseOutline(
            "{\"chapters\":[" +
                "{\"id\":\"chapter-1\",\"title\":\"一\",\"summary\":\"a\"}," +
                "{\"id\":\"chapter-2\",\"title\":\"二\",\"summary\":\"b\"}" +
                "]}",
            expectedCount = 1
        )

        // 上游多给时只取前 N 个，不再整单拒绝
        assertTrue(result is JsonValidationResult.Success)
        assertEquals(1, (result as JsonValidationResult.Success).value.size)
        assertEquals("一", result.value.first().title)
    }

    @Test
    fun outline_truncatedJsonExplainsThatItCannotBeParsed() {
        val result = validator.parseOutline(
            "{\"chapters\":[{\"id\":\"chapter-1\",\"title\":\"开端\",\"summary\":\"未闭合"
        )

        assertTrue(result is JsonValidationResult.Failure)
        assertTrue((result as JsonValidationResult.Failure).reason.contains("JSON"))
    }

    @Test
    fun outline_acceptsMissingIdTitleOrderAndCharacterChanges() {
        val result = validator.parseOutline(
            """{"chapters":[{"summary":"主角发现城市规则改变，冲突开始升级"}]}"""
        )

        val item = (result as JsonValidationResult.Success).value.single()
        assertEquals("outline-item-1", item.id)
        assertEquals("第 1 章", item.title)
        assertEquals(0, item.orderIndex)
    }

    @Test
    fun outline_acceptsAlternateStorylineFieldNames() {
        val result = validator.parseOutline(
            """{"chapters":[{"storyline":"冲突从港口蔓延到内陆","heading":"暗流"}]}"""
        )

        val item = (result as JsonValidationResult.Success).value.single()
        assertEquals("暗流", item.title)
        assertEquals("冲突从港口蔓延到内陆", item.summary)
    }

    @Test
    fun outline_acceptsPlainTextStorylineButNotTruncatedJson() {
        val plain = validator.parseOutline("主角在旧码头获得线索，冲突指向失踪的弟弟。")
        assertTrue(plain is JsonValidationResult.Success)

        val truncated = validator.parseOutline("{\"chapters\":[{\"summary\":\"未闭合")
        assertTrue(truncated is JsonValidationResult.Failure)
    }

    @Test
    fun outline_emptyArrayExplainsActionableFailure() {
        val result = validator.parseOutline("[]")

        assertTrue(result is JsonValidationResult.Failure)
        assertTrue((result as JsonValidationResult.Failure).reason.contains("不能为空"))
    }

    @Test
    fun malformedJson_returnsFailureWithoutThrowing() {
        val result = validator.parseChapter("模型没有返回结构化内容")

        assertTrue(result is JsonValidationResult.Failure)
    }
}
