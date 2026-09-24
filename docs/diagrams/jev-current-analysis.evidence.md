# 架构图代码依据

依据 2026-09-23 当前工作区源码，不代表已安装 APK 的逐字节审计。云端调用正常路径假定 API 配置有效，且 Jev 未触发停止条件。

- Jev 应用：解析调度：[app/src/main/java/com/jev/relationship/domain/realtime/RealtimeAnalysisCoordinator.kt](D:/Jev/app/src/main/java/com/jev/relationship/domain/realtime/RealtimeAnalysisCoordinator.kt:416)
- Jev 基础判断：[app/src/main/java/com/jev/relationship/data/remote/TypeSafeJevAnalyzer.kt](D:/Jev/app/src/main/java/com/jev/relationship/data/remote/TypeSafeJevAnalyzer.kt:20)
- 理解模型：上下文解释：[app/src/main/java/com/jev/relationship/data/remote/DetailedAnalysisEnricher.kt](D:/Jev/app/src/main/java/com/jev/relationship/data/remote/DetailedAnalysisEnricher.kt:20)
- 理解模型：回复建议：[app/src/main/java/com/jev/relationship/domain/AnalysisConversationUseCase.kt](D:/Jev/app/src/main/java/com/jev/relationship/domain/AnalysisConversationUseCase.kt:33)
- 本机解析结果缓存：[app/src/main/java/com/jev/relationship/domain/realtime/RealtimeAnalysisCoordinator.kt](D:/Jev/app/src/main/java/com/jev/relationship/domain/realtime/RealtimeAnalysisCoordinator.kt:469)
- 微信显示「解析：…」：[xposed/src/main/java/com/jev/relationship/xposed/ui/JevEmbeddedAnalysisCardView.kt](D:/Jev/xposed/src/main/java/com/jev/relationship/xposed/ui/JevEmbeddedAnalysisCardView.kt:32)
- Jev 应用：问答协调：[app/src/main/java/com/jev/relationship/domain/chatassistant/ChatAssistantPromptBuilder.kt](D:/Jev/app/src/main/java/com/jev/relationship/domain/chatassistant/ChatAssistantPromptBuilder.kt:30)
- 理解模型：流式问答：[app/src/main/java/com/jev/relationship/domain/chatassistant/ChatAssistantCoordinator.kt](D:/Jev/app/src/main/java/com/jev/relationship/domain/chatassistant/ChatAssistantCoordinator.kt:64)
- 显示回答并保存会话：[app/src/main/java/com/jev/relationship/domain/chatassistant/ChatAssistantCoordinator.kt](D:/Jev/app/src/main/java/com/jev/relationship/domain/chatassistant/ChatAssistantCoordinator.kt:37)

图中模型节点表示由 Jev 应用依次发起的调用阶段，模型服务之间不直接互调。缓存命中会经 IPC 返回微信；Jev 停止条件为 concern=resolved 且 resolved 概率≥0.9，此时跳过两次理解模型调用。
气泡展示以 detailContextual 为前提；不可用时显示提示。理解模型失败或未配置时可能走本地降级，实际云端调用次数会不同。
AI 分析的结果经 IPC 回到对话框，完成后写入本机会话库；恢复已有会话会跳过请求。