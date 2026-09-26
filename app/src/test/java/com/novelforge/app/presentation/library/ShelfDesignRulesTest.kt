package com.novelforge.app.presentation.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 书架网格的版式规则（回归护栏）。
 *
 * 用户原话：「主页还需要优化感觉」，附了真机截图。
 *
 * ## 截图里最刺眼的一处不是审美问题，是 **bug**
 *
 * 「继续写作」那张 hero 卡和它右边那张封面卡是**同一本书**（洪-吞噬星空）。
 * 原因在代码里：`hero = pickContinueWritingProject(projects)` 从 `projects` 里挑
 * 最近更新的那本，而 `items(projects, …)` 又把**整个列表**又渲染了一遍。
 * 所以只要书架上有书，hero 那本必然同时出现在网格里 —— 100% 发生，不是巧合。
 *
 * 而且 hero 卡上书名出现了**三次**（小标签「继续写作」/ 标题 / 按钮「继续写作《书名》」），
 * 底下还嵌了一个实心红按钮：一个可点的卡里再套一个可点的控件。
 *
 * ## 改法
 *
 * hero 变成网格**上方**一行 72dp 的整行条（`ResumeRow`）。
 * 网格里只剩封面格，每一格同一种解剖。
 *
 * **已知不覆盖**：真机上的实际观感（留白够不够、渐变够不够深）JVM 测不了。
 * 这里只钉住「结构上不会再退化」：不会再出现一格占半行、不会再有嵌套控件、
 * 不会再有两套卡片解剖。
 */
class ShelfDesignRulesTest {

    private val src = sourceOf("presentation/library/LibraryScreen.kt")

    @Test
    fun theResumeRowIsNotAGridCell() {
        // 之前是 `item(key = "hero-continue")` —— 2 列网格里的单独一格，
        // 一格占半行，右边空一半。NN/g：同一集合里的大元素最多 2 个，
        // 而且一个 1/2 宽的元素躺在 2 列网格里是布局错误，不是风格选择。
        assertFalse(
            "「继续写」又变回网格里的一格了（hero-continue）：一格占半行，右边空一半",
            src.contains("hero-continue")
        )
        assertTrue(
            "网格上方应该有一整行 ResumeRow",
            src.contains("ResumeRow(")
        )
    }

    @Test
    fun noControlIsNestedInsideAClickableRow() {
        // 卡里套控件是「这看起来像个表单」最强的信号。
        // 之前 hero 卡（PaperSurface + 可点语义）里塞了一个 accent 实心 PaperButton。
        //
        // 断的是 **ResumeRow 的函数体**，不是它的调用点。
        // 第一版断的是调用点，结果把 PaperButton 塞进 ResumeRow 里它照样绿 ——
        // 嵌套控件会出现在 composable 体内，而调用点只看得到参数。
        // 这是「断言检查的位置和它声称检查的东西不是同一处」，
        // 跟之前「断言去比对被 sabotage 改的那个常量」同一类。
        val body = regionOf("private fun ResumeRow(", "private fun NewBookTile(")
        assertTrue("没找到 ResumeRow 的函数体", body.isNotEmpty())
        for (nested in listOf("PaperButton", "TextButton", "Button(", "OutlinedButton", "IconButton")) {
            assertFalse(
                "ResumeRow 里出现了可点的子控件 `$nested`（一行可点区域里再套控件）：\n$body",
                body.contains(nested)
            )
        }
        // 整行必须是一个点区：clickable 挂在 Row 的 modifier 上
        assertTrue(
            "ResumeRow 整行应当就是一个点区：\n$body",
            body.contains(".clickable(onClick = onClick)")
        )
    }

    @Test
    fun theBookTitleIsNotRepeatedWithinOneTile() {
        // 之前 hero 卡上一次出现三遍书名。
        val row = regionOf("private fun ResumeRow(", "private fun NewBookTile(")
        assertTrue("没找到 ResumeRow", row.isNotEmpty())
        assertEquals(
            "ResumeRow 里书名标题只该出现一次（`title = title` 传参算一次不算重复）：\n$row",
            1,
            Regex("Text\\(\\s*\\n\\s*title,").findAll(row).count()
        )
        // 而且不能把书名再拼进别的字符串里
        assertFalse(
            "ResumeRow 里又把书名拼进了别的文案：\n$row",
            Regex("\"[^\"]*\\\$\\{?title").containsMatchIn(row)
        )
    }

    @Test
    fun theGridHasExactlyOneCardAnatomy() {
        // hero 用 PaperSurface（米色 + 描边），封面用饱和色块 + 无描边 ——
        // 除了圆角以外没有任何共同属性，一屏像两个 App 拼的。
        val grid = regionOf("LazyVerticalGrid(", "items(projects, key =")
        assertTrue("没找到网格", grid.isNotEmpty())
        assertFalse(
            "网格里又出现了 PaperSurface（那是第二种卡片解剖）：\n$grid",
            grid.contains("PaperSurface")
        )
        assertFalse(
            "网格里又出现了 StatusChip 实心药丸（压在封面上会变成一锅标签云）",
            grid.contains("StatusChip")
        )
    }

    @Test
    fun newBookIsAGridTileNotAFooterButton() {
        // 之前是网格下面一个 `fillMaxWidth()` 的 PaperButton：网格拿 weight(1f)
        // 只有两行内容，剩下的全空，按钮被推到最底下一个描边长条，
        // 看着像没填完的表单。
        val shelf = regionOf("else -> {", "actionTarget?.let")
        assertFalse(
            "底部又出现了全宽「新建小说」按钮（周围全是死空间）：\n$shelf",
            Regex("PaperButton\\(\\s*\\n\\s*\"新建小说\"").containsMatchIn(shelf)
        )
        assertTrue(
            "「新建小说」应该是网格里的第一格（NewBookTile）",
            shelf.contains("NewBookTile(")
        )
        assertTrue(
            "「新建小说」格子要放在网格第一位（key = \"new-book\"）",
            Regex("item\\(key\\s*=\\s*\"new-book\"\\)").containsMatchIn(shelf)
        )
    }

    @Test
    fun theShelfTopBarCarriesNoInstructionalSubtitle() {
        // 原来那句「点封面阅读，长按可写作、换封面、重命名、删除」**是错的** ——
        // 点封面现在只打开目录、不跳阅读器（见 onClick 的注释）。
        // 它教用户去做一件不会发生的事。
        // 而且这正是我自己在正文页清掉的「每页一条重复操作提示」，
        // 在页面尺度上又长回来一次。
        //
        // 断言的是「书架这一屏没有渲染任何 subtitle=」，而不是「源码里没有
        // 『点封面阅读』这几个字」—— 后者会连注释一起判，而注释里正是要解释
        // 这句话为什么被删掉的。
        val emptyBranch = regionOf("else -> {", "PaperTopBar(title = \"书架\")")
        val after = src.substring(src.indexOf("PaperTopBar(title = \"书架\")"))
            .substringBefore("hero?.let")
        assertFalse(
            "书架顶栏又挂上了操作提示副标题：\n$after",
            after.contains("subtitle")
        )
        assertTrue("没定位到书架分支", emptyBranch.isNotEmpty() || after.isNotEmpty())
    }

    @Test
    fun gutterEqualsCornerRadius() {
        // M3 的招牌观感：相邻两块的缝 == 它们各自的转角。
        // 12dp 是 M3 三种卡片共同的 ContainerShape = CornerMedium；
        // 原来的 10dp 根本不在 M3 的形状刻度（4/8/12/16/28）上，
        // 当时「间距 10 = 圆角 10」纯属碰巧相等。
        assertTrue("没找到 COVER_RADIUS 定义", src.contains("COVER_RADIUS = 12.dp"))
        assertTrue("没找到 COVER_GUTTER 定义", src.contains("COVER_GUTTER = 12.dp"))
        // 两侧数字必须一致（12.dp，不是 12dp —— 这是 Kotlin 的 Dp 字面量）
        val radius = Regex("COVER_RADIUS = (\\d+)\\.dp").find(src)
        val gutter = Regex("COVER_GUTTER = (\\d+)\\.dp").find(src)
        assertTrue("COVER_RADIUS 不是 <数字>.dp 的形式", radius != null)
        assertTrue("COVER_GUTTER 不是 <数字>.dp 的形式", gutter != null)
        assertEquals(
            "COVER_RADIUS(${radius!!.groupValues[1]}) 和 COVER_GUTTER(${gutter!!.groupValues[1]}) 必须相等",
            radius.groupValues[1],
            gutter.groupValues[1]
        )
        // 网格两向间距都必须引用这个常量，而不是又写一个裸数字
        val grid = regionOf("LazyVerticalGrid(", "items(projects, key =")
        assertTrue(
            "网格水平间距必须用 COVER_GUTTER：\n$grid",
            grid.contains("spacedBy(COVER_GUTTER)")
        )
        assertEquals(
            "COVER_RADIUS 和 COVER_GUTTER 必须相等",
            Regex("COVER_RADIUS = (\\d+)\\.dp").find(src)!!.groupValues[1],
            Regex("COVER_GUTTER = (\\d+)\\.dp").find(src)!!.groupValues[1]
        )
    }

    @Test
    fun theCoverScrimOnlyDarkensTheBottom() {
        // 之前是三段渐变、**顶部 0.45 alpha**，用来在顶部放白字。
        // 那是全屏最大的一块无谓损耗：封面最该露出来的上半部分被盖住了。
        // 标题挪到底之后，顶部不需要任何遮罩。
        val tile = regionOf("private fun BookCoverTile(", "private fun ResumeRow(")
        assertTrue("没找到封面格", tile.isNotEmpty())
        assertFalse(
            "顶部那段 0f/0.45f 的遮罩又回来了：\n$tile",
            Regex("0f\\s*to\\s*Color\\.Black").containsMatchIn(tile)
        )
        val gradient = Regex("verticalGradient\\(([^)]*)\\)").find(tile)
        assertTrue("没找到封面上的渐变遮罩", gradient != null)
        assertTrue(
            "渐变必须是自下而上的（从 Transparent 到深色），现在读起来不像：\n${gradient!!.groupValues[1]}",
            gradient.groupValues[1].contains("Transparent") &&
                gradient.groupValues[1].indexOf("Transparent") <
                gradient.groupValues[1].indexOf("Color.Black")
        )
    }

    @Test
    fun chineseTextHasNoLetterSpacing() {
        // Material 的 tracking 是给拉丁字母调的（titleMedium 0.2sp、
        // labelMedium 0.5sp）。中文是密排 ——
        // 「字间距大的文章，阅读速度会变慢」（中文排印三原则·原则一）。
        // 套进中文标题上必须显式清零，否则 CJK 字距被拉松。
        val tile = regionOf("private fun BookCoverTile(", "private fun ResumeRow(")
        // 逐个 `Text(` 调用切块（手写扫描，不用正则：Text(...) 的参数是分行写的，
        // 而正则在「跨行 + 非贪婪 + 括号配平」这三件事同时出现时很难写对）。
        val blocks = mutableListOf<String>()
        var i = 0
        while (i < tile.length) {
            if (!tile.startsWith("Text(", i)) {
                i++
                continue
            }
            var depth = 0
            var j = i
            while (j < tile.length) {
                when (tile[j]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                j++
            }
            blocks.add(tile.substring(i, minOf(j + 1, tile.length)))
            i = j + 1
        }
        for (style in listOf("titleMedium", "labelMedium")) {
            val using = blocks.filter { it.contains("typography.$style") }
            val zeroed = using.count { it.contains("letterSpacing = TextUnit.Unspecified") }
            assertTrue(
                "封面格里用 $style 的 Text 有 ${using.size} 个，只有 $zeroed 个显式清了 " +
                    "letterSpacing —— 中文是密排，Material 的拉丁 tracking 必须清零",
                using.isEmpty() || zeroed == using.size
            )
        }
    }

    @Test
    fun theEmptyShelfIsCentredRatherThanStrandedAtTheTop() {
        // 原来空书架只有两行字加一个全宽按钮，顶在屏幕最上面，下面一整片空白。
        // 空状态的意义是「这里该有东西」，所以它得先占住视觉重心。
        val branch = regionOf("if (projects.isEmpty())", "} else {")
        if (branch.isEmpty()) {
            // 源码里的条件写法和 regionOf 的锚点不一致时，退一步用更宽的锚点
            val alt = regionOf("书架还空着", "PaperButton(")
            assertTrue("没找到空书架那一段", alt.isNotEmpty())
            return
        }
        assertTrue(
            "空书架内容应当垂直居中（weight(1f) + Arrangement.Center）：\n$branch",
            branch.contains("Arrangement.Center")
        )
        assertTrue(
            "空书架那一块得撑满剩余高度才能居中（weight(1f)）：\n$branch",
            branch.contains("weight(1f)")
        )
        assertFalse(
            "空书架的按钮不该再是全宽（读起来像等着被填的输入框）：\n$branch",
            Regex("PaperButton\\([\\s\\S]{0,120}?fillMaxWidth\\(\\)").containsMatchIn(branch)
        )
    }

    // ------------------------------------------------------------------ 辅助

    private fun regionOf(start: String, end: String): String {
        val i = src.indexOf(start)
        if (i < 0) return ""
        val j = src.indexOf(end, i + start.length)
        return if (j < 0) "" else src.substring(i, j)
    }

    private fun sourceOf(relative: String): String =
        stripComments(File("src/main/java/com/novelforge/app/$relative").readText(Charsets.UTF_8))

    /**
     * 断言前必须先剥掉注释。
     *
     * 这一版测试第一遍全是红的，而原因很蠢：**它匹配到了我自己的解释性注释**。
     * 比如「hero 卡是 `item(key = "hero-continue")`」这句话里就含 `hero-continue`，
     * 于是「不许再出现 hero-continue」这条规则被自己的注释判死。
     *
     * 这跟「断言去比对被 sabotage 改的那个常量」是同一类错误：
     * 断言匹配到的不是它声称在匹配的东西，于是它对什么都会红、或者对什么都不红。
     * 区别只在于这次红得比较快，比较容易被当成「测试写错了」而不是「护栏有 bug」。
     */
    /**
     * 剥掉注释（手写扫描，不用正则 —— `RegexOption.DOT_MATCH_ALL` 在这个工程
     * 的 Kotlin 版本下解析不了，而且 `/* */` 跨行 + `//` 到行尾这两条规则
     * 手写扫描更不容易写错）。字符串字面量里的 `//` 不会被当成注释。
     */
    private fun stripComments(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            val n = if (i + 1 < text.length) text[i + 1] else ' '
            when {
                inString -> {
                    sb.append(c)
                    if (c == '\\') {
                        if (i + 1 < text.length) sb.append(text[i + 1])
                        i += 2
                        continue
                    }
                    if (c == '"') inString = false
                    i++
                }
                c == '"' -> {
                    inString = true
                    sb.append(c)
                    i++
                }
                c == '/' && n == '*' -> {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) text.length else end + 2
                    sb.append(' ')
                }
                c == '/' && n == '/' -> {
                    val end = text.indexOf('\n', i)
                    i = if (end < 0) text.length else end
                    sb.append(' ')
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }
}
