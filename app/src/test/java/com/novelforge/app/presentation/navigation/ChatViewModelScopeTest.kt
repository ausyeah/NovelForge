package com.novelforge.app.presentation.navigation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 灵感助手的 ViewModel 作用域（回归护栏）。
 *
 * 曾经的写法是 `composable("chat") { viewModel(factory = ...) }`，也就是
 * 默认的 NavBackStackEntry 作用域。用户发完消息按返回键，这个 entry 当场
 * 销毁 → onCleared → viewModelScope 取消 → 正在跑的 SSE 流被掐断，
 * 而那次生成一个字都没存下来。体感就是"发完退出，回来什么都没有"。
 *
 * 修法是把 ViewModel 挂到 Activity 作用域：退出页面不再销毁它，流在后台
 * 接着跑，重新进入拿到的还是同一个实例。
 *
 * 这条 JVM 测不了（要真的导航），所以从源码结构上钉：Chat 那段必须
 * 显式传 viewModelStoreOwner，而且不能是 entry 作用域。
 */
class ChatViewModelScopeTest {

    @Test
    fun chatDestination_usesAnExplicitViewModelStoreOwner() {
        val source = readSource()
        val block = chatComposableBlock(source)
        assertTrue(
            "没找到 chat 目的地的 composable 块（结构变了？请同步更新本测试）",
            block.isNotEmpty()
        )
        assertTrue(
            "chat 的 viewModel 没有显式指定 viewModelStoreOwner —— " +
                "默认是 NavBackStackEntry 作用域，返回键一按流就被掐断：\n$block",
            block.contains("viewModelStoreOwner")
        )
    }

    @Test
    fun chatViewModel_isKeyedPerScope() {
        val source = readSource()
        val block = chatComposableBlock(source)
        // Activity 作用域下所有 ViewModel 共用一个 store，不给 key 的话
        // A 书的聊天界面会拿到 B 书那个实例（key 相同就复用）。
        assertTrue(
            "chat 的 viewModel 没有按作用域给 key，多本书会串实例：\n$block",
            block.contains("key =")
        )
    }

    @Test
    fun ownerFallsBackToActivityNotToTheEntry() {
        val source = readSource()
        // 拿不到 Activity 时退回 LocalViewModelStoreOwner（= entry），
        // 这是显式降级；关键是别**默认**就落在 entry 上。
        val helper = functionBody(source, "private fun chatViewModelStoreOwner()")
        assertTrue(
            "chatViewModelStoreOwner 里没提到 Activity：\n$helper",
            helper.contains("findActivity")
        )
        assertTrue(
            "chatViewModelStoreOwner 没有降级分支，取不到 Activity 会崩或静默退回 entry",
            helper.contains("?:")
        )
    }

    // ---------------------------------------------------------------- 源码定位

    private fun readSource(): String {
        val relative = "src/main/java/com/novelforge/app/presentation/navigation/NovelForgeNavGraph.kt"
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
        throw AssertionError("找不到 NovelForgeNavGraph.kt（源码不在预期位置）")
    }

    private fun codeSourceDirectory(): File? = try {
        ChatViewModelScopeTest::class.java
            .protectionDomain?.codeSource?.location
            ?.toURI()
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }
    } catch (_: Exception) {
        null
    }

    /**
     * 从 `composable(` 那行起，取整个块（含参数列表和内容 lambda）。
     *
     * 两个坑，都踩过：
     *
     * 1. 不能从 `Routes.CHAT}?projectId=` 那里往后找 `{` —— 那个位置**在字符串
     *    字面量内部**，紧接着的 `{` 是路由模板里的 `{projectId}`，不是内容
     *    lambda 的开头。截出来只有一行路由，护栏对着半截代码断言，
     *    报的却是"你没写 viewModelStoreOwner"，完全指错方向。
     * 2. 配平必须跳过字符串字面量和 // 注释，否则 `"...{projectId}..."`
     *    里的 `}` 会把配平带偏。
     *
     * 所以从 `composable(` 开始扫，取**第一个括号深度为 0 的 `{`** ——
     * 那才是 `) { entry ->` 里的那个。参数列表内部的 `{`（如
     * `navArgument(...) { ... }`）都配平，净深度为 0，会被正确跳过。
     */
    private fun chatComposableBlock(source: String): String {
        // 必须锚在 **chat 那个** composable 上，不能取文件里第一个：
        // 第一个是书架页，于是护栏一路对着书架的 viewModel 断言，
        // 报的却是"chat 没写 viewModelStoreOwner"。
        val routeIndex = source.indexOf("Routes.CHAT}?projectId=")
        if (routeIndex < 0) return ""
        val start = source.lastIndexOf("composable(", routeIndex)
        if (start < 0) return ""
        val open = topLevelBlockBrace(source, start)
        if (open < 0) return ""
        val end = closeBrace(source, open)
        return if (end < 0) source.substring(start) else source.substring(start, end + 1)
    }

    /**
     * 从 [from] 起找第一个"括号深度为 0"的 `{`。
     * 跳过字符串字面量与 // 注释。
     */
    private fun topLevelBlockBrace(source: String, from: Int): Int {
        var parenDepth = 0
        var inString = false
        var i = from
        while (i < source.length) {
            val c = source[i]
            if (c == '"' && (i == 0 || source[i - 1] != '\\')) {
                inString = !inString
            } else if (!inString) {
                when (c) {
                    '(' -> parenDepth++
                    ')' -> if (parenDepth > 0) parenDepth--
                    '{' -> if (parenDepth == 0) return i
                    '/' -> if (i + 1 < source.length && source[i + 1] == '/') {
                        val nl = source.indexOf('\n', i)
                        if (nl < 0) return -1
                        i = nl
                    }
                }
            }
            i++
        }
        return -1
    }

    /**
     * 从 `signature` 起取这个函数的片段（含签名行）。
     *
     * 不能直接 `indexOf('{', start)` 然后配平：`chatViewModelStoreOwner`
     * 是**表达式体**（`= ...` 后面没有 `{`），第一个 `{` 会落到后面另一个
     * 函数（buildExportChapters）的头上，截出完全无关的代码 ——
     * 断言对着别的函数报错，指错方向，比没有护栏还糟。
     *
     * 办法：先看签名那一行**紧跟着**是不是 `{`（块的写法）。
     * 是就配平取整个函数体；不是就取接下来若干行（表达式体就这么几行）。
     */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        if (start < 0) return ""
        val afterSignature = start + signature.length
        val braceOnSameLine = source.indexOf('\n', start).let { nl ->
            nl < 0 || source.substring(afterSignature, nl).contains('{')
        }
        if (braceOnSameLine) {
            val open = source.indexOf('{', afterSignature)
            if (open < 0) return ""
            val end = closeBrace(source, open)
            return if (end < 0) source.substring(start) else source.substring(start, end + 1)
        }
        // 表达式体：取接下来的 12 行，足够覆盖 `= a ?: b ?: c` 这种写法
        var end = 0
        var lines = 0
        var i = start
        while (i < source.length && lines < 12) {
            val nl = source.indexOf('\n', i)
            if (nl < 0) { end = source.length; break }
            end = nl
            i = nl + 1
            lines++
        }
        return source.substring(start, end)
    }

    /**
     * [open] 处的 `{` 所对应的 `}`，按大括号配平并跳过字符串字面量。
     * 找不到就返回 -1（宁可让上层拿"截到文件尾"，也不要给出错的区间）。
     */
    private fun closeBrace(source: String, open: Int): Int {
        var depth = 0
        var inString = false
        var i = open
        while (i < source.length) {
            val c = source[i]
            if (c == '"' && (i == 0 || source[i - 1] != '\\')) {
                inString = !inString
            } else if (!inString) {
                when (c) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                    '/' -> {
                        // 跳过 // 行注释，免得注释里的花括号把配平带偏
                        if (i + 1 < source.length && source[i + 1] == '/') {
                            val nl = source.indexOf('\n', i)
                            if (nl < 0) return -1
                            i = nl
                        }
                    }
                }
            }
            i++
        }
        return -1
    }
}
