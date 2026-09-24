# Jev AI Relationship Assistant

Jev is an Android Compose application for structured chat analysis and reply assistance.

## Compatibility

The current WeChat Xposed integration supports **WeChat 8.0.72 (versionCode 3085) only**, in the main process `com.tencent.mm`. Other WeChat versions have not been adapted or verified and are unsupported; the integration intentionally stays inactive on them. Updating or downgrading WeChat may therefore disable chat capture and embedded analysis until a separate version adapter is added.

当前 Xposed 集成**仅适配微信 8.0.72（versionCode 3085）**。其他微信版本尚未适配、未验证且不支持；模块会在这些版本上保持停用。

## Current MVP

### WeChat reference panels (2026-09-22)

The supported WeChat 8.0.72 integration now displays compact light-gray `Jev:` panels directly below incoming text bubbles. Outgoing messages are retained as preceding context and never trigger or receive analysis panels. Voice, images and call records are not analyzed.

The TypeSafe request batches emotion/intent/risk with a bounded question catalog for memory, whether to answer immediately, trust, communication urgency, current needs, suitable actions and whether tension has been resolved. Panels select the relevant questions for each message. Percentages come from `probabilities` and `noul`, not distribution `confidence`, and are not hardcoded to the reference image. A strong resolved judgment skips additional understanding/reply model calls for that analysis. Unconfigured providers show a labeled local demo.

Both paths rebuild context from the complete local text history for the current WeChat conversation, ending at the analyzed incoming message. Outgoing messages provide context only. Recalled system notices and recalled records are ignored, and a recall clears current cards. The supported custom WeChat `MMNeat7extView` is matched by exact normalized text hash. Same-row avatar positions distinguish wide outgoing bubbles; unmatched text is never attached to an arbitrary row. If local history cannot be read or the chat identity changes, analysis stops without substituting a visible fragment. Disabling analysis or leaving a visible conversation sends an explicit IPC clear command. WeChat's own theme, navigation and composer remain native to the user's phone.

See [reference alignment and verification](docs/reference-parity-progress.md). Build/install both APKs together for the new optional section fields and clear command.

Analysis cards are backed by an encrypted local result cache keyed by WeChat's stable message ID. When a chat opens, retained incoming text messages are backfilled from newest to oldest; cached results are reused and uncached messages are analyzed one at a time. Cards are sent to WeChat as their message rows become visible, and scrolling back restores them from cache without another model request. Outgoing messages supply context only. A compact `Jev` switch sits beside the WeChat chat title's overflow button and enables/disables analysis for that conversation. The supported Xposed path reads the current chat's visible rows and authorized local history inside WeChat, so these cards do not require re-enabling Android Accessibility after a reboot. LSPosed must still have the module enabled and scoped to WeChat, and this adapter is version-gated to WeChat 8.0.72 (3085).

Visible bubble snapshots carry the local message ID from the WeChat row's bound record. This disambiguates repeated text in group chats, gives the cache a persistent key, and lets the card reattach synchronously when WeChat recycles a row. Group history entries with WeChat's sender prefix are matched to the displayed bubble text.

- Paste or import a `.txt` transcript.
- Confirm before any configured remote provider receives the transcript.
- Analyze emotion, intent probabilities, risk, and communication advice.
- Generate gentle, humorous, and serious reply options.
- Save, reopen, and delete local analysis history.
- Encrypt local Room history with SQLCipher and a per-install Keystore-protected passphrase.
- Create and edit user-owned contact relationship memory.
- Select a contact before analysis so bounded memory observations can inform the provider prompt.
- Configure a TypeSafe/Jev provider from the settings screen.
- Configure a separate OpenAI-compatible reply-generation provider.
- Store the provider API key encrypted with Android Keystore-backed AES-GCM.

When no provider is configured, the app uses deterministic offline Fake providers so the complete UI flow can be exercised without credentials.

## Build

The repository includes the Gradle wrapper. With Android SDK 35 and Java 17 installed:

```text
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:lintDebug
gradlew.bat :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Jev provider contract

For structured analysis, configure the TypeSafe API base URL (normally `https://api.typesafe.ai/v1/`) and the `jev-latest` model. The app calls `POST /systemone` with emotion and intent (`Choice`), communication risk (`Score`), and the bounded conversation question catalog described above (`Choice` / `Noul`). It composes the returned decisions into `AnalysisResult`, including a bounded 0–10 risk value and the relevant card sections.

Jev is a decision model and does not generate prose replies. Reply generation is an optional OpenAI-compatible `POST /chat/completions` adapter using the same configured endpoint when a gateway supports both contracts; if it does not, the app falls back to deterministic local reply suggestions.

The following abbreviated request shows the base questions; the current implementation also includes the conversation question catalog:

```json
{
  "state": {"conversation": "...", "relationship_memory": "..."},
  "model": "jev-latest",
  "questions": {
    "emotion": {"type": "choice", "instructions": "...", "criteria": {"calm": "..."}},
    "intent": {"type": "choice", "instructions": "...", "criteria": {"repair": "..."}},
    "risk": {"type": "score", "instructions": "...", "criteria": ["...", "..."]}
  }
}
```

The official endpoint returns typed answers under the same question IDs. The response contract is documented at [TypeSafe AI API reference](https://docs.typesafe.ai/api).

Optional reply content:

```json
{
  "replies": [
    {"tone": "gentle", "text": "我在听"},
    {"tone": "humorous", "text": "被你发现了"},
    {"tone": "serious", "text": "我们认真聊聊"}
  ]
}
```

## Opt-in assistant architecture

The app retains disabled-by-default `JevAccessibilityService` and `FloatingAssistantService` declarations for legacy compatibility. The current embedded-card path uses the paired Xposed module and does not depend on Accessibility. The floating shell is read-only; neither service sends messages.

## Phase 3: LSPosed/Xposed IPC bridge

The repository now contains three Gradle modules:

- `:app`: the main Jev app, encrypted local storage, analysis providers, floating surface, and exported-but-disabled IPC service.
- `:jev-ipc-contract`: versioned `Parcelable` message types and bounded validation shared by both APKs.
- `:xposed`: an independently installable modern LibXposed module. It is scoped only to `com.tencent.mm`, contains no API keys, and installs the version-gated Phase 4 message Hook only after a pairing token is configured.

Build both APKs with:

```text
gradlew.bat :app:assembleDebug :xposed:assembleDebug
```

Phase 3 setup is intentionally opt-in:

1. In the main Jev app, open Settings and generate the Xposed pairing token.
2. Install and enable the `xposed-debug.apk` module in LSPosed for WeChat.
3. Open the module configuration page and save the token. The module writes it through LibXposed Remote Preferences; it does not receive Jev/API keys.
4. Restart WeChat after enabling the module.
5. For bridge validation only, use `安排一次 Phase 3 模拟消息`. The module consumes this test event once per WeChat data state; it is diagnostic-only and is independent from real-message capture.

Phase 3 verifies the protocol, authentication, bounded queue, reconnect policy, and module scope. Automatic sending remains out of scope.

The Phase 3 bridge was verified on a rooted Android 14 realme RMX3800 with WeChat 8.0.72 and LSPosed API 101: the module loaded in WeChat's `:push` process, authenticated to the main app service, and submitted a simulated message accepted by `MessageCaptureCoordinator`. The one-shot test event is consumed on the device and does not repeat for the same runtime marker. Connected instrumentation tests remain unverified because the UTP runner did not complete.

## Phase 4: WeChat 8.0.72 message capture

The real capture path is deliberately narrow and version-isolated:

- Supported package/process: `com.tencent.mm` / `com.tencent.mm` (the WeChat main process only).
- Supported version: WeChat `8.0.72`, versionCode `3085`.
- Hook target: `com.tencent.mm.storage.h9.Cb(com.tencent.mm.storage.f9)` after the original method completes.
- Captured content: ordinary text messages (`type == 1`) with a non-blank conversation ID and body. Images, voice, stickers, and other message types are ignored.
- Direction: WeChat's `isSend` value is mapped to `SELF` or `CONTACT`.
- Transport: normalized messages go through the existing bounded, authenticated `JevIpcClient` IPC path; no direct database access or automatic reply is performed.
- Safety: missing/blank pairing tokens, child processes, unsupported versions, unavailable target classes/methods, reflection errors, and invalid messages leave the Hook inactive or drop only the current event. The bounded message-ID cache prevents duplicate submissions.

The module does not install the real Hook until the user has saved a non-blank pairing token. Logs may contain installation status and sanitized message metadata, but never message bodies, pairing tokens, or full WeChat objects.

Rollback is reversible: disable the module for WeChat in LSPosed or remove the `com.tencent.mm` scope, then restart WeChat. Clearing the pairing token also prevents new IPC connections after the next WeChat restart. The Phase 3 simulation control remains a diagnostic test path and is not required for real capture.

The current supported-version and target-method assumptions are recorded in [the Phase 4 design spec](docs/superpowers/specs/2026-09-21-jev-phase4-wechat-hook-design.md). Adding another WeChat version requires a separate adapter and tests; the existing adapter intentionally stays inert for it.

## Phase 5: opt-in realtime analysis

Realtime analysis is disabled by default. To enable it:

1. Open Settings and turn on `实时分析助手`.
2. Read the consent dialog and choose `确认开启`; cancelling does not persist the setting.
3. Grant overlay permission if prompted, then manually choose `启动悬浮助手`.
4. Keep the Xposed pairing and WeChat scope configured if using real-message capture.

Accepted messages for the same conversation are merged during a 700 ms quiet window and then analyzed once. The read-only result is shown in the shared floating surface and saved to local history. If no Jev API Key is configured, the deterministic local fallback completes the flow without credentials. Realtime mode never starts the foreground service by itself and never sends or auto-replies to messages.

To roll back, turn off `实时分析助手` and/or choose `关闭悬浮助手`; pending work is cancelled and the surface is hidden. Disabling the LSPosed WeChat scope or clearing its pairing token remains the rollback for message capture itself. Message bodies, API keys, and pairing tokens are not written to the realtime logs.

## Remaining verification and next phases

The automated Phase 5 suite passes across the app, IPC contract, and Xposed modules, including app/Xposed lint and both debug APK builds. Final release work still includes a live-key Jev contract test, device-backed SQLCipher verification, manual permission-denial recovery, production hardening of accessibility lifecycle UX, and connected instrumentation coverage. A real-device Phase 5 acceptance run is required when the Android device is connected; it must verify fallback completion, 700 ms coalescing, disable/re-enable recovery, and sanitized logs. The overlay and realtime analysis remain opt-in.
