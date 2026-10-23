package com.novelforge.app.presentation.chat.richtext

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 气泡富文本的链接接线（回归护栏）。
 *
 * 背景：这里曾经用 `Modifier.pointerInput { detectTapGestures { ... } }` 自己
 * 做链接点击的命中测试。`detectTapGestures` 在 `awaitFirstDown` 之后会
 * `consume()` 按下事件，而整棵气泡子树都活在 `SelectionContainer` 里 ——
 * 多出来的这个识别器就把长按选词抢走了（用户报「长按不能复制」）。当时那句
 * 「handler 为 null 时不装手势，所以不受影响」只在默认配置下成立，而
 * assistant 气泡恰恰传了回调。
 *
 * 现在的做法：链接恒定挂 `LinkAnnotation.Url`（命中测试与打开都归 Compose
 * 自己），拦截打开动作靠覆盖 `LocalUriHandler`，一个指针识别器都不装。
 *
 * 手势没法在 JVM 单测里验（那是真机的事），所以这里钉的是**能钉住的那一半**：
 * AnnotatedString 的标注形状，以及源码里不再出现任何指针拦截。
 */
class ChatRichTextLinkTest {

    private fun links(out: AnnotatedString) = out.getLinkAnnotations(0, out.length)

    // ---------- 标注形状：链接挂 LinkAnnotation.Url，罩住链接文字本身 ----------

    @Test
    fun link_carriesALinkAnnotationOverExactlyTheLinkText() {
        val out = buildMarkdownInline("看 [文字](https://example.com)")
        assertEquals("看 文字", out.text)

        val link = links(out).single()
        // 点击路由 + TalkBack 的「链接」全靠 LinkAnnotation.Url
        assertEquals("https://example.com", (link.item as LinkAnnotation.Url).url)
        assertEquals(2 to 4, link.start to link.end)
    }

    /**
     * stringAnnotation(Tag, item) 必须**不再**出现。
     *
     * 那条标注存在的唯一理由是让人自己写指针命中测试；而只要有人照着它去写
     * pointerInput，长按选词就又废了。留着它等于把刚填上的坑重新挖开。
     */
    @Test
    fun noStringAnnotationIsEmittedAnymore() {
        val out = buildMarkdownInline("看 [文字](https://example.com)")
        assertTrue(
            "AnnotatedString 里还有 tag 标注：它只会诱导别人重新写指针命中测试",
            out.getStringAnnotations(0, out.length).isEmpty()
        )
    }

    @Test
    fun linkAnnotation_keepsTheStyleSoLinksStillLookLikeLinks() {
        val out = buildMarkdownInline("[文字](https://a.cn)")
        val url = links(out).single().item as LinkAnnotation.Url
        // TextLinkScope 会把这个 style 合到 span 上；空的话链接就没有视觉提示
        assertNotNull(url.styles?.style)
    }

    @Test
    fun bareUrl_alsoGetsLinkAnnotationSoItIsTappable() {
        val out = buildMarkdownInline("https://example.com")
        assertEquals(1, links(out).size)
        assertEquals("https://example.com", (links(out).single().item as LinkAnnotation.Url).url)
    }

    @Test
    fun plainText_hasNoLinkAnnotationsSoItNeverInstallsLinkOverlays() {
        // BasicText 只有 hasLinks() 为真时才会额外放链接覆盖节点。
        // 不含链接的正文不该为链接付出任何布局/手势代价。
        val out = buildMarkdownInline("没有链接的一段话")
        assertTrue(links(out).isEmpty())
    }

    // ---------- 源码护栏：文件里不得再出现指针拦截 ----------

    @Test
    fun source_installsNoPointerGestureRecognizerAtAll() {
        val code = codeOf(chatRichTextSource())
        // 自检：万一路径解析退化到空串，禁词断言会「全过」而什么也没测到。
        assertTrue(
            "源码读取疑似失败：剥完注释后连函数签名都没有",
            code.contains("fun ChatRichText(")
        )
        // 气泡整棵在 SelectionContainer 里。任何会 consume 按下事件的识别器
        // （pointerInput / detectTapGestures / clickable 系列）都会破坏长按选词。
        val banned = listOf(
            "pointerInput",
            "detectTapGestures",
            "clickable",
            "toggleable",
            "awaitFirstDown",
            "getOffsetForPosition",
            "onTextLayout"
        )
        for (token in banned) {
            val hit = code.lines().filter { it.contains(token) }
            assertTrue(
                "ChatRichText.kt 里出现了 $token —— 会和 SelectionContainer 抢手势：\n" +
                    hit.joinToString("\n") { "  ${it.trim()}" },
                hit.isEmpty()
            )
        }
    }

    @Test
    fun source_interceptsLinkOpensThroughUriHandlerNotPointers() {
        val source = chatRichTextSource()
        // onLinkClick 的实现方式必须是覆盖 LocalUriHandler，而不是自己装手势。
        assertTrue(
            "onLinkClick 必须走 LocalUriHandler 覆盖",
            source.contains("CompositionLocalProvider(LocalUriHandler provides")
        )
        assertTrue(source.contains("override fun openUri(uri: String)"))
        // 链接标注只有一种给法，源码里不该再留任何"要不要用 LinkAnnotation"的开关
        assertFalse(
            "不能再用 useLinkAnnotations 之类的开关分叉",
            source.contains("useLinkAnnotations")
        )
        assertFalse("不能再用 nativeLinks 之类的开关分叉", source.contains("nativeLinks"))
    }

    // ---------- 定位源码：找不到就跳过，别让打包环境下的测试变红 ----------

    /**
     * 从测试进程的工作目录（Gradle 默认是模块目录 `app/`）往上找；
     * 找不到再从本测试类的 class 输出目录往上找。两条路都断掉就
     * `assumeTrue` 跳过 —— 源码级断言在重新打包的环境里本来就无从谈起。
     */
    private fun chatRichTextSource(): String {
        val relative =
            "src/main/java/com/novelforge/app/presentation/chat/richtext/ChatRichText.kt"
        val starts = listOfNotNull(
            File("").absoluteFile.takeIf { it.isDirectory },
            codeSourceDirectory()
        )
        for (start in starts) {
            var dir: File? = start
            while (dir != null) {
                listOf(File(dir, relative), File(dir, "app/$relative"))
                    .firstOrNull { it.isFile }
                    ?.let { return it.readText() }
                dir = dir.parentFile
            }
        }
        assumeTrue("找不到 ChatRichText.kt（源码不在预期位置），跳过源码级断言", false)
        error("unreachable")
    }

    private fun codeSourceDirectory(): File? = try {
        ChatRichTextLinkTest::class.java
            .protectionDomain?.codeSource?.location
            ?.toURI()
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }
    } catch (_: Exception) {
        null
    }

    /**
     * 去掉注释再断言。注释里出现这些词是**故意的**（本文件就写着「不得再
     * 出现 pointerInput」），所以必须先剥掉。
     *
     * 剥法：整块 `/* */`、整行 `//` 与 KDoc 的 `*`、以及「`//` 之前没有
     * 引号」的行尾注释 —— 最后那条避免把 `"https://x"` 里的 `//` 当注释。
     */
    private fun codeOf(source: String): String {
        val noBlockComments = source.replace(Regex("(?s)/\\*.*?\\*/"), "")
        return noBlockComments.lines()
            .filterNot { line ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*")
            }
            .map { line ->
                val i = line.indexOf("//")
                if (i >= 0 && !line.substring(0, i).contains('"')) line.substring(0, i) else line
            }
            .joinToString("\n")
    }
}
