# Jev Xposed 模块与 IPC 设计

**日期：** 2026-09-21  
**状态：** 待确认设计  
**目标阶段：** Phase 3：Xposed 模块接入

## 1. 背景

当前工程已经具备主 App 的 Compose/MVVM/Hilt/Room/Coroutines 架构、结构化 Jev 分析、可选的无障碍读取和只读悬浮窗。现有无障碍方案不是最终的微信消息来源：它只能读取可见 UI 文本，无法稳定区分消息边界、发送者和会话。

本阶段新增一个独立的 LSPosed/Xposed 模块。模块在微信进程中运行，只负责把经过用户授权的聊天事件交给主 App；主 App 继续拥有所有 API Key、分析逻辑、数据库和 UI。真实微信类和方法的定位、Hook 与版本适配属于 Phase 4，不在本阶段实现。

## 2. 目标与非目标

### 目标

- 将 Xposed 代码与主 App 解耦为独立 APK。
- 为主 App 与模块定义版本化、受限的 IPC 数据协议。
- 在主 App 中提供默认关闭、可撤销、带配对令牌的 IPC 接收服务。
- 在模块中实现微信包过滤、连接管理、认证握手和模拟消息发送。
- 保持消息只进入现有的 Conversation Source/分析边界，不让模块直接调用网络或数据库。
- 为 Phase 4 保留稳定的 Hook 适配接口。

### 非目标

- 不在 Phase 3 读取真实微信内部类、数据库或消息方法。
- 不实现自动发送、自动回复、模拟点击或代替用户操作。
- 不把 Jev/API Key 放进模块，也不让模块访问主 App 的密钥存储。
- 不把完整分析结果通过 IPC 回传微信进程。
- 不删除现有 Accessibility Source；它继续作为可选的兼容来源。

## 3. 模块拆分

```text
:app
  主 App、Jev 调用、Room、设置、浮窗、IPC 服务

:jev-ipc-contract
  仅包含跨 APK 共享的 Parcelable DTO、协议常量和字段限制

:xposed
  独立 APK；现代 LibXposed 入口、微信作用域、Hook 适配器、IPC 客户端
```

依赖关系：

```text
:app    -> :jev-ipc-contract
:xposed -> :jev-ipc-contract
:xposed -> compileOnly io.github.libxposed:api:102.0.0
```

`:xposed` 不依赖 Compose、Hilt、Room、Retrofit 或主 App 的实现包。这样可以避免把主 App 的生命周期、网络凭据和 UI 代码加载进微信进程。

现代 LibXposed API 的入口和元数据使用 `XposedModule`、`META-INF/xposed/java_init.list`、`module.prop` 与 `scope.list`；Hook API 版本隔离在 `xposed/runtime`，避免业务协议依赖具体 Hook API。若后续需要兼容旧版 XposedBridge，仅替换该适配层，不改变 IPC 协议。

## 4. IPC 选择

### 4.1 采用：显式绑定的 Binder Service

主 App 声明一个导出的、只接受显式绑定的 `JevIpcService`。Xposed 代码使用微信进程的 `Context` 显式绑定主 App 服务，建立一次连接后通过 Binder 发送消息。

选择理由：

- 适合连续消息流，可以返回明确的接受/拒绝结果。
- 不需要轮询文件或数据库。
- 可以在主 App 关闭集成时主动断开。
- 可以在服务端实现限流、协议版本协商和单连接状态。

### 4.2 不采用的方案

- `ContentProvider`：适合查询和小批量写入，不适合持续事件流，生命周期和错误反馈较弱。
- 广播：容易丢失、缺少背压，也难以表达连接和认证状态。
- 同一 APK 内包含模块：会让 Xposed 代码、主 App 密钥和 UI 形成不必要的耦合，也不符合独立安装/启停模型。
- `signature` 级权限：独立模块和主 App 通常不是同一签名，不能作为唯一认证手段。

## 5. 协议设计

共享模块提供稳定的协议版本和数据上限：

```text
IpcProtocol
  VERSION = 1
  SERVICE_ACTION = com.jev.relationship.action.BIND_IPC
  MAX_TEXT_LENGTH = 4_000
  MAX_BATCH_SIZE = 20
```

核心数据类型：

```text
IpcHello
  protocolVersion: Int
  pairingToken: String
  sourcePackage: String
  moduleVersion: String

CapturedMessage
  conversationId: String
  sender: MessageSender
  text: String
  timestampMs: Long
  sourcePackage: String
  sourceClass: String?
  isOutgoing: Boolean
  messageId: String?

SubmitResult
  accepted: Boolean
  reason: RejectReason?
  serverProtocolVersion: Int
```

`MessageSender` 使用有限枚举（`SELF`、`CONTACT`、`SYSTEM`、`UNKNOWN`），不允许模块自由传入可执行类型或任意 Bundle 类名。所有文本在服务端再次截断、去除空白噪声并校验来源；客户端限制不是安全边界。

服务接口语义：

```text
handshake(hello): HandshakeResult
submit(message): SubmitResult
submitBatch(messages): BatchResult
close(): void
```

Phase 3 先使用 `Messenger`/稳定的 `Parcelable` 消息封装，避免引入 AIDL 编译链；若 Phase 5 需要更高吞吐，再将相同 DTO 映射到 AIDL，业务层接口保持不变。

## 6. 认证与授权

由于 Hook 代码运行在微信进程中，服务端看到的 Binder 调用 UID 通常是微信 UID，而不是模块 APK UID。因此认证采用多层校验，而不是假设能识别模块签名：

1. 主 App 设置中的“启用 Xposed 集成”默认关闭。
2. 主 App 生成高熵随机配对令牌，并通过设置页面展示一次性配对信息。
3. 模块配置中保存令牌；令牌只用于 IPC 握手，不包含任何 Jev/API 凭据。
4. 服务端要求调用包属于 `com.tencent.mm`，且 `sourcePackage` 必须相同。
5. 服务端以常量时间比较令牌，成功握手后只允许该连接提交消息。
6. 用户关闭集成或重新生成令牌后，旧连接和旧令牌立即失效。
7. 服务端限制单连接速率、批量大小、文本长度和并发连接数。

配对令牌属于用户主动授权凭据，不应写入日志、异常消息或分析历史。服务仍保持 `exported=true` 以允许跨 APK 绑定，但只暴露最小 Binder 接口；不提供通用命令执行、文件读取或主 App 内部对象访问能力。

## 7. 主 App 接收链路

```text
JevIpcService
  -> IpcAuthenticator
  -> CapturedMessageValidator
  -> MessageCaptureCoordinator
  -> ConversationSource adapter
  -> bounded/debounced conversation
  -> AnalysisConversationUseCase（Phase 5）
  -> AssistantSurface / FloatingAssistantService
```

Phase 3 中 `MessageCaptureCoordinator` 只做以下工作：

- 接收并验证模拟事件。
- 按 `conversationId` 保存内存中的短窗口。
- 暴露 `StateFlow<ConversationSourceState>` 和最近一条 `Conversation`。
- 在服务重启或认证失效时清空临时缓冲。

Phase 3 不自动触发 AI 请求，也不自动显示分析浮窗。这样可以先验证跨进程数据边界，而不把 IPC 问题与实时分析、网络失败和浮窗生命周期混在一起。

现有 `ConversationSource` 接口需要增加一个 IPC 实现或适配器，但 `AnalysisConversationUseCase` 不直接依赖 `Service`、`Binder` 或 Xposed 类型。

## 8. Xposed 模块边界

模块分成三层：

```text
xposed/entry
  XposedModule 入口、作用域和进程过滤

xposed/runtime
  WechatHookRuntime、版本适配和 Hook 生命周期

xposed/ipc
  主 App 连接、握手、重连、发送队列和失败退避
```

Phase 3 的入口只对 `com.tencent.mm` 生效，并验证模块已启用配置；其他包直接返回，不安装任何 Hook。`WechatHookRuntime` 仅提供测试替身和空实现：

```text
interface WechatMessageEventSource {
    fun start(emit: (CapturedMessage) -> Unit)
    fun stop()
}
```

Phase 4 为不同微信版本增加独立的 Hook adapter，并通过反射/类签名探测选择适配器；找不到稳定入口时报告“不支持当前微信版本”，不能静默读取未知对象或崩溃微信进程。

## 9. 生命周期与失败策略

- 主 App 未运行时：模块可以尝试显式绑定；绑定失败进入指数退避，不阻塞微信线程。
- 微信切换到后台：停止或暂停消息采集，具体策略在 Phase 4 根据消息事件来源确定。
- Binder 断开：清理连接认证状态，保留有限条待发送事件，超过上限丢弃最旧事件并计数。
- 握手失败：不发送聊天文本，等待用户重新配对或配置变更。
- 协议版本不兼容：拒绝连接并返回双方版本，不尝试猜测字段。
- 主 App 分析失败：IPC 层只报告接收成功；分析错误由主 App 自己处理，不能反向影响微信线程。
- 模块异常：所有 Hook 回调必须捕获异常并隔离，不能让异常穿透到微信主线程。

禁止在微信主线程执行 Binder 等待、网络请求、数据库操作或复杂文本处理。发送队列使用有界协程调度器，失败时丢弃/重试策略可观测但不记录原文。

## 10. 隐私与数据策略

- Xposed 模块只传递用户已授权的微信消息字段。
- API Key、数据库密钥和完整关系记忆只存在主 App。
- IPC 日志只记录协议版本、计数、耗时和拒绝原因，不记录消息正文。
- 主 App 的持久化仍遵守现有 SQLCipher 与 Keystore 设计。
- 不自动发送消息；所有回复建议都是展示内容。
- 用户可以随时关闭集成、撤销令牌、禁用 LSPosed 作用域并清空临时缓冲。

## 11. 测试与验收

### `:jev-ipc-contract`

- Parcelable 往返序列化保留字段。
- 协议版本、枚举值和长度上限稳定。
- 空文本、超长文本、非法时间戳和未知来源被拒绝。

### `:app`

- 默认未启用 IPC。
- 正确令牌和微信来源可以握手。
- 错误令牌、非微信来源、超限请求和旧协议被拒绝。
- 令牌轮换使旧连接失效。
- 服务断开时缓冲清空且不会触发自动发送。
- `MessageCaptureCoordinator` 能把事件转换为现有 `Conversation`。

### `:xposed`

- 非微信包不会初始化模块。
- 微信包初始化不会执行真实 Hook。
- 绑定失败不会阻塞 Hook 线程。
- 断线会退避重连且队列有界。
- 模块 APK 能以现代 LibXposed 元数据被识别。

### 集成验证

- 三个模块均可编译。
- 单元测试通过。
- 在无 LSPosed 环境中主 App 仍可正常安装和运行。
- 在测试设备上通过模拟发送器完成一次跨进程握手与消息提交。
- Phase 4 之前，不宣称已经读取真实微信消息。

## 12. 分阶段实现顺序

1. 新增 `:jev-ipc-contract`，先写协议和序列化测试。
2. 在主 App 新增认证仓库、IPC Service、验证器和消息协调器。
3. 在设置页面增加 Xposed 集成开关、配对令牌生成/撤销状态。
4. 新增 `:xposed` 空模块、现代入口、微信作用域和 IPC 客户端。
5. 添加模块侧模拟事件发送器，完成设备上的真实 Binder 验证。
6. 编译、单测、Lint、Debug/Release APK 验证；修复后再进入 Phase 4。

## 13. 风险与决策记录

- **现代 LibXposed API 兼容性：** 当前采用官方现代 API，并将其限制在 `xposed/entry` 与 `xposed/runtime`。若目标设备只支持旧版 Xposed，新增 legacy adapter，不修改 IPC。
- **微信版本变化：** Hook 逻辑必须按版本适配，不能把 Phase 3 的协议验证误当作消息 Hook 完成。
- **跨签名 IPC：** 不使用签名级权限作为唯一边界，采用用户配对令牌、来源校验和最小接口组合。
- **实时分析开销：** Phase 3 不触发 AI；Phase 5 使用去抖、取消旧请求和有界队列控制成本。
- **隐私泄漏：** 原文只在受限内存窗口和主 App 加密数据库中出现，日志与模块配置不得保存 API Key 或分析原文。

## 14. Phase 3 完成定义

当且仅当以下条件全部满足，Phase 3 才算完成：

- `:app`、`:jev-ipc-contract`、`:xposed` 三个模块可编译。
- 主 App 默认关闭并能生成、撤销配对令牌。
- Xposed 模块只声明微信作用域并能连接主 App IPC Service。
- 模拟消息可以跨进程到达 `MessageCaptureCoordinator`。
- 错误来源、令牌、协议和超限请求均被拒绝。
- 单元测试和设备 Binder 验证通过。
- 没有真实微信 Hook、自动发送或隐藏的网络行为。
