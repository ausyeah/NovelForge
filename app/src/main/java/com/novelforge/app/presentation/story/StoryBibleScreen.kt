package com.novelforge.app.presentation.story

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.novelforge.app.domain.model.CharacterProfile
import com.novelforge.app.domain.model.ContinuityFact
import com.novelforge.app.domain.model.ContinuityState
import com.novelforge.app.domain.model.Project
import com.novelforge.app.domain.repository.ProjectRepository
import com.novelforge.app.presentation.common.PaperTopBar
import com.novelforge.app.ui.theme.PaperButton
import com.novelforge.app.ui.theme.PaperSurface
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class StoryBibleViewModel(
    private val projectId: String,
    private val repository: ProjectRepository
) : ViewModel() {
    val project = repository.observeProjects()
        .map { list -> list.firstOrNull { it.id == projectId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val message = _message

    fun saveDraft(characters: List<CharacterProfile>, rules: List<String>, threads: List<String>) {
        val current = project.value ?: return
        viewModelScope.launch {
            runCatching {
                repository.saveProject(
                    current.copy(
                        continuityState = current.continuityState.copy(
                            characters = characters.filter { it.name.isNotBlank() },
                            worldRules = rules,
                            unresolvedThreads = threads
                        ),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }.onSuccess { _message.value = "已写入这本书的记忆" }
                .onFailure { _message.value = it.message ?: "保存失败" }
        }
    }

    fun accept(fact: ContinuityFact, kind: String) {
        updatePending(fact.id) { state ->
            val statement = fact.statement.trim()
            when (kind) {
                "thread" -> state.copy(
                    unresolvedThreads = (state.unresolvedThreads + statement).distinct()
                )
                "resolved" -> state.copy(
                    unresolvedThreads = state.unresolvedThreads.filterNot {
                        it == statement || statement.contains(it) || it.contains(statement)
                    },
                    timelineEvents = (state.timelineEvents + "已解决：$statement").takeLast(40)
                )
                else -> state.copy(
                    factsWithSources = state.factsWithSources + fact.copy(
                        kind = "fact",
                        confirmed = true,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    fun discard(fact: ContinuityFact) {
        updatePending(fact.id) { it }
    }

    private fun updatePending(factId: String, transform: (ContinuityState) -> ContinuityState) {
        val current = project.value ?: return
        viewModelScope.launch {
            runCatching {
                val state = transform(current.continuityState)
                repository.saveProject(
                    current.copy(
                        continuityState = state.copy(
                            pendingFacts = state.pendingFacts.filterNot { it.id == factId }
                        ),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }.onFailure { _message.value = it.message ?: "没能更新记忆" }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    class Factory(
        private val projectId: String,
        private val repository: ProjectRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            StoryBibleViewModel(projectId, repository) as T
    }
}

@Composable
fun StoryBibleScreen(
    viewModel: StoryBibleViewModel,
    onBack: () -> Unit
) {
    val project by viewModel.project.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val current = project
    if (current == null) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            PaperTopBar(title = "本书记忆", onBack = onBack)
            Text("正在打开…")
        }
        return
    }
    StoryBibleEditor(project = current, message = message, viewModel = viewModel, onBack = onBack)
}

@Composable
private fun StoryBibleEditor(
    project: Project,
    message: String?,
    viewModel: StoryBibleViewModel,
    onBack: () -> Unit
) {
    val state = project.continuityState
    var rules by remember(project.id) { mutableStateOf(state.worldRules.joinToString("\n")) }
    var threads by remember(project.id) { mutableStateOf(state.unresolvedThreads.joinToString("\n")) }
    var characters by remember(project.id) { mutableStateOf(state.characters) }
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(1_600)
            viewModel.clearMessage()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PaperTopBar(title = "本书记忆", subtitle = "确认之后才会进入下一章", onBack = onBack)
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
        if (state.pendingFacts.isNotEmpty()) {
            Text("待确认", style = MaterialTheme.typography.titleSmall)
            state.pendingFacts.takeLast(12).asReversed().forEach { fact ->
                PaperSurface(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(fact.statement, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when (fact.kind) {
                                "thread" -> "模型觉得这是一条新伏笔"
                                "resolved" -> "模型觉得这条线索已经收了"
                                else -> "模型觉得这是后文要记住的事实"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { viewModel.accept(fact, "fact") }) { Text("记成事实") }
                            TextButton(onClick = { viewModel.accept(fact, "thread") }) { Text("记成伏笔") }
                            TextButton(onClick = { viewModel.accept(fact, "resolved") }) { Text("已解决") }
                            TextButton(onClick = { viewModel.discard(fact) }) { Text("丢掉") }
                        }
                    }
                }
            }
        }
        Text("不能违反的规则", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = rules,
            onValueChange = { rules = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            label = { Text("一行一条") }
        )
        Text("还没收的伏笔", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = threads,
            onValueChange = { threads = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            label = { Text("一行一条") }
        )
        Text("角色", style = MaterialTheme.typography.titleSmall)
        characters.forEachIndexed { index, character ->
            CharacterEditor(
                character = character,
                onChange = { updated ->
                    characters = characters.mapIndexed { i, item -> if (i == index) updated else item }
                },
                onDelete = { characters = characters.filterIndexed { i, _ -> i != index } }
            )
        }
        TextButton(onClick = {
            characters = characters + CharacterProfile(
                id = UUID.randomUUID().toString(),
                projectId = project.id,
                name = ""
            )
        }) { Text("加一个角色") }
        PaperButton(
            "保存记忆",
            onClick = {
                viewModel.saveDraft(
                    characters = characters,
                    rules = rules.lines().map { it.trim() }.filter { it.isNotEmpty() },
                    threads = threads.lines().map { it.trim() }.filter { it.isNotEmpty() }
                )
            },
            modifier = Modifier.fillMaxWidth(),
            accent = true
        )
    }
}

@Composable
private fun CharacterEditor(
    character: CharacterProfile,
    onChange: (CharacterProfile) -> Unit,
    onDelete: () -> Unit
) {
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = character.name,
                onValueChange = { onChange(character.copy(name = it)) },
                label = { Text("名字") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = character.appearance,
                onValueChange = { onChange(character.copy(appearance = it)) },
                label = { Text("外貌") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = character.personality,
                onValueChange = { onChange(character.copy(personality = it)) },
                label = { Text("性格") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = character.motivation,
                onValueChange = { onChange(character.copy(motivation = it)) },
                label = { Text("动机") },
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = onDelete) { Text("删除这个角色") }
        }
    }
}
