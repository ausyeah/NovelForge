# 大纲分章生成与字数风险提示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让大纲严格按“一章一请求”生成并逐章保存，同时收紧单章字数选项，在用户选择过长字数前明确提示体验、截断风险和模型额度消耗。

**Architecture:** `GenerateOutlineUseCase` 只构造指定章节的请求；`GenerationRuntime` 每个 Worker 只执行一个章节请求，解析并合并到本地累计 envelope，完成后用 WorkManager 追加下一次请求。创作设置页面只展示建议范围内的快捷选项，自定义值限制在 500–10000，并在超过 8000 时显示浅色预警。

**Tech Stack:** Kotlin、Jetpack Compose、WorkManager、Room repository、kotlinx.serialization、JUnit。

**Spec:** 用户对本轮开发的要求：不提供不建议的超长单章选项；自定义字数超过体验阈值时提前提示；大纲必须一章一请求、逐章合并保存并显示进度。

## Global Constraints

- 每个大纲上游请求只能返回一个 `OutlineItem`，不得要求模型生成章节列表或正文。
- 全书章节数只作为背景和进度目标，不能放大单次输出。
- 每成功合并一个章节都要持久化；下一次请求从已保存章节数继续，不能重复已完成章节。
- 大纲请求只携带最近 3 章概要，不得把完整大纲再次塞进 prompt。
- 快捷单章字数选项最高为 10000；自定义值只能是 500–10000。
- 8000 字以上显示风险提示，10000 字以上直接校验失败。
- 不在日志、测试输出或用户回复中输出 API Key、完整 prompt 或完整模型响应。

### Task 1: 修正大纲请求契约

**Files:**
- Modify: `app/src/main/java/com/novelforge/app/domain/usecase/GenerateOutlineUseCase.kt`
- Test: `app/src/test/java/com/novelforge/app/domain/usecase/GenerationUseCaseTest.kt`

**Interfaces:**
- Produces `GenerateOutlineUseCase.buildChapterRequest(project, connection, outputTokenBudget, requestId, chapterNumber, previousChapters): ChatRequest`。
- 请求必须把 `chapters` 限定为恰好一个项，并使用 `chapter-N` 与 `orderIndex = N - 1`。

- [x] **Step 1: 修正所有调用点**

把幂等复用、活动任务复用和首次创建分支统一改为调用 `buildChapterRequest`，为复用任务根据 `partialContent` 解析出的下一章编号提供最近章节上下文；删除未使用的旧创作配置局部变量，并修复 Kotlin 命名参数写法。

- [x] **Step 2: 更新请求契约测试**

断言第 41 章请求只出现第 41 章的约束、禁止其他章节/正文，且不包含旧的“至少生成全部章节”措辞；继续断言创作设定会进入请求，但不打印完整请求到测试日志。

- [x] **Step 3: 运行目标单测**

运行：`gradle.bat testDebugUnitTest --tests com.novelforge.app.domain.usecase.GenerationUseCaseTest --no-daemon`

预期：用例通过，且编译不再出现旧 `buildRequest` 引用或命名参数错误。

### Task 2: 把大纲执行改成逐章 WorkManager 任务

**Files:**
- Modify: `app/src/main/java/com/novelforge/app/infrastructure/jobs/GenerationRuntime.kt`
- Modify: `app/src/main/java/com/novelforge/app/infrastructure/llm/JsonResponseValidator.kt`
- Test: `app/src/test/java/com/novelforge/app/infrastructure/jobs/GenerationRecoveryTest.kt`
- Test: `app/src/test/java/com/novelforge/app/infrastructure/llm/JsonResponseValidatorTest.kt`

**Interfaces:**
- `GenerationRuntime.execute(jobId, runAttemptCount)` 对 `OUTLINE` 使用逐章分支；章节请求成功后累计保存 `{"chapters":[...]}`。
- 每个请求的 `requestId` 形如 `<job.clientRequestId>-outline-chapter-<N>`。
- `JsonResponseValidator.parseOutline(raw, expectedCount = 1)` 用于验证单章响应。

- [x] **Step 1: 先写恢复/分章失败测试**

覆盖这些行为：空累计内容从第 1 章请求开始；累计已有 1 章时下一请求为第 2 章；完成第 1 章后不会再次请求第 1 章；单章 envelope 通过校验，截断 JSON 返回结构化失败原因。

- [x] **Step 2: 实现累计解析和下一章计算**

新增只供 runtime 使用的解析函数：从 `job.partialContent` 读取已保存 envelope，解析失败时只允许在没有可恢复完整章节时从第 1 章重新开始；若已有完整章节而累计 envelope 损坏，则标记 `NEEDS_USER` 并保留原始 partial content。下一章编号为已合并章节数加一，超过项目配置章节数时进入最终完成流程。

- [x] **Step 3: 为每一章构造短请求并保存快照**

每次执行前读取项目创作配置和最近 3 个 `OutlineItem`，调用 `buildChapterRequest`；将当前请求写回 prompt snapshot 后再启动 coordinator。不要把 200 章累计 JSON 放入请求消息。

- [x] **Step 4: 合并单章并追加下一项 WorkRequest**

在单章结构校验成功后，将新项规范化为目标 `id` 和 `orderIndex`，把它和已保存章节合并成 envelope，保存回同一个任务的 `partialContent`。若未达到目标章节数，把任务状态保留为 `RUNNING`/`QUEUED` 并用 `ExistingWorkPolicy.APPEND` 追加同一 `uniqueWorkName(jobId)` 的下一个 Worker；最后一章才保存 `OutlineVersion`、项目流转状态和完成的 LLM call。

- [x] **Step 5: 保证网络重试和取消语义**

网络异常只重试当前章节；已合并章节不丢失。取消或 Worker 被系统终止后保留累计 envelope，下次继续从下一章开始。结构错误进入 `NEEDS_USER`，错误消息说明具体是第几章，不把截断内容当成完整大纲。

- [x] **Step 6: 运行恢复与 validator 目标测试**

运行：`gradle.bat testDebugUnitTest --tests com.novelforge.app.infrastructure.jobs.GenerationRecoveryTest --tests com.novelforge.app.infrastructure.llm.JsonResponseValidatorTest --no-daemon`

预期：逐章请求、断点续传、单章校验和截断错误测试全部通过。

### Task 3: 增加可理解的生成进度

**Files:**
- Modify: `app/src/main/java/com/novelforge/app/presentation/outline/OutlineScreen.kt`
- Modify: `app/src/main/java/com/novelforge/app/presentation/navigation/NovelForgeNavGraph.kt`
- Modify: `app/src/main/java/com/novelforge/app/presentation/common/GenerationStatusCard.kt`

**Interfaces:**
- `OutlineScreen` 新增 `plannedChapterCount: Int?` 参数。
- 导航从项目的 `creativeConfig.chapterCount` 传入计划章节数。

- [x] **Step 1: 计算已完成章节数**

从累计 `partialContent` 解析 `chapters` 数量；解析失败或只有流式半截时显示已接收字符数，但不把它误报成已完成章节。

- [x] **Step 2: 展示当前章节和累计进度**

任务运行时显示“正在生成第 N 章大纲”和“已生成 X/Y 章 · 已保存 Z 个字符”；当前章节尚未合并时显示模型正在思考/接收，不让用户看到长时间不变化的静态文案。

- [x] **Step 3: 编译导航和 UI**

运行：`gradle.bat compileDebugKotlin --no-daemon`，确认新增参数在所有调用点传递且 Compose 类型推导通过。

### Task 4: 收紧单章字数设置并增加风险预警

**Files:**
- Modify: `app/src/main/java/com/novelforge/app/presentation/project/CreativeSetupScreen.kt`
- Modify: `app/src/main/java/com/novelforge/app/presentation/project/CreativeSetupViewModel.kt`
- Test: `app/src/test/java/com/novelforge/app/presentation/project/CreativeSetupViewModelTest.kt`

**Interfaces:**
- 快捷选项最高 `10_000`。
- `validateCreativeSetup` 接受 `500..10_000`，超过范围返回明确中文错误。
- UI 在 `selectedTargetLength > 8_000 && <= 10_000` 时显示浅色 warning 文案。

- [x] **Step 1: 更新设置校验测试**

保留 10000 字合法测试，增加 10001 字非法测试；把原本 12000 字合法断言改成 10000 字，并断言错误文本包含“500 到 10000”。

- [x] **Step 2: 收紧选项和输入标签**

快捷选项只保留 2000、4000、6000、8000、10000；自定义标签改为“自定义每章字数（500-10000）”，输入值仍只允许数字。

- [x] **Step 3: 增加浅色预警和说明**

在保存按钮前显示：`单章超过 8000 字会明显增加生成时间、失败概率和模型额度消耗，建议控制在 2000-8000 字。` 使用 MaterialTheme 的 `onSurfaceVariant` 或同等低强调色，不能伪装成阻塞错误；超过 10000 由校验阻止保存。页面总说明同步解释上游截断和等待时间风险。

- [x] **Step 4: 运行创作设置测试**

运行：`gradle.bat testDebugUnitTest --tests com.novelforge.app.presentation.project.CreativeSetupViewModelTest --no-daemon`

预期：合法边界和非法边界均通过。

### Task 5: 全量验证并安装测试包

**Files:**
- Verify: 上述所有修改文件和测试文件

- [x] **Step 1: 检查差异和敏感信息**

用 `rg` 检查源码和测试中没有 API Key、完整模型响应、完整 prompt 输出；检查旧的“至少生成 N 个章节”提示词和 `50_000` 单章上限均已删除。

- [x] **Step 2: 运行完整验证命令**

运行：

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
& 'C:\Users\26315\AppData\Local\Temp\novelforge-gradle-8.9\gradle-8.9\bin\gradle.bat' `
  testDebugUnitTest `
  compileDebugAndroidTestKotlin `
  lintDebug `
  assembleDebug `
  --no-daemon
```

预期：命令退出码为 0，无测试失败、Kotlin 编译错误或 lint error。

- [x] **Step 3: 保留数据覆盖安装**

命令成功后使用 `adb install -r app/build/outputs/apk/debug/app-debug.apk` 覆盖安装到已连接设备，保留现有用户数据；通过 `adb shell pm list packages` 和安装结果确认包仍为 `com.novelforge.app`。

- [x] **Step 4: 汇报实际结果**

只报告已运行命令和真实退出结果；若构建或设备验证失败，明确失败阶段和下一步，不把未验证内容说成已修复。
