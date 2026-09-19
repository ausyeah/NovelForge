# NovelForge MVP 测试矩阵

## 自动化测试

| 范围 | 命令/入口 | 覆盖内容 |
|---|---|---|
| 领域模型 | `:app:testDebugUnitTest --tests '*SerializationTest'` | 状态、稳定大纲 ID、质量问题定位和 JSON 往返 |
| Room 映射 | `:app:testDebugUnitTest --tests '*EntityMappingTest'` | 生成任务检查点和领域/实体转换 |
| Provider JSON | `:app:testDebugUnitTest --tests '*JsonResponseValidatorTest'` | 代码块、坏 JSON、重复 ID、缺失字段 |
| Provider 网络 | `:app:testDebugUnitTest --tests '*OpenAiCompatibleClientTest'` | usage、SSE、`[DONE]`、429 重试、鉴权 Header |
| 重试策略 | `:app:testDebugUnitTest --tests '*RetryPolicyTest'` | 可重试状态、指数退避和 `Retry-After` |
| 生成恢复 | `:app:testDebugUnitTest --tests '*GenerationRecoveryTest'` | 幂等任务、流式内容持久化、完成状态 |
| TXT 导出 | `:app:testDebugUnitTest --tests '*TxtExporterTest'` | 稳定顺序、中文正文和文件格式 |
| Compose smoke 源码 | `:app:compileDebugAndroidTestKotlin` | 首页入口和基础 Activity 启动测试可编译 |
| AndroidTest 编译 | `:app:compileDebugAndroidTestKotlin` | Room 初始 schema、Compose smoke test 的源码编译 |

## 设备测试

在 API 35 模拟器或真机上执行：

1. 启动 App，确认首页空项目状态和“新建项目”入口。
2. 创建含中文、空格和符号的项目标题，返回首页后确认项目仍存在。
3. 打开模型设置，保存 Base URL、模型名和 API Key；检查 Logcat 没有明文 Key。
4. 杀掉 App 后重启，确认项目列表和设置仍可读取。
5. 生成任务期间切后台、断网、恢复网络；确认状态显示为完成、可恢复部分或失败，不能静默丢失正文。
6. 从已完成项目导出 TXT，并通过 Android Sharesheet 分享 `content://` URI。

## 发布门槛

- `testDebugUnitTest` 退出码为 0，报告中无失败测试。
- `compileDebugAndroidTestKotlin` 退出码为 0。
- `assembleDebug` 退出码为 0，并生成 APK。
- `lintDebug` 的 error 数为 0；warning 必须逐项判断是否与 MVP 可接受范围相符。
- `connectedDebugAndroidTest` 只有在真实连接设备或模拟器上运行并退出码为 0 时，才可标记为通过。
- 发布前再次检查备份规则、日志脱敏、R8、签名、依赖漏洞和第三方 Provider 数据政策。
