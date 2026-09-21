# Jev Phase 4 微信消息 Hook 设计

**日期：** 2026-09-21  
**目标阶段：** Phase 4：微信消息 Hook  
**适配目标：** WeChat 8.0.72（versionCode 3085）

## 1. 目标

在已经验证的 Phase 3 IPC 边界之上，读取用户明确授权范围内的微信文本消息，并将其转换为现有 `CapturedMessage` 后提交给主 App。Phase 4 只负责可靠的消息捕获和版本隔离，不触发 AI 分析、不显示悬浮窗、不自动发送或回复消息。

本阶段的第一条真实消息路径覆盖：

- 微信主进程中的普通文本消息；
- 一对一会话和群聊的会话标识；
- 自己发送与联系人发送方向；
- 本地消息 ID、服务器消息 ID、消息时间和文本内容；
- Hook 失败、版本不匹配、重复消息和 IPC 暂时不可用时的安全降级。

群聊发送者前缀在本阶段不做猜测性解析。群聊先以微信提供的会话 ID 和原始文本进入主 App，后续阶段再基于实际格式增加发送者解析。

## 2. 已确认的微信 8.0.72 结构

设备安装包信息：

- 包名：`com.tencent.mm`；
- versionName：`8.0.72`；
- versionCode：`3085`；
- LSPosed API：101。

静态 APK 分析确认：

1. `com.tencent.mm.storage.f9` 是消息实体，继承 `sm.b8`。
2. `sm.b8` 包含 `content`、`talker`、`type`、`isSend`、`createTime`、`msgId`、`msgSvrId` 等字段，并提供以下可调用方法：
   - `j(): String`：消息正文；
   - `O0(): String`：会话/话题标识；
   - `getType(): Int`：消息类型；
   - `C0(): Int`：发送方向字段；
   - `getCreateTime(): Long`：微信消息时间；
   - `getMsgId(): Long`：本地消息 ID；
   - `I0(): Long`：服务器消息 ID。
3. `com.tencent.mm.storage.h9` 是 `message` 表对应的消息存储类。`Cb(f9): Long` 调用 `Nb(f9, false)`，其字节码包含 `processAddMsg insert db error` 路径，适合作为消息入库后的观察点。

这些类名和方法只属于 `Wechat072MessageAdapter`，不得泄漏到 IPC 合同、主 App 领域层或通用消息模型中。

## 3. 方案与边界

### 3.1 采用存储层观察点

模块在微信主进程 `com.tencent.mm` 中解析并 Hook：

```text
com.tencent.mm.storage.h9.Cb(com.tencent.mm.storage.f9): long
```

Hook 回调必须先调用原方法，再读取 `f9` 快照。这样消息只有在微信完成入库路径后才进入 Jev，避免把尚未落盘的半成品消息交给主 App。

### 3.2 明确不采用的入口

- 网络同步/协议入口：内部 protobuf 和同步链路更复杂，版本耦合更强，留给后续低延迟优化。
- Chatting UI/RecyclerView：只能捕获当前可见会话，后台消息会丢失，不满足实时助手目标。
- 直接读取微信数据库：绕过微信自己的一致性和权限边界，且加密/分表/生命周期不稳定；Phase 4 不读取数据库文件。

### 3.3 进程与版本门禁

- 只在 `processName == "com.tencent.mm"` 安装真实 Hook；`:push`、`:appbrand*` 等进程不安装。
- 读取 `PackageInfo`，只有 versionCode `3085` 且 versionName `8.0.72` 时选择当前适配器。
- 不匹配时记录不含消息正文的拒绝原因，并保持 IPC 客户端可用但不发送任何真实消息。
- 每个进程最多安装一次 Hook；重复回调不得重复安装。

## 4. 模块内组件

### 4.1 `WechatMessageHook` 接口

版本无关的生命周期接口：

```kotlin
interface WechatMessageHook {
    fun install(context: Context, emit: (CapturedMessage) -> Unit): HookInstallResult
    fun uninstall()
}
```

`HookInstallResult` 只表达 `Installed`、`UnsupportedVersion`、`TargetClassUnavailable`、`TargetMethodUnavailable`、`AlreadyInstalled` 和 `Failed`，不携带正文或敏感令牌。

### 4.2 `Wechat072MessageAdapter`

只负责：

1. 通过微信 `ClassLoader` 解析 `h9`、`f9` 和 `sm.b8`；
2. 找到精确的 `Cb(f9): Long` 方法；
3. 在原方法成功返回后读取 `f9`；
4. 将对象转换为 `WechatMessageSnapshot`；
5. 调用 `WechatMessageMapper`，通过 `emit` 交给 IPC 客户端。

反射访问器必须缓存 `Method`，并在单次消息处理内只读取一次字段。所有反射调用都包在 `runCatching`/等价保护中，异常只记录诊断并丢弃当前事件，不能穿透回微信调用栈。

### 4.3 `WechatMessageMapper`

纯 Kotlin、无 Android/Xposed 依赖的映射器：

```kotlin
data class WechatMessageSnapshot(
    val conversationId: String,
    val text: String,
    val type: Int,
    val isOutgoing: Boolean,
    val createTime: Long,
    val localMessageId: Long,
    val serverMessageId: Long,
)

fun map(snapshot: WechatMessageSnapshot): CapturedMessage?
```

映射规则：

- 只接受普通文本类型 `type == 1`；
- `conversationId` 和 `text` 去除首尾空白，任一为空则丢弃；
- `MessageSender.SELF` 对应 `isOutgoing == true`，否则为 `MessageSender.CONTACT`；
- 时间小于 `1_000_000_000_000` 时按微信秒级时间转换为毫秒，否则按毫秒保留；
- 消息 ID 优先使用正数 `localMessageId`，否则使用正数 `serverMessageId`；两者都无效则丢弃；
- `sourcePackage` 固定为 `com.tencent.mm`；
- `sourceClass` 固定为 `com.tencent.mm.storage.h9#Cb`；
- `messageId` 使用 `wechat-8.0.72-<id>` 格式；
- 正文长度限制继续由共享 `CapturedMessageValidator` 执行，不在适配器中复制第二套上限。

### 4.4 去重与 IPC

适配器不自行维护无界集合。使用有限大小的本地 LRU/环形去重缓存，键为 `messageId`，容量与 IPC 批量上限同量级；缓存仅存在于当前微信进程，进程重启后由主 App 的已有验证/队列策略兜底。去重命中时不发送、不记录正文。

`JevIpcClient` 继续负责握手、重连、队列上限和发送失败退避。Hook 回调不能等待网络或 Binder 结果，提交动作必须异步/非阻塞，避免拖慢微信消息入库线程。

## 5. 安全与隐私

- 模块默认关闭；只有用户在模块配置页保存配对令牌并启用作用域后才发送。
- 仅发送通过协议验证的字段；不发送微信数据库路径、账号凭据、联系人完整资料或 API 密钥。
- 日志禁止输出正文、令牌、完整会话内容和联系人隐私字段。
- 适配器只观察消息对象，不修改微信消息对象、不改写数据库、不自动回复。
- 任意 Hook/反射失败都必须 fail-safe：停用当前适配器，保留微信正常运行和主 App 正常启动。

## 6. 测试与验收

### 6.1 单元测试

`WechatMessageMapperTest` 覆盖：

- type 1 文本被映射；
- 非文本类型被拒绝；
- 空会话或空正文被拒绝；
- 自己/联系人方向映射；
- 秒级和毫秒级时间转换；
- 本地 ID 缺失时回退服务器 ID；
- 两个 ID 都无效时拒绝；
- sourcePackage/sourceClass/messageId 格式稳定；
- 超长正文交由共享验证器拒绝。

`WechatHookGateTest` 覆盖：

- 只接受 `com.tencent.mm` 主进程；
- 只接受 versionCode 3085/versionName 8.0.72；
- 子进程和其他版本不安装 Hook；
- 安装结果可重复调用且不会重复安装。

### 6.2 设备验收

在已启用 LSPosed 的 RMX3800 上：

1. 安装新的 Xposed debug APK，重启微信；
2. 确认主进程日志只显示适配器安装成功，不显示正文；
3. 从另一微信账号向测试账号发送一条普通文本；
4. 确认日志出现消息 ID/类型/方向等脱敏元数据；
5. 确认主 App 的 IPC Service 接收并接受该真实消息；
6. 确认非文本消息和重复回调不会进入主 App；
7. 关闭模块或清除令牌后，微信仍能正常启动且不再发送消息。

### 6.3 Phase 4 完成定义

只有以下条件全部满足，Phase 4 才算完成：

- 真实 WeChat 8.0.72 文本消息成功经过 `h9.Cb(f9)` 进入 Jev IPC；
- 映射、过滤、去重和版本门禁均有自动化测试；
- Hook 失败不会影响微信主进程；
- 真实消息正文未出现在日志；
- 非目标版本不会安装错误适配器；
- Phase 3 的全部测试和构建仍然通过；
- 文档明确记录当前只支持的消息类型和版本范围。

## 7. 后续扩展

Phase 4.1 完成后，再分别设计群聊发送者解析、图片/语音/引用消息、多版本适配器和实时分析去抖。任何扩展都不能把版本相关类型直接引入共享 IPC 合同或主 App 领域模型。
