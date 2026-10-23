package com.novelforge.app.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentStep(
    val kind: String,
    val title: String,
    val detail: String,
    val tool: String = ""
)
