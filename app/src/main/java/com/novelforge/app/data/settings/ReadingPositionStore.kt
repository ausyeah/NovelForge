package com.novelforge.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 记录每本书上次读到的章节（orderIndex），用于书架一键续读 */
class ReadingPositionStore(private val context: Context) {
    private val dataStore = PreferenceDataStoreFactory.create {
        context.preferencesDataStoreFile("reading_positions")
    }

    private fun key(projectId: String) = intPreferencesKey("last_read_$projectId")

    fun observe(projectId: String): Flow<Int?> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[key(projectId)] }

    suspend fun lastRead(projectId: String): Int? = dataStore.data
        .catch { emit(emptyPreferences()) }
        .first()[key(projectId)]

    suspend fun record(projectId: String, orderIndex: Int) {
        dataStore.edit { it[key(projectId)] = orderIndex }
    }
}
