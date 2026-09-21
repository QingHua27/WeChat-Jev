# Jev Phase 5：实时关系分析编排设计

日期：2026-09-21  
状态：待用户审阅  
范围：Android 主应用中的实时分析编排、历史保存和悬浮助手联动

## 1. 目标与边界

Phase 4 已经能够把 WeChat 8.0.72 的文本消息安全地送入 Jev 主应用。Phase 5 将这条链路接到现有的分析用例和悬浮助手，但仍保持用户可控、只读和可回滚。

目标：

- 用户主动开启实时助手后，接收到一条或一组消息时触发分析；
- 同一会话在短时间内连续到达的消息合并为一次分析，避免逐条调用；
- 分析结果保存到现有 SQLCipher/Room 历史，并发布给悬浮助手；
- 分析失败、权限不足或功能关闭时有明确状态，不影响 WeChat 和 IPC；
- 未配置 API Key 时继续使用现有本地 fallback 分析，不阻塞实时链路。

非目标：

- 不自动发送或代替用户发送微信消息；
- 不修改现有 IPC 数据契约；
- 不在日志中输出消息正文、token、API Key 或完整对象；
- 不在 Phase 5 引入新的云端存储或新的远程服务；
- 不把实时助手默认打开。

## 2. 用户入口与生命周期

设置页增加“实时分析助手”开关，默认关闭。开启前展示一次持续授权说明：打开后，符合条件的微信消息会自动触发分析；若配置远程 Jev/回复接口，相关分析输入会按该接口设置发送；未配置远程接口时只使用本地 fallback。用户确认后才写入开关。无悬浮窗权限时可以保存开关状态并显示 `PermissionRequired`，不会启动分析任务。

实时工作条件同时满足以下条件：

1. 用户已打开实时分析助手；
2. Jev 的 Xposed/IPC 捕获状态为 `Active`；
3. 悬浮窗权限已授予且 `FloatingAssistantService` 正在运行。

关闭开关、停止悬浮服务、IPC 断开或应用进程销毁时，编排器取消待处理 debounce 和分析任务，清理会话级临时状态。关闭实时助手不会删除已保存历史。

## 3. 组件与职责

### 3.1 `MessageCaptureCoordinator`

继续作为 IPC 的唯一接收边界，保留现有验证、会话消息上限和 reset 行为。新增一个只读的 `SharedFlow<CapturedMessage>`：只有通过验证并写入会话缓冲区的消息才发出事件；被拒绝的消息不进入实时分析。

事件流使用有限缓冲，不能因为分析消费者变慢而阻塞 IPC 提交。事件丢失时不重放旧消息，下一条消息仍可触发新的分析。

### 3.2 `RealtimeAnalysisCoordinator`

由 Hilt 以 singleton 提供，作为唯一的“捕获 → 分析 → 历史 → surface”编排器。依赖：

- `MessageCaptureCoordinator` 的事件流和状态；
- 现有 `AnalysisConversationUseCase`；
- 现有 `HistoryRepository`；
- singleton `AssistantSurfaceCoordinator`；
- 实时助手开关/权限状态提供者。

对外暴露：

- `StateFlow<RealtimeAnalysisState>`；
- `start()` / `stop()` 生命周期方法；
- `setEnabled(Boolean)` 或等价的受控开关入口。

它不直接操作 Android Window，也不依赖 Activity；悬浮服务只负责展示同一个 singleton surface 状态。

### 3.3 `AssistantSurfaceCoordinator`

改为 Hilt singleton。`FloatingAssistantService` 注入并观察该实例，不再在 `onCreate()` 内部创建本地 coordinator。这样 IPC 触发的分析结果和悬浮 UI 使用同一份状态。

现有 `Hidden`、`Ready`、`PermissionRequired`、`Visible`、`Error` 状态继续保留。实时编排器只发布结构化的分析结果；surface coordinator 负责映射到展示状态。

## 4. 数据流与并发策略

```text
WeChat hook
    -> JevIpcClient
    -> JevIpcService
    -> MessageCaptureCoordinator.submit()
    -> accepted-message SharedFlow
    -> RealtimeAnalysisCoordinator
       -> per-conversation debounce (~700 ms)
       -> snapshot latest bounded conversation
       -> AnalysisConversationUseCase
       -> HistoryRepository.save()
       -> AssistantSurfaceCoordinator.show()
       -> RealtimeAnalysisState.Completed
```

每个 `conversationId` 维护一个待执行任务。新消息到达时取消该会话的旧任务并重新计时约 700ms；任务只使用取消后重新读取的最新会话快照，避免分析过期内容。不同会话可以并行 debounce，但实际分析使用受控 dispatcher 和有限并发，第一版限制为一个全局分析任务，后续再根据设备负载调整。

分析任务必须响应 coroutine cancellation。新任务开始时旧结果不能覆盖新结果：使用会话 generation/sequence 校验，只有当前 generation 的结果可以写历史和更新 surface。

实时状态建议包含：

- `Disabled`：功能未开启；
- `WaitingForPermission`：需要悬浮窗或其他运行条件；
- `Collecting(conversationId)`：等待 quiet window；
- `Analyzing(conversationId)`：分析进行中；
- `Completed(conversationId, output, historyId)`：结果已发布并保存；
- `Error(conversationId, message)`：本次失败，后续消息仍可重试。

错误不应终止事件收集。历史保存失败时仍可展示分析结果，并在状态中报告保存失败；分析失败时不写入不完整历史。

## 5. 历史与隐私

复用现有 `HistoryRepository.save()`，保存一次完整分析结果，而不是逐条保存原始捕获事件。现有 Room 数据库使用 SQLCipher；Phase 5 不新增明文文件或网络缓存。

会话快照只保留在现有的有界内存缓冲中。关闭、断开 IPC 或服务停止时清空实时临时状态。日志只允许记录事件类型、方向、稳定 ID、会话 hash/长度和状态，不记录正文或认证材料。

实时模式的持续授权来自用户主动打开并确认“实时分析助手”，不是为每条消息重复弹窗。未配置 API Key 时只执行本地 fallback；配置远程 Jev/回复接口后，自动分析仅使用已配置的接口，并在开启说明中明确远程发送范围。不得新增自动上传旁路。回复生成结果只展示，不自动发送。

## 6. 设置与权限行为

- 实时助手开关默认 `false`，存入现有设置 DataStore 的独立 key；
- “启动悬浮助手”继续负责请求/启动前台悬浮服务；
- 开关打开但没有悬浮权限时，显示权限引导，不启动分析；
- 用户关闭悬浮助手时，surface 隐藏并取消待处理分析，但不撤销 Xposed 配对；
- Xposed 配对关闭只停止消息输入，不删除历史，也不影响手动分析页面。

## 7. 测试策略

纯单元测试：

- 捕获 coordinator 只对 accepted 消息发事件；reset 后不再保留旧会话；
- quiet window 合并连续消息，并取消旧 generation；
- 不同会话互不取消；
- 旧分析结果不能覆盖新 generation；
- 关闭、IPC 断开和权限缺失不会调用分析用例；
- 分析失败、历史保存失败仍能进入可恢复状态；
- API Key 未配置时仍走已有 fallback。

集成/Android 测试：

- Hilt 注入的 capture、realtime coordinator、surface coordinator 是同一生命周期 singleton；
- `FloatingAssistantService` 观察到 IPC 触发的 `Visible` 状态；
- 设置开关和权限状态能正确启停编排器。

真实设备验收：

- WeChat 文本消息可被捕获并触发一次分析状态变化；
- 连续快速消息只产生一次 quiet-window 分析；
- 关闭实时开关后消息仍可被 IPC 接收，但不触发分析；
- 全程日志不出现正文、token 或 API Key。

## 8. 回滚与兼容性

Phase 5 不改变 Phase 4 IPC 协议和 Xposed 模块版本要求。若实时编排出现问题，用户可以关闭实时助手开关或停止悬浮服务，恢复为现有手动分析；Xposed 捕获和 IPC 不应被实时分析异常拖垮。

实现顺序应先建立可测试的事件流和 coordinator，再接入 singleton surface，最后接设置和设备验收。任何新增持久化字段都必须先经过 Room migration 评审；第一版优先复用现有历史表，避免无必要 migration。
