package com.novelforge.app.data.chat

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 灵感助手"跨书串味"的回归测试。
 *
 * 以前所有会话挤在同一个 DataStore 键里，提问又会把整段历史重发一遍，
 * 于是 A 书的人物讨论会被原样塞进 B 书的请求。修法是按 projectId 分桶，
 * 这里守住"分桶"这件事的纯逻辑（作用域归一化 / 键名 / 按桶过滤 / 老数据可读）；
 * ChatHistoryStore 本身要 Context，纯 JVM 单测里跑不起来，故不直接实例化。
 */
class ChatHistoryStoreTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(StoredConversation.serializer())

    /** 老版本写进 DataStore 的原文：没有 projectId 字段。 */
    private val legacyPayload = """
        [{"id":"c-1","title":"A 书的角色讨论","updatedAt":100,
        "messages":[{"role":"user","text":"主角叫周岚"},
        {"role":"assistant","text":"她应该是记录员"}]}]
    """.trimIndent()

    private fun conversation(id: String, projectId: String?) = StoredConversation(
        id = id,
        title = id,
        updatedAt = 1L,
        messages = listOf(StoredChatMessage(role = "user", text = "hi")),
        projectId = projectId
    )

    @Test
    fun legacyJsonWithoutProjectId_stillDecodes_andLandsInGlobalBucket() {
        val decoded = json.decodeFromString(serializer, legacyPayload)

        assertEquals(1, decoded.size)
        // 关键：老数据没有这个字段，缺省必须是 null（= 全局桶），不能因为加了 scope 就丢掉
        assertNull(decoded.single().projectId)
        assertEquals("A 书的角色讨论", decoded.single().title)
        assertEquals(listOf("c-1"), selectChatScope(decoded, null).map { it.id })
    }

    @Test
    fun globalScopeReusesLegacyPreferenceName_soOldDataKeepsShowing() {
        assertEquals("conversations", chatScopePreferenceName(null))
        assertEquals("conversations", chatScopePreferenceName(""))
        assertEquals("conversations", chatScopePreferenceName("   "))
    }

    @Test
    fun eachProjectGetsItsOwnPreferenceName() {
        val bookA = chatScopePreferenceName("book-a")
        val bookB = chatScopePreferenceName("book-b")

        assertNotEquals(bookA, bookB)
        assertNotEquals("conversations", bookA)
        assertTrue(bookA.contains("book-a"))
    }

    @Test
    fun selectingScope_neverReturnsAnotherProjectsConversations() {
        val all = listOf(
            conversation("global", null),
            conversation("a", "book-a"),
            conversation("b", "book-b")
        )

        assertEquals(listOf("a"), selectChatScope(all, "book-a").map { it.id })
        assertEquals(listOf("b"), selectChatScope(all, "book-b").map { it.id })
        assertEquals(listOf("global"), selectChatScope(all, null).map { it.id })
    }

    @Test
    fun blankStoredProjectId_isReadAsGlobalNotAsASeparateBucket() {
        val all = listOf(
            conversation("legacy", null),
            conversation("whitespace", "  ")
        )

        // 空白和 null 必须是同一个桶，否则同一份老数据会散成两个列表
        assertTrue(sameChatScope(null, "  "))
        assertEquals(listOf("legacy", "whitespace"), selectChatScope(all, null).map { it.id })
        assertEquals(listOf("legacy", "whitespace"), selectChatScope(all, "").map { it.id })
    }

    @Test
    fun scopeSurvivesRoundTrip() {
        val encoded = json.encodeToString(
            serializer,
            listOf(conversation("a", "book-a"), conversation("g", null))
        )
        val decoded = json.decodeFromString(serializer, encoded)

        assertEquals("book-a", decoded.first { it.id == "a" }.projectId)
        assertNull(decoded.first { it.id == "g" }.projectId)
        assertEquals(listOf("a"), selectChatScope(decoded, "book-a").map { it.id })
    }

    @Test
    fun deletingFromOneScope_keepsTheOtherScopesIntact() {
        // save/delete 用的都是同一个键名函数，键名不同就等于数据物理隔离
        val before = listOf(
            conversation("a", "book-a"),
            conversation("b", "book-b"),
            conversation("g", null)
        )

        val afterDeleteA = before.filterNot { it.id == "a" }

        assertEquals(listOf("b", "g"), afterDeleteA.map { it.id })
        assertTrue(selectChatScope(afterDeleteA, "book-a").isEmpty())
        assertEquals(listOf("b"), selectChatScope(afterDeleteA, "book-b").map { it.id })
    }
}
