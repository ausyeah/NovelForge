# NovelForge

本地 BYOK 的中文长篇写作应用。模型不负责记设定：每一章生成前，程序从连续性状态里挑出预算内的角色、事实和伏笔，再交给模型写。任务用 WorkManager 跑，页面关掉也能恢复；结构不对的输出不会写入正文。

API Key 只以 Android Keystore 保护的 AES-GCM 密文留在本机。

## 记忆预算

只留最近 12 条事实时，写到后面的章节会把开头的关键设定挤出 prompt。`MemorySelector` 在同样的名额里，优先留下本章标题和概要点到的角色，以及这些角色的旧事实。未确认的章后笔记仍然不进 prompt。

下面这组对照不调用模型，只检查三条必须记住的旧设定有没有进下一章上下文。「全量塞入」能记住，但会带上未确认事实，prompt 也更大。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.novelforge.app.infrastructure.llm.ContinuityBenchmarkTest
```

固定样本是 7 个角色、20 条已确认事实、1 条未确认笔记，本章要点名第 7 个角色和他最早的那条旧设定。测试锁定的结果：

| 策略 | 必须记住的设定 | 未确认笔记漏进 prompt |
|---|---|---|
| 不带记忆 | 0/3 | 0 |
| 只留最近 12 条 | 0/3 | 0 |
| 按本章捞回 | 3/3 | 0 |
| 全量塞入 | 3/3 | 1 |

简历可以写「同样名额下，旧设定召回从 0/3 到 3/3，未确认笔记仍不进入上下文」。这是选择器的对照，不是请人评的正文质量。

## 当前范围

- Android 原生，Kotlin + Jetpack Compose，`minSdk 26`。
- 项目、生成任务、章节修订和模型调用记录保存在本地 Room。
- API Key 使用 Android Keystore 保护的 AES-GCM 密文存储；不会内置共享 Key。
- Provider 以 OpenAI-compatible 为传输基线，但 JSON、流式、usage、鉴权和参数名必须由能力矩阵声明。
- 当前 UI 包含项目、创作设置、大纲、逐章生成、故事圣经、章节阅读、查书助手、灵感对话、导出、用量账本和模型设置。
- 生成请求会先保存 prompt 快照，再由 WorkManager 执行；任务状态、部分正文、章节修订和 LLM 用量写入 Room，Activity 被销毁后仍可恢复任务。
- 生成完成时，大纲/章节修订与项目当前版本指针通过 Room 事务一起提交，避免只写入一半。
- 当前运行时按 OpenAI-compatible 的 HTTPS、Bearer 鉴权、流式 SSE、JSON object 能力作为默认预设；其他 Provider 需要在能力矩阵基础上继续扩展设置项。
- 云同步、在线分享链接、EPUB、社区、TTS、AI 配图和完整 LLM 质检属于后续版本。

## 构建环境

- JDK 17
- Android SDK Platform 35
- Android Build Tools 34 或更高
- Gradle Wrapper 8.9

首次导入时在本机安装 Android SDK，并让 `local.properties` 指向 SDK。`local.properties` 已加入 `.gitignore`，不会作为项目配置提交。

Windows 下 Android Gradle Plugin 对中文路径较敏感，工程已设置 `android.overridePathCheck=true`。如果 forked 的 JUnit 进程仍出现路径乱码，建议用 Android Studio 打开项目，或从 ASCII 路径映射/Junction 运行 Gradle；源码不需要复制。

## 常用命令

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:compileDebugAndroidTestKotlin
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
```

没有可连接的模拟器时，`connectedDebugAndroidTest` 不能报告设备测试通过；应把它记录为环境阻塞而不是通过。本地单元测试和 AndroidTest 源码编译不依赖真实模型 API Key。

## API Key 与数据边界

API Key 只在配置页或请求执行期间存在于内存，并以 Android Keystore 保护的 AES-GCM 密文保存；备份规则排除数据库、文件和设置。不要把 Key、prompt、正文或完整响应写入 Logcat、崩溃上报或 issue。生成时，用户主动提交的问答、角色、上下文和正文会发送给其配置的 LLM 服务商，数据留存和训练政策以该服务商为准。

## 当前已知边界

- 生成结果要求模型返回大纲数组或 `{"summary":"...","content":"..."}` 章节对象；结构校验失败会把任务置为“需要处理”，不会静默写入正文。
- 断点恢复保留任务已收到的部分正文。对结构化 JSON 任务重试时会从同一 prompt 重新生成，避免把两个不完整 JSON 拼接成非法结果；后续可增加真正的 token 级续写。
- 运行时仍是一套 OpenAI-compatible 传输。模型设置可以保存多套接口预设：点按拉回编辑，长按删除；同一服务商的不同接口会标成「名称（2）」。API Key 随预设加密保存。
- 设备测试需要 API 35 模拟器或真机；没有设备时只验证单元测试、AndroidTest 编译、lint 和 APK 打包。

## 工程结构

```text
app/src/main/java/com/novelforge/app/
├── presentation/       Compose UI、导航、ViewModel
├── domain/              领域模型、Repository、用例
├── data/                Room、DataStore、Keystore、Repository 实现
└── infrastructure/     LLM、WorkManager、TXT 导出
```

需求与实现笔记在 `docs/requirements/` 和 `docs/superpowers/plans/`。记忆选择的对照数据由 `ContinuityBenchmark` 生成，不依赖本机绝对路径。
