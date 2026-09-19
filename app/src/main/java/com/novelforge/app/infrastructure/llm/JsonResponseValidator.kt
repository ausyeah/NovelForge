package com.novelforge.app.infrastructure.llm

import com.novelforge.app.domain.model.OutlineItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ChapterEnvelope(
    val summary: String,
    val content: String
)

@Serializable
data class OutlineEnvelope(
    val chapters: List<OutlineItem>
)

sealed interface JsonValidationResult<out T> {
    data class Success<T>(val value: T, val normalizedJson: String) : JsonValidationResult<T>
    data class Failure(val reason: String) : JsonValidationResult<Nothing>
}

class JsonResponseValidator(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
) {
    fun parseChapter(raw: String): JsonValidationResult<ChapterEnvelope> =
        decodeObject(stripInlineReasoning(raw)) { element ->
            val value = json.decodeFromJsonElement<ChapterEnvelope>(element)
            if (value.summary.isBlank()) return@decodeObject "summary 不能为空"
            if (value.content.isBlank()) return@decodeObject "content 不能为空"
            value
        }

    fun parseOutline(raw: String, expectedCount: Int? = null): JsonValidationResult<List<OutlineItem>> {
        return try {
            val cleaned = stripInlineReasoning(raw)
            val normalized = extractJsonValue(cleaned)
            if (normalized == null) {
                // 不对上游文字做过多规范：纯文本直接作为单章概要保留；
                // 但以 { 或 [ 开头却解析失败的，是残缺的 JSON 碎片，
                // 不能把碎片当概要存进大纲，留给手动修复或重试。
                val plainText = cleaned.trim()
                if (plainText.isEmpty()) {
                    return JsonValidationResult.Failure("未找到 JSON 数组或对象")
                }
                if (plainText.startsWith("{") || plainText.startsWith("[")) {
                    return JsonValidationResult.Failure("未找到完整的 JSON 数组或对象")
                }
                val item = OutlineItem(
                    id = "outline-item-1",
                    orderIndex = 0,
                    title = "第 1 章",
                    summary = plainText
                )
                return JsonValidationResult.Success(listOf(item), plainText)
            }
            val element = json.parseToJsonElement(normalized)
            val array = when (element) {
                is JsonArray -> element
                is JsonObject -> sequenceOf("chapters", "outline", "items")
                    .mapNotNull { key -> element[key] as? JsonArray }
                    .firstOrNull()
                    ?: element.takeIf(::looksLikeOutlineItem)?.let(::listOf)
                else -> null
            } ?: return JsonValidationResult.Failure("大纲 JSON 必须是数组或包含 chapters 数组的对象")
            var items = array.mapIndexed { index, item -> parseOutlineItem(item, index) }
            if (items.isEmpty()) return JsonValidationResult.Failure("大纲不能为空")
            // 上游多给了就只取前 N 个，不再整单拒绝
            if (expectedCount != null && items.size > expectedCount) {
                items = items.take(expectedCount)
            }
            if (items.any { it.summary.isBlank() }) {
                return JsonValidationResult.Failure("大纲存在空概要")
            }
            val usedIds = mutableSetOf<String>()
            val normalizedItems = items.mapIndexed { index, item ->
                var candidateId = item.id.ifBlank { "outline-item-${index + 1}" }
                if (!usedIds.add(candidateId)) {
                    candidateId = "outline-item-${index + 1}"
                    var suffix = 2
                    while (!usedIds.add(candidateId)) {
                        candidateId = "outline-item-${index + 1}-$suffix"
                        suffix++
                    }
                }
                item.copy(id = candidateId, orderIndex = index)
            }
            JsonValidationResult.Success(normalizedItems, normalized)
        } catch (error: Exception) {
            JsonValidationResult.Failure("大纲 JSON 无法解析：${error.message.orEmpty()}")
        }
    }

    private fun parseOutlineItem(element: JsonElement, index: Int): OutlineItem {
        val objectValue = element as? JsonObject
            ?: error("第 ${index + 1} 个大纲项必须是对象")

        fun firstText(vararg keys: String): String? = keys.asSequence()
            .mapNotNull { key -> objectValue[key] }
            .mapNotNull(::textValue)
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() }

        val id = firstText("id", "chapterId", "chapter_id").orEmpty()
        val orderIndex = objectValue["orderIndex"]?.let(::intValue)
            ?: objectValue["order"]?.let(::intValue)
            ?: index
        val title = firstText("title", "chapterTitle", "name", "chapterName", "heading")
            .orEmpty()
            .ifBlank { "第 ${index + 1} 章" }
        val summary = firstText(
            "summary",
            "description",
            "synopsis",
            "storyline",
            "plot",
            "content",
            "outline",
            "text"
        ).orEmpty().ifBlank { error("第 ${index + 1} 个大纲项缺少概要") }
        val characterChanges = objectValue["characterChanges"]?.let(::textValue)

        return OutlineItem(
            id = id,
            orderIndex = orderIndex,
            title = title,
            summary = summary,
            characterChanges = characterChanges
        )
    }

    private fun looksLikeOutlineItem(value: JsonObject): Boolean =
        value.keys.any { it in setOf("title", "chapterTitle", "name", "chapterName", "heading") } ||
            value.keys.any {
                it in setOf(
                    "summary", "description", "synopsis", "storyline",
                    "plot", "content", "outline", "text"
                )
            }

    private fun textValue(value: JsonElement): String? = when (value) {
        JsonNull -> null
        is JsonPrimitive -> value.contentOrNull
        is JsonArray -> value.mapNotNull(::textValue)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString("；")
            .takeIf { it.isNotEmpty() }
        else -> value.toString()
    }

    private fun intValue(value: JsonElement): Int? =
        value.jsonPrimitive.intOrNull ?: value.jsonPrimitive.contentOrNull?.toIntOrNull()

    private inline fun <reified T> decodeObject(
        raw: String,
        validate: (JsonElement) -> Any
    ): JsonValidationResult<T> {
        return try {
            val normalized = extractJsonValue(raw) ?: return JsonValidationResult.Failure("未找到 JSON 对象")
            val element = json.parseToJsonElement(normalized)
            if (element !is JsonObject) return JsonValidationResult.Failure("响应必须是 JSON 对象")
            val validationResult = validate(element)
            if (validationResult is String) return JsonValidationResult.Failure(validationResult)
            @Suppress("UNCHECKED_CAST")
            JsonValidationResult.Success(validationResult as T, normalized)
        } catch (error: Exception) {
            JsonValidationResult.Failure("JSON 无法解析：${error.message.orEmpty()}")
        }
    }

    companion object {
        fun extractJsonValue(raw: String): String? {
            val start = raw.indexOfFirst { it == '{' || it == '[' }
            if (start < 0) return null
            val opening = raw[start]
            val closing = if (opening == '{') '}' else ']'
            var depth = 0
            var inString = false
            var escaped = false
            for (index in start until raw.length) {
                val character = raw[index]
                if (inString) {
                    if (escaped) escaped = false
                    else if (character == '\\') escaped = true
                    else if (character == '"') inString = false
                    continue
                }
                when (character) {
                    '"' -> inString = true
                    opening -> depth++
                    closing -> {
                        depth--
                        if (depth == 0) return raw.substring(start, index + 1)
                    }
                }
            }
            return null
        }
    }
}

/**
 * 去掉模型输出中内联的思考片段，只保留正文：
 * - `<think>…</think>` 成对出现时整段剔除；
 * - 只有 `<think>` 没有闭合（输出额度被思考耗尽）时，丢弃到结尾的全部内容。
 */
fun stripInlineReasoning(raw: String): String {
    if (!raw.contains("<think>")) return raw
    val builder = StringBuilder()
    var cursor = 0
    while (true) {
        val start = raw.indexOf("<think>", cursor)
        if (start < 0) {
            builder.append(raw, cursor, raw.length)
            break
        }
        builder.append(raw, cursor, start)
        val end = raw.indexOf("</think>", start + "<think>".length)
        if (end < 0) break
        cursor = end + "</think>".length
    }
    return builder.toString().trim()
}
