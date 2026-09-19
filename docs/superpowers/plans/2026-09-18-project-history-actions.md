# Project History Actions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow long-pressing a project in the home history list to rename it or delete it safely.

**Architecture:** Keep project mutations behind `ProjectRepository`; implement deletion as a Room transaction that removes the project and all project-scoped generated artifacts. Keep dialog state in `HomeScreen`, while `HomeViewModel` performs validated asynchronous rename/delete operations.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Room, coroutines, existing repository/ViewModel patterns.

**Spec:** Current user request: “主界面历史的长按最好多个编辑重命名和删除按钮或者其他功能”。

## Global Constraints

- Long press must not replace the existing tap-to-open behavior.
- Rename must reject blank titles and trim surrounding whitespace.
- Delete must require a second confirmation and must remove project-scoped generated history.
- Do not log or expose API keys during implementation or verification.

### Task 1: Add transactional project deletion

**Files:**
- Modify: `app/src/main/java/com/novelforge/app/domain/repository/ProjectRepository.kt`
- Modify: `app/src/main/java/com/novelforge/app/data/local/Daos.kt`
- Modify: `app/src/main/java/com/novelforge/app/data/repository/RoomProjectRepository.kt`
- Modify: `app/src/main/java/com/novelforge/app/NovelForgeApplication.kt`
- Modify: `app/src/test/java/com/novelforge/app/domain/usecase/GenerationUseCaseTest.kt`

**Interfaces:**
- Produce `ProjectRepository.deleteProject(id: String)`.
- Produce `RoomProjectRepository.deleteProject` as a transaction over project, outline, chapter, generation, prompt, call, character, and quality tables.

- [ ] **Step 1: Extend repository and DAO contracts** with `deleteProject` and project-scoped delete queries.
- [ ] **Step 2: Implement the Room transaction** and pass `AppDatabase` to `RoomProjectRepository`.
- [ ] **Step 3: Update test fakes** to satisfy the repository contract.
- [ ] **Step 4: Run the repository/domain unit tests** and verify compilation.

### Task 2: Add HomeViewModel mutations

**Files:**
- Modify: `app/src/main/java/com/novelforge/app/presentation/home/HomeViewModel.kt`

**Interfaces:**
- Produce `renameProject(projectId: String, title: String, onCompleted: () -> Unit)`.
- Produce `deleteProject(projectId: String, onCompleted: () -> Unit)`.

- [ ] **Step 1: Validate and trim the rename title** before calling `saveProject`.
- [ ] **Step 2: Update `updatedAt` on rename** and call `deleteProject` for deletion.
- [ ] **Step 3: Route failures to an observable UI error** without logging sensitive settings.
- [ ] **Step 4: Add or update focused ViewModel/repository tests** for rename and delete behavior.

### Task 3: Add long-press menu and safe dialogs

**Files:**
- Modify: `app/src/main/java/com/novelforge/app/presentation/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/novelforge/app/presentation/navigation/NovelForgeNavGraph.kt`

**Interfaces:**
- Consume the HomeViewModel mutation callbacks.
- Preserve `onOpenProject` for normal taps.

- [ ] **Step 1: Add `combinedClickable`** to each history card with tap and long-press callbacks.
- [ ] **Step 2: Add an operation dialog** with “重命名”, “删除”, and “取消”.
- [ ] **Step 3: Add a rename dialog** with a prefilled title and blank-title validation.
- [ ] **Step 4: Add a delete confirmation** that explicitly says generated outline/chapter history will be removed.
- [ ] **Step 5: Wire callbacks through `NovelForgeNavGraph`** and display operation failures.

### Task 4: Verify the complete feature

**Files:**
- Verify: `app/src/main/java/com/novelforge/app/presentation/home/HomeScreen.kt`
- Verify: `app/src/main/java/com/novelforge/app/data/repository/RoomProjectRepository.kt`

- [ ] **Step 1: Run unit tests, Android-test compilation, lint, and debug APK assembly.**
- [ ] **Step 2: Install the APK on the connected device.**
- [ ] **Step 3: Verify normal tap still opens a project.**
- [ ] **Step 4: Verify long press opens the menu, rename updates the card, and delete removes it after confirmation.**
- [ ] **Step 5: Check filtered crash logs for `com.novelforge.app` without printing API keys or request headers.**
