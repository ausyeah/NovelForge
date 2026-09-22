# NovelForge 现存问题清单（代码审查）

> 审查范围：`app/src/main/java/com/novelforge/app` 全部源码 + 清单 + 构建配置。
> 生成日期：2026-09-20。每条均标注**精确文件与行号**，按严重程度分级。

---

## 🔴 P0 严重（会造成数据错误 / 功能错乱）

### 1. 导出 TXT 章节编号错误，引子被导出成「第 1 章」
- **位置**：`app/src/main/java/com/novelforge/app/infrastructure/export/TxtExporter.kt:37`
  ```kotlin
  writer.appendLine("第${chapter.orderIndex + 1}章 ${chapter.title}")
  ```
- **问题**：全 App 约定「第 1 个推进段是引子」（`presentation/common/ChapterLabels.kt:3-6`，明确写着"禁止各处再自行 orderIndex+1"），导出却自己 `orderIndex + 1`：
  - 引子（orderIndex=0）被导出为「第1章」，全书编号整体错位 1；
  - `title` 未过 `cleanChapterTitle()`，模型自带的「第 X 章」前缀会与拼出来的编号重复（如「第 2 章 第 2 章 xxx」）。
- **修法**：改用 `chapterLabel(orderIndex)` + `cleanChapterTitle(title)` 拼接，与 `ChapterScreen`/`LibraryScreen` 展示一致。

### 2. 备份导入不是原子操作，失败会留下「半本废书」
- **位置**：`app/src/main/java/com/novelforge/app/infrastructure/backup/BackupStore.kt:69-86`
- **问题**：`saveProject` → 循环 `outlineRepository.save` → 循环 `chapterRepository.save` 分散执行、无事务包裹。中途崩溃/序列化异常（超大备份 OOM、磁盘满）会残留「项目有了、大纲半本、正文半本」的数据；用户只看到"导入失败"却不知已污染本地库。
- **对比**：`data/repository/RoomGenerationArtifactRepository.kt` 所有写路径都包了 `database.withTransaction`，此处是漏网之鱼。
- **修法**：给 `BackupStore` 注入 `AppDatabase`，用 `withTransaction` 包裹导入写库。

### 3. 「导出备份」用书名匹配项目，重名/改名导出错书
- **位置**：`app/src/main/java/com/novelforge/app/presentation/exports/ExportsScreen.kt:148-151, 230-234`
  ```kotlin
  pendingBackupTitle = project.title          // :231 存的是书名
  ...
  val target = projects.firstOrNull { it.title == title }  // :150 按书名找
  ```
- **问题**：两本书同名 → 导出另一本；用户在对话框里改名后 → `target == null` 静默不导出。
- **对比**：`presentation/library/LibraryScreen.kt:330` 存的是 `backupTarget = target`（对象引用），是对的。两处入口实现不一致。
- **修法**：存 `project.id`，用 `firstOrNull { it.id == id }` 找回。

### 4. `parseOutline` 无条件重编 orderIndex，与生成主链路注释直接矛盾 → 删章后撞号串章
- **位置**：`app/src/main/java/com/novelforge/app/infrastructure/llm/JsonResponseValidator.kt:88-100`
  ```kotlin
  val normalizedItems = items.mapIndexed { index, item ->
      var candidateId = item.id.ifBlank { "outline-item-${index + 1}" }
      if (!usedIds.add(candidateId)) { ... }
      item.copy(id = candidateId, orderIndex = index)   // ← 强制压平成 0..N-1
  }
  ```
- **矛盾点**：`infrastructure/jobs/GenerationRuntime.kt:801-803` 的注释明确写：
  > 「不按位置重编 orderIndex/id：检查点里存的就是权威序号，重编会抹平删章留洞，让新批次 "chapter-N" 与既有章节撞号串章」
  但 `readOutlineProgress()`（GenerationRuntime.kt:783）调用的正是这个会重编的 `parseOutline`。注释与实现打架。
- **后果链**：书架删掉中间一章（保留洞 0,1,2,**4**,5…）→ 继续生成下一批时 checkpoint 被压平成 0..N → `nextOrderIndex = N`，新章节 id 按 `"chapter-${start+index}"` 生成 → 与既有章节 id（chapter-1、chapter-2…）**撞号** → `outline.chapters.firstOrNull { it.id == itemId }` 取到错误章节，正文串章；`persistOutline`（GenerationRuntime.kt:1068）同样 `mapIndexed` 压平，最终存库版本与 checkpoint 的洞信息互相矛盾。
- **修法**：为 `parseOutline` 增加"保留原始 orderIndex/id"的解析路径（或加参数 `renumber: Boolean = true`），`readOutlineProgress`/`finishOutlineExecution` 走保留模式；`persistOutline` 停止无条件重编；补「删章后续写」回归测试。

---

## 🟠 P1 中等（功能不健全 / 数据统计失真）

### 5. 灵感助手调用被账本归入「（已删除的项目）」
- **位置**：`app/src/main/java/com/novelforge/app/presentation/chat/ChatScreen.kt:330`（`projectId = ""`）；`data/local/Daos.kt:194`（`COALESCE(p.title, '（已删除的项目）')`）
- **问题**：chat 的 LLM 调用 `projectId` 为空串，LEFT JOIN 匹配不到项目 → 账本「项目分布」卡片显示一行「（已删除的项目）」并吞掉全部对话用量，用户会误以为有书被删。

### 6. 账本「使用日志」分页总数是假的（LIMIT 300 截断）
- **位置**：`data/local/Daos.kt:212`（`observeUsageLog` 带 `LIMIT 300`）；`presentation/ledger/LedgerScreen.kt:216`（`共 ${usageLog.size} 条`）
- **问题**：超过 300 条后总数恒为 300、翻到第 16 页之后是空页。至少应标注「仅显示最近 300 条」。

### 7. 账本三张卡片统计口径不一致
- **位置**：`data/local/Daos.kt:188-189, 197-198, 202-207`
- **问题**：`observeModelSummariesSince` / `observeProjectSummaries` 都有 `HAVING (inputTokens+outputTokens) > 0`，但 `observePurposeSummariesSince` **没有**。于是「总 Tokens」（来自 models）是过滤后的，而「调用次数/成功率」（来自 purposes）是全量的，同一屏对不上。

### 8. `costConfirmationEnabled` 设置项形同虚设
- **位置**：`data/settings/AppSettingsStore.kt:19, 38, 52`
- **问题**：字段有存储、有读写，但全工程无任何消费方（未用于任何调用前确认）。`docs/requirements/mvp-plus-features.md:59` 也自认「形同虚设」。

### 9. 对话历史无限增长，无上限无清理
- **位置**：`data/chat/ChatHistoryStore.kt:38-50`
- **问题**：`conversations` 全部塞进单个 DataStore key，无条数/体积上限。长时间使用文件无限膨胀，且每次 `save` 全量重写整个 JSON。

### 10. `ChapterViewModel.generate` 读取可能未就绪的数据
- **位置**：`presentation/chapter/ChapterViewModel.kt:77`
- **问题**：`outlines.value.firstOrNull()` 的 `outlines` 是 `stateIn(..., emptyList())` 冷启动；进页面立刻点「生成」时 `previousSummary`/`previousTail` 静默丢失，上一章衔接信息为空。

---

## 🟡 P2 轻微（体验 / 隐患 / 死代码）

| # | 位置 | 问题 |
|---|------|------|
| 11 | `presentation/ledger/LedgerScreen.kt:303-307`、`presentation/exports/ExportsScreen.kt:329-330` | `"%.1f万".format(...)` 未指定 Locale，德语等区域会显示 `1,5万`、`1,5 MB` |
| 12 | `data/local/Daos.kt:183` | `observeRecentCalls`（LIMIT 40）定义后无人使用，死代码 |
| 13 | `infrastructure/jobs/GenerationRuntime.kt:1216-1225` | `enqueueNextOutlineWork` 定义后从未被调用（旧批量轮转逻辑残留） |
| 14 | `data/local/Entities.kt:39, 122`、`data/local/Daos.kt:46-52, 259-271` | `character_snapshots`、`quality_runs` 两张表只有定义/删除，**没有任何写入方**，属于未接线的功能骨架 |
| 15 | `presentation/ledger/LedgerScreen.kt:93-94` | `totalCalls`/`totalSuccess` 计算后未使用（实际显示用 `purposes` 汇总） |
| 16 | `presentation/chat/ChatScreen.kt:267` | 流式空响应（`!received`）仍记 `success=true`，账本成功率被污染 |
| 17 | `app/src/main/AndroidManifest.xml:18` | `usesCleartextTraffic="false"`，但 `OpenAiCompatibleClient.kt:386` / `GenerationRuntime.kt:515` 允许 localhost HTTP——本地调试走 HTTP 会被系统直接掐断，功能与配置自相矛盾 |
| 18 | `app/src/main/res/xml/backup_rules.xml`、`data/security/KeystoreApiKeyStore.kt` | 需核对 `allowBackup="true"` 下 Keystore 密钥是否随迁移失效（设备级备份迁移后读 key 抛 `SecureStorageException`，用户需重输 Key）——属于待真机验证项 |

---

## 🟢 建议（工程整洁）

- **位置**：仓库根目录
  - `build_log.txt`、`ui_dump.xml`、`dialog_shot.png`、`ledger_shot.png` 等调试产物是 untracked，应加入 `.gitignore`，避免误提交。
- **位置**：`app/src/test/` 覆盖度：`GenerationRecoveryTest`、`EntityMappingTest` 等已存在，但**没有**针对「删章留洞续写」「备份导入回滚」「导出编号」的测试，上述 P0/P1 均无回归护栏。

---

## 建议修复顺序

1. **P0-1 导出编号**（几行改动，收益明确）
2. **P0-2 备份事务**（几行改动，需注入 database）
3. **P0-3 导出按 id 匹配**（几行改动，抄 LibraryScreen 即可）
4. **P0-4 parseOutline 保留 orderIndex**（动生成主链路，需补回归测试，单独一轮）
5. P1 各项按使用频率排：5 → 6 → 7 → 10 → 8/9

> 每一处修复后请运行 `./gradlew testDebugUnitTest` 确认无回归。
