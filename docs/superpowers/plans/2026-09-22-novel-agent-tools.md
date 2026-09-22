# Novel Agent Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 NovelForge 加一组只读写「这一本书」的工具，调用结果用测试锁死，先不接界面、不跑循环、不开放网络。

**Architecture:** `NovelToolRegistry` 只依赖窄接口 `NovelBookStore`。六个工具的参数校验、摘录裁剪、待确认记忆和「不许删正文」都在注册表里完成。Room 与 `GenerationRuntime.queueChapter` 只出现在适配器里，单测用内存假库。

**Tech Stack:** Kotlin、JUnit4、kotlinx.serialization Json、现有 `Project` / `OutlineVersion` / `ChapterRevision` / `ContinuityState`。不新增 Gradle 依赖。

**Spec:** 本文件就是这一阶段的规格。后续阶段不在本计划内：六步 agent 循环与 WorkManager 断点、轨迹页、本机 MCP。

## Global Constraints

- 本计划不改数据库 schema，不新增 Room 表。
- 工具返回的正文摘录单条不超过 120 个字符，一次最多 5 条。
- `get_story_bible` 必须把 `pendingFacts` 标成待确认，不得放进已确认事实。
- `propose_fact` 只追加 `pendingFacts`，不得写入 `factsWithSources` 或 `unresolvedThreads`。
- `patch_outline` 只产生新的大纲版本，不得调用删除正文的接口。
- `queue_chapter` 在大纲里找不到目标章节时不得调用排队函数。
- 未知工具名、缺字段、空查询返回 `ToolResult.Fail`，不抛给调用方。
- 单测命令：`.\gradlew.bat :app:testDebugUnitTest --tests com.novelforge.app.agent.NovelToolRegistryTest --offline`

---

## File structure

- Create: `app/src/main/java/com/novelforge/app/agent/NovelBookStore.kt` — 工具能看见的书，只有读项目、读大纲、读修订、保存项目、保存大纲、排队一章。
- Create: `app/src/main/java/com/novelforge/app/agent/NovelToolRegistry.kt` — 工具规格、`call`、六个实现。
- Create: `app/src/main/java/com/novelforge/app/agent/RoomNovelBookStore.kt` — 把现有 repository 和 `queueChapter` 接到上面的接口。
- Create: `app/src/test/java/com/novelforge/app/agent/NovelToolRegistryTest.kt` — 内存假库上的行为测试。
- Modify: `app/src/main/java/com/novelforge/app/NovelForgeApplication.kt` — 提供 `novelToolRegistry`，本阶段没有界面调用它。

不修改 `GenerationRuntime` 的生成逻辑。排队仍走它现有的 `queueChapter`。

## Interfaces

后续任务都用这些名字。

```kotlin
interface NovelBookStore {
    suspend fun project(projectId: String): Project?
    suspend fun saveProject(project: Project)
    suspend fun latestOutline(projectId: String): OutlineVersion?
    suspend fun saveOutline(version: OutlineVersion, project: Project)
    suspend fun revisions(projectId: String): List<ChapterRevision>
    suspend fun queueChapter(projectId: String, outlineItemId: String): String
}

data class ToolSpec(val name: String, val description: String, val argumentsHint: String)

sealed interface ToolResult {
    data class Ok(val content: String) : ToolResult
    data class Fail(val reason: String) : ToolResult
}

class NovelToolRegistry(
    private val store: NovelBookStore,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() }
) {
    fun specs(): List<ToolSpec>
    suspend fun call(projectId: String, name: String, arguments: kotlinx.serialization.json.JsonObject): ToolResult
}
```

六个名字固定为：`search_chapters`、`read_chapter`、`get_story_bible`、`propose_fact`、`patch_outline`、`queue_chapter`。

---

### Task 1: 注册表先拒绝坏调用

**Files:**
- Create: `app/src/test/java/com/novelforge/app/agent/NovelToolRegistryTest.kt`
- Create: `app/src/main/java/com/novelforge/app/agent/NovelBookStore.kt`
- Create: `app/src/main/java/com/novelforge/app/agent/NovelToolRegistry.kt`
- Test: `app/src/test/java/com/novelforge/app/agent/NovelToolRegistryTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `NovelBookStore`、`ToolSpec`、`ToolResult`、`NovelToolRegistry.call`、`NovelToolRegistry.specs`

- [ ] **Step 1: Write the failing test**

把下面的测试和假库放进 `NovelToolRegistryTest.kt`。假库先只满足编译，后面的任务往里加字段。

```kotlin
package com.novelforge.app.agent

import com.novelforge.app.domain.model.ChapterRevision
import com.novelforge.app.domain.model.OutlineVersion
import com.novelforge.app.domain.model.Project
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeBook : NovelBookStore {
    var queued: String? = null
    override suspend fun project(projectId: String): Project? = null
    override suspend fun saveProject(project: Project) = Unit
    override suspend fun latestOutline(projectId: String): OutlineVersion? = null
    override suspend fun saveOutline(version: OutlineVersion, project: Project) = Unit
    override suspend fun revisions(projectId: String): List<ChapterRevision> = emptyList()
    override suspend fun queueChapter(projectId: String, outlineItemId: String): String {
        queued = outlineItemId
        return "job-1"
    }
}

class NovelToolRegistryTest {
    @Test
    fun unknownToolAndBlankSearchDoNotThrow() = runBlocking {
        val book = FakeBook()
        val registry = NovelToolRegistry(book)
        val names = registry.specs().map { it.name }
        assertEquals(
            listOf(
                "search_chapters", "read_chapter", "get_story_bible",
                "propose_fact", "patch_outline", "queue_chapter"
            ),
            names
        )
        val unknown = registry.call("p", "delete_book", JsonObject(emptyMap()))
        assertTrue(unknown is ToolResult.Fail)
        val blank = registry.call("p", "search_chapters", buildJsonObject { put("query", JsonPrimitive("  ")) })
        assertTrue(blank is ToolResult.Fail)
        assertEquals(null, book.queued)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.novelforge.app.agent.NovelToolRegistryTest --offline`

Expected: 编译失败，`Unresolved reference: NovelToolRegistry` 或 `NovelBookStore`。

- [ ] **Step 3: Write minimal implementation**

`NovelBookStore.kt` 写本计划 Interfaces 里的接口。`NovelToolRegistry.kt` 写 `specs()` 返回六个 `ToolSpec`，`call` 对未知名字返回 `Fail("未知工具")`。`search_chapters` 读取 `arguments["query"]` 的字符串，空白则 `Fail("查询不能为空")`，其他名字先 `Fail("尚未实现")`。

字符串读取：

```kotlin
private fun JsonObject.text(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.novelforge.app.agent.NovelToolRegistryTest.unknownToolAndBlankSearchDoNotThrow --offline`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/novelforge/app/agent app/src/test/java/com/novelforge/app/agent
git commit -m "feat: reject unknown novel agent tools"
```

---

### Task 2: 搜索和阅读只返回摘录

**Files:**
- Modify: `app/src/test/java/com/novelforge/app/agent/NovelToolRegistryTest.kt`
- Modify: `app/src/main/java/com/novelforge/app/agent/NovelToolRegistry.kt`
- Test: `app/src/test/java/com/novelforge/app/agent/NovelToolRegistryTest.kt`

**Interfaces:**
- Consumes: `NovelToolRegistry.call`、`NovelBookStore.revisions`、`NovelBookStore.latestOutline`
- Produces: `search_chapters` 与 `read_chapter` 的成功文本格式。搜索行以 `chapterId|标题|摘录` 分行。阅读第一行是摘要，之后是结尾，结尾最长 400 字符。

- [ ] **Step 1: Write the failing test**

扩展 `FakeBook`，让它能返回一份大纲和两份修订。新增测试：

```kotlin
@Test
fun searchReturnsAtMostFiveShortExcerpts() = runBlocking {
    val book = bookWithChapters()
    val registry = NovelToolRegistry(book)
    val result = registry.call("p", "search_chapters", buildJsonObject { put("query", JsonPrimitive("玉佩")) })
    val ok = result as ToolResult.Ok
    val lines = ok.content.lines().filter { it.isNotBlank() }
    assertTrue(lines.size <= 5)
    assertTrue(lines.all { it.contains("玉佩") })
    assertTrue(lines.all { it.substringAfterLast('|').length <= 120 })
    assertTrue(lines.none { it.contains("这段不应该整章出现在搜索结果里") })
}

@Test
fun readChapterReturnsSummaryAndTailOnly() = runBlocking {
    val registry = NovelToolRegistry(bookWithChapters())
    val result = registry.call("p", "read_chapter", buildJsonObject { put("chapterId", JsonPrimitive("c1")) })
    val ok = result as ToolResult.Ok
    assertTrue(ok.content.startsWith("摘要：阿禾弄丢了玉佩"))
    assertTrue(ok.content.contains("玉佩"))
    assertTrue(ok.content.length < 500)
    val missing = registry.call("p", "read_chapter", buildJsonObject { put("chapterId", JsonPrimitive("nope")) })
    assertTrue(missing is ToolResult.Fail)
}
```

`bookWithChapters()` 放在测试文件里：项目 id `p`，大纲两项 `c1` 标题「丢失」、`c2` 标题「无关」。`c1` 正文是 `"开头。" + "甲".repeat(800) + "玉佩在井边。" + "这段不应该整章出现在搜索结果里"`，摘要 `"阿禾弄丢了玉佩"`。`c2` 正文不含「玉佩」。`Project` 用 `createdAt = 1`、`updatedAt = 1`，其余用默认值。`OutlineVersion` 的 `version = 1`、`createdAt = 1`。修订 `revision = 1`、`status` 默认即可。

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.novelforge.app.agent.NovelToolRegistryTest --offline`

Expected: FAIL。`search_chapters` 仍返回 `尚未实现`，强转 `ToolResult.Ok` 失败。

- [ ] **Step 3: Write minimal implementation**

`search_chapters`：用 `latestOutline` 拿章节顺序和标题，用 `revisions` 按 `outlineItemId` 取 `revision` 最大的一条。在标题、摘要、正文里找 `query`。摘录取命中点前 20 字加后 40 字，总长再截到 120。最多 5 条，顺序跟大纲 `orderIndex`。没有命中时 `Ok("没有找到")`。

`read_chapter`：参数 `chapterId`。找不到大纲项或没有修订时 `Fail("章节不存在")`。有修订则：

```kotlin
val tail = revision.content.takeLast(400)
ToolResult.Ok("摘要：${revision.summary.orEmpty()}\n结尾：$tail")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.novelforge.app.agent.NovelToolRegistryTest --offline`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/novelforge/app/agent app/src/test/java/com/novelforge/app/agent
git commit -m "feat: search and read chapters as short excerpts"
```

---

### Task 3: 故事圣经分清已确认和待确认

**Files:**
- Modify: `app/src/test/java/com/novelforge/app/agent/NovelToolRegistryTest.kt`
- Modify: `app/src/main/java/com/novelforge/app/agent/NovelToolRegistry.kt`
- Test: `app/src/test/java/com/novelforge/app/agent/NovelToolRegistryTest.kt`

**Interfaces:**
- Consumes: `NovelBookStore.project`
- Produces: `get_story_bible` 文本含四段标题：`规则`、`角色`、`未解伏笔`、`已确认事实`、`待确认`。

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun storyBibleDoesNotTreatPendingAsConfirmed() = runBlocking {
    val book = bookWithChapters()
    book.project = book.project!!.copy(
        continuityState = book.project!!.continuityState.copy(
            worldRules = listOf("不能复活"),
            unresolvedThreads = listOf("玉佩"),
            characters = listOf(
                com.novelforge.app.domain.model.CharacterProfile(
                    id = "a", projectId = "p", name = "阿禾", appearance = "左眼有疤"
                )
            ),
            factsWithSources = listOf(
                com.novelforge.app.domain.model.ContinuityFact(
                    "f1", "阿禾丢了玉佩", "c1", confirmed = true, updatedAt = 1
                )
            ),
            pendingFacts = listOf(
                com.novelforge.app.domain.model.ContinuityFact(
                    "p1", "还没确认的事", "c1", confirmed = false, updatedAt = 2
                )
            )
        )
    )
    val ok = NovelToolRegistry(book).call("p", "get_story_bible", JsonObject(emptyMap())) as ToolResult.Ok
    val confirmed = ok.content.substringAfter("已确认事实").substringBefore("待确认")
    assertTrue(ok.content.contains("不能复活"))
    assertTrue(ok.content.contains("阿禾"))
    assertTrue(confirmed.contains("阿禾丢了玉佩"))
    assertTrue(!confirmed.contains("还没确认的事"))
    assertTrue(ok.content.substringAfter("待确认").contains("还没确认的事"))
}
```

`FakeBook` 增加 `var project: Project?`，`project()` 返回它。`bookWithChapters()` 设置一个非空项目。

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.novelforge.app.agent.NovelToolRegistryTest.storyBibleDoesNotTreatPendingAsConfirmed --offline`

Expected: FAIL，`get_story_bible` 仍是 `尚未实现`。

- [ ] **Step 3: Write minimal implementation**

项目不存在返回 `Fail("项目不存在")`。否则按段拼接，角色一行写 `名字：外貌`。已确认段只用 `factsWithSources.filter { it.confirmed }`。待确认段只用 `pendingFacts`，空则写 `无`。

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.novelforge.app.agent.NovelToolRegistryTest --offline`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/novelforge/app/agent app/src/test/java/com/novelforge/app/agent
git commit -m "feat: expose story bible without promoting pending facts"
```

---

### Task 4: 提议事实只进待确认

**Files:**
- Modify: `app/src/test/java/com/novelforge/app/agent/NovelToolRegistryTest.kt`
- Modify: `app/src/main/java/com/novelforge/app/agent/NovelToolRegistry.kt`
- Test: `app/src/test/java/com/novelforge/app/agent/NovelToolRegistryTest.kt`

**Interfaces:**
- Consumes: `NovelBookStore.saveProject`、`ContinuityFact.kind`
- Produces: `propose_fact` 参数 `statement`、`kind`（只接受 `fact`、`thread`、`resolved`）。成功后 `pendingFacts` 多一条，`confirmed = false`，`factsWithSources` 与 `unresolvedThreads` 不变。

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun proposeFactStaysPending() = runBlocking {
    val book = bookWithChapters()
    val before = book.project!!
    val registry = NovelToolRegistry(book, now = { 9L }, newId = { "new-fact" })
    val ok = registry.call(
        "p",
        "propose_fact",
        buildJsonObject {
            put("statement", JsonPrimitive("阿禾左眼有疤"))
            put("kind", JsonPrimitive("thread"))
        }
    )
    assertTrue(ok is ToolResult.Ok)
    val saved = book.savedProject!!
    assertEquals(1, saved.continuityState.pendingFacts.size)
    val fact = saved.continuityState.pendingFacts.single()
    assertEquals("new-fact", fact.id)
    assertEquals("thread", fact.kind)
    assertEquals(false, fact.confirmed)
    assertEquals(before.continuityState.factsWithSources, saved.continuityState.factsWithSources)
    assertEquals(before.continuityState.unresolvedThreads, saved.continuityState.unresolvedThreads)
    val bad = registry.call("p", "propose_fact", buildJsonObject { put("statement", JsonPrimitive("x")) })
    assertTrue(bad is ToolResult.Fail)
}
```

`FakeBook.saveProject` 把参数记到 `var savedProject: Project?`。

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.novelforge.app.agent.NovelToolRegistryTest.proposeFactStaysPending --offline`

Expected: FAIL，`propose_fact` 尚未实现，或 `savedProject` 仍是 null。

- [ ] **Step 3: Write minimal implementation**

`statement` 去掉空白后长度必须在 2 到 80。`kind` 缺省是 `fact`，不在三个值里则 `Fail("kind 不合法")`。追加到 `pendingFacts` 后 `takeLast(40)`，再 `saveProject`。成功文本：`已放入待确认`。

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.novelforge.app.agent.NovelToolRegistryTest --offline`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/novelforge/app/agent app/src/test/java/com/novelforge/app/agent
git commit -m "feat: propose story facts only as pending"
```

---

### Task 5: 改大纲不碰正文

**Files:**
- Modify: `app/src/test/java/com/novelforge/app/agent/NovelToolRegistryTest.kt`
- Modify: `app/src/main/java/com/novelforge/app/agent/NovelToolRegistry.kt`
- Test: `app/src/test/java/com/novelforge/app/agent/NovelToolRegistryTest.kt`

**Interfaces:**
- Consumes: `NovelBookStore.saveOutline`、`OutlineVersion`
- Produces: `patch_outline` 参数 `chapterId`、`title`、`summary`。`title` 可空，空则保留原标题。`summary` 空白则失败。新版本 `version` 为旧版本 + 1，`id` 用 `newId()`。章节 id 与 `orderIndex` 不变。

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun patchOutlineWritesNewVersionWithoutTouchingRevisions() = runBlocking {
    val book = bookWithChapters()
    val registry = NovelToolRegistry(book, now = { 20L }, newId = { "outline-2" })
    val ok = registry.call(
        "p",
        "patch_outline",
        buildJsonObject {
            put("chapterId", JsonPrimitive("c1"))
            put("summary", JsonPrimitive("阿禾决定下井"))
        }
    )
    assertTrue(ok is ToolResult.Ok)
    val saved = book.savedOutline!!
    assertEquals("outline-2", saved.id)
    assertEquals(2, saved.version)
    assertEquals("阿禾决定下井", saved.chapters.first { it.id == "c1" }.summary)
    assertEquals("丢失", saved.chapters.first { it.id == "c1" }.title)
    assertEquals(0, book.revisionWrites)
    val blank = registry.call(
        "p",
        "patch_outline",
        buildJsonObject {
            put("chapterId", JsonPrimitive("c1"))
            put("summary", JsonPrimitive("  "))
        }
    )
    assertTrue(blank is ToolResult.Fail)
}
```

`FakeBook` 增加 `var savedOutline: OutlineVersion?`、`var revisionWrites: Int = 0`。`saveOutline` 保存 version。这个假库没有删除修订的方法，测试只断言 `revisionWrites` 仍为 0，因此实现里不要给 `FakeBook` 增加写修订的路径。

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.novelforge.app.agent.NovelToolRegistryTest.patchOutlineWritesNewVersionWithoutTouchingRevisions --offline`

Expected: FAIL，`patch_outline` 尚未实现。

- [ ] **Step 3: Write minimal implementation**

读 `latestOutline` 和 `project`。找不到章节 `Fail("章节不存在")`。复制该 `OutlineItem` 的 `summary`，`title` 参数非空才替换。新 `OutlineVersion` 用新 id、`version + 1`、`diffSummary = "agent 修改大纲"`、`createdAt = now()`。`saveOutline` 时把 `project.activeOutlineVersionId` 设为新 id、`updatedAt = now()`。不要读取或保存 `ChapterRevision`。

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.novelforge.app.agent.NovelToolRegistryTest --offline`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/novelforge/app/agent app/src/test/java/com/novelforge/app/agent
git commit -m "feat: patch outline items without deleting prose"
```

---

### Task 6: 排队写章，以及接到现有运行时

**Files:**
- Modify: `app/src/test/java/com/novelforge/app/agent/NovelToolRegistryTest.kt`
- Modify: `app/src/main/java/com/novelforge/app/agent/NovelToolRegistry.kt`
- Create: `app/src/main/java/com/novelforge/app/agent/RoomNovelBookStore.kt`
- Modify: `app/src/main/java/com/novelforge/app/NovelForgeApplication.kt`
- Test: `app/src/test/java/com/novelforge/app/agent/NovelToolRegistryTest.kt`

**Interfaces:**
- Consumes: `GenerationRuntime.queueChapter(projectId, chapter, context, clientRequestId)`、`MemorySelector.select`、`ChapterContext`
- Produces: `queue_chapter` 参数 `chapterId`。成功文本包含返回的 job id。大纲没有该章时 `FakeBook.queued` 保持 null。`NovelForgeApplication.novelToolRegistry`。

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun queueChapterRefusesMissingOutlineItem() = runBlocking {
    val book = bookWithChapters()
    val registry = NovelToolRegistry(book)
    val missing = registry.call("p", "queue_chapter", buildJsonObject { put("chapterId", JsonPrimitive("nope")) })
    assertTrue(missing is ToolResult.Fail)
    assertEquals(null, book.queued)
    val ok = registry.call("p", "queue_chapter", buildJsonObject { put("chapterId", JsonPrimitive("c1")) }) as ToolResult.Ok
    assertEquals("c1", book.queued)
    assertTrue(ok.content.contains("job-1"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.novelforge.app.agent.NovelToolRegistryTest.queueChapterRefusesMissingOutlineItem --offline`

Expected: FAIL，`queue_chapter` 尚未实现。

- [ ] **Step 3: Write minimal implementation**

注册表里先查大纲。没有该 `chapterId` 就 `Fail("章节不存在")` 并且不要调用 `store.queueChapter`。有则调用，成功文本 `已排队 jobId`。

`RoomNovelBookStore` 实现 `NovelBookStore`：`project` / `saveProject` 走 `ProjectRepository`，`latestOutline` 走 `OutlineRepository.latest`，`revisions` 走 `ChapterRepository.allForProject`，`saveOutline` 走 `GenerationArtifactRepository.saveOutlineAndProject`。`queueChapter` 里自己读项目和大纲项，用 `MemorySelector.select(project.continuityState, inputBudget = project.creativeConfig?.inputBudget ?: 8_000)` 组装 `ChapterContext`，上一章结尾用 `chapterRepository.latest` 的 `content.takeLast(1_500)`，然后调用注入的：

```kotlin
private val enqueue: suspend (projectId: String, chapter: OutlineItem, context: ChapterContext) -> String
```

不要在注册表里引用 `GenerationRuntime`。

`NovelForgeApplication` 增加：

```kotlin
val novelToolRegistry by lazy {
    NovelToolRegistry(
        RoomNovelBookStore(
            projectRepository = projectRepository,
            outlineRepository = outlineRepository,
            chapterRepository = chapterRepository,
            artifacts = generationArtifactRepository,
            enqueue = { projectId, chapter, context ->
                generationRuntime.queueChapter(projectId, chapter, context).id
            }
        )
    )
}
```

本任务不添加入口按钮。

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.novelforge.app.agent.NovelToolRegistryTest --offline`

Expected: PASS。再跑 `.\gradlew.bat :app:compileDebugKotlin --offline`，确认适配器能编译。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/novelforge/app/agent app/src/main/java/com/novelforge/app/NovelForgeApplication.kt app/src/test/java/com/novelforge/app/agent
git commit -m "feat: queue a chapter through the novel tool registry"
```

---

## 本计划不做

- 不实现多步循环、步数上限和中断恢复。那是下一份计划，复用这里的 `call`。
- 不做轨迹页面。
- 不监听端口，不做 MCP。MCP 以后只包 `specs()` 和 `call`，不另写一套工具。

## Self-review

- 六个工具各有一个失败或成功测试：未知工具与空搜索、摘录上限、阅读裁剪、待确认隔离、提议不落正式记忆、改大纲不写修订、缺章不排队。
- `queue_chapter` 的真正生成仍走 `GenerationRuntime.queueChapter`，记忆裁剪仍走 `MemorySelector.select`，没有第二套 prompt。
- 没有要求新依赖，也没有 Room migration。
