# Jev 微信聊天内嵌分析卡片 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在用户授权的微信聊天界面中，以只读悬浮覆盖层显示紧跟消息气泡的 Jev 分析卡片，并在无法定位时保留现有悬浮胶囊入口。

**Architecture:** 主 App 继续负责 IPC、实时分析、历史保存和 UI。新增进程内 `InlineAnalysisCardStore` 保存最新卡片，`JevAccessibilityService` 发布当前微信可见节点的脱敏 bounds 快照，悬浮助手服务用 `WindowManager` 将 Compose 卡片定位在匹配气泡下方。Xposed 模块和微信进程不加载任何卡片 UI，也不发送消息。

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Coroutines/StateFlow, Android AccessibilityService, WindowManager, Room/SQLCipher, JUnit 4, Compose Android tests, ADB 真机验收。

**Spec:** `docs/superpowers/specs/2026-09-21-jev-inline-analysis-card-design.md`

## Global Constraints

- 卡片是 Jev Assistant Surface，不是微信消息；不得写入微信数据库、调用微信发送接口或自动回复。
- Xposed/微信进程只提交已授权的 `CapturedMessage`，不运行分析、网络请求、数据库操作或 Compose UI。
- 聊天正文不能进入日志、WindowManager 参数或卡片状态；定位匹配使用脱敏文本哈希和 bounds。
- 实时分析、悬浮窗和无障碍权限都由用户主动开启；收到消息不得静默拉起前台服务。
- 无法可靠定位消息时隐藏卡片并保留悬浮胶囊，不猜测坐标覆盖错误内容。
- 每项代码变更先写对应的失败测试，再实现最小修复；每个任务结束运行该任务的完整测试命令。

---

### Task 1: 建立脱敏消息锚点、卡片状态和纯定位器

**Files:**
- Create: `app/src/main/java/com/jev/relationship/domain/inline/InlineAnalysisCard.kt`
- Create: `app/src/main/java/com/jev/relationship/domain/inline/InlineCardStore.kt`
- Create: `app/src/main/java/com/jev/relationship/domain/inline/InlineMessageLocator.kt`
- Create: `app/src/test/java/com/jev/relationship/domain/inline/InlineCardStoreTest.kt`
- Create: `app/src/test/java/com/jev/relationship/domain/inline/InlineMessageLocatorTest.kt`

**Interfaces:**
- Consumes: `CapturedMessage.messageId`, `CapturedMessage.text`, `CapturedMessage.isOutgoing`, `AnalysisResult` and the existing `AssistantSurfaceState` conventions.
- Produces: `InlineMessageAnchor`, `InlineNodeSnapshot`, `InlineCardPlacement`, `InlineAnalysisCard`, `InlineCardStore`, and `InlineMessageLocator.locate(...)` for later tasks.

- [ ] **Step 1: Write failing store tests.** Verify `show()` replaces the previous card, `clear()` emits no card, and a card with a different message ID cannot be mistaken for the previous one. Use `MutableStateFlow` collection through `store.state.value`; do not put message text into assertions beyond a local hash input.

- [ ] **Step 2: Write failing locator tests.** Cover: matching hash and outgoing side; nonmatching hash returns `null`; input/editor and top-bar classes are ignored; invisible nodes and empty bounds are ignored; a card below the bubble is shifted upward when it would exceed screen height; horizontal placement stays within screen bounds.

- [ ] **Step 3: Run the focused tests and confirm failure.**

  Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*InlineCardStoreTest' --tests '*InlineMessageLocatorTest'`

  Expected: FAIL because the new domain types and locator do not exist.

- [ ] **Step 4: Implement the pure models.** Use value-only models:

  ```kotlin
  data class InlineMessageAnchor(
      val messageId: String,
      val conversationHash: String,
      val textHash: String,
      val isOutgoing: Boolean,
  )

  data class InlineNodeSnapshot(
      val packageName: String,
      val className: String,
      val textHash: String?,
      val left: Int,
      val top: Int,
      val right: Int,
      val bottom: Int,
      val visibleToUser: Boolean,
  )

  data class InlineCardPlacement(val left: Int, val top: Int, val width: Int, val height: Int)
  ```

  `InlineMessageLocator.locate(anchor, nodes, screenWidth, screenHeight, cardWidth, cardHeight)` must select the closest visible text node with the same hash, reject known editor/top-bar classes, place below the node, clamp horizontally, and shift above only when the lower placement does not fit.

- [ ] **Step 5: Implement `InlineCardStore` as a small StateFlow-backed process-local store.** Its public operations are `show(card: InlineAnalysisCard)`, `clear(messageId: String? = null)`, and `state: StateFlow<InlineAnalysisCard?>`. It must never log or retain the source conversation text.

- [ ] **Step 6: Run the focused tests and confirm pass.**

  Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*InlineCardStoreTest' --tests '*InlineMessageLocatorTest'`

- [ ] **Step 7: Commit the isolated domain seam.**

  ```bash
  git add app/src/main/java/com/jev/relationship/domain/inline app/src/test/java/com/jev/relationship/domain/inline
  git commit -m "feat: add inline analysis card domain seam"
  ```

### Task 2: Publish the analyzed message identity into the card store

**Files:**
- Modify: `app/src/main/java/com/jev/relationship/domain/realtime/RealtimeAnalysisCoordinator.kt`
- Modify: `app/src/main/java/com/jev/relationship/domain/realtime/RealtimeAnalysisState.kt`
- Modify: `app/src/main/java/com/jev/relationship/di/AppModule.kt`
- Create: `app/src/main/java/com/jev/relationship/domain/inline/InlineAnalysisCardPublisher.kt`
- Modify: `app/src/test/java/com/jev/relationship/domain/realtime/RealtimeAnalysisCoordinatorTest.kt`
- Create: `app/src/test/java/com/jev/relationship/domain/inline/InlineAnalysisCardPublisherTest.kt`

**Interfaces:**
- Consumes: `MessageCaptureCoordinator.events`, `CapturedMessage.messageId`, `CapturedMessage.text`, `ConversationAnalyzer`, and Task 1 `InlineCardStore`.
- Produces: `InlineAnalysisCardPublisher.publish(anchor, output)` and `clear()`; `RealtimeAnalysisState.Completed` carries the triggering `messageId` when one is available.

- [ ] **Step 1: Extend the realtime tests before production code.** Add a test message with a stable ID and assert that, after the quiet window, the publisher receives an anchor with the same ID, a conversation hash, a text hash, and the analyzed output. Add tests for missing message ID (analysis still completes and the pill remains the fallback) and stop/disable clearing the publisher.

- [ ] **Step 2: Run the focused realtime tests and confirm failure.**

  Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*RealtimeAnalysisCoordinatorTest' --tests '*InlineAnalysisCardPublisherTest'`

  Expected: FAIL because the coordinator has no publisher dependency and no message identity in its completed state.

- [ ] **Step 3: Implement `InlineAnalysisCardPublisher`.** It computes SHA-256 hashes for conversation ID and message text inside the app process, creates an `InlineAnalysisCard`, and delegates to `InlineCardStore`. The publisher accepts only the stable message ID; it must not expose the original text in its state or logs.

- [ ] **Step 4: Thread the triggering `CapturedMessage` through the debounce job.** Capture `message.messageId` and `message.text` when the event is accepted, pass them into `launchAnalysis`, and publish only after the analysis is current and history persistence has completed or produced a nonfatal history warning. Add `messageId` to `Analyzing`, `Completed`, and `Error` state objects without changing the existing debounce or cancellation semantics.

- [ ] **Step 5: Provide the publisher through Hilt and clear it from `stop()`/disabled transitions.** The existing `AssistantSurfaceCoordinator.show()` remains the pill fallback; the new publisher is additive.

- [ ] **Step 6: Run all app unit tests.**

  Run: `.\gradlew.bat :app:testDebugUnitTest`

- [ ] **Step 7: Commit the realtime integration.**

  ```bash
  git add app/src/main/java/com/jev/relationship/domain/realtime app/src/main/java/com/jev/relationship/domain/inline/InlineAnalysisCardPublisher.kt app/src/main/java/com/jev/relationship/di/AppModule.kt app/src/test/java/com/jev/relationship/domain/realtime app/src/test/java/com/jev/relationship/domain/inline/InlineAnalysisCardPublisherTest.kt
  git commit -m "feat: publish analyzed messages for inline cards"
  ```

### Task 3: Publish a脱敏的微信可见窗口快照

**Files:**
- Create: `app/src/main/java/com/jev/relationship/domain/inline/InlineWindowSnapshot.kt`
- Create: `app/src/main/java/com/jev/relationship/service/InlineWindowSnapshotStore.kt`
- Modify: `app/src/main/java/com/jev/relationship/service/JevAccessibilityService.kt`
- Modify: `app/src/main/AndroidManifest.xml` only if the existing accessibility metadata needs the required window event flags
- Create: `app/src/test/java/com/jev/relationship/domain/inline/InlineWindowSnapshotTest.kt`

**Interfaces:**
- Consumes: `AccessibilityService.rootInActiveWindow`, current package name, and accessibility node bounds/text.
- Produces: `InlineWindowSnapshotStore.state: StateFlow<InlineWindowSnapshot?>` containing only package name, screen size, event timestamp, and `InlineNodeSnapshot` values from Task 1.

- [ ] **Step 1: Write failing snapshot tests.** Verify text is normalized and hashed before publication, bounds are copied, empty/invisible nodes are excluded, non-WeChat events do not replace the current snapshot, and `clear()` emits `null`.

- [ ] **Step 2: Run the focused test and confirm failure.**

  Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*InlineWindowSnapshotTest'`

- [ ] **Step 3: Implement the snapshot store and node walker.** Walk only the active WeChat root window on accessibility events. Read raw text only long enough to calculate the same hash used by `InlineMessageAnchor`; never put raw text in `InlineWindowSnapshot`, logs, or WindowManager layout parameters. Recycle node objects where required by the Android API and catch traversal exceptions.

- [ ] **Step 4: Keep the existing conversation source behavior intact.** `JevAccessibilityService` continues to expose its existing `ConversationSource` state and latest conversation, while publishing the additional snapshot to the process-local store. Clear the store on `onInterrupt()` and `stop()`.

- [ ] **Step 5: Run app unit tests and lint.**

  Run: `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug`

- [ ] **Step 6: Commit the snapshot boundary.**

  ```bash
  git add app/src/main/java/com/jev/relationship/domain/inline/InlineWindowSnapshot.kt app/src/main/java/com/jev/relationship/service/InlineWindowSnapshotStore.kt app/src/main/java/com/jev/relationship/service/JevAccessibilityService.kt app/src/test/java/com/jev/relationship/domain/inline/InlineWindowSnapshotTest.kt app/src/main/AndroidManifest.xml
  git commit -m "feat: expose redacted WeChat window snapshots"
  ```

### Task 4: Render and reposition the inline card in the existing foreground overlay

**Files:**
- Create: `app/src/main/java/com/jev/relationship/service/InlineAnalysisCardView.kt`
- Create: `app/src/main/java/com/jev/relationship/service/InlineAnalysisOverlayController.kt`
- Modify: `app/src/main/java/com/jev/relationship/service/FloatingAssistantService.kt`
- Modify: `app/src/main/java/com/jev/relationship/service/FloatingAssistantPill.kt`
- Add or modify: `app/src/androidTest/java/com/jev/relationship/service/FloatingAssistantPillTest.kt`
- Create: `app/src/androidTest/java/com/jev/relationship/service/InlineAnalysisCardViewTest.kt`
- Create: `app/src/test/java/com/jev/relationship/service/InlineAnalysisOverlayControllerTest.kt`

**Interfaces:**
- Consumes: `InlineCardStore.state`, `InlineWindowSnapshotStore.state`, `InlineMessageLocator.locate`, and existing `FloatingAssistantLifecycle` callbacks.
- Produces: an independently managed `ComposeView` overlay card and a stable pill click action that opens Jev without stopping the service.

- [ ] **Step 1: Add failing Compose tests.** Verify the card renders the Jev label, emotion, intent percentages, risk score, and action text; long text remains inside the card; clicking the card invokes `onOpen`. Verify the pill click invokes `onOpen` and never invokes a close callback.

- [ ] **Step 2: Add failing controller tests.** Verify `start()` adds at most one card view, a matching snapshot calls `updateViewLayout` with the locator placement, a missing/invisible match hides the card, and `stop()` removes the card view and clears state.

- [ ] **Step 3: Run the focused tests and confirm failure.**

  Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*InlineAnalysisOverlayControllerTest'; .\gradlew.bat :app:connectedDebugAndroidTest`

  Expected: the new tests fail because the card view/controller and the new pill callback do not exist.

- [ ] **Step 4: Implement the Compose card.** Use a compact rounded surface with the same information hierarchy as the reference image. Make it `wrapContentWidth` with a maximum width, use scrollable content for long suggestions, and expose a single `onOpen` action. Do not include a send/reply button.

- [ ] **Step 5: Implement `InlineAnalysisOverlayController`.** Own one card `ComposeView` in addition to the existing pill view. Subscribe to card and snapshot flows on the service scope, call the pure locator, and update `WindowManager.LayoutParams` using `TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE`, top/start gravity, and clamped screen coordinates. Never add or update a view when the locator returns `null`.

- [ ] **Step 6: Wire the controller into `FloatingAssistantService`.** Start it after the overlay permission check and lifecycle activation; stop it before removing the pill view. Keep the existing user-controlled foreground service lifecycle. Change the pill click to launch `MainActivity` with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP` instead of `stopSelf()`.

- [ ] **Step 7: Run Compose tests, unit tests, and lint.**

  Run: `.\gradlew.bat :app:connectedDebugAndroidTest :app:testDebugUnitTest :app:lintDebug`

- [ ] **Step 8: Commit the overlay implementation.**

  ```bash
  git add app/src/main/java/com/jev/relationship/service app/src/test/java/com/jev/relationship/service app/src/androidTest/java/com/jev/relationship/service
  git commit -m "feat: render inline analysis cards over WeChat"
  ```

### Task 5: Open the corresponding analysis detail without stopping the overlay

**Files:**
- Modify: `app/src/main/java/com/jev/relationship/MainActivity.kt`
- Modify: `app/src/main/java/com/jev/relationship/JevApp.kt`
- Modify: `app/src/main/java/com/jev/relationship/feature/history/HistoryViewModel.kt`
- Modify: `app/src/main/java/com/jev/relationship/feature/history/HistoryScreen.kt` only if the navigation callback needs a stable latest-item path
- Create or modify: `app/src/test/java/com/jev/relationship/feature/history/HistoryViewModelTest.kt`
- Add to the relevant Android test: `app/src/androidTest/java/com/jev/relationship/service/InlineAnalysisCardViewTest.kt`

**Interfaces:**
- Consumes: the overlay's `onOpen` action and the saved history ID from `InlineAnalysisCard` when available.
- Produces: a safe `Intent` extra for opening the latest matching saved analysis; if history was not saved, it opens the normal Jev analysis screen without affecting the overlay service.

- [ ] **Step 1: Write failing navigation tests.** Verify a valid history ID opens the requested detail, a missing ID falls back to the analysis screen, and the `MainActivity` intent does not request service shutdown.

- [ ] **Step 2: Run the focused tests and confirm failure.**

  Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*HistoryViewModelTest'`

- [ ] **Step 3: Implement the intent contract and latest-detail routing.** Define a private constant extra in `MainActivity`, consume it once in `JevApp`, and select the matching `SavedAnalysis` from the observed history list. Keep the existing manual history navigation unchanged.

- [ ] **Step 4: Run all app tests and the connected UI tests.**

  Run: `.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest`

- [ ] **Step 5: Commit the navigation behavior.**

  ```bash
  git add app/src/main/java/com/jev/relationship/MainActivity.kt app/src/main/java/com/jev/relationship/JevApp.kt app/src/main/java/com/jev/relationship/feature/history app/src/test/java/com/jev/relationship/feature/history app/src/androidTest/java/com/jev/relationship/service
  git commit -m "feat: open inline analysis details from overlay"
  ```

### Task 6: End-to-end lifecycle, privacy, and real-device acceptance

**Files:**
- Modify: `docs/superpowers/plans/2026-09-21-jev-inline-analysis-card.md` only for execution checkboxes
- Modify: `.superpowers/sdd/2026-09-21-jev-phase5-realtime-analysis/progress.md` with Phase 6 evidence
- Create: `docs/superpowers/plans/2026-09-21-jev-phase6-inline-analysis-card.md` only if the execution ledger needs a separate checklist

- [ ] **Step 1: Run the complete automated suite.**

  Run: `.\gradlew.bat :jev-ipc-contract:testDebugUnitTest :app:testDebugUnitTest :xposed:testDebugUnitTest :app:lintDebug :xposed:lintDebug :app:assembleDebug :xposed:assembleDebug :app:connectedDebugAndroidTest`

  Expected: all tasks finish with `BUILD SUCCESSFUL`; connected tests include the inline card and pill click tests.

- [ ] **Step 2: Install the new app APK without clearing settings.** Install `app/build/outputs/apk/debug/app-debug.apk` with `adb -s 933e802 install -r`, keep the existing Xposed pairing, and confirm the realtime consent remains enabled.

- [ ] **Step 3: Confirm the service and accessibility prerequisites.** Start the floating assistant from Jev settings, ensure the accessibility service is enabled for WeChat, open the user-authorized “文件传输助手” conversation, and verify the pill is visible.

- [ ] **Step 4: Verify a single inline result.** Clear logcat, send one safe test message through the visible WeChat input, and confirm the message is captured, analysis completes, and one Jev card appears directly below the matching bubble. Logs may show only event status, message ID hash, generation, and placement outcome; never print message text or pairing data.

- [ ] **Step 5: Verify scrolling and replacement.** Scroll the conversation so the target bubble leaves the viewport and return; confirm the card hides while out of view and reappears under the same bubble. Send two messages within the quiet window and confirm only the latest card remains.

- [ ] **Step 6: Verify click and safety behavior.** Tap the card and confirm Jev opens while the floating service remains running. Turn realtime analysis off or stop the service and confirm the card disappears. Inspect WeChat input/history to confirm Jev did not send a message.

- [ ] **Step 7: Run a privacy scan and update the ledger.** Search the changed paths for logging of raw text, tokens, API keys, serialized messages, or debug prefixes; remove any temporary instrumentation. Record only redacted, status-level device evidence in the SDD ledger.

- [ ] **Step 8: Commit documentation and final verification.**

  ```bash
  git add docs/superpowers/plans/2026-09-21-jev-inline-analysis-card.md .superpowers/sdd/2026-09-21-jev-phase5-realtime-analysis/progress.md
  git commit -m "docs: record inline analysis card acceptance"
  ```

