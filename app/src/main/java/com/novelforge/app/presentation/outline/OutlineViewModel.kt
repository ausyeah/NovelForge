package com.novelforge.app.presentation.outline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.novelforge.app.domain.model.FlowState
import com.novelforge.app.domain.model.GenerationJob
import com.novelforge.app.domain.model.GenerationJobStatus
import com.novelforge.app.domain.model.GenerationPurpose
import com.novelforge.app.domain.model.OutlineItem
import com.novelforge.app.domain.model.OutlineVersion
import com.novelforge.app.domain.model.ProjectStatus
import com.novelforge.app.domain.repository.ChapterRepository
import com.novelforge.app.domain.repository.GenerationArtifactRepository
import com.novelforge.app.domain.repository.GenerationRepository
import com.novelforge.app.domain.repository.OutlineRepository
import com.novelforge.app.domain.repository.ProjectRepository
import com.novelforge.app.infrastructure.jobs.GenerationRuntime
import com.novelforge.app.infrastructure.llm.JsonResponseValidator
import com.novelforge.app.infrastructure.llm.JsonValidationResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OutlineViewModel(
    private val projectId: String,
    private val projectRepository: ProjectRepository,
    private val outlineRepository: OutlineRepository,
    private val chapterRepository: ChapterRepository,
    private val generationRepository: GenerationRepository,
    private val generationArtifactRepository: GenerationArtifactRepository,
    private val generationRuntime: GenerationRuntime
) : ViewModel() {
    /** 只看最新版本：全版本历史流在 300+ 章规模下解析代价是 O(N²)，砍掉 */
    val outline: StateFlow<OutlineVersion?> = outlineRepository.observeLatestVersion(projectId).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null
    )

    /** 已保存正文的章节 id 集合（总览卡片颜色区分用） */
    val writtenChapterIds: StateFlow<Set<String>> = chapterRepository
        .observeWrittenItemIds(projectId)
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val activeJobId = MutableStateFlow<String?>(null)
    val activeJob: StateFlow<GenerationJob?> = activeJobId
        .observeJob()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val validator = JsonResponseValidator()

    val autoRun: StateFlow<Boolean> = generationRuntime.autoRunEnabled(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 全自动写作时正在生成的正文任务（跨章节自动追踪最新一个） */
    val chapterJob: StateFlow<GenerationJob?> = generationRepository
        .observeLatestJob(projectId, GenerationPurpose.CHAPTER.name, null)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 计划章数：进度卡的 N，以及判断这一轮是「继续生成」还是「重新生成」 */
    val plannedChapterCount: StateFlow<Int?> = projectRepository.observeProjects()
        .map { all -> all.firstOrNull { it.id == projectId }?.creativeConfig?.chapterCount }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setAutoRun(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { generationRuntime.setAutoRun(projectId, enabled) }
                .onFailure { _error.value = it.message ?: "无法切换全自动" }
        }
    }

    /** 一键全自动：打开开关并从当前进度直接开跑 */
    fun startAutoRun() {
        _error.value = null
        viewModelScope.launch {
            runCatching { generationRuntime.startAutoRun(projectId) }
                .onSuccess {
                    activeJobId.value = generationRepository.findLatestJob(
                        projectId, GenerationPurpose.OUTLINE.name, null
                    )?.id
                }
                .onFailure { _error.value = it.message ?: "无法启动全自动生成" }
        }
    }

    init {
        viewModelScope.launch {
            runCatching {
                activeJobId.value = generationRepository.findLatestJob(
                    projectId,
                    GenerationPurpose.OUTLINE.name,
                    null
                )?.id
            }.onFailure { _error.value = "大纲进度读取失败" }
        }
    }

    fun generate(continueFromExisting: Boolean = false) {
        _error.value = null
        viewModelScope.launch {
            runCatching {
                generationRuntime.queueOutline(
                    projectId,
                    continueFromExisting = continueFromExisting
                )
            }
                .onSuccess { activeJobId.value = it.id }
                .onFailure { _error.value = it.message ?: "无法创建大纲生成任务" }
        }
    }

    // ---------- 大纲编辑缓冲 ----------
    //
    // 这一段以前全是 OutlineScreen 里的 remember。NavHost 是扁平的：
    // outline → chapter → 返回只是把 outline 那一项 un-compose，
    // remember 的值全丢，而 ViewModel 里的东西留着。后果是
    // 「在单章详情改完标题/概要 → 点写这一章 → 返回」之后，编辑连同
    // 「有未保存的修改」提示一起凭空消失，看上去像保存成功了。
    // 编辑缓冲、界面焦点、撤回槽都搬到这里：它们是数据，不是纯 UI 状态。

    /** null = 没有本地编辑，草稿就是已保存的 outline.chapters */
    private val draftOverride = MutableStateFlow<List<OutlineItem>?>(null)

    /** 草稿是从哪个已保存版本复制出来的。版本 id 变了 = 已保存内容换新了 */
    private var draftBaseVersionId: String? = null

    /**
     * 编辑缓冲：总览列表、详情表单、保存按钮的共同数据源。
     *
     * 用 Eagerly 而不是 WhileSubscribed：草稿要在没有任何订阅者时也保持正确
     * （谁在读 .value 就得拿到对的值），而且重定位到新版本不能等界面先订阅。
     */
    val draftItems: StateFlow<List<OutlineItem>> = combine(draftOverride, outline) { draft, saved ->
        draft ?: saved?.chapters.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 有未保存的编辑：退出拦截和「有未保存的修改」提示的唯一依据。
     * 两者以前都由被销毁的 remember 算出来，所以「看起来没改过」其实是没算过。
     */
    val hasUnsavedEdits: StateFlow<Boolean> = combine(draftItems, outline) { draft, saved ->
        saved != null && draft != saved.chapters
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * 单章详情的焦点：存 itemId，不存下标。
     *
     * 下标会随增删章漂移 —— 大纲重排或从本章起重生成之后，原来记着的 12 可能变成别的章，
     * 用户的编辑就落到了错误的一章上。id 不会漂移，必要时按 id 重新解析。
     */
    private val focusedChapterId = MutableStateFlow<String?>(null)

    /** 详情在草稿里的下标；章节被删掉或根本不在草稿里时为 null（详情自己收起） */
    val detailIndex: StateFlow<Int?> = combine(draftItems, focusedChapterId) { items, id ->
        if (id == null) null else items.indexOfFirst { it.id == id }.takeIf { it >= 0 }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 魔法棒撤回槽：itemId -> 优化前原条目；用户再手动编辑该章即作废 */
    private val _undoSlot = MutableStateFlow<Pair<String, OutlineItem>?>(null)
    val undoSlot: StateFlow<Pair<String, OutlineItem>?> = _undoSlot.asStateFlow()

    /**
     * 修复区跟随任务：记住「是为哪个任务打开的」，而不是一个裸 Boolean。
     * 以前是 remember(job?.id)，现在把它和 activeJob 一起算，
     * 换了任务自动收起 —— 否则「修复」按钮会显示成「收起修复」，而屏幕上并没有修复框。
     */
    private val rawEditorJobId = MutableStateFlow<String?>(null)

    /**
     * 修复区里手改的原文。
     *
     * 和 rawEditorJobId 一样不是裸 remember：修复模型输出本来要照着几千字的
     * 原文改，改到一半去别的页面看一眼再回来，清空是不可接受的。
     * 开区那一刻用当时的 partialContent 播种一次，之后流式 checkpoint 改写
     * partialContent 也不去覆盖用户正在打的字。
     */
    private val _rawDraft = MutableStateFlow("")

    val rawDraft: StateFlow<String> = _rawDraft.asStateFlow()

    val rawEditorOpen: StateFlow<Boolean> = combine(rawEditorJobId, activeJob) { editorJobId, job ->
        editorJobId != null && editorJobId == job?.id
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _reverseOrder = MutableStateFlow(true)

    /** 总览排序：true = 倒序（从最新一章往下翻） */
    val reverseOrder: StateFlow<Boolean> = _reverseOrder.asStateFlow()

    private val _editNotice = MutableStateFlow<String?>(null)

    /**
     * 编辑缓冲相关的即时提示。
     * 以前这条只写不显示：润色结果被丢弃时用户什么提示都收不到，
     * 只能自己发现标题没被润色。
     */
    val editNotice: StateFlow<String?> = _editNotice.asStateFlow()

    private val _draftExit = MutableStateFlow<DraftExit>(DraftExit.Idle)

    /** 退出拦截的状态机，屏幕只照着渲染并决定何时真正返回 */
    val draftExit: StateFlow<DraftExit> = _draftExit.asStateFlow()

    init {
        // 已保存版本换了 id（保存成功 / 重新生成落库）→ 草稿归位。
        // 副作用：用户在保存落库的那一瞬间又敲了几个字，这些字会被这次归位抹掉。
        // 取舍是故意的 —— 保留它们的话「有未保存的修改」会永远亮着，用户再也存不下去。
        viewModelScope.launch {
            outline.collect { saved ->
                val id = saved?.id
                if (id == draftBaseVersionId) return@collect
                draftBaseVersionId = id
                draftOverride.value = null
            }
        }
    }

    // ---------- 编辑缓冲的读写 ----------

    fun openChapterDetail(itemId: String) {
        focusedChapterId.value = itemId
    }

    fun closeChapterDetail() {
        focusedChapterId.value = null
    }

    /** 详情里前后翻章：越界直接不动，按钮那边也会置灰 */
    fun moveDetail(delta: Int) {
        val items = draftItems.value
        val id = focusedChapterId.value ?: return
        val current = items.indexOfFirst { it.id == id }
        if (current < 0) return
        val target = (current + delta).coerceIn(0, items.lastIndex)
        if (target != current) focusedChapterId.value = items[target].id
    }

    /** 详情里改标题/概要：按 id 就地替换。手动改动会作废魔法棒的撤回槽 */
    fun updateDraftItem(item: OutlineItem) {
        val items = draftItems.value
        if (items.none { it.id == item.id }) return
        draftOverride.value = items.map { if (it.id == item.id) item else it }
        if (_undoSlot.value?.first == item.id) _undoSlot.value = null
        _editNotice.value = null
    }

    /** 撤回魔法棒润色：只有原条目还挂在草稿里才回滚 */
    fun undoOptimize(itemId: String) {
        val slot = _undoSlot.value ?: return
        if (slot.first != itemId) return
        val items = draftItems.value
        draftOverride.value = items.map { if (it.id == slot.second.id) slot.second else it }
        _undoSlot.value = null
        _editNotice.value = null
    }

    fun toggleRawEditor() {
        if (rawEditorOpen.value) {
            rawEditorJobId.value = null
        } else {
            rawEditorJobId.value = activeJob.value?.id
            _rawDraft.value = activeJob.value?.partialContent.orEmpty()
        }
    }

    fun setRawDraft(text: String) {
        _rawDraft.value = text
    }

    fun toggleReverseOrder() {
        _reverseOrder.value = !_reverseOrder.value
    }

    // ---------- 退出拦截 ----------

    /**
     * 一次「返回」（系统手势 / 顶栏返回按钮）的判定。
     *
     * 以前这里是两个 BackHandler 打架：一个在屏幕级管离开本页，一个在详情面板里
     * 「只关详情」。Compose 里后注册的赢，详情面板恰好渲染得更晚，纯属巧合；
     * 一旦面板挪了位置，返回键的行为就会静悄悄地变。现在合成一处，规则显式。
     */
    fun requestBack() {
        when (
            OutlineEditRules.decideBack(
                detailOpen = detailShowing(),
                wandBusy = _optimizingIndex.value != null,
                hasUnsavedEdits = editsPending()
            )
        ) {
            BackDecision.CLOSE_DETAIL -> closeChapterDetail()
            BackDecision.ASK_BEFORE_LEAVE -> {
                if (draftOverride.value.isNullOrEmpty()) {
                    // 没有可保的东西（大纲已被重生成清空），别拿空对话框吓人
                    _draftExit.value = DraftExit.Leave
                } else {
                    _draftExit.value = DraftExit.Unsaved
                }
            }
            BackDecision.LEAVE -> _draftExit.value = DraftExit.Leave
            // 润色在跑时返回不响应：那一下多半是想「取消」，
            // 去按面板上单独挪出来的「■ 停止优化」比默默离开诚实。
            BackDecision.IGNORE -> Unit
        }
    }

    /** 「先保存」：必须等落库成功才放行返回 */
    fun confirmSaveDraft() {
        if (_draftExit.value == DraftExit.Saving) return
        val items = draftItems.value
        val current = outline.value
        if (current == null || items == current.chapters) {
            _draftExit.value = DraftExit.Leave
            return
        }
        _draftExit.value = DraftExit.Saving
        viewModelScope.launch {
            // 失败不能直接放行：写库是 viewModelScope 里的协程，
            // 此刻离开页面会把它取消掉，存到一半的数据等于没存。
            runCatching { persistOutline(items) }
                .onSuccess { _draftExit.value = DraftExit.Leave }
                .onFailure { _draftExit.value = DraftExit.Failed(it.message ?: "保存大纲失败") }
        }
    }

    /** 「放弃修改」：草稿回退到已保存版本，然后放行返回 */
    fun confirmDiscardDraft() {
        draftOverride.value = null
        _undoSlot.value = null
        focusedChapterId.value = null
        _editNotice.value = null
        _draftExit.value = DraftExit.Leave
    }

    /** 对话框被划掉：什么都不做，但把待决状态收干净 */
    fun dismissDraftExit() {
        if (_draftExit.value == DraftExit.Saving) return
        _draftExit.value = DraftExit.Idle
    }

    /** 屏幕处理完 Leave 之后调用，避免同一个值被 StateFlow 合并掉导致第二次返回失灵 */
    fun consumeDraftExit() {
        _draftExit.value = DraftExit.Idle
    }

    /**
     * 退出判定用同步算，不读派生流。
     *
     * hasUnsavedEdits / detailOpen 都是 Eagerly 的 combine，比写入晚一拍；
     * 界面上晚一拍无所谓，但「用户刚敲完字立刻按返回」必须拦得住，
     * 守卫不能给竞态留窗口。
     */
    private fun editsPending(): Boolean {
        val saved = outline.value?.chapters ?: return false
        val draft = draftOverride.value ?: return false
        return draft != saved
    }

    private fun detailShowing(): Boolean {
        val id = focusedChapterId.value ?: return false
        return draftItems.value.any { it.id == id }
    }

    // ---------- 魔法棒：按作者手改方向润色本章大纲 ----------

    private var optimizeJob: Job? = null

    private val _optimizingIndex = MutableStateFlow<Int?>(null)

    /** 正在润色的章在草稿里的下标；非 null 时全局只有这一个润色任务 */
    val optimizingIndex: StateFlow<Int?> = _optimizingIndex.asStateFlow()

    /**
     * 润色第 index 章（草稿下标）。
     *
     * 不再由调用方传整份列表：草稿就住在这个 ViewModel 里，
     * 让界面把列表再传一遍等于开了第二个数据源，两边不一致时用户改的就不是他看到的那份。
     */
    fun optimizeChapter(index: Int) {
        if (_optimizingIndex.value != null) return
        val items = draftItems.value
        val current = items.getOrNull(index) ?: return
        _error.value = null
        _optimizingIndex.value = index
        optimizeJob = viewModelScope.launch {
            try {
                val project = requireNotNull(projectRepository.getProject(projectId)) { "项目不存在" }
                val optimized = generationRuntime.optimizeOutlineDraft(
                    project = project,
                    current = current,
                    previous = items.getOrNull(index - 1),
                    next = items.getOrNull(index + 1)
                )
                // 结果当场落进缓冲。以前是靠屏幕上的 LaunchedEffect 去消费一次性 StateFlow：
                // 用户等润色期间切去写正文，回来时缓冲已经被 remember 冲掉，润色就凭空蒸发了。
                when (val outcome = OutlineEditRules.applyOptimize(draftItems.value, current, optimized)) {
                    is OptimizeOutcome.Applied -> {
                        draftOverride.value = outcome.items
                        _undoSlot.value = optimized.id to outcome.original
                    }
                    // 等待期间用户又手改了：AI 结果作废，绝不覆盖手打内容
                    OptimizeOutcome.Discarded -> {
                        _editNotice.value = "润色结果已丢弃：等待期间你手动改过本章，保留的是你的版本"
                    }
                    // 这一章已经不在草稿里了（重生成 / 版本切换），无处可放
                    OptimizeOutcome.Missing -> Unit
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Throwable) {
                _error.value = "优化失败：${e.message ?: "未知错误"}"
            } finally {
                _optimizingIndex.value = null
            }
        }
    }

    fun stopOptimize() {
        optimizeJob?.cancel()
        optimizeJob = null
        _optimizingIndex.value = null
    }

    // ---------- 用户手术：从本章起重写大纲 + 正文 ----------

    fun regenerateFrom(item: OutlineItem) {
        if (_optimizingIndex.value != null) stopOptimize()
        // 确认的那一刻就把这一章及之后的章从草稿里剔掉。库那边随后也会真删，
        // 草稿要是留着，用户会看到一批「说要删却还在列表里」的章，
        // 外加一个永远亮着的「有未保存的修改」。
        val tail = OutlineEditRules.dropTailFrom(draftItems.value, item.id)
        if (tail.isNotEmpty()) draftOverride.value = tail
        _undoSlot.value = null
        closeChapterDetail()
        _error.value = null
        viewModelScope.launch {
            runCatching { generationRuntime.regenerateFromChapter(projectId, item.id) }
                .onFailure { _error.value = it.message ?: "重生成准备失败" }
        }
    }

    fun cancel() {
        val jobId = activeJob.value?.id ?: return
        viewModelScope.launch { runCatching { generationRuntime.cancel(jobId) } }
    }

    /** 保存大纲：把编辑缓冲落成新版本。失败只报错误，不动草稿，用户还能改完重试 */
    fun saveDraft() {
        val items = draftItems.value
        if (items == outline.value?.chapters) return
        viewModelScope.launch {
            runCatching { persistOutline(items) }
                .onFailure { _error.value = it.message ?: "保存大纲失败" }
        }
    }

    private suspend fun persistOutline(items: List<OutlineItem>) {
        val current = outline.value ?: return
        // 保留原 orderIndex（删章留洞是铁律）：压平重排会让全书编号错位、续读跳错章
        if (items == current.chapters) return
        val next = current.copy(
            id = UUID.randomUUID().toString(),
            version = current.version + 1,
            chapters = items,
            diffSummary = "用户编辑大纲",
            createdAt = System.currentTimeMillis()
        )
        projectRepository.getProject(projectId)?.let { project ->
            generationArtifactRepository.saveOutlineAndProject(
                next,
                project.copy(
                    activeOutlineVersionId = next.id,
                    status = ProjectStatus.OUTLINING,
                    flowState = FlowState.OUTLINE_EDIT,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        // 被移出大纲的章节同步删正文，杜绝"幽灵已写"计数与 id 复用串书
        val keptIds = items.map { it.id }.toSet()
        val removedIds = current.chapters.map { it.id }.filter { it !in keptIds }
        if (removedIds.isNotEmpty()) {
            chapterRepository.deleteRevisionsForItems(projectId, removedIds)
        }
    }

    /**
     * 任务卡在“需要处理”时，允许用户手动修复模型原始输出：
     * 校验通过则直接落为新的大纲版本，并把任务标记为完成。
     */
    fun saveRawOutline(raw: String) {
        when (val result = validator.parseOutline(raw)) {
            is JsonValidationResult.Failure -> {
                _error.value = "原始输出无法解析：${result.reason}，请修改后再保存，或点击重试重新生成"
            }
            is JsonValidationResult.Success -> viewModelScope.launch {
                runCatching {
                    val current = outline.value
                    val normalized = result.value.mapIndexed { index, item ->
                        item.copy(orderIndex = index)
                    }
                    val next = OutlineVersion(
                        id = UUID.randomUUID().toString(),
                        projectId = projectId,
                        version = (current?.version ?: 0) + 1,
                        chapters = normalized,
                        diffSummary = "手动修复模型输出",
                        createdAt = System.currentTimeMillis()
                    )
                    projectRepository.getProject(projectId)?.let { project ->
                        generationArtifactRepository.saveOutlineAndProject(
                            next,
                            project.copy(
                                activeOutlineVersionId = next.id,
                                status = ProjectStatus.OUTLINING,
                                flowState = FlowState.OUTLINE_EDIT,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    val finishedJob = activeJob.value?.takeIf {
                        it.status in setOf(
                            GenerationJobStatus.NEEDS_USER,
                            GenerationJobStatus.FAILED,
                            GenerationJobStatus.RECOVERABLE_PARTIAL,
                            GenerationJobStatus.CANCELLED
                        )
                    }
                    finishedJob?.let { job ->
                        generationRepository.updateJob(
                            job.copy(
                                status = GenerationJobStatus.COMPLETED,
                                errorType = null,
                                errorMessage = null,
                                partialContent = result.normalizedJson,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    // 手动修复落库 = 新版本，缓冲区跟着归位
                    draftOverride.value = null
                }.onFailure { _error.value = it.message ?: "保存大纲失败" }
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    private fun MutableStateFlow<String?>.observeJob(): Flow<GenerationJob?> = flatMapLatest { id ->
        if (id == null) flowOf(null) else generationRepository.observeJob(id)
    }

    class Factory(
        private val projectId: String,
        private val projectRepository: ProjectRepository,
        private val outlineRepository: OutlineRepository,
        private val chapterRepository: ChapterRepository,
        private val generationRepository: GenerationRepository,
        private val generationArtifactRepository: GenerationArtifactRepository,
        private val generationRuntime: GenerationRuntime
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = OutlineViewModel(
            projectId,
            projectRepository,
            outlineRepository,
            chapterRepository,
            generationRepository,
            generationArtifactRepository,
            generationRuntime
        ) as T
    }
}

/** 退出拦截的状态机：Unsaved → Saving → Leave / Failed */
sealed interface DraftExit {
    /** 没有待决的事 */
    data object Idle : DraftExit

    /** 有未保存的修改，弹询问 */
    data object Unsaved : DraftExit

    /** 「先保存」正在落库 */
    data object Saving : DraftExit

    /** 存失败，对话框留在原地，理由给用户看 */
    data class Failed(val reason: String) : DraftExit

    /** 可以离开了（屏幕收到就 onBack） */
    data object Leave : DraftExit
}

/** 一次「返回」该做什么。抽成纯函数是为了不启动 Compose 也能测 */
internal enum class BackDecision {
    CLOSE_DETAIL,
    ASK_BEFORE_LEAVE,
    LEAVE,
    IGNORE
}

/** 润色结果能不能落进草稿 */
internal sealed interface OptimizeOutcome {
    data class Applied(val items: List<OutlineItem>, val original: OutlineItem) : OptimizeOutcome

    /** 等待期间用户手改过同一条：绝不能用 AI 结果覆盖手打内容 */
    data object Discarded : OptimizeOutcome

    /** 这一章已经不在草稿里了 */
    data object Missing : OptimizeOutcome
}

internal object OutlineEditRules {
    fun decideBack(detailOpen: Boolean, wandBusy: Boolean, hasUnsavedEdits: Boolean): BackDecision = when {
        detailOpen && wandBusy -> BackDecision.IGNORE
        detailOpen -> BackDecision.CLOSE_DETAIL
        hasUnsavedEdits -> BackDecision.ASK_BEFORE_LEAVE
        else -> BackDecision.LEAVE
    }

    /**
     * 润色结果落盘判定。注意比对的是**当前**草稿而不是发起润色时的快照：
     * 用户在等待期间手改过这一章，就该保留手打的版本。
     */
    fun applyOptimize(
        live: List<OutlineItem>,
        original: OutlineItem,
        optimized: OutlineItem
    ): OptimizeOutcome {
        val index = live.indexOfFirst { it.id == original.id }
        if (index < 0) return OptimizeOutcome.Missing
        val current = live[index]
        if (current.title != original.title || current.summary != original.summary) {
            return OptimizeOutcome.Discarded
        }
        return OptimizeOutcome.Applied(
            items = live.mapIndexed { i, item -> if (i == index) optimized else item },
            original = original
        )
    }

    /** 从本章起重生成：这一章及之后都要从草稿里剔掉 */
    fun dropTailFrom(live: List<OutlineItem>, targetId: String): List<OutlineItem> =
        live.dropWhile { it.id != targetId }

    /** 退出对话框里的「改了几章」，按 id 比而不是按下标比 */
    fun countEditedChapters(draft: List<OutlineItem>, saved: List<OutlineItem>): Int =
        draft.count { edited ->
            val original = saved.firstOrNull { it.id == edited.id }
            original == null || original != edited
        }
}
