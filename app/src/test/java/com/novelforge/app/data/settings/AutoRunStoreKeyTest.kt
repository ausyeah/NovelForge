package com.novelforge.app.data.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「删书时清掉的 key 必须正好是写入时用的那个 key」。
 *
 * 这个错最麻烦的地方在于它**不报错**：`remove` 打在一个不存在的条目上是成功的，
 * 于是清理看起来干完了，开关其实还在原地。项目 id 会复用，新书一建出来就是
 * 「已开全自动」，下一章生成成功立刻自动续写 —— 直接开始计费，用户从没按过开关。
 *
 * 所以这里跑的是生产代码里那两个函数本身（`writeAutoRun` / `clearAutoRun`，
 * 也就是 `AutoRunStore.set` / `clear` 的 `edit { }` 块里跑的同一份），
 * 而不是另写一遍 key 拼接去断言自己 —— 那样测不到真正的失配点。
 * `AutoRunStore` 本身要 Context，JVM 上起不来，所以拆成了这两个纯函数来测。
 */
class AutoRunStoreKeyTest {

    /** 读法必须和 AutoRunStore.observe 一致：条目不存在时读作 false。 */
    private fun MutablePreferences.autoRunOf(projectId: String): Boolean =
        this[autoRunPreferencesKey(projectId)] ?: false

    @Test
    fun clearRemovesExactlyTheEntryThatSetWrote() {
        val preferences = mutablePreferencesOf()
        preferences.writeAutoRun("book-A", true)
        assertTrue(preferences.autoRunOf("book-A"))

        preferences.clearAutoRun("book-A")

        assertFalse("清完之后这本书必须读成「没开全自动」", preferences.autoRunOf("book-A"))
        assertNull(
            "条目应当被真的删掉，而不是被写成 false —— 留着 false 也算 id 复用时会读到的状态",
            preferences[autoRunPreferencesKey("book-A")]
        )
    }

    /** 删 A 绝不能带走 B 的开关。这正是当年「A 书的开关驱动 B 书」那个事故的形状。 */
    @Test
    fun clearingOneBookLeavesTheOtherBooksFlagAlone() {
        val preferences = mutablePreferencesOf()
        preferences.writeAutoRun("book-A", true)
        preferences.writeAutoRun("book-B", true)

        preferences.clearAutoRun("book-A")

        assertFalse(preferences.autoRunOf("book-A"))
        assertTrue("A 书的清理不能动 B 书的开关：${preferences}", preferences.autoRunOf("book-B"))
    }

    /**
     * 阴性对照：证明上面那些断言不是空转。
     *
     * 这里故意用一个和写入端不一致的 key 去清（模拟前缀写错这类真实失配），
     * 观测必须仍然读得到 true —— 也就是说这些测试**确实**能看见那个失败模式。
     */
    @Test
    fun theObservationDetectsAMismatchedClearKey() {
        val preferences = mutablePreferencesOf()
        preferences.writeAutoRun("book-A", true)

        // 模拟「clear 用的 key 和 set 用的不是同一个」：前缀不同，清不到。
        preferences.remove(booleanPreferencesKey("autoRun-book-A"))

        assertTrue(
            "key 失配时开关必须仍读得到 true，否则上面几条测试证明不了任何东西",
            preferences.autoRunOf("book-A")
        )
    }

    /** 条目名按 projectId 拼，所以两本书不可能落进同一个条目。 */
    @Test
    fun everyBookGetsItsOwnEntry() {
        assertEquals("auto_run_book-A", autoRunPreferencesKey("book-A").name)
        val ids = listOf("book-A", "book-AB", "book-A2", "A", "")
        val names = ids.map { autoRunPreferencesKey(it).name }
        assertEquals("不同 projectId 必须落到不同条目：$names", names.size, names.distinct().size)
        assertNotEquals(autoRunPreferencesKey("book-A"), autoRunPreferencesKey("book-B"))
    }
}
