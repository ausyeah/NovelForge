package com.novelforge.app.data.agent

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.novelforge.app.agent.AgentStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.agentTraceDataStore by preferencesDataStore(name = "agent_traces")

class AgentTraceStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(AgentStep.serializer())

    fun observe(projectId: String): Flow<List<AgentStep>> = context.agentTraceDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences ->
            val raw = preferences[key(projectId)] ?: return@map emptyList()
            runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
        }

    suspend fun save(projectId: String, steps: List<AgentStep>) {
        context.agentTraceDataStore.edit { preferences ->
            preferences[key(projectId)] = json.encodeToString(serializer, steps.takeLast(12))
        }
    }

    private fun key(projectId: String) = stringPreferencesKey("trace_$projectId")
}
