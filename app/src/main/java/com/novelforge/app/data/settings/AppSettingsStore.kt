package com.novelforge.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")

data class AppSettings(
    val providerName: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val outputBudget: Int = 5_000,
    val costConfirmationEnabled: Boolean = true,
    val disableThinking: Boolean = true,
    /** 主题模式：system / light / dark */
    val themeMode: String = "system",
    /** 空字符串表示不使用自定义壁纸 */
    val wallpaperFileName: String = "",
    /** 壁纸上的纸色遮罩，0–100，越大字越清楚 */
    val wallpaperDim: Int = 75
)

class AppSettingsStore(private val context: Context) {
    val settings: Flow<AppSettings> = context.appSettingsDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences -> readSettings(preferences) }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.appSettingsDataStore.edit { preferences ->
            val next = transform(readSettings(preferences))
            preferences[PROVIDER_NAME] = next.providerName
            preferences[BASE_URL] = next.baseUrl
            preferences[MODEL] = next.model
            preferences[OUTPUT_BUDGET] = next.outputBudget
            preferences[COST_CONFIRMATION] = next.costConfirmationEnabled
            preferences[DISABLE_THINKING] = next.disableThinking
            preferences[THEME_MODE] = next.themeMode
            preferences[WALLPAPER_FILE] = next.wallpaperFileName
            preferences[WALLPAPER_DIM] = next.wallpaperDim
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
        themeMode = preferences[THEME_MODE] ?: "system",
        wallpaperFileName = preferences[WALLPAPER_FILE].orEmpty(),
        // 区间跟着 Theme.wallpaperDimRange 对齐：亮色下限 0.75、暗色 0.60，
        // 上限 0.95。这里还按旧的 35–90 夹的话，滑块永远够不到新的上端。
        wallpaperDim = (preferences[WALLPAPER_DIM] ?: 75).coerceIn(60, 95)
    )

    private companion object {
        val PROVIDER_NAME = stringPreferencesKey("provider_name")
        val BASE_URL = stringPreferencesKey("base_url")
        val MODEL = stringPreferencesKey("model")
        val OUTPUT_BUDGET = intPreferencesKey("output_budget")
        val COST_CONFIRMATION = booleanPreferencesKey("cost_confirmation")
        val DISABLE_THINKING = booleanPreferencesKey("disable_thinking")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val WALLPAPER_FILE = stringPreferencesKey("wallpaper_file")
        val WALLPAPER_DIM = intPreferencesKey("wallpaper_dim")
    }
}
