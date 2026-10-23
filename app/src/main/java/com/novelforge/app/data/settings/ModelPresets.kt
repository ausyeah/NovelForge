package com.novelforge.app.data.settings

data class ModelPreset(
    val id: String,
    val label: String,
    val providerName: String,
    val baseUrl: String,
    val model: String,
    val disableThinking: Boolean,
    val apiKey: String
)

/**
 * 同一套接口（地址 + 模型 + Key）覆盖原预设。
 * 换了接口但服务商同名时，显示名加（2）、（3）。
 */
fun upsertModelPreset(
    existing: List<ModelPreset>,
    editingId: String?,
    providerName: String,
    baseUrl: String,
    model: String,
    disableThinking: Boolean,
    apiKey: String,
    newId: () -> String
): List<ModelPreset> {
    val normalizedUrl = baseUrl.trim().trimEnd('/')
    val normalizedModel = model.trim()
    val normalizedKey = apiKey.trim()
    val normalizedProvider = providerName.trim()
    val targetId = editingId?.takeIf { id -> existing.any { it.id == id } }
        ?: existing.firstOrNull {
            it.baseUrl.trim().trimEnd('/') == normalizedUrl &&
                it.model.trim() == normalizedModel &&
                it.apiKey.trim() == normalizedKey
        }?.id
    val label = presetLabel(
        existing = existing,
        providerName = normalizedProvider,
        baseUrl = normalizedUrl,
        exceptId = targetId
    )
    val preset = ModelPreset(
        id = targetId ?: newId(),
        label = label,
        providerName = normalizedProvider,
        baseUrl = normalizedUrl,
        model = normalizedModel,
        disableThinking = disableThinking,
        apiKey = normalizedKey
    )
    val without = if (targetId == null) existing else existing.filterNot { it.id == targetId }
    return (without + preset).takeLast(12)
}

fun presetLabel(
    existing: List<ModelPreset>,
    providerName: String,
    baseUrl: String,
    exceptId: String?
): String {
    val base = providerName.ifBlank {
        runCatching { java.net.URI(baseUrl).host }.getOrNull().orEmpty()
    }.ifBlank { "未命名" }
    val taken = existing.filter { it.id != exceptId }.map { it.label }.toSet()
    if (base !in taken) return base
    var index = 2
    while ("$base（$index）" in taken) index += 1
    return "$base（$index）"
}
