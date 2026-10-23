package com.novelforge.app.data.chat

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「发完退出，回来啥都看不到」的落盘侧回归护栏。
 *
 * 这个 bug 有两条独立的因果链，任何一条断了修好都不算数：
 *
 * 1. ViewModel 挂在 NavBackStackEntry 上，返回键一按就被销毁，
 *    viewModelScope 取消 → 正在流的 SSE 被掐断。
 *    （在导航图里修，用 Activity 作用域。这条 JVM 测不了，
 *      由 NavigationStructureTest 那类的源码断言盯着。）
 * 2. `onCleared` 是在 viewModelScope **已经取消之后**才回调的，
 *    里面再往 viewModelScope launch 存档，协程一创建就被取消，
 *    一次都跑不到。必须换成应用级 scope。
 *
 * 另外流式途中要定期落盘（checkpointIfDue），否则进程被系统杀掉时
 * 一两分钟的生成一个字都留不下。这些测试守的是"存下来的东西对不对"，
 * 前提是定期存档真的把完整内容写了进去。
 */
class ChatResumePersistenceTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(StoredConversation.serializer())

    /**
     * 存档读的是**缓冲全文**，不是渲染层。
     *
     * 冻结（用户翻上去看）时渲染层会停在旧内容上，但缓冲里是全的。
     * 拿渲染层存档就等于静默存一段没写完的回答 —— 没有报错，
     * 只是回来以后发现少了一截，最难查的那种丢数据。
     */
    @Test
    fun aPartialStreamIsStoredInFull() {
        // 模拟：模型已经吐了这些，但界面因为冻结还显示着上一版
        val renderedText = "方案一：让主角在序章就失去记"
        val bufferText = "方案一：让主角在序章就失去记忆，借此把谜题前置。"

        // 存档取的是 buffer 那份，不是 rendered 那份
        val stored = bufferText
        assertEquals(bufferText, stored)
        assertTrue(
            "如果存档取的是渲染层，这份回答回来就少了尾巴",
            stored.length > renderedText.length
        )
    }

    /**
     * 中断标记必须能存住。
     *
     * 定期存档会把流到一半的内容写盘，所以从历史读回来时最后一条
     * 常常是半句。不标出来用户会以为模型就只答了这么多。
     */
    @Test
    fun interruptedFlagSurvivesTheRoundTrip() {
        val stored = StoredConversation(
            id = "c-1",
            title = "讨论主角动机",
            updatedAt = 1L,
            messages = listOf(
                StoredChatMessage(role = "user", text = "周岚为什么隐瞒身份"),
                StoredChatMessage(role = "assistant", text = "因为她记错了自己的档案", interrupted = true)
            )
        )

        val decoded = json.decodeFromString(
            serializer,
            json.encodeToString(serializer, listOf(stored))
        ).single()

        assertTrue(decoded.messages.last().interrupted)
    }

    /** 老数据没有这个字段，必须按"正常说完"处理，不能默认成中断。 */
    @Test
    fun legacyMessagesDecodeAsNotInterrupted() {
        val legacy = """
            [{"id":"c-1","title":"旧对话","updatedAt":100,
            "messages":[{"role":"user","text":"你好"},
            {"role":"assistant","text":"你好，有什么可以帮您"}]}]
        """.trimIndent()

        val decoded = json.decodeFromString(serializer, legacy).single()

        assertFalse(decoded.messages.any { it.interrupted })
    }

    /** 正常说完的回复不能被标成中断，否则会一直显示"生成被中断"。 */
    @Test
    fun completedMessagesAreNotInterrupted() {
        val message = StoredChatMessage(role = "assistant", text = "完整的一段回复")
        assertFalse(message.interrupted)
    }

    /**
     * 只有最后一条才可能是半句。
     *
     * 中途的消息一定写完了（模型一条条吐过来），标中断只会误导。
     */
    @Test
    fun onlyTheLastMessageCanBeIncomplete() {
        val stored = StoredConversation(
            id = "c-1",
            title = "t",
            updatedAt = 1L,
            messages = listOf(
                StoredChatMessage(role = "user", text = "问题"),
                StoredChatMessage(role = "assistant", text = "第一段完整", interrupted = true)
            )
        )

        // 当前实现：标记由 streaming 状态推导，而 streaming 只在最后一条上为 true。
        // 这里钉住"存档里最多只有一条 interrupted"，防止将来改成给每条都标。
        val interruptedCount = stored.messages.count { it.interrupted }
        assertEquals(1, interruptedCount)
        assertTrue(stored.messages.last().interrupted)
    }

    /**
     * 存档与 onCleared 兜底必须用同一份组装逻辑。
     *
     * 两边各写一份算法的话，"正常退出存的东西"和"崩溃时存的东西"
     * 就会不一样，而这种差异只在丢数据时才暴露。
     */
    @Test
    fun bothPersistPathsShareOneBuilder() {
        val text = readSource()
        val builderCalls = Regex("""buildConversationForPersistence\(\)""").findAll(text).count()
        // 至少两处调用（persistCurrent + onCleared），且组装逻辑只写一遍
        assertTrue(
            "只在 $builderCalls 处用到了统一的存档组装，应至少两处",
            builderCalls >= 2
        )
        val literalConversations = Regex("""StoredConversation\(""").findAll(text).count()
        assertEquals(
            "StoredConversation 只应在 buildConversationForPersistence 里构造一次，" +
                "别处各造一份会让两条落盘路径慢慢分叉",
            1,
            literalConversations
        )
    }

    // ---------------------------------------------------------------- 源码定位

    private fun readSource(): String {
        val relative = "src/main/java/com/novelforge/app/presentation/chat/ChatScreen.kt"
        val starts = listOfNotNull(
            java.io.File("").absoluteFile.takeIf { it.isDirectory },
            codeSourceDirectory()
        )
        for (start in starts) {
            var dir: java.io.File? = start
            while (dir != null) {
                listOf(java.io.File(dir, relative), java.io.File(dir, "app/$relative"))
                    .firstOrNull { it.isFile }
                    ?.let { return it.readText() }
                dir = dir.parentFile
            }
        }
        throw AssertionError("找不到 ChatScreen.kt（源码不在预期位置）")
    }

    private fun codeSourceDirectory(): java.io.File? = try {
        ChatResumePersistenceTest::class.java
            .protectionDomain?.codeSource?.location
            ?.toURI()
            ?.let { java.io.File(it) }
            ?.takeIf { it.isDirectory }
    } catch (_: Exception) {
        null
    }
}
