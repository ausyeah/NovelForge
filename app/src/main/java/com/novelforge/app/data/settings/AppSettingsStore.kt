package com.novelforge.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")

data class AppSettings(
    val providerName: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val outputBudget: Int = 5_000,
    val costConfirmationEnabled: Boolean = true,
    val disableThinking: Boolean = true,
    val autoRunEnabled: Boolean = false,
    /** 主题模式：system / light / dark */
    val themeMode: String = "system"
)

class AppSettingsStore(private val context: Context) {
    val settings: Flow<AppSettings> = context.appSettingsDataStore.data.map { preferences ->
        readSettings(preferences)
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.appSettingsDataStore.edit { preferences ->
            val next = transform(readSettings(preferences))
            preferences[PROVIDER_NAME] = next.providerName
            preferences[BASE_URL] = next.baseUrl
            preferences[MODEL] = next.model
            preferences[OUTPUT_BUDGET] = next.outputBudget
            preferences[COST_CONFIRMATION] = next.costConfirmationEnabled
            preferences[DISABLE_THINKING] = next.disableThinking
            preferences[AUTO_RUN_ENABLED] = next.autoRunEnabled
            preferences[THEME_MODE] = next.themeMode
        }
    }

    private fun readSettings(
        preferences: androidx.datastore.preferences.core.Preferences
    ) = AppSettings(
        providerName = preferences[PROVIDER_NAME].orEmpty(),
        baseUrl = preferences[BASE_URL].orEmpty(),
        model = preferences[MODEL].orEmpty(),
        outputBudget = preferences[OUTPUT_BUDGET] ?: 5_000,
        costConfirmationEnabled = preferences[COST_CONFIRMATION] ?: true,
        disableThinking = preferences[DISABLE_THINKING] ?: true,
        autoRunEnabled = preferences[AUTO_RUN_ENABLED] ?: false,
        themeMode = preferences[THEME_MODE] ?: "system"
    )

    private companion object {
        val PROVIDER_NAME = stringPreferencesKey("provider_name")
        val BASE_URL = stringPreferencesKey("base_url")
        val MODEL = stringPreferencesKey("model")
        val OUTPUT_BUDGET = intPreferencesKey("output_budget")
        val COST_CONFIRMATION = booleanPreferencesKey("cost_confirmation")
        val DISABLE_THINKING = booleanPreferencesKey("disable_thinking")
        val AUTO_RUN_ENABLED = booleanPreferencesKey("auto_run_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }
}
