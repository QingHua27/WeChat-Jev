# Jev 微信聊天内嵌分析结果设计

**日期：** 2026-09-22  
**目标版本：** 微信 8.0.72（versionCode 3085）  
**目标：** 将 Jev 分析结果作为微信当前聊天 View 层级中的只读分析行显示在对应消息下方，不使用 `WindowManager` 悬浮卡片，也不写入或发送微信消息。

## 1. 用户可见结果

当用户主动开启实时分析、完成配对并进入支持的微信聊天窗口时：

1. 微信消息仍由微信自己渲染，Jev 不创建或修改聊天数据。
2. 主 App 在自己的进程完成上下文收集、分析、历史保存和结果脱敏。
3. 分析结果通过已认证 IPC 回传到微信进程。
4. Xposed 模块在当前聊天 Fragment 的消息列表同级 ViewGroup 中插入一个 Jev 只读卡片层。
5. 卡片定位到匹配消息气泡的下方，滚动、重排或切换聊天时跟随/隐藏。

卡片展示与参考图保持同一信息层级：`Jev` 标识、情绪、意图概率、风险等级和建议动作。卡片不是一条真实微信消息，没有发送、转发、回复或写入聊天记录的入口。

## 2. 当前问题根因

现有 `AndroidInlineOverlayHost` 使用 `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`，因此卡片属于 Jev 的独立系统窗口。即使它根据无障碍节点计算了与气泡相近的坐标，它也不属于微信聊天 View 树，切换聊天、窗口过渡和非微信事件都可能导致短暂残留或显示在错误页面。

这次改造的关键不是继续修正悬浮窗坐标，而是把展示宿主移动到微信进程的当前聊天 View 层级；分析仍必须留在主 App 进程。

## 3. 推荐架构

```text
微信消息 Hook（Xposed）
    -> 已认证 Messenger IPC：CapturedMessage
    -> 主 App MessageCaptureCoordinator
    -> RealtimeAnalysisCoordinator debounce + Jev 分析 + 加密历史
    -> IpcAnalysisResult（无原文）
    -> 已认证 Messenger IPC：MSG_ANALYSIS_RESULT
    -> Xposed EmbeddedChatCardHost
    -> ChattingUIFragment 当前 View 树
       └─ MMChattingListView 同级 ViewGroup 的 Jev 卡片层
```

### 3.1 进程职责

主 App 进程负责：

- 读取已授权的消息事件并组成上下文；
- 调用 Jev/API 和保存加密历史；
- 生成只包含展示字段的 `IpcAnalysisResult`；
- 在实时分析停止、配对失效或 IPC 断开时清理结果。

Xposed/微信进程只负责：

- 抓取已经通过现有版本门控的消息事件；
- 建立认证 IPC 并声明 `embedded_chat_card` 能力；
- 接收结构化分析字段并渲染 View；
- 在聊天 Fragment、消息列表布局和滚动变化时更新或移除卡片。

Xposed/微信进程不得调用 AI/API、访问主 App 数据库、保存 API Key/Jev Key，或把聊天原文写入日志。

### 3.2 IPC 结果协议

在现有协议上增加 `MSG_ANALYSIS_RESULT`。客户端 Hello 声明能力，服务端只在握手成功且客户端声明 `embedded_chat_card` 时回传结果。

结果对象只包含：

- `messageId`：现有微信消息稳定 ID；
- `conversationHash`：主 App 内计算的不可逆会话哈希；
- `textHash`：消息正文的不可逆哈希，用于微信 View 树定位；
- `isOutgoing`：消息方向；
- `emotion`；
- `intents`：意图名称和概率；
- `riskLevel`；
- `suggestion`；
- 可选的 `historyId` 仅用于后续打开主 App 详情。

不回传聊天原文、会话原文、配对令牌、API Key 或完整 `Conversation`。

结果按 message ID 去重；新结果覆盖同一消息 ID 的旧结果。清理消息携带 `messageId` 或会话失效标记，不依赖微信发送删除消息。

### 3.3 微信 View 注入边界

仅对微信 8.0.72 / versionCode 3085 启用。现有分析索引确认：

- `com.tencent.mm.ui.chatting.ChattingUIFragment` 持有 `com.tencent.mm.ui.chatting.view.MMChattingListView`；
- Fragment 具有 `onCreateView`、`dealContentView`、`onResume`、`onDestroy` 等生命周期入口；
- `MMChattingListView` 提供自定义聊天列表入口和布局回调。

首版不 Hook 微信 adapter 的 `getItemCount`、`onCreateViewHolder` 或 `onBindViewHolder`，避免伪造数据项与微信内部数据模型耦合。首版使用以下方式：

1. 在 `ChattingUIFragment` 创建完成后，从 Fragment View 树按类名找到 `MMChattingListView`；
2. 找到其最近的 `ViewGroup` 宿主或聊天内容根节点；
3. 插入一个 `JevEmbeddedAnalysisCardView` 作为同级只读子 View；
4. 通过匹配可见消息 TextView 的 `textHash` 和消息方向，计算卡片在宿主坐标系中的位置；
5. 监听布局、滚动和 Fragment 生命周期，只更新同一个 View 的布局/可见性，不反复 add/remove，避免闪烁。

如果宿主不是可安全添加子 View 的 `ViewGroup`、找不到唯一消息候选、当前页面不是聊天 Fragment 或 bounds 不合法，卡片保持隐藏。不得猜测屏幕绝对坐标，也不得把 View 添加到微信窗口之外。

### 3.4 消息锚点定位

Xposed UI 宿主在当前聊天 View 树内只读取 TextView 文本到内存，用同一哈希算法生成候选 `textHash`，随后立即丢弃原文。候选还必须满足：

- 可见且 bounds 有效；
- 不属于标题栏、输入框、系统提示或时间分隔节点；
- 方向与结果的 `isOutgoing` 一致；
- 位于当前聊天列表可见区域内。

如果有多个同 hash 候选，优先选择可见区域中更靠下且方向匹配的最新候选；无法建立唯一且稳定的候选时隐藏卡片，避免误贴到其他消息。日志只记录候选数量、哈希和状态码。

## 4. 生命周期

- `onCreateView` / `onResume`：绑定当前聊天 View 树并尝试显示待展示结果；
- `onLayout` / `onScrollChanged`：使用同一个卡片 View 更新位置；
- `onPause` / `onDestroyView` / `onDestroy`：立即移除卡片 View 和监听器；
- 切换会话：旧会话卡片隐藏，直到结果会话哈希与当前会话匹配；
- IPC 断开、实时分析关闭、配对失效：清空结果并移除卡片；
- 不支持版本、注入失败或定位不确定：不显示错误悬浮卡片，主 App 仍可查看分析结果；旧 WindowManager 卡片仅作为显式兼容回退保留，不作为嵌入通道的默认路径。

在支持嵌入通道且注入成功时，现有 `TYPE_APPLICATION_OVERLAY` 分析卡片和悬浮胶囊都不得启动，避免双重显示和“未进入聊天仍有卡片”；前台服务通知仍可保留作为生命周期入口。

## 5. UI 约束

- 使用微信进程可用的原生 `TextView` / `LinearLayout` / `GradientDrawable`，不加载 Compose；
- 卡片为紧凑浅灰圆角面板，宽度受聊天列表可用宽度限制；
- 结构固定为标题、情绪、意图列表、风险和建议，长建议最多显示受限行数；
- 卡片默认不拦截聊天列表触摸，不提供发送/回复按钮；
- 点击卡片只通过安全的主 App Intent 打开 Jev 详情，不改变微信服务生命周期；
- 所有 View 更新在微信主线程完成，分析结果接收和哈希计算不得阻塞主线程。

## 6. 失败和安全策略

- 版本不匹配：不安装微信 UI Hook；
- 类、字段或生命周期入口找不到：记录脱敏失败原因并停用嵌入通道；
- IPC 未认证：丢弃结果，不创建卡片；
- 结果超长或字段非法：拒绝渲染并记录字段级原因；
- View 树异常、宿主被销毁或位置越界：隐藏当前卡片，不影响微信；
- 任何异常都不能调用微信发送 API、修改微信数据库或导致微信 Activity 崩溃。

## 7. 验收标准

### 自动化

- IPC codec 能往返编码/解码分析结果，并拒绝超长或缺少 message ID 的结果；
- IPC 服务只有已认证且声明能力的客户端能收到结果；
- 实时分析完成后发送一次对应 message ID 的结果，停止/禁用后发送清理；
- UI 状态机覆盖未绑定、已绑定、显示、隐藏、销毁，重复布局不创建多个 View；
- 锚点匹配覆盖方向、重复文本、输入框排除、不可见节点、越界和会话不匹配；
- 原有消息 Hook、消息捕获、历史和分析单元测试继续通过。

### 真机

- 微信 8.0.72 中打开“文件传输助手”或其他用户授权聊天，不发送测试消息也不会出现 Jev 卡片；
- 进入已有聊天并收到/捕获消息后，卡片只显示在对应气泡下方，结果直接属于微信聊天内容区域；
- 滚动聊天、切换聊天、返回微信列表、打开输入法时卡片不会漂移、闪烁或留在其他页面；
- 连续消息只保留当前最新结果，不出现多个悬浮卡片；
- 点击卡片打开 Jev 主 App，微信聊天不发送任何内容；
- 离开聊天、关闭实时分析或断开配对后卡片立即消失；
- 不支持版本不崩溃、不显示错误悬浮卡片，主 App 仍能查看已保存分析。
