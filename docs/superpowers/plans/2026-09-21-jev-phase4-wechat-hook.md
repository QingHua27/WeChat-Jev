# Jev Phase 4 WeChat Message Hook Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]` syntax for tracking.

**Goal:** Replace the Phase 3 simulated WeChat message source with a version-gated, fail-safe real-message hook for WeChat 8.0.72, while preserving the existing Binder/IPC contract and keeping WeChat-specific reflection isolated inside `:xposed`.

**Architecture:** The Xposed entry point performs package/process/version gating, creates one version adapter for WeChat 8.0.72, and submits only normalized `CapturedMessage` values through the existing `JevIpcClient`. The adapter hooks `com.tencent.mm.storage.h9.Cb(com.tencent.mm.storage.f9)` after the original database operation, reads the message through the known 8.0.72 accessors, maps it through a pure mapper, validates it with the shared IPC-contract validator, and applies bounded in-memory deduplication before submission. Unsupported versions, unavailable classes/methods, reflection failures, and IPC failures disable capture without affecting WeChat execution.

**Tech Stack:** Kotlin, JUnit 5, LibXposed API 101, Android Context/ClassLoader, existing `:xposed` module, existing `:jev-ipc-contract` models and validator, Gradle Android build and real-device LSPosed verification.

**Spec:** `docs/superpowers/specs/2026-09-21-jev-phase4-wechat-hook-design.md`

## Global Constraints

- Keep all WeChat package names, obfuscated class names, method names, and field/accessor assumptions inside `:xposed`; do not add WeChat dependencies to `:app` or `:jev-ipc-contract`.
- Install the real hook only when package name is `com.tencent.mm`, process name is exactly `com.tencent.mm`, and version is exactly `8.0.72` / versionCode `3085`.
- Hook `com.tencent.mm.storage.h9.Cb(com.tencent.mm.storage.f9)` after `chain.proceed()` so the message is observed only after WeChat accepts the database operation.
- Capture text messages only (`type == 1`); reject blank content and blank conversation IDs before IPC.
- Map `isSend != 0` to `MessageSender.SELF` and `isSend == 0` to `MessageSender.CONTACT`.
- Normalize WeChat timestamps to epoch milliseconds. Treat values below `1_000_000_000_000` as epoch seconds and multiply by `1_000`.
- Use the local message ID when positive; fall back to the server message ID when the local ID is absent. Reject the message if both IDs are non-positive.
- Use source metadata `source = "wechat"` and `sourceClass = "com.tencent.mm.storage.h9#Cb"`; generated message IDs must be stable and version-scoped as `wechat-8.0.72-<id>`.
- Never log message bodies, conversation text, pairing tokens, or full message objects. Hook failures must be logged as class/method/result categories only.
- Keep the deduplication structure bounded. It must not grow with the lifetime of the WeChat process.
- Preserve the Phase 3 simulation flag for diagnostics, but real capture must not depend on simulation mode. A missing pairing token leaves the real capture path disabled.
- Avoid blocking WeChat's message/database path: the after-hook callback performs bounded reflection, pure mapping, validation, deduplication, and the existing non-blocking IPC submission only.
- Do not alter unrelated dirty worktree files. Use path-scoped commits for Phase 4 files only.

---

## Task 1: Add pure hook-gating models and tests

**Files:**

- Create `xposed/src/main/java/com/jev/relationship/xposed/hook/WechatHookGate.kt` for `WechatVersion`, `HookInstallResult`, and `WechatHookGate`.
- Create `xposed/src/test/java/com/jev/relationship/xposed/hook/WechatHookGateTest.kt`.

**Exact API:**

```kotlin
data class WechatVersion(
    val versionName: String,
    val versionCode: Long,
)

enum class HookInstallResult {
    INSTALLED,
    UNSUPPORTED_PACKAGE,
    UNSUPPORTED_PROCESS,
    UNSUPPORTED_VERSION,
    TARGET_CLASS_UNAVAILABLE,
    TARGET_METHOD_UNAVAILABLE,
    ALREADY_INSTALLED,
    FAILED,
}

object WechatHookGate {
    const val PACKAGE_NAME = "com.tencent.mm"
    const val MAIN_PROCESS = "com.tencent.mm"
    const val TARGET_VERSION_NAME = "8.0.72"
    const val TARGET_VERSION_CODE = 3085L

    fun accepts(
        packageName: String?,
        processName: String?,
        version: WechatVersion,
    ): Boolean
}
```

**Steps:**

- [ ] Write tests for the exact supported package/process/version tuple and for each rejected package, child process, version name, and version code. Run `.\\gradlew.bat :xposed:test --tests '*WechatHookGateTest'`; confirm the new tests fail because the production types do not exist.
- [ ] Implement the constants and pure predicate with no Android or Xposed dependency. Return `false` for null package/process values and for any version mismatch.
- [ ] Run the focused test again and then `.\\gradlew.bat :xposed:test`; confirm the gate tests and all existing Xposed tests pass.
- [ ] Commit only the two Task 1 files with message `test(xposed): add WeChat hook gate`.

## Task 2: Add pure 8.0.72 snapshot mapping and validation tests

**Files:**

- Create `xposed/src/main/java/com/jev/relationship/xposed/hook/WechatMessageMapper.kt`.
- Create `xposed/src/test/java/com/jev/relationship/xposed/hook/WechatMessageMapperTest.kt`.

**Exact API:**

```kotlin
data class WechatMessageSnapshot(
    val type: Int,
    val content: String?,
    val talker: String?,
    val isOutgoing: Boolean,
    val createTime: Long,
    val localMessageId: Long,
    val serverMessageId: Long,
)

object WechatMessageMapper {
    fun map(snapshot: WechatMessageSnapshot): CapturedMessage?
}
```

**Steps:**

- [ ] Add red tests covering: text acceptance; non-text rejection; null/blank content rejection; null/blank talker rejection; contact versus self sender mapping; second-to-millisecond timestamp conversion; millisecond timestamp preservation; positive local-ID preference; server-ID fallback; rejection when both IDs are invalid; exact source/sourceClass/message-ID metadata; and preservation of meaningful content whitespace only after outer blank checks.
- [ ] Add a test proving that an over-limit body is not silently truncated by the mapper and is rejected by `CapturedMessageValidator` before submission. Use the existing validator/constants from `:jev-ipc-contract` rather than duplicating the limit.
- [ ] Run `.\\gradlew.bat :xposed:test --tests '*WechatMessageMapperTest'`; confirm failure before implementation.
- [ ] Implement the mapper as a pure function. It must construct the existing shared `CapturedMessage`, use the stable `wechat-8.0.72-<id>` ID format, and leave final size enforcement to `CapturedMessageValidator`.
- [ ] Run the focused mapper tests and the complete `:xposed` test suite. Confirm all pass without loading Android or Xposed classes.
- [ ] Commit only the Task 2 files with message `feat(xposed): map WeChat message snapshots`.

## Task 3: Add bounded message deduplication

**Files:**

- Create `xposed/src/main/java/com/jev/relationship/xposed/hook/WechatMessageDeduplicator.kt`.
- Create `xposed/src/test/java/com/jev/relationship/xposed/hook/WechatMessageDeduplicatorTest.kt`.

**Exact API:**

```kotlin
class WechatMessageDeduplicator(
    private val capacity: Int = IpcProtocol.MAX_BATCH_SIZE * 8,
) {
    fun shouldEmit(messageId: String): Boolean
}
```

**Steps:**

- [ ] Write red tests for first-seen acceptance, repeated-ID rejection, acceptance after eviction, capacity validation, and concurrent calls from multiple hook callbacks. Run `.\\gradlew.bat :xposed:test --tests '*WechatMessageDeduplicatorTest'`; confirm failure because the class does not exist.
- [ ] Implement a synchronized or lock-protected insertion-ordered bounded set. Reject a non-positive capacity during construction. Evict the oldest key when the configured capacity is exceeded.
- [ ] Run the focused tests and complete `:xposed:test`; confirm duplicate IDs do not result in repeated submissions and the collection remains bounded.
- [ ] Commit only the Task 3 files with message `feat(xposed): bound WeChat message deduplication`.

## Task 4: Implement the WeChat 8.0.72 reflective adapter

**Files:**

- Create `xposed/src/main/java/com/jev/relationship/xposed/hook/WechatMessageHook.kt` for the hook abstraction and install result contract.
- Create `xposed/src/main/java/com/jev/relationship/xposed/hook/Wechat072MessageAdapter.kt` for the 8.0.72 class/method lookup, accessor reads, snapshot creation, mapping, validation, and bounded deduplication.
- Create `xposed/src/test/java/com/jev/relationship/xposed/hook/Wechat072MessageAdapterTest.kt` with fake message/storage classes and a fake after-hook installer where practical.

**Exact API:**

```kotlin
interface WechatMessageHook {
    fun install(context: Context, emit: (CapturedMessage) -> Unit): HookInstallResult
    fun uninstall()
}
```

The adapter must expose a small injectable installer/reader seam for tests, while the production constructor uses the current LibXposed `XposedModule` hook API. The seam is not part of `:jev-ipc-contract` and must not leak into `:app`.

**Production behavior:**

- [ ] Resolve `com.tencent.mm.storage.f9` and `com.tencent.mm.storage.h9` through the WeChat `ClassLoader`, without initializing unrelated classes.
- [ ] Resolve `h9.getDeclaredMethod("Cb", f9Class)` and make it accessible only when necessary. If either class or method is missing, return the corresponding non-throwing `HookInstallResult`.
- [ ] Register one after-hook through the current LibXposed API. Call `chain.proceed()` first and retain its result unchanged.
- [ ] Read only the known 8.0.72 accessors from the `f9` argument: `getType()`, `j()`, `O0()`, `C0()`, `getCreateTime()`, `getMsgId()`, and `I0()`.
- [ ] Convert the read values into `WechatMessageSnapshot`, map to `CapturedMessage`, run `CapturedMessageValidator.validate`, check the bounded deduplicator, and call the injected emitter only for valid first-seen messages.
- [ ] Catch reflection, mapping, validation, and emitter failures at the callback boundary. Log only a short category and continue returning the original result.
- [ ] Make install idempotent and make `uninstall()` release the adapter's installed state without assuming a hook-removal API that LibXposed does not provide. The module lifecycle will retain the adapter for the process lifetime.

**Test-first steps:**

- [ ] Write red tests for target class/method lookup, missing class, missing method, after-original ordering, accessor-to-snapshot conversion, non-text filtering, invalid-ID filtering, mapper/validator integration, deduplication, and non-throwing callback behavior. Run `.\\gradlew.bat :xposed:test --tests '*Wechat072MessageAdapterTest'`; confirm failure before implementation.
- [ ] Implement the injectable seam and production LibXposed bridge with the smallest surface necessary for those tests.
- [ ] Run the focused adapter tests, then `.\\gradlew.bat :xposed:test :jev-ipc-contract:test`; confirm all pass.
- [ ] Commit only the Task 4 files with message `feat(xposed): add WeChat 8.0.72 adapter`.

## Task 5: Integrate the adapter into the Xposed entry point

**Files:**

- Modify `xposed/src/main/java/com/jev/relationship/xposed/JevXposedModule.kt`.
- Modify or replace `xposed/src/main/java/com/jev/relationship/xposed/WechatMessageEventSource.kt` only where required to remove the production dependency on the Phase 3 simulated source.
- Add or update focused module tests under `xposed/src/test/java/com/jev/relationship/xposed/` if the existing test seams support them.

**Steps:**

- [ ] Add a testable process/version decision boundary around the existing `Application.attach` flow. Run the relevant focused tests before implementation and record the expected red result if a new seam is required.
- [ ] Keep the existing `Application.attach` hook as the initialization point, but after the app context and `JevIpcClient` are ready, install `Wechat072MessageAdapter` only when the gate accepts the package, process, and package version and a pairing token is configured.
- [ ] Install at most one adapter per process. Child processes must not install the real hook, even though they may continue to initialize the existing IPC client if that is required by the Phase 3 behavior.
- [ ] Have the adapter emitter call the existing client submission path. Do not add a second IPC protocol, direct socket, database write, or app-to-module callback path.
- [ ] Keep the Phase 3 simulation path behind its existing diagnostic preference and marker. The real hook path must operate independently and must not emit a simulated message during normal capture.
- [ ] Ensure unsupported versions and all adapter-install failures produce a bounded diagnostic log and leave WeChat's startup/message path untouched.
- [ ] Run `.\\gradlew.bat :xposed:test :jev-ipc-contract:test :app:testDebugUnitTest`; confirm all unit tests pass.
- [ ] Commit only the Task 5 files with message `feat(xposed): connect real WeChat capture to IPC`.

## Task 6: Update documentation and run build/static verification

**Files:**

- Modify the relevant Phase 3/Phase 4 section in `README.md`.
- Add a concise supported-version and rollback note if the current module documentation does not already contain one.

**Steps:**

- [ ] Document that the current real capture target is WeChat 8.0.72 / versionCode 3085, the hooked method is `h9.Cb(f9)`, only text messages are captured, and unsupported versions are intentionally inert.
- [ ] Document that users must disable the module or remove the WeChat scope to roll back, and that the Phase 3 simulation flag remains diagnostic-only.
- [ ] Run the complete static/unit verification:

  ```powershell
  .\\gradlew.bat :jev-ipc-contract:test :app:testDebugUnitTest :xposed:test
  .\\gradlew.bat :app:lintDebug :xposed:lintDebug :app:assembleDebug :xposed:assembleDebug
  ```

- [ ] Inspect the generated unit-test reports and confirm zero failures/errors. If any connected instrumentation/UTP runner remains incomplete, report it separately instead of treating it as a passing test.
- [ ] Commit only the documentation file with message `docs: document Phase 4 WeChat capture`.

## Task 7: Perform real-device LSPosed acceptance

**Scope:** Use the already configured Android 14 realme RMX3800 device (`933e802`), WeChat package `com.tencent.mm`, LSPosed API 101, and the existing pairing configuration. Do not print or expose the pairing token.

**Steps:**

- [ ] Install the freshly built `xposed` artifact and confirm the module remains enabled and scoped only to `com.tencent.mm`.
- [ ] Force-stop and relaunch WeChat. Capture filtered logs showing module load, accepted package/process/version gate, adapter installation, IPC binding/handshake, and absence of hook exceptions. Verify no log line contains message body, token, or a full message object.
- [ ] Send one ordinary text message from a second account or device to the configured WeChat account. Confirm one corresponding IPC submission reaches `JevIpcService` and is accepted by the existing app-side path.
- [ ] Send or cause a duplicate delivery of the same WeChat message and confirm the bounded deduplicator prevents a second submission.
- [ ] Exercise a non-text message if available and confirm no `CapturedMessage` submission is produced.
- [ ] Disable the module or remove the WeChat scope, relaunch WeChat, and confirm WeChat starts and operates without the hook. Re-enable only if needed for final evidence.
- [ ] Record the final acceptance evidence in the implementation response: supported version, process gate, hook target, one accepted text message, duplicate suppression, non-text filtering, and rollback result.

## Completion Criteria

- [ ] All pure gate, mapper, deduplicator, and adapter tests pass.
- [ ] Existing IPC-contract, app unit, Xposed unit, lint, and debug assembly checks pass.
- [ ] The real hook is inert for unsupported package/process/version combinations.
- [ ] WeChat 8.0.72 text messages reach the existing Jev IPC path exactly once per message ID under normal delivery.
- [ ] No sensitive body/token data appears in logs.
- [ ] A module disable/unscoping rollback leaves WeChat operational.
- [ ] The final response includes exact verification commands and any incomplete instrumentation limitation without overstating success.
