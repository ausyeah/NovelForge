package com.novelforge.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.autoRunDataStore by preferencesDataStore(name = "auto_run")

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
        .map { preferences -> preferences[key(projectId)] ?: false }

    suspend fun set(projectId: String, enabled: Boolean) {
        context.autoRunDataStore.edit { preferences -> preferences[key(projectId)] = enabled }
    }

    /** 书被删除时一并清掉，避免 id 复用后继承到旧的开关状态。 */
    suspend fun clear(projectId: String) {
        context.autoRunDataStore.edit { preferences -> preferences.remove(key(projectId)) }
    }

    private fun key(projectId: String) = booleanPreferencesKey("auto_run_$projectId")
}
