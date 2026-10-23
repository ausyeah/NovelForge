package com.novelforge.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/** 记录每本书上次读到第几章（orderIndex），续读按钮用 */
class ReadingPositionStore(private val context: Context) {
    private val dataStore = PreferenceDataStoreFactory.create {
        context.preferencesDataStoreFile("reading_positions")
    }

    private fun key(projectId: String) = intPreferencesKey("last_read_$projectId")

    suspend fun lastRead(projectId: String): Int? = dataStore.data
        .catch { emit(emptyPreferences()) }
        .first()[key(projectId)]

    suspend fun record(projectId: String, orderIndex: Int) {
        dataStore.edit { it[key(projectId)] = orderIndex }
    }
}
