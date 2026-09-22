package com.novelforge.app.presentation.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.novelforge.app.agent.AgentStep
import com.novelforge.app.agent.BookQuestion
import com.novelforge.app.data.agent.AgentTraceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AgentAssistViewModel(
    private val projectId: String,
    private val bookQuestion: BookQuestion,
    private val traceStore: AgentTraceStore
) : ViewModel() {
    val steps: StateFlow<List<AgentStep>> = traceStore.observe(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun ask(text: String) {
        val normalized = text.trim()
        if (normalized.isEmpty() || _busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            val question = listOf(AgentStep("user", "提问", normalized))
            traceStore.save(projectId, question)
            runCatching { bookQuestion.ask(projectId, normalized) }
                .onSuccess { answer ->
                    traceStore.save(
                        projectId,
                        question + AgentStep("reply", "回答", answer)
                    )
                }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    _error.value = error.message ?: "提问失败"
                }
            _busy.value = false
        }
    }

    class Factory(
        private val projectId: String,
        private val bookQuestion: BookQuestion,
        private val traceStore: AgentTraceStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AgentAssistViewModel(projectId, bookQuestion, traceStore) as T
    }
}
