package com.novelforge.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.novelforge.app.data.security.KeystoreApiKeyStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.modelPresetDataStore by preferencesDataStore(name = "model_presets")

class ModelPresetStore(
    private val context: Context,
    private val keyStore: KeystoreApiKeyStore
) {
    private val json = Json { ignoreUnknownKeys = true }

    val presets: Flow<List<ModelPreset>> = context.modelPresetDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences -> decode(preferences[KEY]) }

    suspend fun read(): List<ModelPreset> = presets.first()

    suspend fun write(presets: List<ModelPreset>) {
        val stored = presets.map { preset ->
            StoredPreset(
                id = preset.id,
                label = preset.label,
                providerName = preset.providerName,
                baseUrl = preset.baseUrl,
                model = preset.model,
                disableThinking = preset.disableThinking,
                sealedKey = runCatching { keyStore.seal(preset.apiKey) }.getOrDefault("")
            )
        }
        context.modelPresetDataStore.edit { preferences ->
            preferences[KEY] = json.encodeToString(stored)
        }
    }

    private fun decode(raw: String?): List<ModelPreset> {
        if (raw.isNullOrBlank()) return emptyList()
        val stored = runCatching { json.decodeFromString<List<StoredPreset>>(raw) }.getOrNull() ?: return emptyList()
        return stored.mapNotNull { item ->
            val key = runCatching { keyStore.open(item.sealedKey) }.getOrNull() ?: return@mapNotNull null
            ModelPreset(
                id = item.id,
                label = item.label,
                providerName = item.providerName,
                baseUrl = item.baseUrl,
                model = item.model,
                disableThinking = item.disableThinking,
                apiKey = key
            )
        }
    }

    @Serializable
    private data class StoredPreset(
        val id: String,
        val label: String,
        val providerName: String,
        val baseUrl: String,
        val model: String,
        val disableThinking: Boolean = true,
        val sealedKey: String = ""
    )

    private companion object {
        val KEY = stringPreferencesKey("presets")
    }
}
