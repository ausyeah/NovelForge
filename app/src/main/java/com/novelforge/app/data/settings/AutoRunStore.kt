package com.novelforge.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.autoRunDataStore by preferencesDataStore(name = "auto_run")

/**
 * 一键全自动的条目名。**observe / set / clear 只能走这一个函数。**
 *
 * 单独抽出来是因为「删书时清掉的 key 必须正好是写入时用的那个 key」这件事
 * JVM 上没有 Context 就测不了，而它必须被钉住：两边格式只要差一个前缀，
 * `remove` 就静默打在空气上 —— 不报错，开关还在，于是 id 复用后新书一建出来
 * 就是「已开全自动」，下一章生成成功立刻开始自动续写并计费。
 * 见 AutoRunStoreKeyTest。
 */
internal fun autoRunPreferencesKey(projectId: String) = booleanPreferencesKey("auto_run_$projectId")

/**
 * 写和删各自做成对 [MutablePreferences] 的操作。
 *
 * 这样 `edit { }` 里跑的代码和单元测试里跑的代码是同一份函数，而不是
 * 「测试里另写一遍 key 拼接」，那种测试只会断言自己写对了。
 */
internal fun MutablePreferences.writeAutoRun(projectId: String, enabled: Boolean) {
    this[autoRunPreferencesKey(projectId)] = enabled
}

internal fun MutablePreferences.clearAutoRun(projectId: String) {
    remove(autoRunPreferencesKey(projectId))
}

/**
 * 「一键全自动」必须按书存。
 *
 * 之前它挂在全局 AppSettings 上，于是：给 A 书打开全自动之后，B 书的顶栏也显示已打开；
 * 而 B 书任何一章生成成功都会调 driveAutoNext(B)，读到那个全局开关就开始自动往下写 B，
 * 直接开始计费。两个书共用一把锁，谁在跑都互相排队。
 */
class AutoRunStore(private val context: Context) {

    fun observe(projectId: String): Flow<Boolean> = context.autoRunDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences -> preferences[autoRunPreferencesKey(projectId)] ?: false }

    suspend fun set(projectId: String, enabled: Boolean) {
        context.autoRunDataStore.edit { preferences -> preferences.writeAutoRun(projectId, enabled) }
    }

    /**
     * 书被删除时一并清掉，避免 id 复用后继承到旧的开关状态。
     *
     * 调用方是 `LibraryViewModel.delete()`，和 `BookCoverStore.clear()` 并排放在那里。
     * 这个方法以前**全项目没有任何地方调用过** —— 键是写进去了，只是删书时没人回收。
     */
    suspend fun clear(projectId: String) {
        context.autoRunDataStore.edit { preferences -> preferences.clearAutoRun(projectId) }
    }
}
