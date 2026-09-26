package com.novelforge.app.presentation.story

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.novelforge.app.infrastructure.llm.MemorySelector
import com.novelforge.app.infrastructure.llm.statementSimilarity
import com.novelforge.app.presentation.common.PaperTopBar
import com.novelforge.app.ui.theme.PaperButton
import com.novelforge.app.ui.theme.PaperSurface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** 一条待确认记忆的两种结局分开建模：成功提示会自动消失，错误不能。 */
sealed interface StoryBibleEvent {
    data class Saved(val text: String) : StoryBibleEvent
    data class Failed(val text: String) : StoryBibleEvent
}

class StoryBibleViewModel(
    private val projectId: String,
    private val repository: ProjectRepository
) : ViewModel() {
    val project = repository.observeProjects()
        .map { list -> list.firstOrNull { it.id == projectId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _event = MutableStateFlow<StoryBibleEvent?>(null)
    val event = _event.asStateFlow()

    fun saveDraft(characters: List<CharacterProfile>, rules: List<String>, threads: List<String>) {
        viewModelScope.launch {
            runCatching {
                repository.mutateContinuity(projectId) { state ->
                    state.copy(
                        characters = characters.filter { it.name.isNotBlank() },
                        worldRules = rules,
                        unresolvedThreads = threads
                    )
                }
            }.onSuccess { _event.value = StoryBibleEvent.Saved("已写入这本书的记忆") }
                .onFailure { _event.value = StoryBibleEvent.Failed(it.message ?: "保存失败") }
        }
    }

    fun accept(fact: ContinuityFact, kind: String) {
        mutate(fact.id, successText = "已记入本书记忆") { state -> applyAccept(state, fact, kind) }
    }

    fun discard(fact: ContinuityFact) {
        mutate(fact.id, successText = null) { it }
    }

    /** 一键处理剩下的待确认条目。攒到上限就会静默丢弃，所以必须给一条出路。 */
    fun acceptAllPending(kind: String) {
        viewModelScope.launch {
            runCatching {
                repository.mutateContinuity(projectId) { state ->
                    state.pendingFacts
                        .filter { it.kind == kind || (kind == "fact" && it.kind != "thread" && it.kind != "resolved") }
                        .fold(state) { acc, fact -> applyAccept(acc, fact, kind) }
                        .let { next -> next.copy(pendingFacts = next.pendingFacts.filterNot { it.kind == kind }) }
                }
            }.onSuccess { _event.value = StoryBibleEvent.Saved("已批量处理待确认条目") }
                .onFailure { _event.value = StoryBibleEvent.Failed(it.message ?: "批量处理失败") }
        }
    }

    fun discardAllPending() {
        viewModelScope.launch {
            runCatching {
                repository.mutateContinuity(projectId) { it.copy(pendingFacts = emptyList()) }
            }.onSuccess { _event.value = StoryBibleEvent.Saved("已清空待确认条目") }
                .onFailure { _event.value = StoryBibleEvent.Failed(it.message ?: "清空失败") }
        }
    }

    /**
     * 置顶：让这条设定每章都带，且整条排在事实列表最前面。
     *
     * 两条保证都在 [com.novelforge.app.infrastructure.llm.MemorySelector] 里兑现：
     * 占名额靠 `pinned` 是名额排序的第一键，排在最前靠收尾的呈现顺序
     * （置顶组整体提到最前，组内仍从旧到新；其余事实从旧到新）。
     * 以前呈现顺序是纯粹按 `updatedAt` 排的，于是名额保住了、顺序没保住。
     */
    fun togglePin(fact: ContinuityFact) {
        mutate(null, successText = null) { state ->
            state.copy(
                factsWithSources = state.factsWithSources.map {
                    if (it.id == fact.id) it.copy(pinned = !it.pinned) else it
                }
            )
        }
    }

    fun forget(fact: ContinuityFact) {
        mutate(null, successText = null) { state ->
            state.copy(factsWithSources = state.factsWithSources.filterNot { it.id == fact.id })
        }
    }

    fun clearEvent() {
        _event.value = null
    }

    /**
     * 确认一条待确认记忆。说的是同一件事的旧设定会被顶掉，
     * 免得两条互相矛盾的事实一起占名额、还一起发给模型。
     *
     * 判定分两级：
     * 1. 主体+属性名相同 —— 精确、可信。抽记忆时要求模型给出这两个字段，
     *    「沈砚/左臂状态」从「已断」变成「已接上」就是同一格里的新值。
     * 2. 词面高度重合 —— 兜底，覆盖旧数据（没有 subject/predicate）和伏笔。
     *    阈值只能定在 0.6 这种「近乎重复」的水平：真实的矛盾对
     *    （左臂已断 vs 左臂已接上）词面只有 0.30，而不同角色的事也有 0.10，
     *    调低阈值会把不相干的设定互相吃掉。
     */
    private fun applyAccept(
        state: ContinuityState,
        fact: ContinuityFact,
        kind: String
    ): ContinuityState {
        val statement = fact.statement.trim()
        val promoted = fact.copy(
            id = UUID.randomUUID().toString(),
            kind = "fact",
            confirmed = true,
            updatedAt = System.currentTimeMillis()
        )
        val hasSubject = promoted.subject.isNotBlank() && promoted.predicate.isNotBlank()
        val kept = state.factsWithSources.filterNot { old ->
            when {
                old.statement == statement -> true
                hasSubject && old.subject == promoted.subject && old.predicate == promoted.predicate -> true
                else -> statementSimilarity(old.statement, statement) >= SUPERSEDE_THRESHOLD
            }
        }
        return when (kind) {
            "thread" -> state.copy(
                unresolvedThreads = (state.unresolvedThreads + statement).distinct()
            )
            "resolved" -> state.copy(
                unresolvedThreads = state.unresolvedThreads.filterNot {
                    it == statement || statement.contains(it) || it.contains(statement)
                },
                timelineEvents = (state.timelineEvents + "已解决：$statement").takeLast(40)
            )
            else -> state.copy(factsWithSources = kept + promoted)
        }
    }

    /**
     * 事务内读-改-写。factId 非空时顺手把这条从待确认里摘掉。
     * 不能沿用「读一份 project 快照 → 改 → 整行 REPLACE」：章后抽记忆和用户点确认
     * 是并发的两条路径，整行覆盖会让后写的一方把先写的一方刚存的记忆整段抹掉。
     */
    private fun mutate(
        factId: String?,
        successText: String?,
        transform: (ContinuityState) -> ContinuityState
    ) {
        viewModelScope.launch {
            runCatching {
                repository.mutateContinuity(projectId) { state ->
                    val next = transform(state)
                    if (factId == null) next
                    else next.copy(pendingFacts = next.pendingFacts.filterNot { it.id == factId })
                }
            }.onSuccess {
                if (successText != null) _event.value = StoryBibleEvent.Saved(successText)
            }.onFailure {
                _event.value = StoryBibleEvent.Failed(it.message ?: "没能更新记忆")
            }
        }
    }

    class Factory(
        private val projectId: String,
        private val repository: ProjectRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            StoryBibleViewModel(projectId, repository) as T
    }

    private companion object {
        /**
         * 词面去重阈值。这是**兜底**，矛盾主要靠 subject+predicate 精确判定。
         *
         * 0.6 只能命中近乎逐字重复 —— 真实的矛盾对（「左臂已断，不能再持剑」
         * vs「左臂已经接上，可以持剑了」）词面只有 0.30，而不同角色的事
         * （「阿禾丢了玉佩」vs「白露在城南开了一间药铺」）也有 0.10。
         * 调低它去抓矛盾，会把不相干的设定互相吃掉，那是更糟的错误。
         */
        const val SUPERSEDE_THRESHOLD = 0.6
    }
}

@Composable
fun StoryBibleScreen(
    viewModel: StoryBibleViewModel,
    onBack: () -> Unit
) {
    val project by viewModel.project.collectAsStateWithLifecycle()
    val event by viewModel.event.collectAsStateWithLifecycle()
    val current = project
    if (current == null) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            PaperTopBar(title = "本书记忆", onBack = onBack)
            Text("正在打开…")
        }
        return
    }
    StoryBibleEditor(project = current, event = event, viewModel = viewModel, onBack = onBack)
}

@Composable
private fun StoryBibleEditor(
    project: Project,
    event: StoryBibleEvent?,
    viewModel: StoryBibleViewModel,
    onBack: () -> Unit
) {
    val state = project.continuityState
    var rules by rememberSaveable(project.id) { mutableStateOf(state.worldRules.joinToString("\n")) }
    var threads by rememberSaveable(project.id) { mutableStateOf(state.unresolvedThreads.joinToString("\n")) }
    var characters by remember(project.id) { mutableStateOf(state.characters) }
    // 队列最多 40 条。以前只渲染最后 12 条又没有「展开」，
    // 于是另外 28 条在界面上根本不存在，用户永远处理不掉，
    // 下一章又被 takeLast(40) 悄悄挤掉 —— 记忆就这么攒不起来。
    var showAllPending by rememberSaveable { mutableStateOf(false) }
    // 只有成功提示自动消失；错误 1.6 秒后就没人看得见了
    LaunchedEffect(event) {
        if (event is StoryBibleEvent.Saved) {
            kotlinx.coroutines.delay(1_600)
            viewModel.clearEvent()
        }
    }
    val pendingVisible = if (showAllPending) state.pendingFacts else state.pendingFacts.takeLast(12)
    val hiddenPending = (state.pendingFacts.size - pendingVisible.size).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PaperTopBar(title = "本书记忆", subtitle = "确认之后才会进入下一章", onBack = onBack)
        when (val current2 = event) {
            is StoryBibleEvent.Saved -> Text(
                current2.text,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
            is StoryBibleEvent.Failed -> Text(
                current2.text,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            null -> Unit
        }
        if (state.pendingFacts.isNotEmpty()) {
            Text(
                "待确认 ${state.pendingFacts.size} 条",
                style = MaterialTheme.typography.titleSmall
            )
            pendingVisible.asReversed().forEach { fact ->
                PaperSurface(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(fact.statement, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when (fact.kind) {
                                "thread" -> "模型判定：新伏笔"
                                "resolved" -> "模型判定：已收束的线索"
                                else -> "模型判定：需要记录的事实"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { viewModel.accept(fact, "fact") }) { Text("记成事实") }
                            TextButton(onClick = { viewModel.accept(fact, "thread") }) { Text("记成伏笔") }
                            TextButton(onClick = { viewModel.accept(fact, "resolved") }) { Text("已解决") }
                            TextButton(onClick = { viewModel.discard(fact) }) { Text("忽略") }
                        }
                    }
                }
            }
            if (hiddenPending > 0 || showAllPending) {
                TextButton(onClick = { showAllPending = !showAllPending }) {
                    Text(if (showAllPending) "只看最新 12 条" else "还有 $hiddenPending 条，全部展开")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { viewModel.acceptAllPending("fact") }) { Text("全部记成事实") }
                TextButton(onClick = { viewModel.discardAllPending() }) { Text("全部忽略") }
            }
        }
        if (state.factsWithSources.isNotEmpty()) {
            Text(
                "已确认事实 ${state.factsWithSources.size} 条",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                "每章只带 ${MemorySelector.MAX_FACTS} 条。置顶的永远带上，其余按和本章的相关度取。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.factsWithSources.asReversed().take(30).forEach { fact ->
                PaperSurface(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(fact.statement, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(
                                selected = fact.pinned,
                                onClick = { viewModel.togglePin(fact) },
                                label = { Text(if (fact.pinned) "已置顶" else "置顶") }
                            )
                            TextButton(onClick = { viewModel.forget(fact) }) { Text("不再记住") }
                        }
                    }
                }
            }
        }
        Text("不能违反的规则", style = MaterialTheme.typography.titleSmall)
        if (state.worldRules.size > MemorySelector.MAX_RULES) {
            Text(
                "有 ${state.worldRules.size} 条，每章最多携带 ${MemorySelector.MAX_RULES} 条，" +
                    "本章用不到的会被舍弃。建议合并同类规则。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedTextField(
            value = rules,
            onValueChange = { rules = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            label = { Text("一行一条") }
        )
        Text("还没收的伏笔", style = MaterialTheme.typography.titleSmall)
        if (state.unresolvedThreads.size > MemorySelector.MAX_THREADS) {
            Text(
                "有 ${state.unresolvedThreads.size} 条，每章只带 ${MemorySelector.MAX_THREADS} 条，" +
                    "最近新增的优先。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
                value = character.aliases.joinToString("、"),
                onValueChange = { raw ->
                    onChange(
                        character.copy(
                            aliases = raw.split("、", "，", ",")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() && it != character.name }
                        )
                    )
                },
                label = { Text("别名 / 称号（顿号分隔）") },
                supportingText = { Text("别名在概要中出现时同样视为点名，否则该角色不会被带入本章。") },
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
            OutlinedTextField(
                value = character.abilities,
                onValueChange = { onChange(character.copy(abilities = it)) },
                label = { Text("当前状态 / 能力限制") },
                supportingText = { Text("在此填写受伤或已失去的事物；内容会随角色档案一并发送给模型。") },
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = onDelete) { Text("删除这个角色") }
        }
    }
}
