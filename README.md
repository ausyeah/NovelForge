# NovelForge

本地优先的 Android 长篇写作应用。你带自己的模型 Key。程序负责记住设定、恢复任务和限制上下文，模型只负责写。

写下一章之前，会从连续性状态里挑出预算内的角色、已确认事实和未解伏笔。未确认的笔记不会进 prompt。结构不合法的输出不会写入正文。生成任务交给 WorkManager，页面关掉后仍可恢复。

API Key 只以 Android Keystore 保护的 AES-GCM 密文留在这台手机上。

## 这个项目在解决什么

长篇生成失败，通常不是因为单章写不出来，而是写到后面把开头的设定挤出了上下文，或者把模型自己抽出的笔记当成了已经发生的事。

`MemorySelector` 用固定名额做对照，不调用模型。样本是 7 个角色、20 条已确认事实、1 条未确认笔记。本章要点名其中一个角色，以及他最早的那条旧设定。

| 策略 | 必须记住的设定 | 未确认笔记漏进 prompt |
|---|---|---|
| 不带记忆 | 0/3 | 0 |
| 只留最近 12 条 | 0/3 | 0 |
| 按本章捞回 | 3/3 | 0 |
| 全量塞入 | 3/3 | 1 |

同样名额下，必须记住的旧设定从 0/3 变成 3/3。全量塞入也能记住，但会把未确认笔记带进去，prompt 也更大。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.novelforge.app.infrastructure.llm.ContinuityBenchmarkTest
```

针对这本书提问时也一样：程序附上大纲和相关片段，不发送全文。

## 现在能做什么

- 配置一套或多套 OpenAI-compatible 接口。点按预设拉回编辑，长按删除。同一服务商的不同接口会标成「名称（2）」。
- 从题材、文笔和篇幅生成大纲，再逐章写正文。
- 维护本书记忆：角色、不能违反的规则、未解伏笔。章后抽出的笔记要确认后才进入下一章。
- 在大纲页提问。材料是各章标题、概要，以及问题命中的短摘录。
- 阅读、对照上一稿、导出 TXT、查看 Token 用量。
- 换一张壁纸。取景比例锁定为手机屏幕。

云同步、社区、TTS、AI 配图和让模型给自己打分，不在当前范围内。

## 工程

Android 原生，Kotlin，Jetpack Compose，`minSdk 26`。本地数据在 Room。后台生成用 WorkManager。大纲、修订和项目版本指针在同一个事务里提交。

```text
app/src/main/java/com/novelforge/app/
├── agent/           提问时如何挑选大纲和相关片段
├── presentation/    Compose UI、导航、ViewModel
├── domain/          领域模型、Repository、用例
├── data/            Room、DataStore、Keystore
└── infrastructure/  LLM、WorkManager、导出
```

## 构建

- JDK 17
- Android SDK Platform 35
- Gradle Wrapper 8.9

把本机 SDK 路径写进 `local.properties`。这个文件已忽略，不会提交。

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

没有设备时，不要把 `connectedDebugAndroidTest` 记成通过。单元测试不需要真实的 API Key。

Windows 上如果工程路径含中文，已设置 `android.overridePathCheck=true`。JUnit 子进程仍出现路径乱码时，用 Android Studio 打开，或从 ASCII 路径的目录联接运行 Gradle。

## 数据边界

Key、prompt 和正文不要写入 Logcat 或崩溃上报。备份不包含数据库和 Key。用户主动提交的内容和设定会发给他自己配置的模型服务，留存政策以该服务商为准。

## 已知边界

- 大纲和章节必须返回约定的 JSON。校验失败时任务停在「需要处理」，不会静默写入。
- 结构化生成中断后，用原来的 prompt 重新生成，避免把两段残缺 JSON 拼在一起。
- 提问的回答保存在本地。这次提问本身不是 WorkManager 任务，进程死在请求中途时需要再问一次。
- 传输层目前是 OpenAI-compatible 的 HTTPS 和 Bearer 鉴权。
