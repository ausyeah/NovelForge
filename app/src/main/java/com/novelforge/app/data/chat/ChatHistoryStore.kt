package com.novelforge.app.data.chat

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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
        context.chatHistoryDataStore.data
            .catch { emit(emptyPreferences()) }
            .map { preferences -> trim(decode(preferences[KEY])) }

    suspend fun save(conversation: StoredConversation) {
        context.chatHistoryDataStore.edit { preferences ->
            val list = decode(preferences[KEY]).filterNot { it.id == conversation.id }
            preferences[KEY] = json.encodeToString(SERIALIZER, trim(list + conversation))
        }
    }

    suspend fun delete(id: String) {
        context.chatHistoryDataStore.edit { preferences ->
            val list = decode(preferences[KEY]).filterNot { it.id == id }
            preferences[KEY] = json.encodeToString(SERIALIZER, trim(list))
        }
    }

    private fun decode(raw: String?): List<StoredConversation> =
        raw?.let { runCatching { json.decodeFromString(SERIALIZER, it) }.getOrNull() }
            .orEmpty()

    /** 历史无限堆在单个 DataStore 里，文件大到一定程度读出来会直接 OOM 闪退。 */
    private fun trim(list: List<StoredConversation>): List<StoredConversation> =
        list.sortedByDescending { it.updatedAt }
            .take(MAX_CONVERSATIONS)
            .map { conversation ->
                if (conversation.messages.size <= MAX_MESSAGES) conversation
                else conversation.copy(messages = conversation.messages.takeLast(MAX_MESSAGES))
            }

    private companion object {
        val KEY = stringPreferencesKey("conversations")
        val SERIALIZER = ListSerializer(StoredConversation.serializer())
        const val MAX_CONVERSATIONS = 30
        const val MAX_MESSAGES = 40
    }
}
