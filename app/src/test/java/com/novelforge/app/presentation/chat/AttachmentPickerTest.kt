package com.novelforge.app.presentation.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 附件选择器不许「静默失败」——用户点下去必须要有反应（回归护栏）。
 *
 * 用户原话，连着报了两轮：
 *
 * > 文件依旧发送不出去，也没有提示，根本啥都没有
 * > 一个假按钮
 *
 * ## 真的原因：`PickVisualMedia` 的权限标记没在 manifest 里
 *
 * `ActivityResultContracts.PickVisualMedia` 需要 manifest 里声明
 * `<meta-data android:name="androidx.activity.result.contract.action.PICK_VISUAL_MEDIA_PERMISSION" android:value="true"/>`。
 * 缺了它，`launch()` **静默什么都不做**：不弹选择器、结果回调不触发、也不抛异常。
 *
 * 于是整条链是这样断的：
 * - 用户点「＋」→ 弹窗正常出现（这一步不依赖 manifest）
 * - 点「图片」→ `launch()` 静默失败，回调不触发
 * - 回调不触发 → `addImageAttachment` 不会被调 → 也没有机会写失败提示
 * - 界面上一句话都没有
 *
 * **看起来就是一个纯哑的按钮。** 而我之前两次让用户去看「输入框上方那行灰字」
 * 是彻底错的方向：那种失败根本走不到写提示的地方。
 *
 * ## 为什么必须有护栏
 *
 * 这类 bug 的特点是**编译照过、单测全绿、真机上什么都不发生**。
 * manifest 少一行 meta-data，没有任何编译期或运行期的检查会告诉你。
 * 唯一的证据是「用户点了没反应」。
 *
 * **已知不覆盖**：图片/文档选出来之后能不能真的发给模型（那需要真机 +
 * 真实模型），以及各类厂商 ROM 上系统选择器的可用性差异。
 */
class AttachmentPickerTest {

    @Test
    fun photoPickerPermissionMetaDataIsDeclared() {
        val manifest = manifestSource()
        assertTrue(
            "manifest 里没有 PICK_VISUAL_MEDIA_PERMISSION 的 meta-data —— " +
                "PickVisualMedia.launch() 会**静默失败**：不弹选择器、回调不触发、不报错。\n" +
                "用户看到的就是「＋ → 图片」然后没反应，一个纯哑的按钮。",
            manifest.contains("PICK_VISUAL_MEDIA_PERMISSION")
        )
        // 而且值必须是 true，写成 false 等于没写
        assertTrue(
            "PICK_VISUAL_MEDIA_PERMISSION 的值不是 true —— 等于没声明。",
            Regex("PICK_VISUAL_MEDIA_PERMISSION\"\\s+android:value=\"true\"").containsMatchIn(
                manifest.replace(Regex("\\s+"), " ")
            )
        )
    }

    @Test
    fun launchingThePickerIsWrappedSoFailuresSurface() {
        val code = stripComments(chatSource())
        assertTrue(
            "photoLauncher.launch 没有被包在 runCatching 里 —— 选择器打不开时（厂商 ROM、" +
                "系统组件缺失）异常会被吞掉，用户看不到任何原因。",
            Regex("runCatching\\s*\\{[\\s\\S]{0,200}?photoLauncher\\.launch").containsMatchIn(code)
        )
        assertTrue(
            "fileLauncher.launch 没有被包在 runCatching 里 —— 同上。",
            Regex("runCatching\\s*\\{[\\s\\S]{0,400}?fileLauncher\\.launch").containsMatchIn(code)
        )
        assertTrue(
            "launch 失败时没有写附件提示 —— 那正是「没有提示」的由来",
            code.contains("setAttachmentNotice(")
        )
    }

    @Test
    fun attachmentFailuresAreShownProminently() {
        val code = stripComments(chatSource())
        // 附件提示原来是一行 onSurfaceVariant + bodySmall 的淡灰小字。
        // 附件加不上时它是唯一的说明，而那个淡度让「有提示」和「没提示」看起来一样。
        //
        // 只在 `attachmentNotice?.let {` 那一段里找，别拿整个文件比 ——
        // 后面别处也有 onErrorContainer（消息气泡的中断标记就是红的），
        // 范围太宽这条就永远绿，等于没有。
        val start = code.indexOf("attachmentNotice?.let")
        assertTrue("找不到附件提示的渲染处，源码结构变了？", start >= 0)
        // 窗口是猜的 —— 第一个 900 字符切在 ✕ 之前，测试红而代码是对的。
        // 改成按「下一个 attachmentNotice 或对话历史面板」截断，跟住结构的边界。
        val end = listOfNotNull(
            code.indexOf("attachmentNotice?.let", start + 10).takeIf { it > 0 },
            code.indexOf("historyOpen", start).takeIf { it > 0 },
            code.length
        ).min()
        val segment = code.substring(start, end)
        assertTrue(
            "附件提示没有用 error 配色 —— 加不上附件时用户看不出来。\n" +
                "它原来是 onSurfaceVariant + bodySmall，淡到和背景几乎一样。\n\n" +
                "当前那段：\n$segment",
            segment.contains("onErrorContainer")
        )
        assertTrue(
            "附件提示应该带一个 ✕ 关闭按钮 —— 它是错误，用户需要能自己清掉",
            segment.contains("contentDescription = \"关闭附件提示\"")
        )
    }

    @Test
    fun thePlusButtonSaysWhatItDoes() {
        val code = stripComments(chatSource())
        assertTrue(
            "「＋」按钮的 contentDescription 丢了 —— 读屏用户听不出这是加附件",
            code.contains("添加图片或文件")
        )
    }

    // ------------------------------------------------------------------ 取文件

    private fun manifestSource(): String = readOne(
        listOf("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml")
    ) { it.name == "AndroidManifest.xml" }

    private fun chatSource(): String = readOne(
        listOf(
            "app/src/main/java/com/novelforge/app/presentation/chat",
            "src/main/java/com/novelforge/app/presentation/chat"
        )
    ) { it.name == "ChatScreen.kt" }

    private fun readOne(candidates: List<String>, match: (File) -> Boolean): String {
        val roots = listOfNotNull(
            File("").absoluteFile.takeIf { it.isDirectory },
            codeSourceDir()
        )
        for (root in roots) {
            for (candidate in candidates) {
                val f = File(root, candidate)
                if (f.isDirectory) {
                    val hit = f.walkTopDown().firstOrNull(match)
                    if (hit != null) return hit.readText()
                } else if (f.isFile && match(f)) {
                    return f.readText()
                }
            }
            // 从 app 子目录再试一次（测试常在 app/ 下运行）
            val sub = File(root, "app")
            if (sub.isDirectory) {
                for (candidate in candidates) {
                    val f = File(sub, candidate)
                    if (f.isDirectory) {
                        val hit = f.walkTopDown().firstOrNull(match)
                        if (hit != null) return hit.readText()
                    }
                }
            }
        }
        throw AssertionError("找不到 ${candidates.first()}（源码不在预期位置）")
    }

    private fun codeSourceDir(): File? = try {
        AttachmentPickerTest::class.java.protectionDomain?.codeSource?.location
            ?.toURI()?.let { File(it) }?.takeIf { it.isDirectory }
    } catch (_: Exception) {
        null
    }

    private fun stripComments(source: String): String = buildString {
        source.lineSequence().forEach { line ->
            var inString = false
            var cut = line.length
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '\\' && inString -> i++
                    c == '"' -> inString = !inString
                    !inString && c == '/' && i + 1 < line.length && line[i + 1] == '/' -> {
                        cut = i; i = line.length
                    }
                }
                if (i < line.length) i++
            }
            append(line, 0, cut).append('\n')
        }
    }
}
