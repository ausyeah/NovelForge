package com.novelforge.app.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class AgentStep(
    val kind: String,
    val title: String,
    val detail: String,
    val tool: String = ""
)

sealed interface AgentDecision {
    data class CallTool(val name: String, val arguments: JsonObject) : AgentDecision
    data class Reply(val text: String) : AgentDecision
}

fun interface AgentModel {
    suspend fun decide(request: String, steps: List<AgentStep>, specs: List<ToolSpec>): AgentDecision
}

class NovelAgent(
    private val registry: NovelToolRegistry,
    private val model: AgentModel,
    private val maxSteps: Int = 6
) {
    suspend fun run(
        projectId: String,
        request: String,
        steps: List<AgentStep> = emptyList(),
        onStep: suspend (List<AgentStep>) -> Unit = {}
    ): List<AgentStep> {
        val trace = steps.toMutableList()
        var tools = trace.count { it.kind == "tool" }
        while (tools < maxSteps) {
            when (val decision = model.decide(request, trace.toList(), registry.specs())) {
                is AgentDecision.Reply -> {
                    trace += AgentStep("reply", "回答", decision.text.ifBlank { "没有更多要说的。" })
                    onStep(trace.toList())
                    return trace
                }
                is AgentDecision.CallTool -> {
                    tools += 1
                    val repeatedQueue = repeatedQueue(trace, decision)
                    if (repeatedQueue != null) {
                        trace += AgentStep("tool", toolTitle(decision.name), repeatedQueue, decision.name)
                        onStep(trace.toList())
                        continue
                    }
                    val result = registry.call(projectId, decision.name, decision.arguments)
                    val detail = when (result) {
                        is ToolResult.Ok -> result.content
                        is ToolResult.Fail -> "失败：${result.reason}"
                    }
                    trace += AgentStep("tool", toolTitle(decision.name), detail, decision.name)
                    onStep(trace.toList())
                }
            }
        }
        trace += AgentStep("reply", "回答", "已到 6 步上限，先停在这里。")
        onStep(trace.toList())
        return trace
    }
}

private fun repeatedQueue(trace: List<AgentStep>, decision: AgentDecision.CallTool): String? {
    if (decision.name != "queue_chapter") return null
    val chapterId = decision.arguments.text("chapterId")
    if (chapterId.isBlank()) return null
    val previous = trace.lastOrNull {
        it.kind == "tool" && it.tool == "queue_chapter" && it.detail.startsWith("已排队 $chapterId")
    } ?: return null
    return "已经排过，不再重复。${previous.detail}"
}

internal fun toolTitle(name: String): String = when (name) {
    "search_chapters" -> "搜索章节"
    "read_chapter" -> "阅读章节"
    "get_story_bible" -> "读取记忆"
    "propose_fact" -> "提议事实"
    "patch_outline" -> "修改大纲"
    "queue_chapter" -> "排队写章"
    else -> name
}

private fun JsonObject.text(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
