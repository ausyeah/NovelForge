# Creative Setup Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent outline generation before the user completes the required creative choices, persist those choices on the project, and use them in the outline prompt.

**Architecture:** Add a project-scoped creative setup screen between project creation and the outline editor. The screen keeps temporary form state locally and persists a validated `CreativeConfig` plus the core questionnaire answers through `ProjectRepository`; navigation sends existing projects with no setup back to this screen. The domain use case remains the final guard, so a worker or direct caller cannot create an outline job without a completed setup.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Android ViewModel/coroutines, Room-backed repositories, kotlinx.serialization, JUnit.

**Spec:** `C:/Users/26315/.fintwind/projects/2026-09-18/new-chat-3/novel-app-技术方案.md` — sections 3.1, 4.2, 6.1, 7.1, and 9.1.

## Global Constraints

- Required first-layer answers are the creative premise, protagonist, and core conflict; the user cannot start outline generation while any is blank.
- Required quantitative selections are at least one genre tag, writing style, tone, thrill frequency, and chapter count; selected values are visibly marked.
- Persist `CreativeConfig` and `QuestData` on the existing `Project`; do not change the Room schema because these fields are already serialized columns.
- The outline prompt must use the saved chapter count and creative preferences instead of a hard-coded one-chapter request.
- Chapter choices must include long-form ranges through 200 chapters and allow a validated custom chapter count.
- Genre, writing style, tone, thrill frequency, and per-chapter length must each provide a custom input path; custom text must be persisted and injected into prompts.
- Do not log or expose API keys, prompts, complete responses, or generated正文 during implementation or verification.

---

### Task 1: Enforce setup at the domain boundary and use saved configuration

**Files:**
- Modify: `app/src/main/java/com/novelforge/app/domain/usecase/GenerateOutlineUseCase.kt`
- Test: `app/src/test/java/com/novelforge/app/domain/usecase/GenerationUseCaseTest.kt`

**Interfaces:**
- Consume `Project.creativeConfig` and `Project.questData.answers`.
- Preserve `QueuedGeneration`, but make invocation fail with `IllegalStateException("请先完成创作设置")` when no configuration exists.
- Produce an outline request whose user prompt contains the saved chapter count, target length, genre tags, style, tone, thrill frequency, and questionnaire answers.

- [ ] **Step 1: Add a failing use-case test** that constructs a project without `creativeConfig`, calls `GenerateOutlineUseCase`, and asserts the clear setup error.
- [ ] **Step 2: Add a failing request-content test** with a completed `CreativeConfig` and answers, then assert the generated request contains the configured chapter count and core answers rather than the literal `1`.
- [ ] **Step 3: Run the focused unit tests** with `./gradlew.bat :app:testDebugUnitTest --tests "com.novelforge.app.domain.usecase.GenerationUseCaseTest"` and verify the new tests fail before implementation.
- [ ] **Step 4: Change `GenerateOutlineUseCase`** to require a completed config, pass it into `buildRequest`, and compose the prompt from the persisted config and answers.
- [ ] **Step 5: Re-run the focused tests** and verify they pass without weakening the existing project-creation assertions.

### Task 2: Build the required creative setup form

**Files:**
- Create: `app/src/main/java/com/novelforge/app/presentation/project/CreativeSetupScreen.kt`
- Create: `app/src/main/java/com/novelforge/app/presentation/project/CreativeSetupViewModel.kt`
- Test: `app/src/test/java/com/novelforge/app/presentation/project/CreativeSetupViewModelTest.kt`

**Interfaces:**
- `CreativeSetupScreen(project: Project, onSave: (CreativeConfig, QuestData) -> Unit, onBack: () -> Unit)` renders required fields and choices.
- `CreativeSetupViewModel` loads one project by ID and exposes `StateFlow<Project?>`; `save(config, questData, onSaved)` validates and persists the updated project.
- Saving sets `flowState = FlowState.OUTLINE_GENERATE`, `status = ProjectStatus.OUTLINING`, updates `updatedAt`, and leaves the API settings untouched.

- [ ] **Step 1: Write the ViewModel test** for saving a complete setup, asserting trimmed answers, persisted config, `OUTLINE_GENERATE`, `OUTLINING`, and the updated timestamp.
- [ ] **Step 2: Write the validation test** for blank premise/protagonist/conflict or missing quantitative choice, asserting a user-readable error and no repository write.
- [ ] **Step 3: Implement the ViewModel** with a repository fake-friendly constructor, one-project loading, validation, and observable error state.
- [ ] **Step 4: Implement the Compose form** with text fields for premise/protagonist/conflict, selectable chips for genre/style/tone/thrill frequency, chapter-count choices, and a disabled save button until every required value is present.
- [ ] **Step 5: Initialize existing values** when a project already has `creativeConfig` or answers, so returning to setup edits instead of clearing the form.
- [ ] **Step 6: Run the focused ViewModel tests** and compile the app to catch Compose/API mismatches.

### Task 3: Put setup into the navigation flow and gate old projects

**Files:**
- Modify: `app/src/main/java/com/novelforge/app/presentation/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/novelforge/app/presentation/navigation/NovelForgeNavGraph.kt`

**Interfaces:**
- New projects navigate to `creative-setup/{projectId}` after creation.
- Opening a project with `creativeConfig == null` navigates to setup; configured projects continue to `outline/{projectId}`.
- Setup save navigates to the outline route only after repository persistence succeeds.

- [ ] **Step 1: Update the create callback** so the newly created project opens setup instead of returning to home and making outline generation immediately available.
- [ ] **Step 2: Add the setup destination** with a keyed `CreativeSetupViewModel`, loading the project and showing a loading/error state when it is unavailable.
- [ ] **Step 3: Add the existing-project gate** in `onOpenProject`, preserving normal history-card taps and sending only unconfigured projects to setup.
- [ ] **Step 4: Make setup save return to the outline editor** and clear the setup back-stack entry so pressing back does not reopen a half-completed form unexpectedly.
- [ ] **Step 5: Confirm the outline screen remains unchanged for configured projects** and that its generate button can only reach the guarded use case.

### Task 4: Verify the corrected flow and the supplied provider configuration

**Files:**
- Verify: `app/src/main/java/com/novelforge/app/presentation/project/CreativeSetupScreen.kt`
- Verify: `app/src/main/java/com/novelforge/app/domain/usecase/GenerateOutlineUseCase.kt`
- Verify: `app/src/main/java/com/novelforge/app/infrastructure/jobs/GenerationRuntime.kt`

- [ ] **Step 1: Run the full verification suite**: `./gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug`.
- [ ] **Step 2: Locate the Android SDK `adb.exe`, install the debug APK on device `1d140d4a`, and launch the app without printing sensitive settings.**
- [ ] **Step 3: Exercise the UI**: create a project, verify the setup screen appears, verify the generate action is unavailable until required selections are made, save, and verify the outline screen appears.
- [ ] **Step 4: Use the user-provided Bookman/书生 Base URL and model in the app settings, enter the supplied key only on the device, save, and run connection test.** Report only success/failure and sanitized protocol field names; never print the key or full response.
- [ ] **Step 5: Inspect filtered `logcat` for `com.novelforge.app` if the request fails, retaining only exception messages and JSON field names/types.**

### Task 5: Add long-form and custom creative options

**Files:**
- Modify: `app/src/main/java/com/novelforge/app/domain/model/CreativeConfig.kt`
- Modify: `app/src/main/java/com/novelforge/app/presentation/project/CreativeSetupScreen.kt`
- Modify: `app/src/main/java/com/novelforge/app/presentation/project/CreativeSetupViewModel.kt`
- Modify: `app/src/main/java/com/novelforge/app/domain/usecase/GenerateOutlineUseCase.kt`
- Modify: `app/src/main/java/com/novelforge/app/infrastructure/llm/PromptBuilder.kt`
- Test: `app/src/test/java/com/novelforge/app/presentation/project/CreativeSetupViewModelTest.kt`
- Test: `app/src/test/java/com/novelforge/app/domain/usecase/GenerationUseCaseTest.kt`

**Interfaces:**
- `CreativeConfig` keeps the existing enum values for backward compatibility and adds `CUSTOM` plus nullable custom labels with serialization defaults.
- Prompt labels are exposed by `writingStylePromptLabel()`, `tonePromptLabel()`, and `thrillFrequencyPromptLabel()` and are used by both outline and chapter prompts.
- The setup form offers chapter counts through 200, custom chapter/length numeric fields, a custom genre tag adder, and custom text fields for style/tone/frequency.

- [ ] **Step 1: Add tests** proving valid custom values pass setup validation and that custom labels/long-form chapter counts appear in outline prompts.
- [ ] **Step 2: Add backward-compatible model fields and prompt-label helpers.**
- [ ] **Step 3: Add custom controls and long-form chapter choices to the setup screen.**
- [ ] **Step 4: Validate and normalize custom values before persistence, including chapter count 1–200 and length 500–10000.**
- [ ] **Step 5: Run focused tests, full verification, rebuild, reinstall, and inspect the updated setup screen on the device.**
