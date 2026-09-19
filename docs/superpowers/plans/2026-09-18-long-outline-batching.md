# Long Outline Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make long-outline generation reliable by requesting exactly one outline chapter per upstream request, merging validated items locally, and resuming from saved progress after a retry or app restart.

**Architecture:** Keep one durable `GenerationJob`, but make the Worker execute one bounded outline-item request at a time. Each request is streamed through the existing coordinator, parsed independently, normalized to one absolute chapter ID/order, and merged into a valid `{"chapters": [...]}` checkpoint. The existing chapter-generation path remains unchanged, and an interrupted outline resumes from the first missing chapter.

**Tech Stack:** Kotlin, Kotlin Coroutines/Flow, Room, WorkManager, kotlinx.serialization, OkHttp, JUnit.

**Spec:** `C:/Users/26315/.fintwind/projects/2026-09-18/new-chat-3/novel-app-技术方案.md`

## Global Constraints

- Never log, display, or return the API key, full prompt, or full model response.
- Preserve the existing durable `GenerationJob` and WorkManager retry behavior.
- Each upstream outline request must produce exactly one bounded, parseable JSON object before it contributes a chapter to the merged outline.
- Existing short-outline and chapter-generation behavior must remain compatible.

### Task 1: Lock down the one-chapter outline request contract

**Files:**
- Modify: `app/src/main/java/com/novelforge/app/domain/usecase/GenerateOutlineUseCase.kt`
- Test: `app/src/test/java/com/novelforge/app/domain/usecase/GenerationUseCaseTest.kt`

**Interfaces:**
- Produces `buildChapterRequest(project, connection, outputTokenBudget, requestId, chapterNumber, previousOutline): ChatRequest`.
- The prompt requires exactly one outline item for the requested chapter and forbids generating other chapters or正文.

- [ ] Add a test that requests chapter 41 and asserts the user message contains exactly one-chapter wording, the chapter number, and a prohibition on generating other chapters or正文.
- [ ] Run the focused use-case test and verify the new assertion fails before implementation.
- [ ] Implement the public batch-request builder while leaving the existing initial prompt snapshot behavior intact.
- [ ] Run the focused use-case tests and verify the contract passes.

### Task 2: Execute and merge one-chapter outline requests with durable checkpoints

**Files:**
- Modify: `app/src/main/java/com/novelforge/app/infrastructure/jobs/GenerationRuntime.kt`
- Modify: `app/src/main/java/com/novelforge/app/infrastructure/jobs/GenerationCoordinator.kt` only if the batch lifecycle needs a narrowly scoped status helper.
- Test: `app/src/test/java/com/novelforge/app/infrastructure/jobs/GenerationRecoveryTest.kt`

**Interfaces:**
- `GenerationRuntime.execute` calls `executeOutlineChapters` for `OUTLINE` jobs.
- `executeOutlineChapters` reads already merged chapters from `partialContent`, requests the next chapter only, validates it, rewrites ID/order to the absolute position, and saves the merged JSON while status remains `RUNNING`.

- [ ] Add a fake-client test for three chapters and assert three upstream requests each contain one requested chapter and the final job contains three chapters.
- [ ] Add a retry/resume test where the first chapter is already in `partialContent`, the next execution requests only chapter 2, and no duplicate chapters are created.
- [ ] Run the focused recovery tests and verify they fail before the batching implementation.
- [ ] Implement one-chapter requests, a merged JSON checkpoint after every successful request, and `NEEDS_USER` handling for a malformed item.
- [ ] Preserve the merged checkpoint when a later HTTP/Socket error causes WorkManager retry; the next attempt must resume from the saved chapter count.
- [ ] Run the focused recovery tests and verify both batch and resume behavior pass.

### Task 3: Make validation and status messages actionable

**Files:**
- Modify: `app/src/main/java/com/novelforge/app/infrastructure/llm/JsonResponseValidator.kt` only where needed to accept the exact batch envelope without weakening non-empty title/summary checks.
- Modify: `app/src/main/java/com/novelforge/app/presentation/outline/OutlineScreen.kt`
- Modify: `app/src/main/java/com/novelforge/app/presentation/common/GenerationStatusCard.kt`
- Test: `app/src/test/java/com/novelforge/app/infrastructure/llm/JsonResponseValidatorTest.kt`

**Interfaces:**
- A valid one-chapter response returns a non-empty `chapters` array containing exactly one item.
- The UI reports batch progress using saved chapter count/character count and explains that the model is thinking without exposing reasoning text.

- [ ] Add validator coverage for a one-chapter envelope with compact fields and for malformed/truncated JSON with an actionable reason.
- [ ] Add the outline screen text for “已生成 X/N 章，正在生成下一章” when merged content is available.
- [ ] Keep the existing retry action visible for malformed output and retain the raw checkpoint locally.
- [ ] Run validator and UI-adjacent unit tests.

### Task 4: Full verification and device installation

**Files:**
- No production files beyond Tasks 1–3.

- [ ] Run `testDebugUnitTest`.
- [ ] Run `compileDebugAndroidTestKotlin`.
- [ ] Run `lintDebug`.
- [ ] Run `assembleDebug`.
- [ ] Install `app/build/outputs/apk/debug/app-debug.apk` with `adb install -r` without clearing app data.
- [ ] Confirm the installed package and check logcat for crashes without printing secrets or model content.
