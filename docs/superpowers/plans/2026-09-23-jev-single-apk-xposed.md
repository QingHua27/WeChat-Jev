# Jev Single APK with LSPosed Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Package the Jev host app and its LSPosed module as one APK while retaining WeChat hooks and removing manual pairing-token entry.

**Architecture:** Keep `com.jev.relationship` as the only application ID. Convert `:xposed` to an Android library consumed by `:app`, merge its runtime components and Xposed metadata into the host APK, and use the in-app LSPosed configuration Activity to provision Remote Preferences before the host stores the same token. Keep Messenger authentication and require the user to enable the new Jev module, select WeChat scope, and restart WeChat.

**Tech Stack:** Android Gradle Plugin, Kotlin, Jetpack Compose, Android library/application modules, LibXposed API 101 and Service 101, DataStore, Messenger IPC, Robolectric/JUnit, adb.

**Spec:** `docs/superpowers/specs/2026-09-23-jev-single-apk-xposed-design.md`

## Global Constraints

- Keep the host `applicationId` as `com.jev.relationship`.
- Keep `:xposed` as an Android library and do not produce a second installable APK.
- Keep `META-INF/xposed/module.prop`, `java_init.list`, and `scope.list` in the final APK at their exact paths.
- Keep the Xposed API as `compileOnly`; keep LibXposed Service packaged at runtime.
- Keep the IPC random-token handshake and WeChat caller-package validation.
- Do not log pairing tokens or chat message contents.
- Do not attempt to enable LSPosed or change module scope programmatically.
- Only mark pairing complete after Remote Preferences persistence and host DataStore persistence both succeed.

---

### Task 1: Package the Xposed runtime inside the Jev APK

**Files:**
- Modify: `xposed/build.gradle.kts`
- Modify: `app/build.gradle.kts`
- Modify: `xposed/src/main/AndroidManifest.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Move: `xposed/src/main/resources/META-INF/xposed/module.prop` to `app/src/main/resources/META-INF/xposed/module.prop`
- Move: `xposed/src/main/resources/META-INF/xposed/java_init.list` to `app/src/main/resources/META-INF/xposed/java_init.list`
- Move: `xposed/src/main/resources/META-INF/xposed/scope.list` to `app/src/main/resources/META-INF/xposed/scope.list`
- Modify: `app/proguard-rules.pro`
- Test: `app/src/test/java/com/jev/relationship/xposed/XposedModuleMetadataTest.kt`

**Interfaces:**
- `:app` depends on the `:xposed` Android library.
- The library keeps namespace `com.jev.relationship.xposed`; its provider authority resolves against the consuming app ID.
- The app APK contains the sole launcher Activity plus the Xposed runtime, provider, and non-launcher pairing Activity.

- [x] **Step 1: Add a failing metadata contract test**

Create `XposedModuleMetadataTest` with one test that reads the three classpath resources and asserts exact values:

```kotlin
private fun resource(path: String): String =
    requireNotNull(javaClass.classLoader?.getResourceAsStream(path))
        .bufferedReader().use { it.readText() }

@Test
fun `module metadata targets WeChat and names the Xposed entry`() {
    assertEquals("com.jev.relationship.xposed.JevXposedModule", resource("META-INF/xposed/java_init.list").trim())
    assertEquals("com.tencent.mm", resource("META-INF/xposed/scope.list").trim())
    assertTrue(resource("META-INF/xposed/module.prop").contains("staticScope=true"))
}
```

- [x] **Step 2: Run the test and confirm the app does not yet expose the metadata**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.jev.relationship.xposed.XposedModuleMetadataTest`

Expected: the new test fails because the metadata is still owned by the standalone Xposed application, not the host APK.

- [x] **Step 3: Convert the module and merge runtime components**

Change `:xposed` to `com.android.library`, remove its `applicationId` and launcher intent filter, and add it to `:app` dependencies. Keep its manifest provider and pairing Activity; make the Activity internal and launchable only by the Jev app. Move `META-INF/xposed/*` into the app resources so AGP writes them to the final APK root. Add the module keep/adapt rules to `app/proguard-rules.pro`, and retain a single application label and launcher Activity.

- [x] **Step 4: Run module tests and build the unified APK**

Run: `.\gradlew.bat :xposed:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`

Expected: all tests pass and `app/build/outputs/apk/debug/app-debug.apk` is produced; Gradle has no standalone Xposed APK task after `:xposed` becomes a library.

- [x] **Step 5: Inspect the APK contents and manifest**

Run `jar tf app/build/outputs/apk/debug/app-debug.apk` and verify the three `META-INF/xposed/*` entries. Use `apkanalyzer manifest print app/build/outputs/apk/debug/app-debug.apk` to verify the host package ID, merged Xposed provider, internal pairing Activity, and exactly one launcher Activity.

---

### Task 2: Add a one-shot Remote Preferences provisioning Activity

**Files:**
- Modify: `xposed/src/main/java/com/jev/relationship/xposed/JevXposedConfigActivity.kt`
- Modify: `xposed/src/main/java/com/jev/relationship/xposed/XposedModulePreferences.kt`
- Create: `xposed/src/main/java/com/jev/relationship/xposed/XposedPairingProvisioner.kt`
- Create: `xposed/src/test/java/com/jev/relationship/xposed/XposedPairingProvisionerTest.kt`

**Interfaces:**
- `JevXposedConfigActivity.EXTRA_PAIRING_TOKEN` carries the pending token from Jev Settings.
- `XposedPairingProvisioner.save(preferences: SharedPreferences, token: String): Boolean` persists the token with `commit()` and returns the actual persistence result.
- Activity result is `RESULT_OK` only after the remote preference write succeeds; errors stay visible with a retry action.

- [x] **Step 1: Write tests for remote preference write success and invalid input**

Use Robolectric preferences and assert that `save(preferences, "random-token")` returns true and stores the exact value under `XposedModulePreferences.PAIRING_TOKEN`; assert that `save(preferences, "  ")` returns false and does not change the stored token.

- [x] **Step 2: Run the new tests and confirm the provisioner does not exist**

Run: `.\gradlew.bat :xposed:testDebugUnitTest --tests com.jev.relationship.xposed.XposedPairingProvisionerTest`

Expected: compilation fails because `XposedPairingProvisioner` is not implemented.

- [x] **Step 3: Implement provisioning and Activity result handling**

Implement the small provisioner over `SharedPreferences`. Register with `XposedServiceHelper` once from the host `Application`, because its listener is process-scoped and has no unregister API. In `JevXposedConfigActivity`, receive the token extra, obtain Remote Preferences through that application-scoped bridge, call the provisioner, and return success only after a successful commit. If the service is unavailable or commit fails, show the precise state and allow retry without displaying the token. Remove token text entry and its save/copy workflow; retain only diagnostics needed to retry LSPosed connection.

- [x] **Step 4: Run the Xposed unit tests**

Run: `.\gradlew.bat :xposed:testDebugUnitTest`

Expected: provisioning tests and all existing hook, history, IPC, and UI tests pass.

---

### Task 3: Replace manual token copy with pairing-result flow in Jev Settings

**Files:**
- Modify: `app/src/main/java/com/jev/relationship/data/settings/XposedIntegrationSettings.kt`
- Modify: `app/src/main/java/com/jev/relationship/data/settings/DataStoreXposedIntegrationRepository.kt`
- Modify: `app/src/main/java/com/jev/relationship/feature/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/jev/relationship/feature/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/jev/relationship/JevApp.kt`
- Modify: `app/src/test/java/com/jev/relationship/feature/settings/SettingsViewModelTest.kt`
- Modify: `app/src/test/java/com/jev/relationship/data/settings/XposedIntegrationSettingsTest.kt`

**Interfaces:**
- Add `XposedIntegrationRepository.activateWithPairingToken(token: String)` to persist a successfully provisioned token and enable integration.
- Add `SettingsViewModel.beginXposedPairing(): String`, `completeXposedPairing(token: String)`, and `failXposedPairing(message: String)`.
- Add `pairingInProgress` and `pairingError` to `SettingsUiState`.
- `JevApp` launches `JevXposedConfigActivity` through `ActivityResultContracts.StartActivityForResult`; only an `RESULT_OK` result calls `completeXposedPairing`.

- [x] **Step 1: Add failing ViewModel tests for pair, fail, retry, and disable**

Update `RecordingXposedRepository` to implement `activateWithPairingToken` and expose `savedToken`. Add these assertions:

```kotlin
val token = viewModel.beginXposedPairing()
advanceUntilIdle()
assertTrue(viewModel.uiState.value.pairingInProgress)
assertFalse(viewModel.uiState.value.xposed.enabled)

viewModel.completeXposedPairing(token)
advanceUntilIdle()
assertEquals(token, xposedRepository.savedToken)
assertTrue(viewModel.uiState.value.xposed.enabled)
assertFalse(viewModel.uiState.value.pairingInProgress)
```

Add separate tests asserting `failXposedPairing("LSPosed service unavailable")` leaves the repository unpaired and exposes the message, retry clears the old error and returns a fresh token, and `disableXposedIntegration()` removes the saved token.

- [x] **Step 2: Run the ViewModel tests and confirm the current immediate-token flow violates them**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.jev.relationship.feature.settings.SettingsViewModelTest`

Expected: the new tests fail because `enableXposedIntegration()` currently stores a token before the LSPosed module receives it.

- [x] **Step 3: Implement pending and completed pairing states**

Generate the pending token with `XposedPairingTokenGenerator.generate()` in `beginXposedPairing`. Keep it only in the ViewModel while the Activity is open. Persist and enable through `activateWithPairingToken` only after Activity success. Clear pending state on completion, failure, cancel, and disable. Preserve the current encrypted DataStore storage implementation.

- [x] **Step 4: Replace token UI with one connect action and explicit states**

Change SettingsScreen to show “连接 LSPosed”, “正在连接”, “等待 LSPosed 服务”, “同步失败，重试”, “已同步，重启微信后生效”, and “已连接” states as applicable. Remove token text display, clipboard action, and manual rotation action. Keep “关闭并撤销配对”. Add migration guidance to enable the new module/scope and disable the old module without claiming Jev can toggle LSPosed.

- [x] **Step 5: Wire Jev Settings to the Activity result**

Use `rememberLauncherForActivityResult(StartActivityForResult())`. Start the internal provisioning Activity with `EXTRA_PAIRING_TOKEN`; send `RESULT_OK` to `completeXposedPairing`, and send cancellation/failure to `failXposedPairing`. Ensure a canceled result cannot mark integration enabled.

- [x] **Step 6: Run app tests**

Run: `.\gradlew.bat :app:testDebugUnitTest`

Expected: settings state tests, token generator tests, and all existing app tests pass; no UI offers manual token copying.

---

### Task 4: Verify installation migration and end-to-end behavior

**Files:**
- Modify: `docs/superpowers/specs/2026-09-23-jev-single-apk-xposed-design.md` only if device evidence requires a clarified acceptance detail.
- Verify: `app/build/outputs/apk/debug/app-debug.apk`
- Verify: installed packages and LSPosed module/scope state on the connected Android device.

**Interfaces:**
- Existing `com.jev.relationship` install is an in-place host upgrade.
- New unified LSPosed module identity is the host package `com.jev.relationship`.
- Old standalone package `com.jev.relationship.xposed` is disabled before uninstall.

- [x] **Step 1: Run the complete relevant test suite and build**

Run: `.\gradlew.bat :jev-ipc-contract:testDebugUnitTest :xposed:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`

Expected: all tests pass and the single unified APK builds.

- [x] **Step 2: Install as an upgrade and verify host data remains**

Install the unified APK with `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Open Jev and verify the saved model configuration, existing chat assistant session, and history are still present before changing any LSPosed state.

- [ ] **Step 3: Switch LSPosed to the new module package**

Enable module `com.jev.relationship`, select `com.tencent.mm` scope, disable old module `com.jev.relationship.xposed`, and restart WeChat. Do not uninstall the old APK until the new module has been verified.

- [ ] **Step 4: Complete automatic pairing and verify WeChat behavior**

In Jev Settings tap “连接 LSPosed”; verify success without using the clipboard or entering a token. Restart WeChat if prompted. Confirm one contextual card per target incoming message, the header controls, local-history-backed “AI分析”, and no duplicate cards.

- [ ] **Step 5: Verify failure recovery and close the old installation**

Temporarily test the unavailable-service state by opening Jev pairing while the LSPosed service is unavailable; verify it reports failure and can retry without marking paired. Re-enable the service, pair successfully, then uninstall the disabled `com.jev.relationship.xposed` package after the user-facing migration instructions have been verified.

## Plan Self-Review

- Spec coverage: APK identity and metadata are covered in Task 1; pairing persistence and error states in Tasks 2–3; migration and feature parity in Task 4.
- Data preservation: the host application ID remains unchanged; old module preferences are deliberately replaced with a fresh token for the new module identity.
- Security: pairing remains random and authenticated; the host stores the token only after remote persistence succeeds; logs and UI do not expose it.
- Packaging risk: final APK entries and manifest are inspected directly; library source resources are not assumed to become APK-root metadata automatically.
- Device risk: the plan requires a real install/upgrade and LSPosed scope switch before considering the merge complete.
- Git commits are not listed because the repository has no configured `user.name` or `user.email`; no identity will be invented.
