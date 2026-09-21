# Jev AI Relationship Assistant

Jev is an Android Compose application for structured chat analysis and reply assistance.

## Current MVP

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

For structured analysis, configure the TypeSafe API base URL (normally `https://api.typesafe.ai/v1/`) and the `jev-latest` model. The app calls `POST /systemone` with three typed questions: dominant emotion (`Choice`), main communication intent (`Choice`), and communication risk (`Score`). It composes the returned decisions into the app's `AnalysisResult`, including a bounded 0–10 risk value and a local safety-aware suggestion.

Jev is a decision model and does not generate prose replies. Reply generation is an optional OpenAI-compatible `POST /chat/completions` adapter using the same configured endpoint when a gateway supports both contracts; if it does not, the app falls back to deterministic local reply suggestions.

The TypeSafe request shape is:

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

The app now includes disabled-by-default `JevAccessibilityService` and `FloatingAssistantService` declarations. The accessibility parser only accepts visible text from the configured WeChat package after the user enables the service. The floating shell is read-only and stops when overlay permission is missing; neither service sends messages.

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

## Remaining verification and next phases

The remaining release work includes a live-key Jev contract test, device-backed SQLCipher verification, manual permission-denial recovery, production hardening of accessibility lifecycle UX, and connected instrumentation coverage. Phase 5 will connect accepted real messages to debounced AI analysis and the read-only floating assistant. The overlay remains opt-in.
