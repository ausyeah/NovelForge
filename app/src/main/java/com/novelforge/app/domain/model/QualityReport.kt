package com.novelforge.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class QualityReport(
    val runId: String,
    val pass: Boolean,
    val localChecks: List<QualityCheck> = emptyList(),
    val llmChecks: List<QualityCheck> = emptyList(),
    val issues: List<QualityIssue> = emptyList(),
    val iterations: Int = 0
)

@Serializable
data class QualityCheck(
    val type: String,
    val passed: Boolean,
    val details: String? = null
)

@Serializable
data class QualityIssue(
    val type: String,
    val severity: IssueSeverity,
    val paragraphId: String? = null,
    val quote: String? = null,
    val details: String,
    val suggestion: String? = null
)

@Serializable
enum class IssueSeverity {
    LOW,
    MEDIUM,
    HIGH
}
