package com.novelforge.app.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AgentStep(
    val kind: String,
    val title: String,
    val detail: String
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
                    val result = registry.call(projectId, decision.name, decision.arguments)
                    val detail = when (result) {
                        is ToolResult.Ok -> result.content
                        is ToolResult.Fail -> "失败：${result.reason}"
                    }
                    trace += AgentStep("tool", decision.name, detail)
                    onStep(trace.toList())
                }
            }
        }
        trace += AgentStep("reply", "回答", "已到 6 步上限，先停在这里。")
        onStep(trace.toList())
        return trace
    }
}
