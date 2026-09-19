# NovelForge MVP+ 补充功能实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 MVP 闭环上分批补齐 P0（通知/Token 统计/深色模式）、P1（Chat/阅读/备份）、P2（质检/故事圣经/多章队列），并完成 P3 可选加分项，同时激活 `QualityReport`、`ContinuityState`、`CharacterProfile`、`LlmCall` 等闲置资产。

**Architecture:** 保持现有 `presentation / domain / data / infrastructure` 分层不变。所有新增存储复用 DataStore 与 Project JSON 序列化列，**不修改 Room schema（DB 保持 v1）**。LLM 调用统一走现有 `LLMClient`；后台任务统一走现有 `GenerationRuntime` + WorkManager。

**Tech Stack:** 与 MVP 一致：Kotlin 2.x、Compose Material 3、Room、DataStore、WorkManager、OkHttp、kotlinx.serialization、JUnit。

**Spec:** `docs/requirements/mvp-plus-features.md`（v0.2，已决策）。

## Global Constraints

- **不修改 Room schema**；如需持久化新数据，使用 DataStore 或 Project 既有 JSON 序列化列。
- API Key、prompt、完整响应、生成的正文不得写入日志、通知正文或导出文件。
- 每个任务完成后运行对应测试或静态验证；环境缺失时如实报告阻塞项，不得把未运行说成通过。
- 不得破坏现有 MVP 闭环（设置 → 创作设置 → 大纲 → 章节 → 导出）；涉及现有调用点的改动先确认现状再改。
- 费用只统计 token 数量，不做金额换算（决策 D4）。

---

### Task 1: F-01 生成完成系统通知

**Files:**
- Modify: `app/src/main/java/com/novelforge/app/infrastructure/jobs/GenerationRuntime.kt`
- Modify: `app/src/main/java/com/novelforge/app/NovelForgeApplication.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/novelforge/app/infrastructure/notifications/GenerationNotifier.kt`

**Interfaces:**
- Produce `GenerationNotifier.notifyJobResult(job: GenerationJob, projectTitle: String)`，按 `GenerationJobStatus` 分类：COMPLETED（大纲/章节完成）、FAILED、NEEDS_USER、RECOVERABLE_PARTIAL。
- `NovelForgeApplication` 提供 `generationNotifier`。

- [ ] **Step 1: 建立通知 channel**（`GenerationNotifier` 内 `NotificationChannel`，低重要性，创建时幂等）。
- [ ] **Step 2: 在 `GenerationRuntime.execute()` 收尾分支调用 notifier**，按 COMPLETED/FAILED/NEEDS_USER 分类发送；正文只含项目标题 + 结果类型，不含正文内容。
- [ ] **Step 3: 处理权限**：Android 13+ 运行时申请 `POST_NOTIFICATIONS`（在 `MainActivity` 或首页入口处申请一次）；未授权时 `notify` 静默跳过，不 crash。
- [ ] **Step 4: 单元/验证**：`GenerationRecoveryTest` 附近补充 notifier 的分类测试（用 fake NotificationManager 或纯逻辑单测分类函数）；运行 `testDebugUnitTest`、`lintDebug`、`assembleDebug`。
- [ ] **Step 5: 真机验证**：后台生成时切后台，完成后收到分类正确通知；拒绝权限后不崩溃。

### Task 2: F-02 Token 消耗统计（仅数量）

**Files:**
- Modify: `app/src/main/java/com/novelforge/app/domain/repository/LlmCallRepository.kt`
- Modify: `app/src/main/java/com/novelforge/app/data/repository/RoomLlmCallRepository.kt`
- Modify: `app/src/main/java/com/novelforge/app/presentation/outline/OutlineScreen.kt`
- Create: `app/src/main/java/com/novelforge/app/presentation/common/TokenUsageCard.kt`（可选）

**Interfaces:**
- Produce `LlmCallRepository.observeUsageForProject(projectId: String): Flow<ProjectTokenUsage>`，`ProjectTokenUsage(callCount, inputTokens, outputTokens, totalTokens)`。

- [ ] **Step 1: 写聚合 DAO/仓库单测**：`LlmCallDao` 增加聚合查询（`SELECT COUNT(*), SUM(...) FROM llm_calls WHERE projectId = :projectId`），`RoomLlmCallRepository` 暴露 `observeUsageForProject`。
- [ ] **Step 2: 在 `OutlineScreen` 顶部加统计卡片**：显示调用次数、input/output/total token；无数据时显示"暂无生成记录"。
- [ ] **Step 3: 运行测试与构建**：`testDebugUnitTest` + `assembleDebug`。
- [ ] **Step 4: 真机验证**：生成 1-2 章后统计卡片数字与预期一致且实时更新。

### Task 3: F-03 深色模式

**Files:**
- Modify: `app/src/main/java/com/novelforge/app/data/settings/AppSettingsStore.kt`
- Modify: `app/src/main/java/com/novelforge/app/MainActivity.kt`
- Modify: `app/src/main/java/com/novelforge/app/ui/theme/Theme.kt`
- Modify: `app/src/main/java/com/novelforge/app/presentation/settings/SettingsScreen.kt`

**Interfaces:**
- `AppSettings.themeMode: ThemeMode`（枚举 `SYSTEM / LIGHT / DARK`，默认 `SYSTEM`）。
- `NovelForgeTheme(darkTheme: Boolean)` 已存在，`MainActivity` 读取 `themeMode` 决定 `darkTheme`（SYSTEM 时用 `isSystemInDarkTheme()`）。

- [ ] **Step 1: 扩展 `AppSettingsStore`**：加 `themeMode` 字段与读写（DataStore 存枚举名，未知值回退 SYSTEM）。
- [ ] **Step 2: `MainActivity` 收集 themeMode** 并传入 `NovelForgeTheme`。
- [ ] **Step 3: 设置页加三档选择**（跟随系统/浅色/深色，`SegmentedButton` 或单选），保存后立即生效。
- [ ] **Step 4: 验证**：运行 `testDebugUnitTest` + `assembleDebug`；真机切换三档即时生效且重启保持。

### Task 4: F-04 Chat 对话功能（DataStore 持久化）

**Files:**
- Create: `app/src/main/java/com/novelforge/app/data/settings/ChatHistoryStore.kt`
- Create: `app/src/main/java/com/novelforge/app/presentation/chat/ChatViewModel.kt`
- Create: `app/src/main/java/com/novelforge/app/presentation/chat/ChatScreen.kt`
- Modify: `app/src/main/java/com/novelforge/app/presentation/navigation/NovelForgeNavGraph.kt`
- Modify: `app/src/main/java/com/novelforge/app/presentation/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/novelforge/app/NovelForgeApplication.kt`

**Interfaces:**
- `ChatHistoryStore`：独立 DataStore（名称 `chat_history`），`load(): List<ChatMessage>`、`save(messages: List<ChatMessage>)`，上限 50 条，超出裁剪最旧。
- `ChatViewModel`：`messages: StateFlow<List<ChatMessage>>`、`send(text)`、`cancel()`、`streaming: StateFlow<Boolean>`；复用 `AppSettingsStore` + `ApiKeyStore` + `LLMClient.streamChat()`。
- 上下文裁剪：保留首条 system（若有）+ 最近 K 轮，超 `inputBudget` 截断尾部文本。

- [ ] **Step 1: 实现 `ChatHistoryStore`**（序列化 `List<ChatMessage>`，裁剪上限）+ 单测（超限裁剪、往返一致）。
- [ ] **Step 2: 实现 `ChatViewModel`**：加载历史 → `send()` 追加 user 消息 → `streamChat` 流式追加 assistant 内容 → 每轮完成写回 store；`cancel()` 取消当前流（复用现有取消语义）。
- [ ] **Step 3: 实现 `ChatScreen`**：消息列表（气泡 UI）+ 输入框 + 发送/取消按钮 + 流式状态。
- [ ] **Step 4: 接入导航与入口**：首页加"创作助手"入口，`NovelForgeNavGraph` 加 `chat` route；`NovelForgeApplication` 提供 `chatHistoryStore`。
- [ ] **Step 5: 测试与验证**：`ChatHistoryStore` 单测 + `testDebugUnitTest` + `assembleDebug`；真机多轮对话流式显示、重启后历史恢复、超 50 条裁剪不崩溃。
- [ ] **注意**：内容不进日志；无 API Key 时给出设置引导提示。

### Task 5: F-05 章节字数统计 + 阅读模式

**Files:**
- Modify: `app/src/main/java/com/novelforge/app/presentation/chapter/ChapterScreen.kt`
- Modify: `app/src/main/java/com/novelforge/app/presentation/outline/OutlineScreen.kt`
- Modify: `app/src/main/java/com/novelforge/app/presentation/navigation/NovelForgeNavGraph.kt`（如需跨章导航）

**Interfaces:**
- 字数 = `revision.content.length`（正文长度，显示"本章 N 字"）。
- 阅读模式为 `ChapterScreen` 内部状态：`reading: Boolean`，全屏 + 字号可调（记住状态）+ 深色纸张底色 + 章末"下一章"。

- [ ] **Step 1: 字数显示**：大纲页章节卡片与章节页标题下方显示字数。
- [ ] **Step 2: 阅读模式**：`ChapterScreen` 加阅读/编辑模式切换（顶部图标），阅读模式隐藏操作按钮，只留字号调节与退出；章末"下一章"按钮按 `orderIndex` 计算相邻项。
- [ ] **Step 3: 跨章导航**：若"下一章"需要导航到相邻章节，在 `NovelForgeNavGraph` 的 chapter route 中传递 `nextItemId` 或复用 `onOpenChapter` 回调。
- [ ] **Step 4: 验证**：`testDebugUnitTest` + `assembleDebug`；真机验证切换模式、调字号、下一章跳转。

### Task 6: F-06 项目数据 JSON 备份/导出

**Files:**
- Create: `app/src/main/java/com/novelforge/app/infrastructure/export/ProjectExporter.kt`
- Modify: `app/src/main/java/com/novelforge/app/presentation/outline/OutlineScreen.kt`（或章节页菜单）
- Modify: `app/src/main/java/com/novelforge/app/presentation/navigation/NovelForgeNavGraph.kt`（入口回调）
- Modify: `app/src/main/java/com/novelforge/app/data/local/`（如需导出用，只读，不改 schema）

**Interfaces:**
- `ProjectExporter.export(project, outlineVersions, chapterRevisions, questData, creativeConfig, continuityState): String` 返回 JSON；`writeToCache + shareUri` 复用 `TxtExporter` 的 FileProvider 套路。
- 导出内容：`Project`（含 config/quest/continuity）+ 全部 `OutlineVersion` + 全部 `ChapterRevision`。**不含 prompt 快照与 API Key**。

- [ ] **Step 1: 定义导出模型与序列化**（`ExportBundle` 用 `@Serializable`），单测往返一致。
- [ ] **Step 2: 实现 `ProjectExporter`**：从 repository 拉取数据 → 序列化 → 写 cache 文件 → 返回 URI。
- [ ] **Step 3: 接 UI 入口**：大纲页加"导出项目数据"按钮 → Sharesheet 分享 JSON。
- [ ] **Step 4: 测试**：序列化单测 + `testDebugUnitTest` + `assembleDebug`；真机导出文件可被文件管理器读取，内容完整且无 prompt 快照。
- [ ] **Step 5: 更新备份/隐私说明**：README 与测试矩阵记录"导出 JSON 不含 prompt 快照"。

### Task 7: F-07 本地质量检查（激活 QualityReport）

**Files:**
- Create: `app/src/main/java/com/novelforge/app/infrastructure/quality/LocalQualityChecker.kt`
- Create: `app/src/main/java/com/novelforge/app/data/repository/RoomQualityRunRepository.kt`
- Create: `app/src/main/java/com/novelforge/app/domain/repository/QualityRunRepository.kt`（若不存在）
- Modify: `app/src/main/java/com/novelforge/app/infrastructure/jobs/GenerationRuntime.kt`
- Modify: `app/src/main/java/com/novelforge/app/presentation/chapter/ChapterScreen.kt`

**Interfaces:**
- `LocalQualityChecker.check(chapterContent: String, targetLength: Int, characterNames: List<String>): QualityReport`。
- 规则：① 重复/近似段落（相似度阈值，如 Dice/Jaccard）；② 字数偏离目标 ±50%；③ 角色名拼写不一致（对照故事圣经角色名列表，检查常见形近错写）；④ AI 套话清单（固定开头句/高频套话）。全部为"警告 + 证据"（`QualityIssue` 带 `paragraphId/quote/severity`），不自动否决。
- `QualityRunRepository.save(run)` + `observeForChapter(chapterRevisionId)`。

- [ ] **Step 1: 写规则单测**（fixture：重复段落 → 命中；字数偏离 → 命中；角色名错写 → 命中；正常文本 → 无问题）。
- [ ] **Step 2: 实现 `LocalQualityChecker`**（纯 Kotlin，不依赖 Android）。
- [ ] **Step 3: 实现 `QualityRunRepository`（Room 版）**，复用现有 `QualityRunEntity`/`QualityRunDao`（`saveJobAndLlmCall` 不改动）。
- [ ] **Step 4: 接入 `GenerationRuntime.persistChapter`**：章节成功保存后运行本地检查并写 `quality_runs`；检查失败不影响章节保存（仅警告）。
- [ ] **Step 5: 章节页展示**：读取该章节最新 `QualityRun`，问题列表（段落引用 + 严重度 + 建议）。
- [ ] **Step 6: 验证**：`LocalQualityChecker` 单测 + 全量 `testDebugUnitTest` + `assembleDebug`；真机生成含重复段落的章节后报告正确展示。

### Task 8: F-08 故事圣经/设定管理（激活 ContinuityState / CharacterProfile）

**Files:**
- Create: `app/src/main/java/com/novelforge/app/presentation/story/StoryBibleScreen.kt`
- Create: `app/src/main/java/com/novelforge/app/presentation/story/StoryBibleViewModel.kt`
- Modify: `app/src/main/java/com/novelforge/app/presentation/chapter/ChapterViewModel.kt`
- Modify: `app/src/main/java/com/novelforge/app/infrastructure/jobs/GenerationRuntime.kt`（如 prompt 注入点需要）
- Modify: `app/src/main/java/com/novelforge/app/presentation/navigation/NovelForgeNavGraph.kt`
- Modify: `app/src/main/java/com/novelforge/app/domain/model/Outline.kt`（`ContinuityState` 扩展字段，如 `characters: List<CharacterProfile>`）

**Interfaces:**
- `StoryBibleViewModel`：加载 `Project` → 编辑 `ContinuityState`（worldRules / characterStates / timelineEvents / unresolvedThreads / factsWithSources）+ 角色列表 → `saveProject` 持久化（**沿用 `continuityStateJson` 列，不迁移**）。
- `ChapterViewModel.generate()`：从 `project.continuityState` 读取角色与设定，替换 `characters = emptyList()`；`ChapterContext` 携带真实数据。
- `PromptBuilder` 已支持 `characters`，确认角色列表与 `CharacterProfile` 映射（若 `ContinuityState` 增加 `characters: List<CharacterProfile>` 字段，需确认 `ignoreUnknownKeys` 序列化兼容）。

- [ ] **Step 1: 扩展 `ContinuityState`**（如加 `characters: List<CharacterProfile>`），确认序列化兼容（旧 JSON 无该字段 → 默认空列表）。
- [ ] **Step 2: 写 ViewModel 单测**：编辑角色/世界观 → saveProject 后 `Project.continuityState` 含新数据。
- [ ] **Step 3: 实现 `StoryBibleScreen` + ViewModel**：分区编辑世界观/角色/时间线/线索，保存即持久化。
- [ ] **Step 4: 接入导航**：大纲页加"设定"入口 → story 页。
- [ ] **Step 5: 修改 `ChapterViewModel.generate()`**：读取真实角色与连续性状态注入 `ChapterContext`。
- [ ] **Step 6: 验证**：ViewModel 单测 + 全量 `testDebugUnitTest` + `assembleDebug`；真机：填写设定 → 生成章节 → 抽查 prompt 含设定关键字（只验证存在性，不打印完整 prompt）；旧项目无设定数据时行为不变。

### Task 9: F-09 多章生成队列

**Files:**
- Modify: `app/src/main/java/com/novelforge/app/infrastructure/jobs/GenerationRuntime.kt`
- Modify: `app/src/main/java/com/novelforge/app/presentation/outline/OutlineScreen.kt`

**Interfaces:**
- `GenerationRuntime.queueAllChapters(projectId: String, items: List<OutlineItem>)`：按 `orderIndex` 串行排队；`queueNextChapter(projectId)` 在当前章 COMPLETED 后调度下一章。
- 队列状态：`queueState: StateFlow<QueueState>`（IDLE / RUNNING(currentIndex, total) / PAUSED / CANCELLED），存入 DataStore 或从 `generation_jobs` 表推导（优先从表推导，避免额外存储）。
- 失败/NEEDS_USER → 队列 PAUSED，用户确认后继续或取消。

- [ ] **Step 1: 写队列状态推导单测**：从 `generation_jobs` 现有状态（QUEUED/RUNNING/COMPLETED/FAILED）推导队列进度；确认 `findActiveJob` 幂等语义不产生重复章节。
- [ ] **Step 2: 实现 `queueAllChapters` / 调度下一章逻辑**：串行创建 job + WorkManager 唯一任务；当前章成功 → 创建下一章；失败暂停。
- [ ] **Step 3: 大纲页 UI**：加"生成全部章节"按钮与队列进度显示（"3/12 章"）。
- [ ] **Step 4: 验证**：单测 + `testDebugUnitTest` + `assembleDebug`；真机点"生成全部"逐章完成；中途失败队列暂停且不自动跳过；取消后不再调度。
- [ ] **注意**：F-01 通知对每章分别提醒或聚合提醒（选简单方案并注明）。

### Task 10: P3 可选加分项（F-10 ~ F-15，逐项独立）

**Files:**
- F-10 大纲文本导出：`infrastructure/export/OutlineExporter.kt`（复用 TxtExporter 套路）
- F-11 全文搜索：`data/local/Daos.kt`（`LIKE` 查询）+ 新增 `presentation/search/SearchScreen.kt`
- F-12 生成前 Token 预估：`PromptBuilder` 或 `GenerationRuntime` 增加估算函数（按字符数/token 比例粗估，**不换算费用**）
- F-13 归档项目：`HomeViewModel` + `HomeScreen` 长按菜单加"归档/取消归档"；`observeAll` 过滤或分组 ARCHIVED
- F-14 TTS 朗读：`ChapterScreen` 加播放按钮，`android.speech.tts.TextToSpeech`（系统 TTS，注意中文字库可用性）
- F-15 每日写作统计：按天聚合 `LlmCall`/章节完成量，`presentation/stats/StatsScreen.kt`

- [ ] **Step 1（F-10）**：大纲版本渲染为 Markdown 分享；单测顺序与内容。
- [ ] **Step 2（F-11）**：DAO `searchChapters(projectId, query)`；搜索页展示匹配章节与摘要片段。
- [ ] **Step 3（F-12）**：估算函数单测（粗估量级正确即可）。
- [ ] **Step 4（F-13）**：归档/取消归档 + 首页分组展示。
- [ ] **Step 5（F-14）**：TTS 播放/停止，设备无中文语音时提示。
- [ ] **Step 6（F-15）**：按天聚合展示。
- [ ] **Step 7**：每项独立运行 `testDebugUnitTest` + `assembleDebug`；逐项更新测试矩阵。

### Task 11: 整体验证与文档回看

**Files:**
- Modify: `docs/testing/mvp-test-matrix.md`
- Modify: `README.md`

- [ ] **Step 1: 全量验证**：`./gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug`，每条命令输出明确 PASS 或环境阻塞原因。
- [ ] **Step 2: 真机回归**：MVP 闭环（设置 → 创作设置 → 大纲 → 章节 → 导出）不被新增功能破坏；通知权限、DataStore 持久化、导出 JSON 验证。
- [ ] **Step 3: 隐私复查**：`rg` 检查新增代码无 API Key / prompt / 完整正文日志；导出 JSON 不含 prompt 快照。
- [ ] **Step 4: 更新测试矩阵与 README**：新增功能的测试入口与验证项；备份/隐私边界说明。

## Spec Coverage Review

- P0 体验：Task 1（通知）、Task 2（Token 统计）、Task 3（深色模式）。
- P1 创作增强：Task 4（Chat + DataStore）、Task 5（阅读模式）、Task 6（JSON 备份）。
- P2 激活闲置资产：Task 7（激活 QualityReport）、Task 8（激活 ContinuityState/CharacterProfile）、Task 9（多章队列）。
- P3 可选：Task 10。
- 全局约束：不迁移 DB（D2/D3 推论）、费用只统计 token（D4）、隐私红线（Global Constraints）、不破坏 MVP 闭环（Task 11 回归）。
