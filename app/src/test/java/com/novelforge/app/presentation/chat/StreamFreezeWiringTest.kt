package com.novelforge.app.presentation.chat

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 「翻上去别动」这条规则真的接线了吗（回归护栏）。
 *
 * 曾经 `frozen` 这个字段只写不读：`setFrozen` 把它存起来，`publishStream`
 * 只看节流窗口，压根没查它。于是整条"冻结策略"是句空话 —— 列表在 20Hz
 * 长高，reverseLayout 下最后一条变高会把上面所有内容顶走，用户看到的就是
 * "自己没动，页面自己在抽"，而"触底才跟随"也因为列表一直在跟手势抢锚点
 * 而形同虚设。
 *
 * 这种 bug 编译得过、单测测不出（ViewModel 依赖 Android 的 store，JVM 里
 * 构造不出来），所以只能从源码结构上钉：publishStream 的函数体里，
 * 写 `lastPublishAt` 之前**必须**先判 frozen。
 */
class StreamFreezeWiringTest {

    /**
     * 「冻结」必须是一条**独占**的早退，不能是被别的条件顺带捎上的。
     *
     * 第一版这里只查"函数体里出现过 frozen"，结果 `if (false && frozen) return`
     * 照样通过 —— 护栏等于没有。现在要求条件里只有 frozen 一个词：
     * 多加任何别的条件都会让它失效，那正是它当初失效的方式。
     */
    private val soleGuard = Regex("""if\s*\(\s*frozen\s*\)\s*\{?\s*return\b""")

    @Test
    fun publishStream_gatesOnFrozen_beforeRecordingPublishTime() {
        val body = functionBody(source(), "private fun publishStream(force: Boolean)")
        assumeTrue("没在 ChatScreen.kt 里找到 publishStream，跳过", body.isNotEmpty())

        val guard = soleGuard.find(body)
        assertTrue(
            "publishStream 里没有「if (frozen) return」这条独占早退 —— " +
                "冻结策略又变成只写不读了：\n$body",
            guard != null
        )
        val guardAt = requireNotNull(guard).range.first

        val timestampIndex = body.indexOf("lastPublishAt =")
        assertTrue(
            "publishStream 里没有写 lastPublishAt（结构变了？请同步更新本测试）：\n$body",
            timestampIndex >= 0
        )
        assertTrue(
            "publishStream 先记了时间戳、后判 frozen，冻结就晚了半帧：\n$body",
            guardAt < timestampIndex
        )
    }

    @Test
    fun setFrozen_stillUnfreezesWithAFlush() {
        val body = functionBody(source(), "fun setFrozen(value: Boolean)")
        assumeTrue("没找到 setFrozen，跳过", body.isNotEmpty())
        // 解冻必须补齐，否则攒下的那截要等下一个节流窗口才出现
        assertTrue("解冻没有补齐缓冲：\n$body", body.contains("flushPending()"))
    }

    @Test
    fun persistenceReadsTheBuffer_notTheRenderedMessages() {
        val text = source()
        // 冻结期间 _messages 里的最后一条是短的。存档如果直接读它，
        // 就是把没写完的回答静默存进历史 —— 没有任何报错，最难查的那种丢数据。
        val helper = functionBody(text, "private fun currentMessagesForPersistence()")
        assumeTrue("没找到 currentMessagesForPersistence，跳过", helper.isNotEmpty())
        assertTrue(
            "存档没有取缓冲里的全文：\n$helper",
            helper.contains("streamText") && helper.contains("streamReasoning")
        )
    }

    // ---------------------------------------------------------------- 源码定位

    /**
     * 从测试进程的工作目录（Gradle 默认是模块目录 app/）往上找；
     * 找不到再从本测试类的 class 输出目录往上找。两条路都断掉就
     * assumeTrue 跳过 —— 源码级断言在重新打包的环境里本来就无从谈起。
     */
    private fun source(): String {
        val relative = "src/main/java/com/novelforge/app/presentation/chat/ChatScreen.kt"
        val starts = listOfNotNull(
            File("").absoluteFile.takeIf { it.isDirectory },
            codeSourceDirectory()
        )
        for (start in starts) {
            var dir: File? = start
            while (dir != null) {
                listOf(File(dir, relative), File(dir, "app/$relative"))
                    .firstOrNull { it.isFile }
                    ?.let { return stripComments(it.readText()) }
                dir = dir.parentFile
            }
        }
        assumeTrue("找不到 ChatScreen.kt（源码不在预期位置），跳过源码级断言", false)
        error("unreachable")
    }

    private fun codeSourceDirectory(): File? = try {
        StreamFreezeWiringTest::class.java
            .protectionDomain?.codeSource?.location
            ?.toURI()
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }
    } catch (_: Exception) {
        null
    }

    /**
     * 从 `signature` 那一行起，取到下一个顶层声明为止。
     * 靠大括号配平，所以函数体里有嵌套的 when/if 也不会提前截断。
     */
    private fun functionBody(text: String, signature: String): String {
        val start = text.indexOf(signature)
        if (start < 0) return ""
        val open = text.indexOf('{', start)
        if (open < 0) return ""
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
            i++
        }
        return text.substring(open)
    }

    /**
     * 剥掉注释再断言。注释里出现 frozen / lastPublishAt 是**故意**的
     * （本文件就写着这些词），不剥掉的话护栏会一直被自己的注释绊倒。
     */
    private fun stripComments(source: String): String {
        val noBlock = source.replace(Regex("(?s)/\\*.*?\\*/"), "")
        return noBlock.lines()
            .filterNot { line ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")
    }
}
