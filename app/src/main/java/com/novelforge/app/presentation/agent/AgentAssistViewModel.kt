package com.novelforge.app.presentation.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.novelforge.app.agent.AgentModel
import com.novelforge.app.agent.AgentStep
import com.novelforge.app.agent.NovelAgent
import com.novelforge.app.agent.NovelToolRegistry
import com.novelforge.app.data.agent.AgentTraceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AgentAssistViewModel(
    private val projectId: String,
    registry: NovelToolRegistry,
    model: AgentModel,
    private val traceStore: AgentTraceStore
) : ViewModel() {
    private val agent = NovelAgent(registry, model)
    private var request: String = ""

    val steps: StateFlow<List<AgentStep>> = traceStore.observe(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun ask(text: String) {
        val normalized = text.trim()
        if (normalized.isEmpty() || _busy.value) return
        runFrom(listOf(AgentStep("user", "提问", normalized)), normalized)
    }

    fun continueRun() {
        if (_busy.value) return
        val saved = steps.value
        if (saved.lastOrNull()?.kind == "reply") return
        val question = saved.lastOrNull { it.kind == "user" }?.detail.orEmpty()
        if (question.isBlank()) return
        runFrom(saved, question)
    }

    private fun runFrom(start: List<AgentStep>, question: String) {
        request = question
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            traceStore.save(projectId, start)
            runCatching {
                agent.run(projectId, question, start) { latest ->
                    traceStore.save(projectId, latest)
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                _error.value = error.message ?: "查书中断"
            }
            _busy.value = false
        }
    }

    class Factory(
        private val projectId: String,
        private val registry: NovelToolRegistry,
        private val model: AgentModel,
        private val traceStore: AgentTraceStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AgentAssistViewModel(projectId, registry, model, traceStore) as T
    }
}
