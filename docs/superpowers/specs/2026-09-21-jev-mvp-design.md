# Jev AI Relationship Assistant — Phase 1 MVP Design

## Goal

Build a publishable foundation for an Android AI relationship assistant. The first release closes the core loop: a user pastes or imports a chat transcript, explicitly approves sending it to a configured AI endpoint, receives structured Jev intent/emotion/risk analysis, and gets multiple reply suggestions.

## Scope

### Included in Phase 1

- Kotlin Android application using Jetpack Compose, MVVM, Hilt, Retrofit, Room, DataStore, and Coroutines.
- Manual transcript input through multiline text and `.txt` import.
- Explicit privacy confirmation before any network request.
- Configurable AI providers with a Jev analyzer contract and a reply-generation contract.
- Fake providers for deterministic local development and tests.
- Remote provider adapter with an OpenAI-compatible JSON boundary so the endpoint can be configured without changing UI/domain code.
- Structured analysis result: emotion, intent probabilities, risk score, suggestion, and reply variants.
- Local analysis history stored in Room; API settings stored separately in DataStore.
- Loading, empty, validation, offline/failure, and successful result states.
- Foundation interfaces for future contact memory and accessibility/floating assistant input.

### Explicitly deferred

- Reading WeChat through AccessibilityService.
- Floating-window permission and overlay UI.
- Xposed/Root hooks into `com.tencent.mm`.
- Vector search and long-term relationship memory.
- Billing, account sync, and cloud backup.

These are separate product risks and will consume the same `ConversationSource` and `ContactMemory` seams rather than coupling them into the MVP.

## Architecture

```text
Compose screens
      ↓ events/state
AnalyzeViewModel ──→ AnalyzeConversationUseCase
      ↓                    ↓
  UiState         JevAnalyzer + ReplyGenerator
                           ↓
              Fake or OpenAI-compatible remote adapter

AnalyzeConversationUseCase ──→ HistoryRepository ──→ Room
SettingsRepository ──────────→ DataStore
```

The domain layer owns stable models and interfaces. Data adapters translate DTOs and persistence entities into those models. The UI only consumes `StateFlow<AnalyzeUiState>` and never knows the provider or database implementation.

## Domain contracts

```kotlin
interface JevAnalyzer {
    suspend fun analyze(conversation: Conversation): AnalysisResult
}

interface ReplyGenerator {
    suspend fun generate(
        conversation: Conversation,
        analysis: AnalysisResult,
    ): List<ReplySuggestion>
}
```

Confidence values are normalized to `0.0..1.0`; risk is normalized to `0..10`. Invalid remote payloads are rejected at the adapter boundary rather than rendered as misleading output.

## Privacy and security

- No transcript leaves the device before the user confirms the exact action in a dialog.
- Clearing the input clears the in-memory transcript and result state.
- History is local-only in Phase 1 and can be deleted from the history screen.
- API keys are not placed in source, resources, logs, or Room rows. Settings are isolated behind `SettingsRepository`; secure storage is an explicit hardening task before production release.
- Remote errors are shown without echoing the transcript or secret key.
- The app must make provider URL and data-sharing status visible in settings.

## UI design

Operating mode: `create`.

Visual posture: calm guidance with premium trust. The input screen has one dominant action, a roomy multiline transcript surface, and a short privacy explanation. The result screen leads with the primary intent and risk level, then provides supporting emotion/suggestion detail and reply cards. Colors communicate risk state rather than decorate the whole canvas. All content is scrollable and supports long strings, keyboard insets, dark theme, and narrow widths.

## Failure handling

- Blank input: inline validation, no network call.
- User cancels privacy confirmation: return to input with no side effect.
- Network/provider error: retain transcript locally in the current state, show retry, and do not create a misleading history entry.
- Malformed provider response: show a safe parse error and log only a redacted technical message.
- Local database failure: show the result in memory and surface a non-blocking history warning.

## Acceptance criteria for Phase 1

1. A fresh install opens to a usable transcript input screen.
2. Empty input cannot start analysis.
3. A valid transcript can be analyzed with the Fake provider and produces a deterministic structured result.
4. The privacy confirmation appears before a remote provider call.
5. Result UI renders emotion, intent probabilities, risk, suggestion, and at least three reply styles.
6. Successful results are persisted locally and can be reopened or deleted.
7. Provider settings do not contain hardcoded credentials.
8. Unit tests cover parsing/validation, use-case orchestration, confidence/risk normalization, and failure mapping.
9. The debug APK and test suite build from the repository with the Gradle wrapper.
