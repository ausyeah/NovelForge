package com.novelforge.app.data.chat

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.chatHistoryDataStore by preferencesDataStore(name = "chat_history")

@Serializable
data class StoredChatMessage(
    val role: String,
    val text: String,
    val reasoning: String = ""
)

@Serializable
data class StoredConversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<StoredChatMessage>
)

class ChatHistoryStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    val conversations: Flow<List<StoredConversation>> =
        context.chatHistoryDataStore.data.map { preferences ->
            decode(preferences[KEY]).sortedByDescending { it.updatedAt }
        }

    suspend fun save(conversation: StoredConversation) {
        context.chatHistoryDataStore.edit { preferences ->
            val list = decode(preferences[KEY]).filterNot { it.id == conversation.id }
            preferences[KEY] = json.encodeToString(SERIALIZER, list + conversation)
        }
    }

    suspend fun delete(id: String) {
        context.chatHistoryDataStore.edit { preferences ->
            val list = decode(preferences[KEY]).filterNot { it.id == id }
            preferences[KEY] = json.encodeToString(SERIALIZER, list)
        }
    }

    private fun decode(raw: String?): List<StoredConversation> =
        raw?.let { runCatching { json.decodeFromString(SERIALIZER, it) }.getOrNull() }
            .orEmpty()

    private companion object {
        val KEY = stringPreferencesKey("conversations")
        val SERIALIZER = ListSerializer(StoredConversation.serializer())
    }
}
