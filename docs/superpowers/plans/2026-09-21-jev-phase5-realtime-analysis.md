# Jev Phase 5 Realtime Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect accepted WeChat message events to debounced analysis, encrypted history, and the shared floating assistant surface while keeping realtime mode opt-in and read-only.

**Architecture:** Extend `MessageCaptureCoordinator` with a bounded accepted-message `SharedFlow`. A Hilt singleton `RealtimeAnalysisCoordinator` owns per-conversation quiet-window jobs, stale-result protection, analysis, history persistence, and surface updates. `AssistantSurfaceCoordinator` becomes the singleton observed by `FloatingAssistantService`; a separate DataStore-backed realtime setting defaults to disabled.

**Tech Stack:** Kotlin, coroutines/Flow, Hilt, Jetpack Compose, DataStore Preferences, Room + SQLCipher, JUnit, `kotlinx-coroutines-test`, existing Jev/fallback analyzers.

**Spec:** `docs/superpowers/specs/2026-09-21-jev-phase5-realtime-analysis-design.md`

## Global Constraints

- Realtime mode defaults to `false` and requires an explicit user confirmation before enabling.
- Use a per-conversation quiet window of approximately 700 ms.
- Do not modify the existing IPC contract or WeChat/Xposed protocol.
- Do not log message bodies, pairing tokens, API keys, or complete captured objects.
- Do not auto-send or auto-reply in WeChat.
- Reuse the existing SQLCipher-backed Room history repository; do not add a database migration for the first version.
- When no API Key is configured, use the existing local fallback analyzer/reply generator.
- Stopping the floating service, disabling realtime mode, IPC reset, cancellation, and app process death must clear transient realtime jobs without deleting saved history.

## Review Focus

- Accepted-versus-rejected capture: rejected IPC messages must not enter the realtime event stream; test in Task 1.
- Quiet-window races: an older analysis must not overwrite a newer generation; test in Task 2.
- Service/process lifecycle: the floating service and coordinator must share one surface instance and cancel work on destruction; test in Task 4.
- Remote/fallback privacy: no API Key must use local fallback, and enabling realtime must be the explicit standing consent for configured remote analysis; test in Task 5.
- Persistence failure: a history write failure must still allow the analysis result to reach the surface and remain recoverable; test in Task 2.

---

### Task 1: Publish accepted capture events and define realtime state

**Files:**
- Create: `app/src/main/java/com/jev/relationship/domain/realtime/RealtimeAnalysisState.kt`
- Modify: `app/src/main/java/com/jev/relationship/ipc/MessageCaptureCoordinator.kt`
- Test: `app/src/test/java/com/jev/relationship/ipc/MessageCaptureCoordinatorTest.kt`

**Interfaces:**
- Consumes: existing `CapturedMessage`, `CapturedMessageValidator`, `ConversationSourceState`, and bounded conversation buffer.
- Produces: `MessageCaptureCoordinator.events: SharedFlow<CapturedMessage>` and `RealtimeAnalysisState` for Tasks 2–5.

- [ ] **Step 1: Write failing capture-event tests**

Add tests that submit a valid message after `activate()`, collect one event, and assert the event equals the accepted message. Add a second test that submits an invalid blank-text message and asserts the event collector receives nothing while `SubmitResult.accepted` is false. Add a reset test that verifies `latestConversation()` is cleared and no event is emitted by reset itself.

```kotlin
@Test
fun `only accepted messages are emitted`() = runTest {
    val coordinator = MessageCaptureCoordinator()
    coordinator.activate()
    val emitted = async { coordinator.events.first() }

    val result = coordinator.submit(validMessage(text = "hello"))

    assertTrue(result.accepted)
    assertEquals("hello", emitted.await().text)
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*MessageCaptureCoordinatorTest'`

Expected: compilation/test failure because `events` and the new state type do not yet exist.

- [ ] **Step 3: Define explicit realtime state types**

Create `RealtimeAnalysisState` as a sealed interface with these concrete states and fields:

```kotlin
sealed interface RealtimeAnalysisState {
    data object Disabled : RealtimeAnalysisState
    data object WaitingForPermission : RealtimeAnalysisState
    data class Collecting(val conversationId: String) : RealtimeAnalysisState
    data class Analyzing(val conversationId: String, val generation: Long) : RealtimeAnalysisState
    data class Completed(
        val conversationId: String,
        val output: AnalysisOutput,
        val historyId: Long?,
        val historyError: String? = null,
    ) : RealtimeAnalysisState
    data class Error(val conversationId: String, val message: String) : RealtimeAnalysisState
}
```

- [ ] **Step 4: Add a bounded accepted-message SharedFlow**

In `MessageCaptureCoordinator`, add a private `MutableSharedFlow<CapturedMessage>(extraBufferCapacity = IpcProtocol.MAX_BATCH_SIZE)` and expose it with `asSharedFlow()`. Emit with `tryEmit(message)` only after validation, insertion, sorting, and trimming succeed. Keep the existing submit result and IPC behavior unchanged.

- [ ] **Step 5: Run the focused test and the existing IPC tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*MessageCaptureCoordinatorTest' :jev-ipc-contract:testDebugUnitTest`

Expected: PASS; rejected messages remain rejected and the existing IPC contract tests remain green.

- [ ] **Step 6: Commit the event boundary**

```bash
git add app/src/main/java/com/jev/relationship/ipc/MessageCaptureCoordinator.kt app/src/main/java/com/jev/relationship/domain/realtime/RealtimeAnalysisState.kt app/src/test/java/com/jev/relationship/ipc/MessageCaptureCoordinatorTest.kt
git commit -m "feat: publish accepted messages for realtime analysis"
```

### Task 2: Implement debounced realtime analysis orchestration

**Files:**
- Create: `app/src/main/java/com/jev/relationship/domain/realtime/ConversationAnalyzer.kt`
- Create: `app/src/main/java/com/jev/relationship/domain/realtime/RealtimeAnalysisCoordinator.kt`
- Modify: `app/src/main/java/com/jev/relationship/domain/AnalysisConversationUseCase.kt`
- Test: `app/src/test/java/com/jev/relationship/domain/realtime/RealtimeAnalysisCoordinatorTest.kt`

**Interfaces:**
- Consumes: `MessageCaptureCoordinator.events`, `MessageCaptureCoordinator.latestConversation()`, `AnalysisConversationUseCase`, `HistoryRepository`, and `AssistantSurfaceCoordinator`.
- Produces: singleton-ready `RealtimeAnalysisCoordinator.start()`, `stop()`, `setEnabled(Boolean)`, `state: StateFlow<RealtimeAnalysisState>`.

- [ ] **Step 1: Add a testable analyzer seam**

Define:

```kotlin
fun interface ConversationAnalyzer {
    suspend operator fun invoke(conversation: Conversation): AnalysisOutput
}
```

Make `AnalysisConversationUseCase` implement `ConversationAnalyzer` without changing its existing `invoke` behavior. The coordinator will call `analyzer(conversation)`; the manual analysis flow may continue injecting the concrete use case.

- [ ] **Step 2: Write failing coordinator tests**

Use `runTest` and a fake `ConversationAnalyzer`, fake `HistoryRepository`, and real `MessageCaptureCoordinator`/`AssistantSurfaceCoordinator`. Cover:

1. Messages for one conversation within 700ms yield one analyzer call with the latest bounded snapshot.
2. A new message cancels the prior generation and the old deferred result cannot update `state` or surface.
3. Different conversation IDs do not cancel each other’s collection jobs.
4. `stop()` cancels pending work and returns state to `Disabled`.
5. A history exception produces `Completed(historyId = null, historyError != null)` and still calls `surface.show()`.
6. An analyzer exception produces `Error` and later messages can retry.

```kotlin
@Test
fun `quiet window coalesces messages for one conversation`() = runTest {
    val analyzer = RecordingAnalyzer()
    val capture = MessageCaptureCoordinator().also { it.activate() }
    val coordinator = fixture(capture = capture, analyzer = analyzer)
    coordinator.setEnabled(true)
    coordinator.start()

    capture.submit(validMessage(conversationId = "chat-a", text = "one"))
    advanceTimeBy(500)
    capture.submit(validMessage(conversationId = "chat-a", text = "two"))
    advanceTimeBy(699)
    assertEquals(0, analyzer.calls)
    advanceTimeBy(1)

    runCurrent()
    assertEquals(1, analyzer.calls)
    assertEquals("one\ntwo", analyzer.lastConversation.text)
}
```

- [ ] **Step 3: Run the focused tests and verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*RealtimeAnalysisCoordinatorTest'`

Expected: compilation failure for the missing coordinator/seam, followed by behavioral failures until the implementation exists.

- [ ] **Step 4: Implement coordinator lifecycle and event collection**

Implement `RealtimeAnalysisCoordinator` with an injected `CoroutineScope`, a `MutableStateFlow`, a collector `Job`, a `MutableMap<String, Job>` for quiet-window jobs, and a monotonically increasing `Long` generation per conversation. `start()` must be idempotent; `stop()` cancels the collector and all per-conversation jobs, clears maps, hides the surface, and emits `Disabled`.

The event collector must ignore events when disabled, emit `Collecting(conversationId)`, cancel only the prior job for that conversation, and launch a new job that delays `QUIET_WINDOW_MS = 700L` before reading `latestConversation()`.

- [ ] **Step 5: Implement stale-result protection and persistence ordering**

After the delay, increment that conversation’s generation and emit `Analyzing`. Run the analyzer on the coordinator dispatcher by calling `analyzer(conversation)`. Before publishing anything, check that the job is active and its generation is still current. Call `historyRepository.save()` first but catch its exception; then call `surface.show(output.analysis)` for a valid output. Emit `Completed` with `historyId` or `historyError`. Catch non-cancellation analyzer errors as `Error`; rethrow `CancellationException` so a newer message can cancel the old task.

- [ ] **Step 6: Run focused and full app tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*RealtimeAnalysisCoordinatorTest'`

Expected: PASS for all debounce, cancellation, stale-generation, persistence-failure, and retry tests.

Then run: `.\gradlew.bat :app:testDebugUnitTest`

Expected: PASS with no failures or errors.

- [ ] **Step 7: Commit the coordinator**

```bash
git add app/src/main/java/com/jev/relationship/domain/realtime app/src/main/java/com/jev/relationship/domain/AnalysisConversationUseCase.kt app/src/test/java/com/jev/relationship/domain/realtime/RealtimeAnalysisCoordinatorTest.kt
git commit -m "feat: debounce realtime conversation analysis"
```

### Task 3: Add opt-in realtime settings and Hilt runtime wiring

**Files:**
- Create: `app/src/main/java/com/jev/relationship/data/settings/RealtimeAssistantSettings.kt`
- Create: `app/src/main/java/com/jev/relationship/data/settings/DataStoreRealtimeAssistantRepository.kt`
- Create: `app/src/main/java/com/jev/relationship/di/ApplicationScope.kt`
- Modify: `app/src/main/java/com/jev/relationship/di/AppModule.kt`
- Modify: `app/src/main/java/com/jev/relationship/domain/realtime/RealtimeAnalysisCoordinator.kt`
- Test: `app/src/test/java/com/jev/relationship/data/settings/DataStoreRealtimeAssistantRepositoryTest.kt`
- Test: `app/src/test/java/com/jev/relationship/domain/realtime/RealtimeAnalysisCoordinatorSettingsTest.kt`

**Interfaces:**
- Consumes: the coordinator from Task 2 and the existing `settingsDataStore` extension.
- Produces: `RealtimeAssistantSettingsRepository.settings: Flow<RealtimeAssistantSettings>`, a default-disabled Hilt binding, and an application coroutine scope.

- [ ] **Step 1: Write failing repository/default-state tests**

Define the required behavior before implementation: a fresh repository emits `RealtimeAssistantSettings(enabled = false)`, `setEnabled(true)` persists true, and `setEnabled(false)` persists false. Add a coordinator test proving disabled settings suppress analysis even after `start()`.

- [ ] **Step 2: Run focused tests to verify the missing types fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*DataStoreRealtimeAssistantRepositoryTest' --tests '*RealtimeAnalysisCoordinatorSettingsTest'`

Expected: compilation failure because the repository and settings types are not defined.

- [ ] **Step 3: Implement the DataStore repository**

Create:

```kotlin
data class RealtimeAssistantSettings(val enabled: Boolean = false)

interface RealtimeAssistantSettingsRepository {
    val settings: Flow<RealtimeAssistantSettings>
    suspend fun setEnabled(enabled: Boolean)
}
```

Use a dedicated boolean key such as `realtime_assistant_enabled` in the existing DataStore. Do not reuse Xposed or provider keys.

- [ ] **Step 4: Add an application scope and Hilt providers**

Create a qualifier `@ApplicationScope` and provide `CoroutineScope(SupervisorJob() + Dispatchers.Default)` as a `@Singleton`. Bind the DataStore repository, `ConversationAnalyzer` to `AnalysisConversationUseCase`, `AssistantSurfaceCoordinator` as a singleton, and `RealtimeAnalysisCoordinator` as a singleton. Do not create any coordinator inside a service or Activity.

- [ ] **Step 5: Make coordinator enabled state follow the repository**

Add the repository as a coordinator dependency and observe its `settings` flow while started. On false call the coordinator’s existing `setEnabled(false)` path so it cancels pending work, hides the surface, and emits `Disabled`; on true call `setEnabled(true)` so it can consume accepted events when the runtime surface is active. Production callers never set this flag directly; the repository is the source of truth. Keep the direct method only as a small unit-test seam.

- [ ] **Step 6: Run focused and full tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*DataStoreRealtimeAssistantRepositoryTest' --tests '*RealtimeAnalysisCoordinatorSettingsTest' :app:testDebugUnitTest`

Expected: PASS, including existing Hilt-compilation paths.

- [ ] **Step 7: Commit settings and DI wiring**

```bash
git add app/src/main/java/com/jev/relationship/data/settings app/src/main/java/com/jev/relationship/di app/src/main/java/com/jev/relationship/domain/realtime app/src/test/java/com/jev/relationship/data/settings app/src/test/java/com/jev/relationship/domain/realtime
git commit -m "feat: add opt-in realtime assistant runtime"
```

### Task 4: Share the surface coordinator with the floating service

**Files:**
- Modify: `app/src/main/java/com/jev/relationship/domain/surface/AssistantSurfaceCoordinator.kt`
- Modify: `app/src/main/java/com/jev/relationship/service/FloatingAssistantService.kt`
- Modify: `app/src/main/AndroidManifest.xml` if Hilt service entry requirements need updating
- Test: `app/src/test/java/com/jev/relationship/service/FloatingAssistantServiceLifecycleTest.kt`

**Interfaces:**
- Consumes: singleton `AssistantSurfaceCoordinator` and `RealtimeAnalysisCoordinator` from Task 3.
- Produces: service lifecycle hooks that make the shared surface visible while the foreground overlay is alive and stop realtime work on destruction.

- [ ] **Step 1: Write failing lifecycle tests**

Pin that creating the service does not instantiate a private surface coordinator, that the injected coordinator is activated only after overlay permission checks, and that `onDestroy()` calls realtime `stop()` and hides/removes the overlay. If a full Android service test is too heavyweight, extract a small `FloatingAssistantLifecycle` collaborator and unit-test that collaborator with fakes.

- [ ] **Step 2: Run the focused test and verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*FloatingAssistantServiceLifecycleTest'`

Expected: failure because the service currently constructs `AssistantSurfaceCoordinator()` locally and has no realtime lifecycle dependency.

- [ ] **Step 3: Convert surface state to the Hilt singleton**

Keep `AssistantSurfaceCoordinator` API compatible, add no Android dependencies to it, and bind it as a singleton from `AppModule`. Inject it into `FloatingAssistantService` with `@AndroidEntryPoint` and inject `RealtimeAnalysisCoordinator` separately.

- [ ] **Step 4: Wire service start/stop without changing overlay behavior**

After the existing permission check and foreground notification succeed, call the shared surface `activate()` and realtime coordinator `start()`. Compose must collect `surfaceCoordinator.state`, not a locally-created state. In `onDestroy()`, call realtime `stop()`, `surfaceCoordinator.hide()`, remove the view, destroy the owner, and stop foreground service as today.

- [ ] **Step 5: Run focused and full tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*FloatingAssistantServiceLifecycleTest' :app:testDebugUnitTest :app:lintDebug`

Expected: PASS with no new lint warnings.

- [ ] **Step 6: Commit the shared surface integration**

```bash
git add app/src/main/java/com/jev/relationship/domain/surface/AssistantSurfaceCoordinator.kt app/src/main/java/com/jev/relationship/service/FloatingAssistantService.kt app/src/main/AndroidManifest.xml app/src/test/java/com/jev/relationship/service/FloatingAssistantServiceLifecycleTest.kt
git commit -m "feat: connect realtime analysis to shared floating surface"
```

### Task 5: Add the settings UI and explicit consent flow

**Files:**
- Modify: `app/src/main/java/com/jev/relationship/feature/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/jev/relationship/feature/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/jev/relationship/JevApp.kt`
- Test: `app/src/test/java/com/jev/relationship/feature/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `RealtimeAssistantSettingsRepository` and runtime state from Task 3; existing overlay-management callbacks remain in `JevApp`.
- Produces: a default-off switch, explicit consent dialog, permission guidance, and repository-backed enable/disable behavior.

- [ ] **Step 1: Add failing ViewModel tests for consent and default-off state**

Extend the existing settings test fake with realtime settings. Assert that the initial UI state is disabled, requesting enable only shows consent, cancel leaves it disabled, confirm persists true, and disable persists false. Assert that provider/Xposed settings behavior remains unchanged.

- [ ] **Step 2: Run the focused tests and verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*SettingsViewModelTest'`

Expected: compilation failure for the missing realtime UI state and actions.

- [ ] **Step 3: Implement ViewModel state and actions**

Add a realtime settings field and explicit actions such as `requestEnableRealtime()`, `confirmEnableRealtime()`, `cancelEnableRealtime()`, and `disableRealtime()`. Only `confirmEnableRealtime()` may call `setEnabled(true)`. Use the existing `MutableStateFlow` pattern and keep API/Xposed save logic separate.

- [ ] **Step 4: Implement the Compose switch and consent dialog**

Add a “实时分析助手” section near the existing floating assistant/Xposed controls. The switch is disabled by default, opens a dialog with the local-vs-remote data explanation from the spec, and shows `PermissionRequired` guidance when overlay permission is missing. Do not display or log message content in this screen.

- [ ] **Step 5: Wire runtime callbacks in `JevApp`**

Pass the new ViewModel state/actions to `SettingsScreen`. Reuse the existing `Settings.canDrawOverlays(context)` check and overlay settings Intent. Starting/stopping the floating service remains a user action; enabling realtime alone must not silently create a foreground service.

- [ ] **Step 6: Run focused and full app verification**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*SettingsViewModelTest' :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`

Expected: PASS, zero lint errors, and a debug APK.

- [ ] **Step 7: Commit the opt-in UI**

```bash
git add app/src/main/java/com/jev/relationship/feature/settings app/src/main/java/com/jev/relationship/JevApp.kt app/src/test/java/com/jev/relationship/feature/settings/SettingsViewModelTest.kt
git commit -m "feat: add explicit realtime assistant consent"
```

### Task 6: End-to-end regression, privacy review, and device acceptance

**Files:**
- Modify: `README.md` with Phase 5 enablement, consent, fallback, and rollback instructions
- Modify: `docs/superpowers/sdd/2026-09-21-jev-phase4-wechat-hook-plans/progress.md` with Phase 5 evidence if the ledger is still used
- Test: existing app, IPC-contract, and Xposed test suites; add no new production surface unless a test exposes a concrete gap

**Interfaces:**
- Consumes: all components from Tasks 1–5.
- Produces: verified APKs, documented enable/disable flow, and device evidence without exposing message content or secrets.

- [ ] **Step 1: Run the complete automated suite**

Run:

```bash
.\gradlew.bat :jev-ipc-contract:testDebugUnitTest :app:testDebugUnitTest :xposed:testDebugUnitTest :app:lintDebug :xposed:lintDebug :app:assembleDebug :xposed:assembleDebug
```

Expected: `BUILD SUCCESSFUL`, zero test failures/errors, zero app/Xposed lint errors, and both debug APKs produced.

- [ ] **Step 2: Perform the privacy/output review**

Search source and filtered device logs for `Log.*` calls in the new realtime path. Confirm only event metadata/status is logged and that no message body, token, API key, or serialized captured object is emitted. Confirm no new network client or IPC field was added.

- [ ] **Step 3: Install and configure the device build**

Install `app/build/outputs/apk/debug/app-debug.apk` and the already-compatible `xposed/build/outputs/apk/debug/xposed-debug.apk` as needed. Keep realtime disabled initially. Confirm the Phase 4 token/Hook path still reports `handshake accepted=true` and `IPC submit accepted=true` when a test message is sent.

- [ ] **Step 4: Verify opt-in realtime behavior on device**

Grant overlay permission, enable realtime through the consent dialog, start the floating assistant, and send a plain-text test message from WeChat File Transfer Assistant. Verify the filtered log has no body/token and the floating surface reaches a result state. With API Key unset, verify the local fallback path completes.

- [ ] **Step 5: Verify debounce, disable, and recovery**

Send two plain-text test messages in the same conversation within the quiet window and verify one analysis completion. Disable realtime and send another message; verify IPC still accepts it but no realtime analysis state is emitted. Re-enable and confirm a later message recovers without reinstalling.

- [ ] **Step 6: Update documentation and ledger**

Document exact user controls, the fact that realtime mode is opt-in, the 700ms merge behavior, local fallback when no API Key is configured, and rollback by disabling/stopping the assistant. Record only safe metadata and command results in the ledger.

- [ ] **Step 7: Commit documentation and final verification**

```bash
git add README.md docs/superpowers/sdd/2026-09-21-jev-phase4-wechat-hook-plans/progress.md
git commit -m "docs: document phase 5 realtime assistant"
```

Then rerun the complete automated suite from Step 1 and capture the final `BUILD SUCCESSFUL` output before reporting the phase complete.
