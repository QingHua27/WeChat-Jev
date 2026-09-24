# Jev 主 App 与 LSPosed 模块合并设计

## 目标

将 Jev 主 App 与当前独立发布的 LSPosed/Xposed 模块合并为一个可安装 APK。用户从 Jev 一个入口完成模型设置、微信聊天分析和模块配对，不再复制或手工输入桥接令牌。微信内嵌卡片、本地聊天记录读取和“AI分析”继续由 LSPosed 注入实现。

合并不负责安装、启用或配置 LSPosed，也不绕过框架权限。用户仍需在 LSPosed 中启用 Jev 模块、将微信加入作用域，并在令牌同步后重启微信。

## 当前结构

- `:app` 是主 Android application，包名为 `com.jev.relationship`，拥有模型配置、数据库、主界面和认证 IPC 服务。
- `:xposed` 是第二个 Android application，包名为 `com.jev.relationship.xposed`，包含 LSPosed 模块入口、微信钩子、嵌入式卡片、配置 Activity 和 LibXposed Service Provider。
- `:jev-ipc-contract` 定义主 App 与注入端之间的 Messenger 协议。
- 注入端从 LSPosed Remote Preferences 读取配对令牌；主 App 在加密 DataStore 保存同一令牌。当前设置页要求用户复制令牌并在另一个应用中粘贴。
- LSPosed 通过 APK 中的 `META-INF/xposed/module.prop`、`java_init.list` 和 `scope.list` 识别模块、加载入口并确定作用域。

## 方案

### APK 与 Gradle 模块

保留 `com.jev.relationship` 作为唯一 applicationId，避免升级主 App 时丢失其 DataStore、模型配置和数据库。将 `:xposed` 从独立 application 改为 Android library，并由 `:app` 依赖；保留独立的 Xposed namespace 和单元测试。Xposed 运行时类、LibXposed Service Provider、配置 Activity 及必要资源由主 APK 一并打包。

把当前 Xposed 元数据合并到最终 APK 的 `META-INF/xposed/` 路径，并在打包验收中检查三份元数据均可读取。配置 Activity 不再注册桌面启动入口，由 Jev 设置流程显式启动。主 App 原有 launcher Activity 保持唯一桌面入口。Xposed API 继续使用 `compileOnly`，避免把框架 API 本身打入 APK；LibXposed Service 保留运行时依赖。

合并后只发布主 App APK；Gradle 可继续保留 `:xposed` 作为供主 App 依赖和承载测试的 library 模块，但不再产出第二个安装包。

### 自动配对

用 Jev 设置页的“连接 LSPosed”取代复制令牌流程。用户点击后，Jev 生成新的随机令牌，并通过合并 APK 内的配置 Activity 连接 LibXposed Service。配置 Activity 将令牌写入当前统一模块的 LSPosed Remote Preferences，并在写入确认后向主 App 返回成功；主 App 随后加密保存同一令牌并启用 IPC 集成。

只有 Remote Preferences 写入和主 App 保存都成功时，界面才显示“已连接”。LSPosed 服务不可用、写入失败或等待超时都显示可读原因和重试操作，且不能显示为已配对。失败重试可以生成新令牌并覆盖远端值。关闭集成时，主 App 拒绝握手并清除本地令牌；远端令牌清理作为尽力操作，不作为认证安全边界。

保留现有 IPC 握手令牌认证、来源包名校验和请求能力限制，不加入静态共享密钥，不记录令牌或聊天正文日志。自动同步只移除人工复制步骤，不改变认证模型。

### 迁移

升级主 App 时保持其 applicationId，因此现有 Jev 数据和模型设置应保留。旧 Xposed APK 的包名及其 LSPosed Remote Preferences 与新模块身份不同；首次连接时创建新令牌，不假定能够读取旧模块配置。

迁移流程为：安装统一 APK；在 LSPosed 启用新的 Jev 模块并选择微信作用域；停用旧的 `com.jev.relationship.xposed` 模块；在 Jev 设置中执行“连接 LSPosed”；重启微信；确认内嵌卡片和“AI分析”正常后，再卸载旧 Xposed APK。应用提供迁移说明，避免新旧模块同时注入造成重复卡片。

不静默卸载旧 APK，不尝试自动修改 LSPosed 启用状态或作用域。用户可以在验证之前保留旧安装以便回退。

### 状态与失败处理

设置界面至少区分：未连接、正在连接、等待 LSPosed 服务、同步失败（可重试）、已同步并待微信重启、已连接。应用不声称可以检测或代替用户打开 LSPosed 的模块开关和微信作用域。

如果新模块已经启用但微信仍运行旧进程，状态提示要求重启微信。若 IPC 握手未通过，保留已同步状态并展示连接错误；不要求用户查看或复制密钥。关闭集成后，服务拒绝后续握手。

## 验收标准

1. Release/Debug 的 Jev 主 APK 包名仍为 `com.jev.relationship`，并且安装包内含 Xposed Java 入口、模块属性、微信作用域、Service Provider 和配置 Activity。
2. 发布流程只产生一个可安装 Jev APK 和一个桌面入口；`:xposed` 不再产生独立 APK。
3. 覆盖安装当前主 App 后，原模型配置、数据库和会话数据仍可读取。
4. 首次配对和重新配对均不需要复制或手输令牌；Remote Preferences 未确认写入时不能显示“已连接”。
5. LSPosed 未就绪、超时和同步失败均能重试，失败状态不会错误启用 IPC。
6. IPC 的有效令牌请求成功，错误或缺失令牌请求被拒绝；关闭集成后握手被拒绝。
7. 设备迁移时只启用新的 Jev 模块与微信作用域；停用旧模块后，微信内每条目标消息最多显示一张卡片，历史记录读取和“AI分析”均可用。
8. 至少验证一次从现有安装覆盖更新、配对、新旧模块切换和微信进程重启的完整设备流程。

## 测试范围

- Gradle 单元测试：配对状态转换、成功/失败结果处理、重试、令牌旋转和关闭集成。
- Xposed 配置流程测试：令牌通过 intent 传入、写入 Remote Preferences 后返回成功；服务不可用和写入失败时返回失败。
- IPC 测试：现有认证正反例保持通过。
- APK 检查：解包确认 `META-INF/xposed/*`、Provider authority、唯一 launcher Activity 和包名。
- 设备验收：覆盖升级保留数据；确认 LSPosed 识别新模块及微信作用域；自动配对；关闭旧模块；重启微信；检查无重复卡片并验证聊天分析。

## 不在本次范围

- 移除 LSPosed 或改回无障碍读取。
- 自动授予 root、启用 LSPosed 模块或修改其作用域。
- 修改模型请求、AI 提示词、微信解析逻辑或卡片样式。
- 自动静默卸载用户现有的 `com.jev.relationship.xposed` APK。

## 风险与验证点

- 新模块的 applicationId 从 `com.jev.relationship.xposed` 变为 `com.jev.relationship`，必须通过设备验证 LSPosed 能识别新 APK 元数据并加载模块。
- LibXposed Remote Preferences 是否可由合并 APK 内的 Jev 设置流程可靠写入，必须在真机上验证。无法连接时必须保留可重试错误状态，不能退回手工复制流程并假报成功。
- Xposed 元数据位于 APK 的资源路径而非普通 Android manifest 声明中；Gradle 合并后应通过检查最终 APK 内容验证，不只依赖源码目录或单元测试。
- 旧模块若仍启用会与新模块同时注入，因此设备迁移必须显式验证停用旧模块后的行为。

## 参考

- LSPosed 现代模块文档说明 Java 入口、静态作用域和模块属性通过 `META-INF/xposed/` 元数据打包：[Develop Xposed Modules Using Modern Xposed API](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API/a7f346f7ec01ea698e1b187fc20de5fc727b0d09)。
- LibXposed Service 是模块与框架配置服务间的通信接口：[libxposed/service](https://github.com/libxposed/service)。
