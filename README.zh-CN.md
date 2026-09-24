# Jev AI 关系助手

[English](README.md) · [简体中文](README.zh-CN.md)

Jev 是一款 Android 聊天理解助手，通过 LSPosed 在微信聊天界面中展示上下文解析。主应用和 Xposed 模块已合并在同一个 APK 中。

## 适配范围

当前仅适配**微信 8.0.72（versionCode 3085）**的主进程 `com.tencent.mm`。其他微信版本尚未适配和验证，不受支持；模块会在这些版本上保持停用。升级或降级微信后，聊天解析可能无法使用，需为新版本单独开发并测试适配器。

## 功能

- 在对方发送的文本消息下方显示简短解析卡片。自己发送的消息只作为上下文，不会生成卡片；撤回消息和非文本消息不会分析。
- 使用当前聊天的本地文本记录构建上下文。已缓存的结果会在滑动返回时恢复，不必再次请求模型。
- 点击“AI分析”查看整段聊天的理解结果，并基于最新本地上下文继续追问。
- 实时解析默认关闭；阅读说明并同意后才能开启。Jev 不会自动发送消息或回复。
- 可保存和切换多个理解模型预设。API Key 使用 Android Keystore 加密保存在本机。

只有在用户点击“AI分析”或主动开启实时解析后，聊天文本才会发送到设置中指定的模型服务。

## 安装与启用

1. 构建并安装 Jev APK。
2. 在 LSPosed 中启用 Jev 模块，并将作用域设置为微信（`com.tencent.mm`）。
3. Jev 会通过 LSPosed 在本机自动配置模块配对；无需另装 Xposed APK，也无需手动复制桥接令牌。
4. 修改 LSPosed 模块或作用域后，重启微信使设置生效。

微信内嵌功能要求 LSPosed 持续启用，且作用域包含微信。Xposed 聊天接入不需要 Android 无障碍权限。

## 模型配置

在 Jev 设置中填写理解模型的 Base URL、API Key 和模型名称。TypeSafe 服务默认使用 `https://api.typesafe.ai/v1/` 和 `jev-latest`。也可以选配 OpenAI-compatible 模型，用于对话追问和回复建议。

模型 API Key 不会传给本地 Xposed 模块。Jev 不会在日志中记录聊天正文、模型密钥或配对令牌。

## 构建与测试

环境要求：Android SDK 37、Java 17。项目已包含 Gradle Wrapper。

```text
gradlew.bat test
gradlew.bat lint
gradlew.bat :app:assembleDebug
```

生成的单一 APK（包含 LSPosed 模块）位于 `app/build/outputs/apk/debug/app-debug.apk`。

项目包含三个 Gradle 模块：`:app`、`:xposed` 和 `:jev-ipc-contract`。`:xposed` 会作为库合并进 Jev APK，不是需要单独安装的应用。
