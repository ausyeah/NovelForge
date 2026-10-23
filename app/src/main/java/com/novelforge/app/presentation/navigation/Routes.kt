package com.novelforge.app.presentation.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 全 app 的路由常量。以前 12 条路由全是散落在 NavHost 里的裸字符串，
 * `popUpTo("creative-setup/$projectId")` 这类手拼字符串一旦写错会**静默失败**
 * （不抛异常，只是没弹栈），用户于是要连按十几次返回才能退出。
 */
object Routes {
    // —— 底部三个一级目的地 ——
    const val BOOKS = "books"
    const val CHAT = "chat"
    const val SETTINGS = "settings"

    // —— 设置的二级页（不是一级目的地，底部栏仍然停在「设置」）——
    const val LEDGER = "ledger"
    const val EXPORTS = "exports"

    // —— 新建一本 ——
    const val CREATE = "create"

    // —— 书内 ——
    const val CREATIVE_SETUP = "creative-setup/{projectId}"
    const val OUTLINE = "outline/{projectId}"
    const val MEMORY = "memory/{projectId}"
    const val CHAPTER = "chapter/{projectId}/{outlineItemId}"

    fun creativeSetup(projectId: String) = "creative-setup/$projectId"
    fun outline(projectId: String) = "outline/$projectId"
    fun outlineAuto(projectId: String) = "outline/$projectId?autostart=true"
    fun memory(projectId: String) = "memory/$projectId"
    fun chapter(projectId: String, itemId: String) =
        "chapter/$projectId/${encodeSegment(itemId)}"
    fun chapterAuto(projectId: String, itemId: String) =
        "chapter/$projectId/${encodeSegment(itemId)}?autostart=true"
    fun chat(projectId: String? = null) =
        if (projectId.isNullOrBlank()) CHAT else "chat?projectId=$projectId"

    /**
     * 路径段百分号编码。
     *
     * 不用 `android.net.Uri.encode`：那会让整个 Routes 在 JVM 单元测试里直接抛
     * 「not mocked」，于是这个最需要被测的地方反而测不了。不保留的字符集按
     * RFC 3986 的 unreserved，与 Android 的实现一致；`URLEncoder` 也不行，
     * 它把空格编成 `+`，而路径段里 `+` 是字面的加号。
     */
    internal fun encodeSegment(raw: String): String {
        if (raw.isEmpty()) return raw
        val out = StringBuilder(raw.length + 8)
        for (byte in raw.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            val unreserved = (char in 'a'..'z') || (char in 'A'..'Z') ||
                (char in '0'..'9') || char in "-_.!~*'()"
            if (unreserved) {
                out.append(char)
            } else {
                out.append('%').append(HEX[value shr 4]).append(HEX[value and 0x0F])
            }
        }
        return out.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}

/**
 * 底部栏的三个目的地。数量按 Material 的 3–5 个来定。
 *
 * [label] 是**给人读的**名字：画在无障碍 `contentDescription` 上，不再当图标用。
 * [icon] 是给眼睛看的 24dp 矢量图标，取自 `material-icons-core`（零新依赖）。
 */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    // ▤ → Icons.List：三条横线读起来就是一排书脊
    Books(Routes.BOOKS, "书架", Icons.AutoMirrored.Filled.List),
    // ✎ → Icons.Edit：铅笔，对应「写点什么」
    Chat(Routes.CHAT, "灵感", Icons.Filled.Edit),
    // ⚙ → Icons.Settings：唯一一个语义完全一致的
    Settings(Routes.SETTINGS, "设置", Icons.Filled.Settings);

    companion object {
        /**
         * 路由带 query 参数（`chat?projectId=p1`、`outline/p1?autostart=true`），
         * 所以这里自己先剥掉 query 再比，省得每个调用点都记得 substringBefore('?')。
         */
        fun fromRoute(route: String?): TopLevelDestination? =
            entries.firstOrNull { it.route == route?.substringBefore('?') }
    }
}

/**
 * 哪些页面显示底部栏。
 *
 * 创作设置和正文页是「单任务页」，按 Google 的说法属于可以隐藏底栏的情形：
 * 写正文时满屏只有字和进度，底栏是干扰。但**返回键必须留着** ——
 * 隐藏底栏又把返回藏起来是这份设计唯一明确禁止的失败模式。
 *
 * 书内的大纲页和本书记忆则保留底栏：它们的父级就是「书架」这个 tab，
 * 保留底栏才能一步跳回书架，而不用先退出这本书。
 *
 * ## 输入法弹起时一律隐藏（`imeVisible`）
 *
 * 这一条不是审美选择，是修一个实打实的布局 bug。
 *
 * 根因是三层高度对不上：
 * - Scaffold 把 bottomBar 的高度算进 `innerPadding`，于是 NavHost 的内容区
 *   变成「窗口高 − 底栏高」；
 * - 底栏自己**没有**加 imePadding，所以它被画在窗口最底部 —— 也就是**键盘底下**，
 *   用户根本看不见；
 * - 页面（比如 `ChatScreen`）在内容 Column 上加 `imePadding()`，把输入框压到
 *   「窗口高 − 底栏高 − 键盘高」的位置。
 *
 * 而键盘顶边在「窗口高 − 键盘高」。两个一减，**输入框和键盘之间正好空出
 * 一个底栏的高度**（约 80dp）。用户看到的就是「输入框浮在半空，够不着键盘」。
 *
 * 底栏在这个状态下既看不见也点不着，却仍然在占高度 —— 所以弹起输入法时直接
 * 不显示它：少一层没人看得见的控件，输入框贴回键盘，消息区还多出 80dp。
 *
 * 受影响的不止聊天：设置（API Key / Base URL）、大纲（改标题概要）、
 * 本书记忆（一堆输入框）都是「有底栏 + 有输入框」，同一个 bug。
 *
 * 安全性：这些页面顶栏都有「返回」，所以隐藏底栏不会让用户失去可见的退路。
 * 唯一没有顶栏返回可用的是书架页，而书架页没有输入框，这条规则对它不生效。
 * （**如果以后把书架页那个没用的返回也删了，这条安全性论证要重写。**）
 */
fun showsBottomBar(route: String?, imeVisible: Boolean = false): Boolean {
    if (imeVisible) return false
    if (route == null) return true
    // 只看第一段路径。这里必须按「填充后的实际路由」判断，
    // 所以不能拿 Routes.OUTLINE（"outline/{projectId}" 这个 pattern）去比 ——
    // 实际传进来的是 "outline/p1"，比不相等就会把书的 hub 错判成单任务页。
    val top = route.substringBefore('?').substringBefore('/')
    return top in BOTTOM_BAR_ROOTS
}

private val BOTTOM_BAR_ROOTS = setOf("books", "chat", "settings", "ledger", "exports", "outline", "memory")

@Composable
fun NovelForgeBottomBar(
    current: TopLevelDestination?,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(modifier = modifier) {
        TopLevelDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = current == destination,
                onClick = { onSelect(destination) },
                icon = {
                    // **用矢量图标，不用文字排版符号。**
                    //
                    // 原来是 `Text("▤")` / `Text("✎")` / `Text("⚙")`。两个问题：
                    // 1. `▤` `✎` `⚙` 都在 Unicode 的「杂项符号」区，**不是所有
                    //    OEM 字体都收**。缺字形时 Android 画一个豆腐块 □，
                    //    而且没有任何编译期或运行期告警 —— 只在用户那台机器上出现。
                    // 2. 按 `bodyLarge` = 16sp 画进 M3 的 **24dp 图标槽**，字形
                    //    撑不满也居不准，看起来像「图标没加载出来」。
                    //
                    // `material-icons-core` 里就有现成的（Settings / Edit / List），
                    // **不需要新依赖** —— 它已经是 material3 的传递依赖。
                    // 菜单/书籍类图标（MenuBook / AutoStories）在 icons-extended 里，
                    // 那个包我们没有，所以在这里能用的就这三个。
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(24.dp)
                            // 文字拿掉之后无障碍读屏就听不出这是哪一栏了 ——
                            // 图标本身没有名字。所以 label 字符串从可见文本降级成
                            // contentDescription：**内容一个字没少，只是不再画出来。**
                            .semantics { contentDescription = destination.label }
                    )
                },
                label = null,
                modifier = Modifier.padding(vertical = 0.dp)
            )
        }
    }
}
