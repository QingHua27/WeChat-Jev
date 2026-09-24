# Jev 微信聊天内嵌分析结果 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在微信 8.0.72 的当前聊天 View 层级中显示 Jev 只读分析卡片，默认不再使用独立悬浮窗口，也不发送或写入微信消息。

**Architecture:** 主 App 继续独占消息上下文、Jev/API 调用、加密历史和结果生成；新增已认证 IPC 结果回传通道，只回传 message ID、哈希和结构化分析字段。Xposed 模块在 `ChattingUIFragment` 的 `MMChattingListView` 同级 ViewGroup 中维护一个原生 Android 卡片 View，按可见消息节点定位并随生命周期/滚动更新。

**Tech Stack:** Kotlin, Android Views, LibXposed API 101, Messenger IPC, Coroutines/StateFlow, Hilt, JUnit 4, Android instrumentation tests, ADB 真机验收。

**Spec:** `docs/superpowers/specs/2026-09-22-jev-wechat-embedded-analysis-design.md`

## Global Constraints

- 只对微信 8.0.72 / versionCode 3085 安装嵌入 UI Hook；其他版本安全停用。
- Xposed/微信进程不得运行 AI/API、访问主 App 数据库、保存密钥或把聊天原文写入日志。
- 分析结果必须作为微信 View 树中的只读 View，不写入微信消息数据库，不调用微信发送接口。
- 支持嵌入通道启用时，不启动 `TYPE_APPLICATION_OVERLAY` 分析卡片或悬浮胶囊；旧悬浮实现只保留兼容回退路径。
- 锚点匹配失败、重复候选、生命周期不稳定或字段非法时隐藏卡片，不猜坐标、不影响微信。
- 遵循测试先行：每个生产改动前先写一个能表达行为的失败测试，先运行得到失败，再实现最小修复。
- 保护现有用户未提交改动；不执行 reset、checkout、clean 或提交操作，除非用户另行要求。

## 文件与边界

### IPC 合同

- Modify: `jev-ipc-contract/src/main/java/com/jev/relationship/ipc/IpcProtocol.kt` — 新增分析结果消息类型、客户端能力和结果 Bundle keys。
- Modify: `jev-ipc-contract/src/main/java/com/jev/relationship/ipc/IpcModels.kt` — Hello 能力字段和脱敏分析结果模型。
- Modify: `jev-ipc-contract/src/main/java/com/jev/relationship/ipc/IpcCodec.kt` — 结果编码/解码和字段长度限制。
- Create: `jev-ipc-contract/src/test/java/com/jev/relationship/ipc/IpcAnalysisResultCodecTest.kt` — 合同往返与恶意/非法字段测试。

### 主 App 结果回传

- Create: `app/src/main/java/com/jev/relationship/ipc/IpcAnalysisResultBroadcaster.kt` — 管理经过握手认证的 Messenger 目标，只发送脱敏结果。
- Modify: `app/src/main/java/com/jev/relationship/ipc/JevIpcService.kt` — 注册/撤销结果接收端，处理能力声明和结果回传。
- Modify: `app/src/main/java/com/jev/relationship/ipc/IpcSession.kt` — 保存认证能力状态并清理会话。
- Modify: `app/src/main/java/com/jev/relationship/domain/realtime/RealtimeAnalysisCoordinator.kt` — 分析完成后发布结果，停止/禁用时清理。
- Modify: `app/src/main/java/com/jev/relationship/di/AppModule.kt` — 提供单例 broadcaster。
- Create or Modify: `app/src/test/java/com/jev/relationship/ipc/IpcAnalysisResultBroadcasterTest.kt` — 认证目标、发送失败和清理测试。
- Modify: `app/src/test/java/com/jev/relationship/domain/realtime/RealtimeAnalysisCoordinatorTest.kt` — 验证 message ID 对应的结果只发布一次。

### Xposed/微信嵌入渲染

- Create: `xposed/src/main/java/com/jev/relationship/xposed/ui/EmbeddedChatCardHost.kt` — 生命周期、结果状态和单一 View 实例管理。
- Create: `xposed/src/main/java/com/jev/relationship/xposed/ui/WechatChatViewLocator.kt` — 从当前 Fragment View 树找到聊天列表和安全 ViewGroup 宿主。
- Create: `xposed/src/main/java/com/jev/relationship/xposed/ui/WechatMessageAnchorResolver.kt` — 对可见 TextView 做脱敏哈希、方向和 bounds 匹配。
- Create: `xposed/src/main/java/com/jev/relationship/xposed/ui/JevEmbeddedAnalysisCardView.kt` — 原生 View 卡片和紧凑布局。
- Modify: `xposed/src/main/java/com/jev/relationship/xposed/JevXposedModule.kt` — 安装 UI 生命周期 Hook、能力声明和结果路由。
- Modify: `xposed/src/main/java/com/jev/relationship/xposed/ipc/JevIpcClient.kt` — 声明嵌入能力并接收 `MSG_ANALYSIS_RESULT`。
- Create: `xposed/src/test/java/com/jev/relationship/xposed/ui/WechatMessageAnchorResolverTest.kt` — 哈希、方向、排除和重复候选测试。
- Create: `xposed/src/test/java/com/jev/relationship/xposed/ui/EmbeddedChatCardHostTest.kt` — 状态机、View 复用、清理和布局调度测试。
- Create: `xposed/src/test/java/com/jev/relationship/xposed/JevXposedModuleUiActivationTest.kt` — 版本门控和 UI hook 安装条件。

### 主 App 兼容行为

- Modify: `app/src/main/java/com/jev/relationship/service/FloatingAssistantService.kt` — 嵌入通道可用时不启动分析悬浮卡片，离开微信/通道失效时清理旧卡片。
- Modify: `app/src/main/java/com/jev/relationship/service/AndroidInlineOverlayHost.kt` — 保留兼容实现但增加显式通道开关，不能在嵌入模式下自行恢复显示。
- Modify: `app/src/main/java/com/jev/relationship/service/InlineAnalysisOverlayController.kt` — 对非当前微信聊天立即隐藏，避免旧卡片残留。
- Add or Modify: `app/src/test/java/com/jev/relationship/service/InlineAnalysisOverlayControllerTest.kt` — 覆盖嵌入模式禁止显示和窗口切换清理。

### 文档与验收

- Modify: `docs/superpowers/specs/2026-09-22-jev-wechat-embedded-analysis-design.md` only when implementation evidence changes a stated boundary.
- Modify: `docs/superpowers/plans/2026-09-22-jev-wechat-embedded-analysis.md` to mark completed steps and record test/device evidence.
- Modify: relevant `.superpowers/sdd/` progress ledger after device acceptance; do not include raw chat text, tokens, API keys or screenshots containing secrets.

---

### Task 1: Extend the IPC contract for a redacted analysis result

**Files:**
- Modify: `jev-ipc-contract/src/main/java/com/jev/relationship/ipc/IpcProtocol.kt`
- Modify: `jev-ipc-contract/src/main/java/com/jev/relationship/ipc/IpcModels.kt`
- Modify: `jev-ipc-contract/src/main/java/com/jev/relationship/ipc/IpcCodec.kt`
- Test: `jev-ipc-contract/src/test/java/com/jev/relationship/ipc/IpcAnalysisResultCodecTest.kt`

**Interfaces:**
- Consumes: existing `IpcHello`, `AnalysisResult` field semantics, `MAX_TEXT_LENGTH` conventions.
- Produces: `IpcCapabilities.EMBEDDED_CHAT_CARD`, `IpcAnalysisResult`, `IpcIntentProbability`, `IpcProtocol.MSG_ANALYSIS_RESULT`, `IpcCodec.encodeAnalysisResult()` and `decodeAnalysisResult()`.

- [ ] **Step 1: Write the failing codec tests.** Add tests that construct a result with one message ID, hashed conversation/text anchors, emotion, two intent probabilities, risk and suggestion; assert every field survives Bundle encode/decode. Add a test that blank message ID, an oversized emotion, an oversized suggestion, a risk outside `0..10`, and more than the allowed intent count are rejected before encoding.

- [ ] **Step 2: Run the contract test and observe the expected failure.**

  Run: `.\gradlew.bat :jev-ipc-contract:testDebugUnitTest --tests '*IpcAnalysisResultCodecTest'`

  Expected: FAIL because the new result model, capability and codec methods do not exist.

- [ ] **Step 3: Implement the minimum contract.** Add Bundle keys and a separate result message code. Keep the result model in `jev-ipc-contract`; do not import app-only `AnalysisOutput`. Encode only structured presentation fields, clamp/reject invalid values deterministically, and never add a raw text field.

- [ ] **Step 4: Run the focused contract tests.**

  Run: `.\gradlew.bat :jev-ipc-contract:testDebugUnitTest --tests '*IpcAnalysisResultCodecTest'`

  Expected: PASS.

- [ ] **Step 5: Run the existing contract suite.**

  Run: `.\gradlew.bat :jev-ipc-contract:testDebugUnitTest`

  Expected: PASS with no changes to existing authentication and message validation behavior.

### Task 2: Send analysis results only to an authenticated capable client

**Files:**
- Create: `app/src/main/java/com/jev/relationship/ipc/IpcAnalysisResultBroadcaster.kt`
- Modify: `app/src/main/java/com/jev/relationship/ipc/JevIpcService.kt`
- Modify: `app/src/main/java/com/jev/relationship/ipc/IpcSession.kt`
- Modify: `app/src/main/java/com/jev/relationship/di/AppModule.kt`
- Test: `app/src/test/java/com/jev/relationship/ipc/IpcAnalysisResultBroadcasterTest.kt`

**Interfaces:**
- Consumes: Task 1 `IpcAnalysisResult`, capability declaration, existing Messenger handshake.
- Produces: `IpcAnalysisResultBroadcaster.setClient(Messenger)`, `clearClient()`, `publish(IpcAnalysisResult)`, and an authenticated service result path using `MSG_ANALYSIS_RESULT`.

- [ ] **Step 1: Write failing broadcaster tests.** Use a fake target Messenger/handler boundary and verify: no result is sent before `setClient`; a result is sent once after registration; `clearClient` prevents later sends; RemoteException clears the target; and a client without `embedded_chat_card` capability is not registered.

- [ ] **Step 2: Run the focused test.**

  Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*IpcAnalysisResultBroadcasterTest'`

  Expected: FAIL because the broadcaster and capability-aware registration do not exist.

- [ ] **Step 3: Implement the broadcaster and service wiring.** Keep the target Messenger and capability state on the service/main thread. On accepted Hello, register the reply Messenger only when authenticated and capable. Expose `embeddedClientActive: StateFlow<Boolean>` for lifecycle gating. On disconnect, settings disable, unbind, service destroy, or send failure, clear it. Continue sending the existing handshake/submit acknowledgements.

- [ ] **Step 4: Run the focused and existing IPC tests.**

  Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*IpcAnalysisResultBroadcasterTest' --tests '*MessageCaptureCoordinatorTest'`

  Expected: PASS.

### Task 3: Publish the completed analysis through the result channel

**Files:**
- Modify: `app/src/main/java/com/jev/relationship/domain/realtime/RealtimeAnalysisCoordinator.kt`
- Create: `app/src/main/java/com/jev/relationship/ipc/AnalysisOutputIpcMapper.kt` — 将 app-only `AnalysisOutput` 转换成 contract 结果。
- Modify: `app/src/main/java/com/jev/relationship/di/AppModule.kt`
- Modify: `app/src/test/java/com/jev/relationship/domain/realtime/RealtimeAnalysisCoordinatorTest.kt`

**Interfaces:**
- Consumes: existing `CapturedMessage`, `AnalysisOutput`, Task 1 `IpcAnalysisResult`, Task 2 broadcaster.
- Produces: one result for the current analysis generation with the triggering `messageId`, conversation/text hashes, direction and bounded display fields; `clear()` on stop/disable.

- [ ] **Step 1: Add failing coordinator tests.** Assert that an accepted message with a stable ID produces one redacted IPC result after the quiet window and that a stale cancelled generation produces none. Assert that disable/stop clears the broadcaster. Assert that a message without ID still completes the local analysis but does not publish a UI result.

- [ ] **Step 2: Run the focused realtime tests.**

  Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*RealtimeAnalysisCoordinatorTest'`

  Expected: FAIL because the coordinator has no result broadcaster and no result mapping.

- [ ] **Step 3: Implement the result mapping.** In `AnalysisOutputIpcMapper.toIpcResult(...)`, reuse the existing app-side SHA-256 text hashing helper. Map `AnalysisOutput.analysis` to contract fields; preserve intent ordering and risk value; bound display strings before creating the IPC object. Keep existing history persistence and local state behavior intact until the supported embedded client is known to be active.

- [ ] **Step 4: Run all app unit tests.**

  Run: `.\gradlew.bat :app:testDebugUnitTest`

  Expected: PASS.

### Task 4: Receive results and install the exact WeChat 8.0.72 UI hook

**Files:**
- Modify: `xposed/src/main/java/com/jev/relationship/xposed/ipc/JevIpcClient.kt`
- Modify: `xposed/src/main/java/com/jev/relationship/xposed/JevXposedModule.kt`
- Create: `xposed/src/main/java/com/jev/relationship/xposed/ui/EmbeddedChatCardHost.kt`
- Create: `xposed/src/main/java/com/jev/relationship/xposed/ui/WechatChatViewLocator.kt`
- Modify or add: `xposed/src/test/java/com/jev/relationship/xposed/JevXposedModuleTest.kt`

**Interfaces:**
- Consumes: Task 1 result message, Task 2 service capability negotiation, exact WeChat class names from the 8.0.72 analysis index.
- Produces: `JevIpcClient` result callback, `EmbeddedChatCardHost.onAnalysisResult()`, `onFragmentViewCreated()`, `onResume()`, `onPause()`, `onDestroyView()`, and version-gated hook installation.

- [ ] **Step 1: Write failing IPC receive and activation tests.** Verify that a `MSG_ANALYSIS_RESULT` invokes the UI callback, a malformed Bundle is ignored, and `WechatHookActivation`/module activation installs the embedded host only for package `com.tencent.mm`, versionCode 3085, valid pairing and a process with the chat UI. Verify unsupported version does not attempt class lookup or hook installation.

- [ ] **Step 2: Run the focused Xposed tests.**

  Run: `.\gradlew.bat :xposed:testDebugUnitTest --tests '*JevXposedModuleTest' --tests '*WechatHookActivationTest'`

  Expected: FAIL because the callback and UI host do not exist.

- [ ] **Step 3: Implement result receive and lifecycle hook seams.** Add the capability to Hello, decode `MSG_ANALYSIS_RESULT`, and forward it to a host owned by the WeChat process. Hook `ChattingUIFragment.onCreateView` after the original method to attach to the returned/current Fragment root; hook `onResume`, `onPause`, `onDestroyView` and `onDestroy` for visibility and cleanup. Use reflection/class-name lookup only; do not compile against WeChat classes.

- [ ] **Step 4: Implement `WechatChatViewLocator`.** Search a Fragment root for the class name `com.tencent.mm.ui.chatting.view.MMChattingListView`; choose its nearest usable `ViewGroup` ancestor or an explicit chat-content root. Return `null` on ambiguity, detached views, or unsupported host types. Keep all lookups defensive and catch reflection/View traversal exceptions.

- [ ] **Step 5: Run the focused Xposed tests again.**

  Run: `.\gradlew.bat :xposed:testDebugUnitTest --tests '*JevXposedModuleTest' --tests '*WechatHookActivationTest'`

  Expected: PASS.

### Task 5: Render one stable native card inside the current chat hierarchy

**Files:**
- Create: `xposed/src/main/java/com/jev/relationship/xposed/ui/WechatMessageAnchorResolver.kt`
- Create: `xposed/src/main/java/com/jev/relationship/xposed/ui/JevEmbeddedAnalysisCardView.kt`
- Modify: `xposed/src/main/java/com/jev/relationship/xposed/ui/EmbeddedChatCardHost.kt`
- Test: `xposed/src/test/java/com/jev/relationship/xposed/ui/WechatMessageAnchorResolverTest.kt`
- Test: `xposed/src/test/java/com/jev/relationship/xposed/ui/EmbeddedChatCardHostTest.kt`

**Interfaces:**
- Consumes: Task 1 `IpcAnalysisResult`, Task 4 current chat host and lifecycle callbacks.
- Produces: a single reusable card View, candidate bounds, and idempotent `render()`/`hide()` operations that never create a system window.

- [ ] **Step 1: Write failing anchor tests.** Build fake View-tree snapshots with text hashes, bounds, class names and inferred left/right direction. Cover exact hash match, direction match, input/editor/top-bar exclusion, invisible/empty bounds, conversation mismatch, duplicate candidates and no candidate. For duplicate candidates, assert the resolver chooses the lowest visible matching candidate only when the configured ambiguity policy allows it; otherwise returns no placement.

- [ ] **Step 2: Run the focused anchor tests.**

  Run: `.\gradlew.bat :xposed:testDebugUnitTest --tests '*WechatMessageAnchorResolverTest'`

  Expected: FAIL because the resolver and snapshot types do not exist.

- [ ] **Step 3: Implement pure candidate resolution.** Hash TextView text with the same UTF-8 SHA-256 normalization used by the app contract. Never retain candidate text after hashing. Reject non-chat classes and invalid bounds, then calculate a placement below the message inside the chat host coordinate space. If placement would cover the input area or leave the host, try the space above; otherwise return `null`.

- [ ] **Step 4: Write failing host tests.** Verify a result before a Fragment is attached is retained without creating a View; attaching a host creates exactly one card; repeated layout/scroll callbacks reuse that View; a changed result updates it in place; hide/destroy removes it and all listeners; and no call reaches `WindowManager.addView`.

- [ ] **Step 5: Run the host tests and observe failure.**

  Run: `.\gradlew.bat :xposed:testDebugUnitTest --tests '*EmbeddedChatCardHostTest'`

  Expected: FAIL because the native card View and host lifecycle are not implemented.

- [ ] **Step 6: Implement `JevEmbeddedAnalysisCardView`.** Build a compact native `LinearLayout`/`TextView` card with rounded light-gray background and bounded text. Render only `emotion`, intents, risk and suggestion from the contract. Set `importantForAccessibility` to `NO` unless the view is explicitly made clickable for opening Jev; make the card non-focusable and non-touch-consuming by default.

- [ ] **Step 7: Implement host rendering.** Insert one card into the chosen in-Window ViewGroup, update it with `FrameLayout.LayoutParams`/translation in host coordinates, and attach a layout/scroll listener that posts a coalesced render on the main thread. On every render, verify the Fragment is resumed, the result conversation matches the current host, the anchor is visible and the card remains inside the chat content area. Hide instead of removing during ordinary scroll; remove only on lifecycle destruction or result clear.

- [ ] **Step 8: Run the focused Xposed tests.**

  Run: `.\gradlew.bat :xposed:testDebugUnitTest --tests '*WechatMessageAnchorResolverTest' --tests '*EmbeddedChatCardHostTest'`

  Expected: PASS.

### Task 6: Make the embedded path exclusive and prevent stale external cards

**Files:**
- Modify: `app/src/main/java/com/jev/relationship/service/FloatingAssistantService.kt`
- Modify: `app/src/main/java/com/jev/relationship/service/AndroidInlineOverlayHost.kt`
- Modify: `app/src/main/java/com/jev/relationship/service/InlineAnalysisOverlayController.kt`
- Modify: `app/src/test/java/com/jev/relationship/service/InlineAnalysisOverlayControllerTest.kt`

**Interfaces:**
- Consumes: embedded-capability status from IPC/integration settings and existing overlay lifecycle.
- Produces: no `TYPE_APPLICATION_OVERLAY` analysis card while the capable embedded client is active; immediate clearing when the user leaves the current chat or the channel disconnects.

- [ ] **Step 1: Add failing lifecycle tests.** Assert that `embeddedChatCardActive=true` prevents both pill and card-host start, that an IPC disconnect removes any existing overlay card, and that a non-WeChat accessibility package or stopped service hides the old host immediately rather than waiting for its grace timer.

- [ ] **Step 2: Run the focused service tests.**

  Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*InlineAnalysisOverlayControllerTest'`

  Expected: FAIL because the controller has no embedded-channel gate and the old grace behavior remains.

- [ ] **Step 3: Implement the gate and cleanup.** Treat the authenticated embedded client as the preferred Assistant Surface. While the embedded channel is active, do not call `WindowManager.addView` for either the analysis card or the floating pill; keep only the foreground-service notification. Stop/remove any old overlay card when the channel becomes active and do not recreate it from stale `InlineCardStore` state. On loss of capability, leave the old overlay host disabled by default and expose the main App route unless an explicit compatibility fallback setting exists.

- [ ] **Step 4: Run app tests and lint.**

  Run: `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug`

  Expected: PASS.

### Task 7: Verify device behavior and record redacted evidence

**Files:**
- Modify: `docs/superpowers/plans/2026-09-22-jev-wechat-embedded-analysis.md` — check completed steps and record commands/results.
- Modify: relevant `.superpowers/sdd/` progress ledger — record only hashes/status/version and no raw transcript.

- [ ] **Step 1: Run the complete automated suite.**

  Run: `.\gradlew.bat :jev-ipc-contract:testDebugUnitTest :app:testDebugUnitTest :xposed:testDebugUnitTest :app:lintDebug :xposed:lintDebug :app:assembleDebug :xposed:assembleDebug`

  Expected: `BUILD SUCCESSFUL` with all existing and new unit/lint/assemble checks passing.

- [ ] **Step 2: Install without clearing user settings.** Install the rebuilt app and Xposed APKs on the already paired test device using `adb -s 933e802 install -r`; do not print or copy pairing tokens. Restart WeChat only after the APK install succeeds.

- [ ] **Step 3: Verify the no-floating-card baseline.** With Jev enabled but no active chat, confirm no Jev card is visible in the WeChat conversation list, settings, launcher or another app. Enter an authorized chat and confirm the card View is absent until a matching analysis result exists.

- [ ] **Step 4: Verify embedded rendering.** In the authorized chat, trigger one safe test message through the existing user-controlled flow, wait for analysis, and confirm one compact Jev card is inside the message list directly below the matching bubble. Use `dumpsys window windows` only to confirm no Jev analysis `TYPE_APPLICATION_OVERLAY` window is present.

- [ ] **Step 5: Verify lifecycle and scrolling.** Scroll the target bubble out and back into view, switch to another chat, press back to the chat list, open the input method, stop/restart the assistant and disconnect pairing. Confirm the card hides/removes without flicker, duplicate cards or messages sent by Jev.

- [ ] **Step 6: Run privacy and regression scans.** Search changed paths for raw text/token/API-key logging, run existing message-hook tests, and ensure all new logs contain only package/version/status/hash/enum values. Record redacted evidence in the ledger.

## Review Checkpoints

- After Task 1, review the IPC contract before connecting app and Xposed code.
- After Task 4, verify the 8.0.72 lifecycle hook on-device before polishing card layout.
- After Task 5, verify that one reusable View follows scroll without any WindowManager call.
- Before completion, run the complete suite and the device acceptance checklist; do not claim success based only on compilation.

## Execution evidence (2026-09-22)

- Tasks 1–5 implemented with contract, app IPC, realtime publishing, Xposed result routing, exact-version UI hook, locator, anchor resolver, native card, and host lifecycle tests.
- Task 6 implemented as an embedded-only default: `FloatingAssistantService` now starts only the foreground runtime and creates no `WindowManager` window; the overlay permission and stale floating-card path were removed from the user-facing flow.
- Automated verification passed: `:jev-ipc-contract:testDebugUnitTest`, `:app:testDebugUnitTest`, `:xposed:testDebugUnitTest`, `:app:lintDebug`, `:xposed:lintDebug`, `:app:assembleDebug`, and `:xposed:assembleDebug`.
- Device `933e802`: both APKs installed with `adb install -r` without clearing data; WeChat was force-stopped and relaunched. Current window inspection showed only the WeChat activity and system windows, with no Jev `TYPE_APPLICATION_OVERLAY` window.
- Remaining device acceptance: verify the paired WeChat chat produces one embedded card and exercise scroll/chat-switch/disconnect cleanup interactively.
