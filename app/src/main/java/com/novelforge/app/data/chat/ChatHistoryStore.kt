package com.novelforge.app.data.chat

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.chatHistoryDataStore by preferencesDataStore(name = "chat_history")

/** 历史遗留的全局键名：全局桶继续用它，老数据不用迁移也能读出来。 */
private const val LEGACY_KEY_NAME = "conversations"

@Serializable
data class StoredChatMessage(
    val role: String,
    val text: String,
    val reasoning: String = "",
    /**
     * 附件只存文件路径，字节在 files/chat-attachments/ 下。
     * 内联 base64 的话，一张 2 MB 的图就是 2.8 MB，40 条消息把 DataStore
     * 撑到 100 MB —— 而它是在主线程反序列化的，直接 ANR。
     * 可空 + 默认值：老 JSON 没有这个字段照样解得出来。
     */
    val attachments: List<StoredAttachment> = emptyList(),
    /**
     * 这条回复是中途断掉的（进程被系统杀掉），不是模型说完的。
     *
     * 定期存档会把流到一半的内容落盘，所以最后一条经常是半句。
     * 不标出来的话，用户从历史里翻出来会以为模型就只答了这么多。
     * 可空 + 默认 false：老 JSON 没这个字段解得出来，按"正常"处理。
     */
    val interrupted: Boolean = false
)

@Serializable
data class StoredConversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<StoredChatMessage>,
    /**
     * 这条会话属于哪本书。
     * 以前所有会话都挤在同一个 DataStore 键里，提问又会把整段历史重发一遍，
     * 于是 A 书的人物讨论会被原样塞进 B 书的请求，模型把两本书的人物混成一套。
     * 可空 + 默认值：老 JSON 没有这个字段照样能解出来，落到全局桶，不丢数据。
     */
    val projectId: String? = null,
    /**
     * 这段对话要不要「思考过程」。
     *
     * 以前是硬编码的：灵感助手永远带思考。开着能看见模型怎么想的，
     * 但明显更慢、token 烧得更多，而且有的模型会先thinking很久才吐第一个字。
     * 砍脑子的场景（快速追问设定、让它直接给方案）就很难受。
     * 可空 + 默认 true：老会话没这个字段就按以前的「一直开」处理。
     */
    val thinkingEnabled: Boolean = true
)

/** 全局「灵感」桶：拿不到书上下文时的作用域，老会话也归这里。 */
const val GLOBAL_CHAT_SCOPE: String = ""

/** 作用域归一化：空白一律等价于"没有书"，免得 null / "" / "  " 变成三个桶。 */
fun normalizeChatScope(projectId: String?): String = projectId?.trim().orEmpty()

/** 两条会话是否属于同一个作用域（null / 空白 == 全局桶）。 */
fun sameChatScope(stored: String?, requested: String?): Boolean =
    normalizeChatScope(stored) == normalizeChatScope(requested)

/**
 * 一个作用域一个 DataStore 键。全局桶沿用历史遗留的键名，老数据原地可读；
 * 各本书的会话从此真正分开存放，跨书读不到。
 */
fun chatScopePreferenceName(projectId: String?): String {
    val scope = normalizeChatScope(projectId)
    return if (scope.isEmpty()) LEGACY_KEY_NAME else "$LEGACY_KEY_NAME.p-$scope"
}

/**
 * 读侧再按作用域过滤一次：DataStore 里同一份列表是按整条记录的 projectId 存的，
 * 就算有记录因为历史原因落在了别的桶里，也不会被本作用域读到。
 */
fun selectChatScope(
    conversations: List<StoredConversation>,
    projectId: String?
): List<StoredConversation> = conversations.filter { sameChatScope(it.projectId, projectId) }

class ChatHistoryStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 全局「灵感」桶（没有书上下文时的默认作用域）。 */
    val conversations: Flow<List<StoredConversation>> = conversationsIn(GLOBAL_CHAT_SCOPE)

    /**
     * 指定作用域的会话流。
     * flowOn 必须在这里：JSON 反序列化动辄几 MB，ChatViewModel 是在
     * viewModelScope(= Dispatchers.Main.immediate) 上收集它的，
     * 以前首开和每次回显自己的保存都会在主线程上解析一遍。
     * distinctUntilChanged 掐掉"读回来又原样写回去"的自激回声。
     */
    fun conversationsIn(projectId: String?): Flow<List<StoredConversation>> {
        val key = scopeKey(projectId)
        return context.chatHistoryDataStore.data
            .catch { emit(emptyPreferences()) }
            .map { preferences -> selectChatScope(trim(decode(preferences[key])), projectId) }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()
    }

    /** 会话按自己的 projectId 落桶，不需要调用方再传一遍作用域。 */
    suspend fun save(conversation: StoredConversation) {
        val key = scopeKey(conversation.projectId)
        context.chatHistoryDataStore.edit { preferences ->
            val list = decode(preferences[key]).filterNot { it.id == conversation.id }
            preferences[key] = json.encodeToString(SERIALIZER, trim(list + conversation))
        }
    }

    /**
     * 只改一段会话的「思考」开关，不碰消息。
     *
     * 不能让调用方走 save(整段)：那样要把整份会话读出来、改一个字段、再写回去，
     * 而这份会话可能有好几 MB 文本。
     */
    suspend fun updateThinking(id: String, projectId: String?, enabled: Boolean) {
        val key = scopeKey(projectId)
        context.chatHistoryDataStore.edit { preferences ->
            val list = decode(preferences[key])
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) return@edit
            preferences[key] = json.encodeToString(
                SERIALIZER,
                list.toMutableList().also { it[index] = it[index].copy(thinkingEnabled = enabled) }
            )
        }
    }

    /** 只能删本作用域里的会话：别把别的桶的记录误删。 */
    suspend fun delete(id: String, projectId: String? = null) {
        val key = scopeKey(projectId)
        context.chatHistoryDataStore.edit { preferences ->
            val list = decode(preferences[key]).filterNot { it.id == id }
            preferences[key] = json.encodeToString(SERIALIZER, trim(list))
        }
    }

    private fun scopeKey(projectId: String?): Preferences.Key<String> =
        stringPreferencesKey(chatScopePreferenceName(projectId))

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
        val SERIALIZER = ListSerializer(StoredConversation.serializer())
        const val MAX_CONVERSATIONS = 30
        const val MAX_MESSAGES = 40
    }
}
