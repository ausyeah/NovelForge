# NovelForge MVP+ 补充需求

> 版本：v0.2（已决策）
> 关联文档：`docs/superpowers/plans/2026-09-18-novelforge-mvp.md`、`2026-09-18-creative-setup-flow.md`、`2026-09-18-project-history-actions.md`
> 状态：需求已澄清，实施计划见 `docs/superpowers/plans/2026-09-18-mvp-plus-features.md`

## 一、背景与目标

MVP 核心闭环（配置 → 创建 → 设置 → 大纲 → 逐章 → 导出）已基本成型，但存在两类缺口：

1. **体验缺口**：后台生成无通知、花费不可见、无深色模式。
2. **资产闲置**：`QualityReport`、`ContinuityState`、`CharacterProfile`、`LlmCall`、`ProjectStatus.ARCHIVED` 等模型/表已定义但从未被调用。

本需求分三批补齐：**P0 体验补齐（小）→ P1 创作增强（中）→ P2 激活闲置资产（中）**，另有 P3 可选加分项。每批独立可交付、可验证，不阻塞 MVP 现有闭环。

## 二、决策记录（v0.2 确认）

| # | 决策点 | 结论 | 影响 |
|---|--------|------|------|
| D1 | 实施范围 | **全部 P0-P3** | 计划覆盖全部 15 项功能，分三批排期 |
| D2 | Chat 对话历史持久化 | **DataStore**，存最近 N 条消息 JSON | 不需要 Room 迁移，但有存储上限 |
| D3 | 故事圣经实现深度 | **沿用 Project JSON 列**（`continuityStateJson` + `creativeConfigJson` 序列化） | 不加表、不迁移 DB，最快激活僵尸模型 |
| D4 | Token 统计精度 | **只显示 Token 数量**，不做费用换算 | 无需单价映射表，避免"估算费用"争议 |

**重要推论：D2 + D3 意味着本批次所有功能都不需要 Room schema 变更，DB 保持 v1。**

## 三、现有可复用资产盘点

| 资产 | 位置 | 状态 |
|------|------|------|
| LLM 流式/非流式客户端 | `infrastructure/llm/OpenAiCompatibleClient.kt` | ✅ 可用 |
| API Key Keystore 存储 | `data/security/KeystoreApiKeyStore.kt` | ✅ 可用 |
| Provider 设置 | `data/settings/AppSettingsStore.kt` | ✅ 可用 |
| WorkManager 后台任务 | `infrastructure/jobs/GenerationWorker.kt` | ✅ 可用 |
| TXT 导出 + FileProvider 分享 | `infrastructure/export/TxtExporter.kt` | ✅ 可用 |
| LLM 调用记录表 | `LlmCallEntity` + `LlmCallDao.observeForProject` | ⚠️ 有表无 UI |
| 质量报告模型 | `QualityReport / QualityCheck / QualityIssue` | ⚠️ 模型存在，无实现 |
| 连续性/角色模型 | `ContinuityState / CharacterProfile / CharacterSnapshotEntity` | ⚠️ 模型存在，无 UI 与数据流 |
| 深色配色 | `ui/theme/Theme.kt`（`DarkColors` 已写好） | ⚠️ 无开关 |
| 归档状态 | `ProjectStatus.ARCHIVED` | ⚠️ 枚举存在，无入口 |
| 对话历史存储 | DataStore（`AppSettingsStore` 同款套路，可新建独立 DataStore） | ⚠️ 需新建 |

## 四、需求清单

### P0：体验补齐（约 1 天）

#### F-01 生成完成系统通知

- **价值**：大纲/章节生成由 WorkManager 在后台执行，用户在等待期间无任何完成/失败提示，是当前最直接的体验缺口。
- **行为**：生成结束时发送通知，按结果分类：大纲完成、章节完成、生成失败、需要处理（NEEDS_USER）。P0 只要求"通知存在且分类正确"，不要求深链跳转（点开进 App 即可）。
- **实现思路**：在 `GenerationRuntime.execute()` 各收尾分支调用 `NotificationManagerCompat.notify()`；注册 `POST_NOTIFICATIONS` 运行时权限（Android 13+，minSdk 26 需在旧版本跳过）；建立通知 channel。
- **涉及文件**：`GenerationRuntime.kt`、`NovelForgeApplication.kt`、`AndroidManifest.xml`、新增 `presentation/notification/`（或放 infrastructure）。
- **注意**：通知正文不得包含 API Key、完整正文；失败通知只带错误类型摘要。
- **验收**：后台生成时锁屏/切后台，完成后收到分类正确的通知；未授权通知权限时 App 不崩溃。
- **工作量**：★ 半天。

#### F-02 Token 消耗统计（仅数量）

- **价值**：BYOK 模式下用户自付 token 费，当前 `LlmCall` 表和 `costConfirmationEnabled` 开关形同虚设。
- **行为**：展示**真实 token 数量**（input/output/total）与调用次数，**不做费用换算**。展示位置：项目详情页（大纲页或章节页顶部卡片）。
- **实现思路**：复用 `LlmCallDao.observeForProject(projectId)` 聚合 token 总量与调用次数；新增 `RoomLlmCallRepository` 聚合方法；UI 在 `OutlineScreen` 顶部加统计卡片。
- **涉及文件**：`RoomLlmCallRepository.kt`（新增聚合方法）、`domain/repository/LlmCallRepository.kt`（查现有接口）、`OutlineScreen.kt`（或新组件 `TokenUsageCard`）。
- **注意**：无费用换算，避免单价争议。
- **验收**：生成若干章节后，统计卡片显示累计 token 与调用次数，随生成实时更新。
- **工作量**：★☆ 半天。

#### F-03 深色模式

- **价值**：夜间创作刚需；`DarkColors` 已存在，成本极低。
- **行为**：设置页加"外观"选项：跟随系统 / 浅色 / 深色 三档，偏好存 DataStore，应用启动时生效。
- **实现思路**：`AppSettingsStore` 加 `themeMode` 字段 → `MainActivity` 读取偏好传入 `NovelForgeTheme(darkTheme = ...)`（跟随系统用 `isSystemInDarkTheme()`）。
- **涉及文件**：`Theme.kt`、`AppSettingsStore.kt`、`SettingsScreen.kt`、`MainActivity.kt`。
- **验收**：切换三档后立即生效，重启后保持。
- **工作量**：★ 20 分钟。

### P1：创作增强（约 1.5–2 天）

#### F-04 Chat 对话功能（DataStore 持久化）

- **价值**：创作过程中的即时问答（设定头脑风暴、段落润色建议、剧情走向讨论），补全"人机协作"体验。
- **行为**：首页新增"创作助手"入口 → 聊天页；多轮对话、流式输出、发送中可取消；对话历史存 DataStore（最近 N 条，如 50 条/最后 10 轮），重启 App 可恢复最近对话。
- **实现思路**：
  - 新增 `ChatHistoryStore`（独立 DataStore，存 `List<ChatMessage>` JSON，限制条数，超出裁剪）。
  - `ChatViewModel`：加载历史 → 维护消息列表 → 调用 `LLMClient.streamChat()` → 流式追加 → 每轮结束写回 DataStore。
  - 上下文裁剪：保留 system 提示 + 最近 K 轮，超过 `inputBudget` 时按 `takeLastByCodePoint` 截断。
  - 不依赖项目上下文（通用助手）；后续如需"针对某项目提问"再扩展。
- **涉及文件**：新增 `data/settings/ChatHistoryStore.kt`、`presentation/chat/ChatViewModel.kt`、`presentation/chat/ChatScreen.kt`；修改 `NovelForgeNavGraph.kt`、`HomeScreen.kt`（入口）、`NovelForgeApplication.kt`（提供 store）。
- **注意**：DataStore 大小上限——超过 50 条时丢弃最旧；内容不进日志。
- **验收**：多轮对话流式显示；取消停止输出；重启 App 后最近对话仍在；连发超过上限时最旧消息被裁剪不崩溃。
- **工作量**：★★ 1 天。

#### F-05 章节字数统计 + 阅读模式

- **价值**：小说 App 的核心体验是"读"；字数统计给用户完成感。
- **行为**：
  1. 大纲页章节卡片、章节页显示"本章 N 字"。
  2. 阅读模式：全屏正文、字号可调、深色纸张底色、章末"下一章"按钮。
- **实现思路**：字数 = `content.length`；阅读模式为 `ChapterScreen` 内状态切换（普通/阅读），复用现有 revision 数据；"下一章"按 `orderIndex` 导航到相邻 `outlineItemId`。
- **涉及文件**：`ChapterScreen.kt`、`OutlineScreen.kt`、`NovelForgeNavGraph.kt`（如需要跨章节跳转）。
- **验收**：字数与内容一致；阅读模式全屏无 UI 干扰；下一章按大纲顺序切换。
- **工作量**：★☆ 半天。

#### F-06 项目数据 JSON 备份/导出

- **价值**：本地优先 App 数据安全兜底；当前唯一导出是 TXT，大纲/设置/角色无法导出。
- **行为**：项目页提供"导出项目数据"：大纲版本、章节修订、创作设置、问答、连续性状态序列化为单个 JSON，经 Sharesheet 分享。**P1 只做导出，不做导入**。
- **实现思路**：新增 `ProjectExporter`（复用 `TxtExporter` 的 FileProvider 套路），从各 repository 拉取后 `kotlinx.serialization` 序列化。**明确不含 prompt 快照与 API Key**。
- **涉及文件**：新增 `infrastructure/export/ProjectExporter.kt`、入口（`OutlineScreen` 或章节页菜单）、`NovelForgeNavGraph.kt`。
- **验收**：导出 JSON 可被其他工具读取；不含 prompt 快照；含全部大纲与章节。
- **工作量**：★☆ 半天。

### P2：激活闲置资产（约 2 天）

#### F-07 AI 质量检查（激活 `QualityReport`）

- **价值**：v1.3 计划的"质检"是僵尸模块；逐章生成后跑一组**本地规则检查**（不调 LLM，零成本），发现明显问题并展示证据。
- **行为**：章节生成完成后自动运行本地检查：重复/近似段落、字数明显偏离目标（对照 `targetLength` ±50%）、角色名拼写不一致（对照故事圣经角色档案）、明显"AI 套话"（固定开头句/高频套话清单）。结果写入 `QualityRunEntity`，章节页展示问题列表（段落引用 + 严重度）。
- **实现思路**：新增 `LocalQualityChecker`（纯 Kotlin 可单测）；`GenerationRuntime.persistChapter` 成功后调用并写 `quality_runs` 表；章节页读取展示。
- **涉及文件**：新增 `infrastructure/quality/LocalQualityChecker.kt`、`RoomQualityRunRepository`（`QualityRunDao` 已存在）、`ChapterScreen.kt`。
- **注意**：沿用"警告 + 证据"原则，不自动否决正文；规则全部本地，无额外 token 成本。
- **验收**：构造含重复段落/超长/角色名错误的章节，生成后质检报告列出对应问题与严重度。
- **工作量**：★★ 1 天。

#### F-08 故事圣经/设定管理（激活 `ContinuityState`、`CharacterProfile`）

- **价值**：当前 `ChapterViewModel.generate()` 里 `characters = emptyList()`、`continuityState` 为空结构体——模型全闲置。故事圣经是长期一致性基础。
- **行为**：新增"设定"页：编辑世界观规则、角色档案（外观/性格/动机/能力/关系）、时间线事件、未解决线索；这些数据注入章节生成 prompt（替换 `characters = emptyList()` 硬编码）。
- **实现思路**：
  - **沿用 Project JSON 列**（D3）：`Project.continuityState` + `Project.questData` 已序列化进 `ProjectEntity.continuityStateJson`，编辑即 `saveProject`，**不加表不迁移**。
  - 新建 `StoryBibleScreen` + `StoryBibleViewModel`（编辑 ContinuityState 各字段 + 简单角色表单）。
  - `ChapterViewModel` 组装 `ChapterContext` 时从 `project.continuityState` 读取真实数据；角色档案先存 `questData.answers` 或 `continuityState` 扩展字段（MVP 简版：角色列表放 `ContinuityState.characterStates`，或用新增 JSON 列字段——由实施时在满足"不迁移"前提下选定）。
  - `PromptBuilder.buildSystemPrompt` 已支持传 characters，改调用处即可。
- **涉及文件**：新增 `presentation/story/StoryBibleScreen.kt`、`StoryBibleViewModel.kt`；修改 `ChapterViewModel.kt`、`GenerationRuntime.kt`、`NovelForgeNavGraph.kt`、`domain/model/Outline.kt`（如 ContinuityState 需加角色字段，属模型层扩展，不迁移 DB）。
- **注意**：prompt 预算——角色档案多时按 `inputBudget` 裁剪后再注入；若 `ContinuityState` 增加新字段，需确认序列化兼容（`ignoreUnknownKeys` 已开，安全）。
- **验收**：设定页编辑角色与世界观 → 生成章节 → prompt 中包含设定（通过断点或日志抽查，注意隐私不打印完整 prompt，可只验证长度与关键字）。
- **工作量**：★★ 1 天。

#### F-09 多章生成队列

- **价值**：逐章点"生成这一章"太碎；整本小说往往想一口气排队生成。
- **行为**：大纲页加"生成全部章节"：按 `orderIndex` 串行排队，逐个复用现有 `GenerationJob` 幂等逻辑；支持整体取消。
- **实现思路**：在 `GenerationRuntime` 增加"调度下一章"逻辑：当前章 COMPLETED → 自动创建下一章 job（复用 `findActiveJob` / `createJob` / WorkManager 唯一任务）；失败/NEEDS_USER 时暂停队列，由用户决定继续。**串行而非并行**（避免同项目多 job 互相覆盖上下文/预算）。
- **涉及文件**：`GenerationRuntime.kt`、`OutlineScreen.kt`（"生成全部"按钮 + 队列状态显示）。
- **注意**：必须复用 `findActiveJob(projectId, CHAPTER, targetId)` 去重语义；F-01 通知对每章完成分别提醒（或聚合提醒，实施时选简单方案）。
- **验收**：点"生成全部"后逐章自动生成到完成；中途一章失败队列暂停并提示；取消后不再自动调度。
- **工作量**：★★ 1 天。

### P3：体验加分（可选）

| ID | 功能 | 说明 | 工作量 |
|----|------|------|--------|
| F-10 | 大纲文本导出 | 大纲版本渲染为 Markdown/文本分享，复用 TxtExporter 套路 | ★ |
| F-11 | 全文搜索 | 章节正文/大纲内搜索，Room `LIKE` 查询 | ★☆ |
| F-12 | 生成前 Token 预估 | 按章节数+目标字数估算本次请求 input/output token 量（**不换算费用**，与 D4 一致） | ★ |
| F-13 | 归档项目 | 激活 `ARCHIVED`：首页长按菜单加"归档/取消归档"，归档项目不显示在主列表（或折叠区） | ★ |
| F-14 | TTS 朗读 | 章节页朗读（长期功能） | ★★☆ |
| F-15 | 每日写作统计 | 按天聚合 LlmCall/章节完成量，简单列表或图表 | ★☆ |

## 五、实施顺序与验证

1. **P0（F-01/02/03）** → 跑 `testDebugUnitTest + lintDebug + assembleDebug`。
2. **P1（F-04/05/06）** → 同上 + 真机验证通知、Chat 重启恢复、导出文件可读。
3. **P2（F-07/08/09）** → 同上 + 真机验证整本生成队列与质检展示。
4. 每个功能完成后更新测试矩阵 `docs/testing/mvp-test-matrix.md`。

## 六、风险与注意

1. **无 Room 迁移**（D2+D3 推论）：本批次所有功能保持 DB v1，规避迁移风险；若未来要 Chat 升级为 Room 存储再单独规划 v1→v2。
2. **DataStore 上限**：F-04 历史消息必须裁剪（建议 50 条），防止偏好文件无限增长。
3. **通知权限**：Android 13+ 需 `POST_NOTIFICATIONS` 运行时申请；未授权时静默降级不 crash。
4. **隐私红线**：通知、统计、导出内容均不得含 API Key、完整 prompt、完整响应；F-06 导出 JSON **不含 prompt 快照**。
5. **队列幂等**：F-09 必须复用 `findActiveJob` 语义，防止重复生成同一章节。
6. **上下文预算**：F-04 长对话、F-08 角色档案都需按 `inputBudget/safetyMargin` 裁剪，沿用 v1.3 预算模型。
7. **僵尸模块激活顺序**：F-07/F-08 涉及现有代码调用点改动（`GenerationRuntime.persistChapter`、`ChapterViewModel.generate`），改动前先确认这两处当前行为，避免破坏现有生成闭环。
