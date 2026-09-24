# 微信聊天内 AI 整体分析与连续问答 Implementation Plan

> **For agentic workers:** Follow this plan task by task in this session, keeping each behavior test-first and independently verified.

**Goal:** Add a centered “AI分析” dialog inside WeChat that summarizes the complete latest local chat and supports persisted follow-up questions per conversation.

**Architecture:** The Xposed process owns only the title button, dialog, and authenticated IPC endpoint. The Jev app loads complete WeChat history through `LocalHistoryBroker`, assembles fresh transcript plus saved turns, calls the configured understanding provider, and stores successful exchanges in Room.

**Tech Stack:** Kotlin, Android Views/Dialog, Messenger IPC, Room/SQLCipher, Retrofit, JUnit, Robolectric, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-23-wechat-ai-conversation-assistant-design.md`

## Global Constraints

- Scope remains WeChat 8.0.72 / versionCode 3085.
- The feature is user-triggered and independent of the realtime Jev message-card toggle.
- Every report and follow-up uses a fresh full local text transcript; do not silently truncate or fall back to visible bubbles.
- Keep history keyed by the WeChat internal conversation ID; single and group chats remain isolated.
- Never log chat text, prompt, question, model response, or provider secret.
- Use the configured reply/understanding provider and persist only successful assistant exchanges.

---

### Task 1: Define the assistant IPC contract

**Files:**
- Modify: `jev-ipc-contract/src/main/java/com/jev/relationship/ipc/IpcProtocol.kt`
- Create: `jev-ipc-contract/src/main/java/com/jev/relationship/ipc/ChatAssistantProtocol.kt`
- Test: `jev-ipc-contract/src/test/java/com/jev/relationship/ipc/ChatAssistantProtocolTest.kt`

**Interfaces:**
- `ChatAssistantRequest(requestId, conversationId, title, question?)` encodes/decodes a validated Bundle.
- `ChatAssistantResult(requestId, conversationId, turns, error?)` encodes/decodes the stored transcript and current answer.
- A turn contains role `user` or `assistant`, content, and creation time.

- [x] Write round-trip tests for initial report requests, follow-up requests, successful results, and error results, plus rejection of blank/oversized IDs, unsupported roles, and oversized questions.
- [x] Run the focused contract tests and confirm the new tests fail because the contract is absent.
- [x] Add message IDs, keys, request/result Bundle codecs, and strict field bounds while keeping existing protocol numbers unchanged.
- [x] Rerun the focused contract tests and require them to pass.

### Task 2: Persist per-conversation assistant turns and build model prompts

**Files:**
- Create: `app/src/main/java/com/jev/relationship/data/local/ChatAssistantSessionEntity.kt`
- Create: `app/src/main/java/com/jev/relationship/data/local/ChatAssistantTurnEntity.kt`
- Create: `app/src/main/java/com/jev/relationship/data/local/ChatAssistantDao.kt`
- Create: `app/src/main/java/com/jev/relationship/data/local/RoomChatAssistantStore.kt`
- Create: `app/src/main/java/com/jev/relationship/domain/chatassistant/ChatAssistantStore.kt`
- Create: `app/src/main/java/com/jev/relationship/domain/chatassistant/ChatAssistantPromptBuilder.kt`
- Modify: `app/src/main/java/com/jev/relationship/data/local/JevDatabase.kt`
- Modify: `app/src/main/java/com/jev/relationship/di/AppModule.kt`
- Test: `app/src/test/java/com/jev/relationship/domain/chatassistant/ChatAssistantPromptBuilderTest.kt`
- Test: `app/src/test/java/com/jev/relationship/data/local/ChatAssistantStoreTest.kt`

**Interfaces:**
- `ChatAssistantStore.load(conversationId): ChatAssistantSession?` returns prior turns for one chat.
- `ChatAssistantStore.saveReport(conversationId, title, question, assistantText)` creates/updates the session and stores the automatic report request/answer pair.
- `ChatAssistantStore.appendExchange(conversationId, title, question, answer)` stores the user/assistant pair atomically after a successful response.
- `ChatAssistantPromptBuilder.build(transcript, turns, question, refreshReport)` returns ordered `ChatMessage`s with fresh context separate from durable turns.

- [x] Write prompt tests proving the chronological local-history order is preserved, every record is included, stored turns retain order, and the current question is last.
- [x] Write store tests proving conversation isolation and reopen reuse.
- [x] Run focused app tests and confirm expected failures.
- [x] Add the Room v4 session/turn entities, DAO, store, and migration 3→4 with cascade/index; bind the store in Hilt.
- [x] Rerun focused tests and compile Room/Hilt generated code.

### Task 3: Support multi-turn understanding-provider requests

**Files:**
- Modify: `app/src/main/java/com/jev/relationship/data/remote/OpenAiCompatibleClient.kt`
- Modify: `app/src/main/java/com/jev/relationship/data/remote/OpenAiCompatibleApi.kt`
- Test: `app/src/test/java/com/jev/relationship/data/remote/ChatAssistantCompletionTest.kt`

**Interfaces:**
- Add `complete(settings, messages: List<ChatMessage>): String`; retain the existing system/user overload as a delegating compatibility API.

- [x] Write a request test that checks the configured model and exact role/content order in the serialized chat completion body.
- [x] Run the focused test and observe failure before implementation.
- [x] Implement multi-message completion and verify the reply still rejects empty responses.
- [x] Rerun the focused test.

### Task 4: Coordinate latest-history analysis and follow-up requests

**Files:**
- Create: `app/src/main/java/com/jev/relationship/domain/chatassistant/ChatAssistantCoordinator.kt`
- Modify: `app/src/main/java/com/jev/relationship/di/AppModule.kt`
- Modify: `app/src/main/java/com/jev/relationship/ipc/JevIpcService.kt`
- Test: `app/src/test/java/com/jev/relationship/domain/chatassistant/ChatAssistantCoordinatorTest.kt`
- Test: `app/src/test/java/com/jev/relationship/ipc/AuthenticatedIpcClientPolicyTest.kt`

**Interfaces:**
- `ChatAssistantCoordinator.handle(request): ChatAssistantResult` reads latest `LocalConversationHistory.load(conversationId, title)`, loads saved turns, calls the configured provider, and saves only successful outputs.
- `JevIpcService` accepts assistant requests only from its authenticated paired WeChat Messenger client and replies with the correlated request ID.

- [x] Write coordinator tests for fresh history on report and follow-up, previous-turn reuse, provider selection, chat isolation, and no partial saved exchange after provider failure.
- [x] Write IPC access-policy tests for authenticated binder and WeChat caller requirements.
- [x] Run focused tests and observe the expected failures.
- [x] Implement report/follow-up prompts with evidence-versus-inference instructions, wire provider/store/history dependencies, and handle errors without fallback to stale context.
- [x] Extend the authenticated service dispatch and return readable errors without logging private content.
- [x] Persist automatic report requests with their answers, cancel closed/replaced in-flight jobs, and return correlated errors for malformed requests with valid identifiers.
- [x] Rerun focused tests and app lint/unit tests.

### Task 5: Route assistant IPC to the WeChat dialog

**Files:**
- Modify: `xposed/src/main/java/com/jev/relationship/xposed/ipc/JevIpcClient.kt`
- Modify: `xposed/src/main/java/com/jev/relationship/xposed/ipc/IpcResponseRouter.kt`
- Test: `xposed/src/test/java/com/jev/relationship/xposed/ipc/IpcResponseRouterTest.kt`

**Interfaces:**
- `JevIpcClient.requestChatAssistant(request, callback): Boolean` sends only when authenticated and invokes the callback with matching request results.
- `IpcResponseRouter` decodes assistant results and delivers them to the registered request callback.

- [x] Write router tests for correct callback routing, unknown IDs, and service disconnect.
- [x] Run focused Xposed tests and observe expected failures.
- [x] Add bounded pending callback tracking, timeout/disconnect cleanup, authenticated sending, and response routing.
- [x] Rerun focused tests and Xposed lint/unit tests.
- [x] Verify cancellation stops an in-flight report before persistence and the client drops responses after cancel.

### Task 6: Add the title button and centered in-WeChat assistant dialog

**Files:**
- Create: `xposed/src/main/java/com/jev/relationship/xposed/ui/ChatAssistantDialog.kt`
- Modify: `xposed/src/main/java/com/jev/relationship/xposed/ui/WechatChatUiHook.kt`
- Modify: `xposed/src/main/java/com/jev/relationship/xposed/JevXposedModule.kt`
- Test: `xposed/src/test/java/com/jev/relationship/xposed/ui/ChatAssistantDialogTest.kt`
- Test: `xposed/src/test/java/com/jev/relationship/xposed/ui/WechatChatUiHookTest.kt`

**Interfaces:**
- `ChatAssistantDialog.open()` renders the latest report/history, privacy notice, retry state, scrollable turns, multiline composer, and close action.
- The title bar shows an independent “AI分析” action adjacent to the Jev realtime switch; all responses are checked against the currently open conversation ID.

- [x] Write Robolectric tests for button presence independent of realtime toggle, centered dialog rendering, turns, loading/error/retry, follow-up send, and dismissal.
- [x] Run focused tests and observe expected failures.
- [x] Implement the native centered rounded dialog with current WeChat Activity context, fresh report request on each open, fresh-history follow-up requests, and request cleanup on chat/activity changes.
- [x] Rerun focused tests and Xposed lint/unit tests.

### Task 7: Full regression and final verification

**Files:**
- Test: existing app/Xposed/IPC test suites.

- [x] Run `./gradlew :jev-ipc-contract:testDebugUnitTest :app:testDebugUnitTest :xposed:testDebugUnitTest` (267 tests, zero failures/errors).
- [x] Run `./gradlew :app:lintDebug :xposed:lintDebug :app:assembleDebug :xposed:assembleDebug`.
- [x] Inspect the implementation and confirm the new transcript reuses `ChatTextPolicy`, while existing card hooks and store are unchanged by this feature.
- [x] Check connected phone and APK signing identity. The installed Jev/Xposed apps were signed with a different key than these debug APKs, so Android cannot update them in place; uninstalling would erase the phone's existing local configuration and data. No APK was installed and no chat was opened or sent to the model. Device UI acceptance remains pending an APK signed with the existing key, or a user-approved backup/reinstall plan.
