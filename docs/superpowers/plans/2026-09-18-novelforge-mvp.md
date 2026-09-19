# NovelForge MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 NovelForge 技术方案收敛为可验证的 Android 本地 MVP，并实现“配置模型 → 创建项目 → 结构化大纲 → 逐章生成 → 本地保存/恢复”的最小闭环。

**Architecture:** 使用单一 Android `app` 模块起步，内部按 `presentation / domain / data / infrastructure` 分包，避免空目录式多模块。Domain 只依赖模型和端口；Room、DataStore、Keystore、OkHttp 和 WorkManager 通过基础设施实现。生成过程以持久化 `GenerationJob` 为核心，UI 只是观察状态，不直接持有长时间网络任务。

**Tech Stack:** Kotlin 2.x、Android SDK 35+、Jetpack Compose、ViewModel + 单向数据流、Kotlin Coroutines/Flow、Room、Proto DataStore、Android Keystore、OkHttp、kotlinx.serialization、WorkManager、JUnit、MockWebServer、Turbine。

**Spec:** `C:\Users\26315\.fintwind\projects\2026-09-18\new-chat-3\novel-app-技术方案.md`，修订为 v1.3。

## Global Constraints

- 平台固定为 Android 原生，`minSdk` 26，使用 Kotlin 和 Jetpack Compose。
- 第一版只支持用户自带 API Key；不得内置或共享开发者 API Key。
- 第一版不承诺云同步和在线分享链接；只实现本地 TXT 导出和 Android 文件分享。
- 章节生成必须持久化任务状态、部分内容和错误信息，支持进程重启后恢复或明确失败。
- LLM 供应商能力必须显式声明；不能因为“OpenAI 兼容”就假设 JSON、流式和 usage 行为一致。
- 上下文预算必须区分输入预算、输出预算和安全余量。
- API Key、prompt、正文和诊断日志不得写入普通日志或未经说明的第三方崩溃报告。
- 章节正文、角色、大纲和 LLM 调用都必须可追溯到版本或快照。
- 质量检查默认提供警告和证据，不得仅凭任意单项本地规则自动否决用户作品。
- 每个任务结束后运行该任务列出的测试或静态验证；Android SDK 缺失时必须明确报告阻塞项，不得把未构建说成已通过。

---

### Task 1: 修订 v1.3 技术方案

**Files:**
- Modify: `C:\Users\26315\.fintwind\projects\2026-09-18\new-chat-3\novel-app-技术方案.md`

**Interfaces:**
- Consumes: 当前 v1.2 方案和本计划中的 Global Constraints。
- Produces: v1.3 方案，包含 MVP 非目标、生成任务模型、Provider 能力矩阵、上下文预算、连续性状态、版本化数据、安全隐私、成本限制、质量报告证据格式、导出边界和验收标准。

- [ ] **Step 1: 在文档开头加入范围声明**

  将版本更新为 v1.3，并明确第一版是“本地 BYOK MVP”，不包含云同步、在线分享链接和多平台支持。

- [ ] **Step 2: 增加生成任务和恢复章节**

  定义 `GenerationJob` 的状态、重试规则、幂等键、部分内容检查点、进程重启恢复和 `finish_reason=length` 处理。

- [ ] **Step 3: 修正上下文和 Provider 设计**

  将 `contextBudget` 拆成 `inputBudget`、`outputBudget` 和 `safetyMargin`；为 Provider 增加流式、JSON、JSON Schema、usage、鉴权和参数风格能力矩阵。

- [ ] **Step 4: 增加连续性状态和版本化数据要求**

  定义稳定的 `outlineItemId`、章节修订、角色快照、prompt 快照、LLM 调用记录和故事圣经字段。

- [ ] **Step 5: 修正安全、隐私、质检和分享表述**

  明确 LLM 服务商会接收用户主动提交的上下文；增加备份、日志、删除和 API Key 风险；让质检返回问题位置和严重度；将“分享链接”改为后续云功能。

- [ ] **Step 6: 增加 MVP 验收标准和测试矩阵**

  覆盖坏 JSON、429、超时、断网、杀进程、重复请求、迁移、导出和 Token 成本上限。

- [ ] **Step 7: 验证文档结构**

  Run: `rg -n "v1\.3|GenerationJob|inputBudget|outlineItemId|LlmCall|本地 BYOK|分享链接|验收标准" "C:\\Users\\26315\\.fintwind\\projects\\2026-09-18\\new-chat-3\\novel-app-技术方案.md"`

  Expected: 每个关键词至少命中一个对应章节，文档末尾版本号为 v1.3。

### Task 2: 创建可构建的 Android 工程骨架

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `app/build.gradle.kts`
- Create: `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/novelforge/app/NovelForgeApplication.kt`
- Create: `app/src/main/java/com/novelforge/app/MainActivity.kt`
- Create: `app/src/main/java/com/novelforge/app/ui/theme/Color.kt`
- Create: `app/src/main/java/com/novelforge/app/ui/theme/Theme.kt`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`

**Interfaces:**
- Consumes: Android SDK 35、JDK 17 和 version catalog。
- Produces: 可以由 Android Studio 导入的单模块工程；`MainActivity` 使用 Compose 显示空项目首页。

- [ ] **Step 1: 写入 Gradle 基础配置**

  配置 `compileSdk = 35`、`minSdk = 26`、`targetSdk = 35`、JVM 17、Compose 编译插件、serialization 插件和 version catalog。

- [ ] **Step 2: 配置应用依赖和 Manifest**

  加入 Compose、Lifecycle、Navigation、Room、DataStore、OkHttp、serialization、WorkManager、Hilt 和测试依赖；Manifest 只声明网络权限和 Application。

- [ ] **Step 3: 创建最小 Compose 入口**

  `MainActivity` 使用 `NovelForgeTheme` 和 `NovelForgeApp`，初始 UI 显示项目为空以及“新建项目”入口。

- [ ] **Step 4: 运行工程级静态检查**

  Run: `Get-ChildItem -Recurse -File | Select-Object -ExpandProperty FullName`

  Expected: 工程文件路径完整；如果本机没有 Android SDK 或 Gradle Wrapper，记录为环境阻塞，不伪造构建成功。

### Task 3: 建立 Domain 模型、序列化和版本化接口

**Files:**
- Create: `app/src/main/java/com/novelforge/app/domain/model/Project.kt`
- Create: `app/src/main/java/com/novelforge/app/domain/model/CreativeConfig.kt`
- Create: `app/src/main/java/com/novelforge/app/domain/model/Outline.kt`
- Create: `app/src/main/java/com/novelforge/app/domain/model/Chapter.kt`
- Create: `app/src/main/java/com/novelforge/app/domain/model/CharacterProfile.kt`
- Create: `app/src/main/java/com/novelforge/app/domain/model/GenerationJob.kt`
- Create: `app/src/main/java/com/novelforge/app/domain/model/LlmCall.kt`
- Create: `app/src/main/java/com/novelforge/app/domain/model/QualityReport.kt`
- Create: `app/src/main/java/com/novelforge/app/domain/repository/ProjectRepository.kt`
- Create: `app/src/main/java/com/novelforge/app/domain/repository/GenerationRepository.kt`
- Test: `app/src/test/java/com/novelforge/app/domain/model/SerializationTest.kt`

**Interfaces:**
- Consumes: 无基础设施依赖的 Kotlin 标准库和 kotlinx.serialization。
- Produces: 稳定 ID、状态枚举、版本号、快照和 Token 用量的领域契约；后续 Room 和 UI 只依赖这些类型。

- [ ] **Step 1: 写序列化失败测试**

  覆盖 `GenerationJobStatus`、`OutlineItem.id`、`QualityIssue.paragraphId` 和可选字段的 JSON 往返。

- [ ] **Step 2: 运行测试确认失败**

  Run: `./gradlew :app:testDebugUnitTest --tests '*SerializationTest'`

  Expected: 在模型尚未实现时失败；若 Gradle/Android SDK 不可用，记录实际环境错误。

- [ ] **Step 3: 实现最小领域模型**

  `Chapter` 使用 `outlineItemId` 而不是可变章节号作为关联；`QualityReport` 使用 `issues` 数组返回严重度、段落 ID、引用和原因；`LlmCall` 拆分 input/output token。

- [ ] **Step 4: 运行模型测试**

  Run: `./gradlew :app:testDebugUnitTest --tests '*SerializationTest'`

  Expected: PASS。

### Task 4: 实现 Room 持久化、任务检查点和安全配置端口

**Files:**
- Create: `app/src/main/java/com/novelforge/app/data/local/AppDatabase.kt`
- Create: `app/src/main/java/com/novelforge/app/data/local/Entities.kt`
- Create: `app/src/main/java/com/novelforge/app/data/local/Daos.kt`
- Create: `app/src/main/java/com/novelforge/app/data/local/DatabaseMigrations.kt`
- Create: `app/src/main/java/com/novelforge/app/data/security/ApiKeyStore.kt`
- Create: `app/src/main/java/com/novelforge/app/data/security/KeystoreApiKeyStore.kt`
- Create: `app/src/main/java/com/novelforge/app/data/settings/AppSettingsStore.kt`
- Test: `app/src/test/java/com/novelforge/app/data/local/EntityMappingTest.kt`
- Test: `app/src/androidTest/java/com/novelforge/app/data/local/DatabaseMigrationTest.kt`

**Interfaces:**
- Consumes: Task 3 的领域模型。
- Produces: 项目、大纲版本、章节修订、角色快照、生成任务和 LLM 调用的 DAO；API Key 只通过 `ApiKeyStore` 端口访问。

- [ ] **Step 1: 写实体映射测试**

  验证 `outlineItemId`、任务状态、Token 明细和质量问题 JSON 可以持久化，不把可变章节号当作主键。

- [ ] **Step 2: 实现 Room 表和 DAO**

  用独立表保存 `ProjectEntity`、`OutlineVersionEntity`、`ChapterRevisionEntity`、`GenerationJobEntity`、`LlmCallEntity` 和 `QualityRunEntity`；用事务完成章节修订与任务状态更新。

- [ ] **Step 3: 实现 Keystore API Key 存储**

  使用 Android Keystore 保护 AES-GCM 密钥；每个密文保存版本和随机 IV；禁止记录明文 Key；为备份规则保留明确策略。

- [ ] **Step 4: 运行 Room 和安全相关测试**

  Run: `./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest`

  Expected: 单元测试通过；若没有模拟器，单独报告 `connectedDebugAndroidTest` 的环境缺失。

### Task 5: 实现 LLM Provider 能力矩阵、JSON 校验和网络容错

**Files:**
- Create: `app/src/main/java/com/novelforge/app/infrastructure/llm/LLMClient.kt`
- Create: `app/src/main/java/com/novelforge/app/infrastructure/llm/ProviderCapabilities.kt`
- Create: `app/src/main/java/com/novelforge/app/infrastructure/llm/OpenAiCompatibleClient.kt`
- Create: `app/src/main/java/com/novelforge/app/infrastructure/llm/JsonResponseValidator.kt`
- Create: `app/src/main/java/com/novelforge/app/infrastructure/llm/RetryPolicy.kt`
- Create: `app/src/main/java/com/novelforge/app/infrastructure/llm/PromptBuilder.kt`
- Test: `app/src/test/java/com/novelforge/app/infrastructure/llm/JsonResponseValidatorTest.kt`
- Test: `app/src/test/java/com/novelforge/app/infrastructure/llm/RetryPolicyTest.kt`
- Test: `app/src/test/java/com/novelforge/app/infrastructure/llm/OpenAiCompatibleClientTest.kt`

**Interfaces:**
- Consumes: `ApiKeyStore`、`ModelPreset`、Task 3 的 `LlmCall` 和 provider capability matrix。
- Produces: `ModelPreset`、`ChatOptions`、`ChatRequest`、`LlmResponse`、`StreamEvent` 和 `LLMClient`；接口固定为 `suspend fun chat(request: ChatRequest): LlmResponse` 与 `fun streamChat(request: ChatRequest): Flow<StreamEvent>`，所有请求都携带 `ChatOptions.responseFormat` 和取消语义。

- [ ] **Step 1: 用 fixture 写坏 JSON 和截断响应测试**

  覆盖代码块包裹、首尾噪声、缺少字段、`finish_reason=length`、JSON Schema 不支持和流末 usage 缺失。

- [ ] **Step 2: 实现本地 JSON 提取和 schema 校验**

  解析顺序为严格解析、提取 JSON、字段校验；失败时返回可展示的结构化错误，不直接触发无限重试。

- [ ] **Step 3: 实现 OpenAI-compatible 请求和 SSE 解析**

  处理 `[DONE]`、多行 data、取消、UTF-8、流末 usage、4xx/5xx/429 和 `Retry-After`；重试只针对明确的可重试错误，并携带请求幂等标识。

- [ ] **Step 4: 实现 PromptBuilder 的章节上下文接口**

  输入包含 system prompt、故事圣经、角色快照、相关章节摘要和当前大纲项；先按预算裁剪再构造请求。

- [ ] **Step 5: 运行 LLM 层测试**

  Run: `./gradlew :app:testDebugUnitTest --tests '*JsonResponseValidatorTest' --tests '*RetryPolicyTest' --tests '*OpenAiCompatibleClientTest'`

  Expected: PASS，且测试日志不包含 API Key 或正文。

### Task 6: 实现本地 MVP 生成协调器和恢复流程

**Files:**
- Create: `app/src/main/java/com/novelforge/app/domain/usecase/CreateProjectUseCase.kt`
- Create: `app/src/main/java/com/novelforge/app/domain/usecase/GenerateOutlineUseCase.kt`
- Create: `app/src/main/java/com/novelforge/app/domain/usecase/GenerateChapterUseCase.kt`
- Create: `app/src/main/java/com/novelforge/app/infrastructure/jobs/GenerationWorker.kt`
- Create: `app/src/main/java/com/novelforge/app/infrastructure/jobs/GenerationCoordinator.kt`
- Test: `app/src/test/java/com/novelforge/app/domain/usecase/GenerationUseCaseTest.kt`
- Test: `app/src/test/java/com/novelforge/app/infrastructure/jobs/GenerationRecoveryTest.kt`

**Interfaces:**
- Consumes: Task 4 的 DAO、Task 5 的 `LLMClient`、`GenerationJob` 和 `PromptBuilder`。
- Produces: 创建项目、生成大纲、逐章生成、保存部分内容、取消、重试、失败恢复和用户确认的可观察状态。

- [ ] **Step 1: 写恢复场景测试**

  覆盖请求开始后进程终止、流式中途断网、坏 JSON、模型输出截断、重复点击生成和用户取消。

- [ ] **Step 2: 实现幂等的任务创建**

  以 `projectId + purpose + targetId + clientRequestId` 去重；同一任务只能有一个 running 状态。

- [ ] **Step 3: 实现流式检查点**

  每 200ms 或 500 个字符保存部分内容；UI 不直接保存网络回调；任务完成时用事务写入章节修订和 LLM 用量。

- [ ] **Step 4: 接入 WorkManager Worker**

  Worker 从数据库恢复任务，不依赖 Activity 存活；网络错误使用有限重试；用户取消转为 `CANCELLED`，不得再次自动重试。

- [ ] **Step 5: 运行恢复测试**

  Run: `./gradlew :app:testDebugUnitTest --tests '*GenerationUseCaseTest' --tests '*GenerationRecoveryTest'`

  Expected: PASS；重复触发不会创建第二个相同章节结果。

### Task 7: 实现 Compose MVP 界面和本地导出

**Files:**
- Create: `app/src/main/java/com/novelforge/app/presentation/navigation/NovelForgeNavGraph.kt`
- Create: `app/src/main/java/com/novelforge/app/presentation/home/HomeScreen.kt`
- Create: `app/src/main/java/com/novelforge/app/presentation/project/CreateProjectScreen.kt`
- Create: `app/src/main/java/com/novelforge/app/presentation/outline/OutlineScreen.kt`
- Create: `app/src/main/java/com/novelforge/app/presentation/chapter/ChapterScreen.kt`
- Create: `app/src/main/java/com/novelforge/app/presentation/common/GenerationStatusCard.kt`
- Create: `app/src/main/java/com/novelforge/app/infrastructure/export/TxtExporter.kt`
- Test: `app/src/androidTest/java/com/novelforge/app/presentation/SmokeTest.kt`
- Test: `app/src/test/java/com/novelforge/app/infrastructure/export/TxtExporterTest.kt`

**Interfaces:**
- Consumes: Task 4 的 repositories、Task 6 的状态流和 `Project/Outline/Chapter` 模型。
- Produces: 首页、新建项目、问答最小表单、大纲卡片、逐章生成状态、错误/取消/恢复提示和 TXT 分享。

- [ ] **Step 1: 写 TXT 导出测试**

  验证章节顺序按 `orderIndex` 输出、正文特殊字符保留、空章节有明确处理。

- [ ] **Step 2: 实现导航和首页**

  首页读取 Room 项目列表；无项目时显示新建入口；已有任务显示恢复卡片。

- [ ] **Step 3: 实现最小问答和大纲编辑**

  问答允许跳过；大纲只提供标题、概要编辑和上下移动；移动使用稳定 ID，不直接修改历史章节身份。

- [ ] **Step 4: 实现章节页面**

  显示流式正文、任务状态、取消、重试和通过；杀进程后从数据库恢复部分内容。

- [ ] **Step 5: 实现 TXT 导出和 Android 分享**

  使用 `FileProvider` 或 `content://` URI；不虚构在线链接；导出前显示作品范围和文件位置。

- [ ] **Step 6: 运行 UI 与导出测试**

  Run: `./gradlew :app:testDebugUnitTest --tests '*TxtExporterTest' :app:connectedDebugAndroidTest`

  Expected: 单元测试通过；设备测试在可用模拟器上通过。

### Task 8: 验证、文档回看和发布前检查

**Files:**
- Modify: `README.md`
- Create: `docs/testing/mvp-test-matrix.md`
- Create: `app/src/main/res/xml/backup_rules.xml`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: Task 1–7 的实现和 v1.3 验收标准。
- Produces: 本地运行说明、测试矩阵、备份策略和诚实的环境验证报告。

- [ ] **Step 1: 写运行说明**

  说明 Android SDK、JDK 17、模型预设、API Key 不入库、调试方式和当前不支持的功能。

- [ ] **Step 2: 加入备份与日志检查**

  排除 API Key 密文和临时生成缓存；确认 Timber/Logcat 等日志不包含 Key、prompt 和正文。

- [ ] **Step 3: 运行全部可用验证**

  Run: `./gradlew testDebugUnitTest lintDebug assembleDebug`

  Expected: 每条命令输出明确 PASS 或环境阻塞原因；不能以未运行代替通过。

- [ ] **Step 4: 对照 v1.3 逐项回看**

  检查 MVP 非目标、恢复、Provider 能力、隐私边界、成本记录、质量证据和分享范围均与代码一致。

## Spec Coverage Review

- 核心创作闭环：Task 3、Task 6、Task 7。
- JSON 输出和模型差异：Task 5。
- 断点恢复和进程重启：Task 4、Task 6。
- 角色/大纲/章节版本：Task 3、Task 4。
- API Key 和本地隐私：Task 4、Task 8。
- TXT 导出：Task 7。
- EPUB、在线分享、云同步、多语言和高级 LLM 质检明确列为后续版本，不属于本计划的 MVP 交付范围。
