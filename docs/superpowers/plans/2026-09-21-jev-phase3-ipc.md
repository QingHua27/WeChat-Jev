# Jev Phase 3 IPC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an independently installable LSPosed/Xposed module that can submit authenticated, bounded simulated WeChat messages to the main Jev App over Binder without exposing AI credentials or implementing real WeChat hooks.

**Architecture:** Add a small Android library `:jev-ipc-contract` containing only stable Parcelable protocol types and validation limits. Add a main-App `Messenger` service backed by an opt-in encrypted pairing-token repository and a pure message capture coordinator. Add a separate `:xposed` application using the modern LibXposed API as `compileOnly`; Phase 3 initializes only for WeChat, connects to the service, and exposes a simulated source while real WeChat method hooks remain a Phase 4 adapter.

**Tech Stack:** Kotlin, Android SDK 35 for the main App and 37 for `:xposed` (required by the current LibXposed service AAR), Gradle 8.13, AndroidX Lifecycle/Coroutines, Hilt/Room in `:app`, `android.os.Messenger` transport, `io.github.libxposed:api:102.0.0` compile-only plus `io.github.libxposed:service:102.0.0` in `:xposed`.

**Spec:** `docs/superpowers/specs/2026-09-21-jev-xposed-ipc-design.md`

## Global Constraints

- The Xposed module never stores or receives Jev/API keys.
- IPC is disabled by default and requires an explicit user-generated pairing token.
- Only `com.tencent.mm` is accepted as the source package.
- No real WeChat Hook, automatic sending, automatic replying, or hidden network behavior is implemented in Phase 3.
- Every new behavior gets a failing unit test before production implementation.
- Chat text is bounded at 4,000 characters per message and 20 messages per batch.
- The existing Accessibility Source remains available and is not replaced.
- Existing uncommitted user work must be preserved; use working-tree verification checkpoints instead of destructive cleanup.

## File Map

- Create `jev-ipc-contract/build.gradle.kts`: Android library configuration.
- Create `jev-ipc-contract/src/main/AndroidManifest.xml`: non-application library manifest.
- Create `jev-ipc-contract/src/main/java/com/jev/relationship/ipc/`: protocol constants, Parcelable DTOs, and pure validator.
- Modify `settings.gradle.kts`: include `:jev-ipc-contract` and `:xposed`.
- Modify `app/build.gradle.kts`: depend on `:jev-ipc-contract`.
- Create `app/src/main/java/com/jev/relationship/ipc/`: app-side auth repository, coordinator, Binder service, and Hilt bindings.
- Modify `app/src/main/AndroidManifest.xml`: declare opt-in exported Binder service and controlled permission metadata.
- Modify `app/src/main/java/com/jev/relationship/data/settings/`: persist encrypted pairing token and integration toggle through the existing settings repository.
- Modify settings UI/ViewModel: expose enable/rotate/disable Xposed integration state without exposing the token in logs.
- Create `xposed/build.gradle.kts`: standalone APK configuration and compile-only modern LibXposed API.
- Create `xposed/src/main/AndroidManifest.xml`: module metadata, exported launcher/config activity if needed, and safe application declaration.
- Create `xposed/src/main/java/com/jev/relationship/xposed/`: module entry, WeChat scope filter, IPC client, and no-op/simulated message source.
- Create `xposed/src/main/resources/META-INF/xposed/`: `java_init.list`, `module.prop`, and `scope.list`.
- Create unit tests under `jev-ipc-contract/src/test`, `app/src/test`, and `xposed/src/test` for the pure boundaries.
- Modify `README.md` and Phase 3 plan checkboxes only after implementation verification.

---

### Task 1: Scaffold the shared contract library

**Files:**
- Create: `jev-ipc-contract/build.gradle.kts`
- Create: `jev-ipc-contract/src/main/AndroidManifest.xml`
- Create: `jev-ipc-contract/src/main/java/com/jev/relationship/ipc/IpcProtocol.kt`
- Create: `jev-ipc-contract/src/main/java/com/jev/relationship/ipc/IpcModels.kt`
- Create: `jev-ipc-contract/src/main/java/com/jev/relationship/ipc/CapturedMessageValidator.kt`
- Test: `jev-ipc-contract/src/test/java/com/jev/relationship/ipc/CapturedMessageValidatorTest.kt`
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces `IpcProtocol.VERSION`, `IpcProtocol.WECHAT_PACKAGE`, `IpcHello`, `CapturedMessage`, `MessageSender`, `RejectReason`, `SubmitResult`, and `CapturedMessageValidator.validate(message)`.
- `CapturedMessageValidator` must be platform-independent except for the shared Android model types and return deterministic rejection reasons.

- [x] **Step 1: Write failing tests** for accepting a bounded WeChat message, rejecting blank/overlong text, rejecting a non-WeChat source, and rejecting a batch larger than 20.
- [x] **Step 2: Run the contract test** and confirm the expected missing-type failure.
- [x] **Step 3: Add the Android library module** with SDK 35, Java/Kotlin 17, `kotlin-parcelize`, and no app/UI dependencies.
- [x] **Step 4: Implement Parcelable protocol models** with explicit enum values and bounded validation constants.
- [x] **Step 5: Run `:jev-ipc-contract:test`** and confirm the tests pass.
- [x] **Step 6: Include the contract in the full lint/build verification** with no new errors.

### Task 2: Add encrypted opt-in pairing settings

**Files:**
- Modify: `app/src/main/java/com/jev/relationship/data/settings/ProviderSettings.kt`
- Modify: `app/src/main/java/com/jev/relationship/data/settings/DataStoreSettingsRepository.kt`
- Modify: `app/src/main/java/com/jev/relationship/data/settings/SettingsRepository.kt`
- Create: `app/src/main/java/com/jev/relationship/data/settings/XposedIntegrationSettings.kt`
- Create: `app/src/test/java/com/jev/relationship/data/settings/XposedIntegrationSettingsTest.kt`
- Modify: `app/src/main/java/com/jev/relationship/di/AppModule.kt`

**Interfaces:**
- Produces `XposedIntegrationSettings(enabled: Boolean, pairingToken: String?)` and repository methods `currentXposedIntegrationSettings()`, `saveXposedIntegrationSettings()`, and `rotateXposedPairingToken()`.
- Reuse the existing `SecretProtector`; the plaintext token must not be persisted in ordinary DataStore preferences.

- [x] **Step 1: Write failing tests** for default-disabled state, generated high-entropy token, disable clearing the active token, and rotation invalidating the previous token.
- [x] **Step 2: Run the targeted settings tests** and confirm the expected missing-type failure.
- [x] **Step 3: Implement token generation and encrypted persistence** using the existing Keystore-backed protector and a cryptographically secure random source.
- [x] **Step 4: Run targeted tests** and confirm they pass without changing existing provider-settings behavior.
- [x] **Step 5: Run the full settings test package** and verify no regression in API-key encryption tests.

### Task 3: Implement pure capture validation and coordination

**Files:**
- Create: `app/src/main/java/com/jev/relationship/ipc/MessageCaptureCoordinator.kt`
- Create: `app/src/main/java/com/jev/relationship/ipc/IpcAuthenticator.kt`
- Create: `app/src/test/java/com/jev/relationship/ipc/IpcAuthenticatorTest.kt`
- Create: `app/src/test/java/com/jev/relationship/ipc/MessageCaptureCoordinatorTest.kt`
- Modify: `app/src/main/java/com/jev/relationship/domain/source/ConversationSource.kt` only if a narrow adapter method is required.

**Interfaces:**
- `IpcAuthenticator.authenticate(hello, settings, callerPackages): AuthResult` validates enabled state, token, protocol, package, and source package.
- `MessageCaptureCoordinator.submit(message): SubmitResult` validates again, keeps a bounded per-conversation window, exposes `StateFlow<ConversationSourceState>`, and exposes the latest `Conversation`.
- The coordinator must not call Jev, Room, Retrofit, or UI code.

- [x] **Step 1: Write failing tests** for disabled auth, wrong token, non-WeChat caller/source, protocol mismatch, successful auth, bounded window ordering, and reset on disconnect.
- [x] **Step 2: Run the targeted tests** and confirm the expected missing-type failures.
- [x] **Step 3: Implement pure auth and coordination** with deterministic results and no raw-message logging.
- [x] **Step 4: Run targeted tests** and confirm all pass.
- [x] **Step 5: Run existing source/surface tests** to verify the old Accessibility Source behavior is unchanged.

### Task 4: Add the main-App Binder service

**Files:**
- Create: `app/src/main/java/com/jev/relationship/ipc/JevIpcService.kt`
- Create: `app/src/main/java/com/jev/relationship/ipc/IpcServiceBinder.kt`
- Create: `app/src/test/java/com/jev/relationship/ipc/IpcServiceBinderTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/jev/relationship/di/AppModule.kt`

**Interfaces:**
- `JevIpcService` owns only the Android service lifecycle and delegates all policy to `IpcAuthenticator` and `MessageCaptureCoordinator`.
- Binder operations: `handshake(IpcHello): HandshakeResult`, `submit(CapturedMessage): SubmitResult`, and `disconnect()`.
- Explicit binding must target the main App service component; implicit intents are rejected.

- [x] **Step 1: Write failing session-policy tests** for handshake-before-submit, successful handshake/submit, post-revoke rejection, oversized input rejection, and disconnect reset.
- [x] **Step 2: Run targeted tests** and confirm they fail before session implementation.
- [x] **Step 3: Implement the Messenger facade** with no blocking network/database work and a bounded input check.
- [x] **Step 4: Declare the service** as exported only because cross-APK binding requires it, with the user-controlled enable check as the authoritative gate.
- [x] **Step 5: Bind the service through Hilt/application dependencies** without injecting Android Service instances into the domain layer.
- [x] **Step 6: Run app unit tests and `:app:lintDebug`**; fix all new manifest or lifecycle issues.

### Task 5: Add settings UI and user controls

**Files:**
- Modify: `app/src/main/java/com/jev/relationship/feature/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/jev/relationship/feature/settings/SettingsScreen.kt`
- Modify: `app/src/test/java/com/jev/relationship/feature/settings/SettingsViewModelTest.kt`
- Modify: `README.md`

**Interfaces:**
- UI displays disabled/enabled/revoked status and a one-time pairing token action.
- UI must never render API keys or pairing tokens in debug logs; the token is revealed only after an explicit user action and can be regenerated.

- [x] **Step 1: Write failing ViewModel tests** for default disabled state, enable/disable, rotate-token invalidation, and error propagation.
- [x] **Step 2: Run the targeted ViewModel tests** and confirm failure for missing state/actions.
- [x] **Step 3: Implement state and Compose controls** using existing settings patterns and clear “不会自动发送消息” copy.
- [x] **Step 4: Run ViewModel tests and Compose compilation**; verify the settings screen compiles.
- [x] **Step 5: Update the README with the Phase 3 setup/limitations.**

### Task 6: Scaffold the standalone modern Xposed module

**Files:**
- Create: `xposed/build.gradle.kts`
- Create: `xposed/proguard-rules.pro`
- Create: `xposed/src/main/AndroidManifest.xml`
- Create: `xposed/src/main/resources/META-INF/xposed/java_init.list`
- Create: `xposed/src/main/resources/META-INF/xposed/module.prop`
- Create: `xposed/src/main/resources/META-INF/xposed/scope.list`
- Create: `xposed/src/main/java/com/jev/relationship/xposed/JevXposedModule.kt`
- Create: `xposed/src/main/java/com/jev/relationship/xposed/WechatScope.kt`
- Create: `xposed/src/main/java/com/jev/relationship/xposed/WechatMessageEventSource.kt`
- Create: `xposed/src/test/java/com/jev/relationship/xposed/WechatScopeTest.kt`
- Modify: `settings.gradle.kts`
- Modify: root `build.gradle.kts` only if a shared plugin declaration is required.

**Interfaces:**
- `WechatScope.isSupported(packageName)` returns true only for `com.tencent.mm`.
- `JevXposedModule` does nothing for non-WeChat packages and installs no real Hook in Phase 3.
- `WechatMessageEventSource` exposes `start(emit)`/`stop()` and a simulated event method used only by tests/debug configuration.

- [x] **Step 1: Write scope tests** for exact WeChat match and rejection of lookalike packages.
- [x] **Step 2: Run `:xposed:test`** after scaffolding and verify the scope tests.
- [x] **Step 3: Add the standalone application module** with `compileOnly("io.github.libxposed:api:102.0.0")`, `implementation("io.github.libxposed:service:102.0.0")`, no Hilt/Compose/Room dependencies, and the shared contract dependency.
- [x] **Step 4: Add modern LibXposed resource metadata** and a minimal `XposedModule` entry that only filters package name.
- [x] **Step 5: Implement the no-op event-source seam** and keep all real WeChat class discovery out of Phase 3.
- [x] **Step 6: Run module unit tests and `:xposed:assembleDebug`**; lint is included in final verification.

### Task 7: Add the module-side Binder client and device simulation

**Files:**
- Create: `xposed/src/main/java/com/jev/relationship/xposed/ipc/JevIpcClient.kt`
- Create: `xposed/src/main/java/com/jev/relationship/xposed/ipc/BackoffPolicy.kt`
- Create: `xposed/src/test/java/com/jev/relationship/xposed/ipc/BackoffPolicyTest.kt`
- Create: `xposed/src/test/java/com/jev/relationship/xposed/ipc/JevIpcClientTest.kt`
- Create: `app/src/androidTest/java/com/jev/relationship/ipc/JevIpcServiceInstrumentedTest.kt`

**Interfaces:**
- `JevIpcClient` performs explicit component binding, one handshake, and bounded submit calls on a background executor.
- `BackoffPolicy` returns deterministic delays capped at 30 seconds and never blocks a Hook callback.

- [x] **Step 1: Write failing client/backoff tests** for handshake ordering, no-send-before-auth, bounded queue, disconnect retry, and capped exponential backoff.
- [x] **Step 2: Run module tests** and confirm the expected missing-type failures.
- [x] **Step 3: Implement the client** with a queue cap, retry cancellation, and redacted diagnostics.
- [ ] **Step 4: Implement an Android instrumentation test** that binds to `JevIpcService`, performs a real handshake using test settings, submits a simulated message, and asserts coordinator state.
- [x] **Step 5: Verify the LSPosed runtime and Binder handshake on the available Android device**; connected instrumentation remains unavailable because the UTP runner did not complete.
- [x] **Step 6: Run all unit tests, compile Android tests, lint, and both APK builds**; App Release required a Gradle daemon stop after a Windows file-lock retry.

### Task 8: Final Phase 3 verification and documentation

**Files:**
- Modify: `docs/superpowers/plans/2026-09-21-jev-phase3-ipc.md`
- Modify: `docs/superpowers/plans/2026-09-21-jev-phase2.md` only for cross-phase status
- Modify: `README.md`

- [x] Run `./gradlew.bat :jev-ipc-contract:test :xposed:test :app:testDebugUnitTest`.
- [x] Run `./gradlew.bat :app:compileDebugAndroidTestKotlin :xposed:lintDebug :app:lintDebug :app:assembleDebug :xposed:assembleDebug :app:assembleRelease`.
- [x] Count unit-test XML results and confirm zero failures/errors for executed unit-test suites.
- [x] Inspect APK outputs and manifest declarations.
- [x] Mark only evidence-backed plan items complete.
- [x] Record the missing connected instrumentation result explicitly; LSPosed runtime/Binder verification completed separately.
