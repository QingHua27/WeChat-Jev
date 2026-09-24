# Jev AI Relationship Assistant

[English](README.md) · [简体中文](README.zh-CN.md)

Jev is an Android companion that adds contextual message analysis to WeChat through LSPosed. It includes a settings app and an Xposed module in a single APK.

## Compatibility

The current WeChat integration supports **WeChat 8.0.72 (versionCode 3085) only**, in the main process `com.tencent.mm`. Other WeChat versions have not been adapted or verified and are unsupported. The module intentionally stays inactive on them. Updating or downgrading WeChat may disable embedded analysis until a separate adapter is implemented and tested.

## Features

- Show a concise analysis card under incoming text messages. Outgoing messages provide context only; recalled and non-text messages are excluded.
- Build analysis context from the current conversation's local text history. Cached results return when scrolling without another model request.
- Open `AI分析` to review the conversation and ask follow-up questions using the latest local context.
- Enable optional realtime analysis after consent. Jev never sends messages or replies automatically.
- Save multiple understanding-model presets. API keys are encrypted with Android Keystore-backed encryption.

Chat text is sent to the configured model only when the user starts `AI分析` or enables realtime analysis. The model provider is configured by the user in Jev settings.

## Install and enable

1. Build and install the Jev APK.
2. Enable Jev in LSPosed and scope it to WeChat (`com.tencent.mm`).
3. The app provisions the local module pairing through LSPosed; there is no separate Xposed APK or manual token copy.
4. Restart WeChat after changing LSPosed module or scope settings.

LSPosed must remain enabled and scoped to WeChat for embedded features to work. Android Accessibility is not required for the Xposed chat integration.

## Model configuration

Configure the understanding-model Base URL, API key, and model name in Jev settings. The built-in TypeSafe provider uses `https://api.typesafe.ai/v1/` and the `jev-latest` model. An optional OpenAI-compatible provider can be configured for conversational follow-up and reply generation.

The local Xposed module does not receive model API keys. Jev does not log chat bodies, model keys, or pairing tokens.

## Build and test

Requirements: Android SDK 37 and Java 17. The repository includes the Gradle wrapper.

```text
gradlew.bat test
gradlew.bat lint
gradlew.bat :app:assembleDebug
```

The single APK, including the LSPosed module, is written to `app/build/outputs/apk/debug/app-debug.apk`.

The project is split into three Gradle modules: `:app`, `:xposed`, and `:jev-ipc-contract`. The Xposed module is a library merged into the Jev APK, not a separate installable app.
