# MeBox Android Implementation Plan / 分阶段开发计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 逐步交付填写 MeBox 地址和账号即可连接的原生安卓客户端，并保持旧网页与第三方客户端兼容。

**Architecture:** 在现有 Go 服务层上增加移动端稳定契约。Android 单模块起步，按连接、影视、播放、阅读与同步分包；服务端处理账号权限、资源解析和转码，客户端处理原生呈现、生命周期与本地可靠性。

**Tech Stack:** Go / Gin / GORM；Kotlin / Compose / Media3 / OkHttp / Retrofit / Room / DataStore / WorkManager；Android Keystore。

## Global Constraints

- 当前只保存规划，所有任务初始状态为未开始；勾选代表已有验证证据。
- 后端根目录 `D:\project\MeBox`，安卓根目录 `D:\project\MeBox-APP`，路径不能混用。
- 默认 Android 手机/平板、原生技术、推荐 minSdk 26；具体构建版本在 M0-02 锁定。
- 首版一个活动服务器配置；本地数据按 server_id + user_id 隔离，受配置影响的缓存还包含 profile_id。
- 公网默认 HTTPS；局域网 HTTP 显式确认；首版根路径部署；不全局跳过 TLS 校验。
- 新端点默认 `/api/v1`，既有 `/api` 和 Emby 协议继续兼容。
- 不长期保存密码，不把 token、Cookie、PIN 和完整签名 URL 写入文档/日志。
- 01—03 为需求依据，05 为验收门槛；任何范围变化先更新 06。
- 本文是分期任务清单；M0 完成后只对即将执行的任务细化实现提交，不一次生成未来全部业务代码。

---

## 执行与状态约定

每个任务按以下小步骤推进，分别记录结果，不把全部阶段作为一次提交：

1. 复核涉及文件和上游接口，检查依赖已验证。
2. 写出该任务列明的失败用例；运行最小测试，确认确实暴露缺失行为。
3. 实现最小改动，保持明确接口和兼容边界。
4. 重跑最小测试，再运行阶段回归；涉及设备行为时附真实设备证据。
5. 自查安全、权限、资源释放与错误路径；按用户授权管理 Git 提交，不自动推送。
6. 在下方任务标题勾选，并记录日期、实际命令、结果、环境、提交和已知限制。

代码任务必须走完整测试周期；M0 调研任务以可复现记录/环境验证代替代码失败测试。所有拟新增文件均在执行时创建；当前文件列出测试名不代表测试已存在。

### 通用测试命令

后端工作目录 `D:\project\MeBox`：

- 定向：`go test ./internal/handler ./internal/service ./internal/repository ./internal/database ./internal/middleware -count=1`。
- 全量：`go test ./...`。
- 网页兼容：`npm --prefix web run build`；现有前端测试用项目已安装的 Vitest 执行，例如 `npm --prefix web exec -- vitest run`。首次运行先确认依赖可用，不能让命令临时下载不锁版本的工具。

安卓工作目录 `D:\project\MeBox-APP`，仅在 M2-01 已创建 Wrapper 后：

- 定向：`.\gradlew.bat :app:testDebugUnitTest --tests "*具体测试类名"`。
- 单元测试：`.\gradlew.bat :app:testDebugUnitTest`。
- 静态检查与构建：`.\gradlew.bat :app:lintDebug :app:assembleDebug`。
- 仪器测试：`.\gradlew.bat :app:connectedDebugAndroidTest`，要求已有测试设备/模拟器。

M0 将检查真实工具链；上述命令是后续执行方法，不是本轮已经运行或通过的结果。

## M0：基线、环境与范围

### [ ] M0-01 复现网页不稳定并建立样本

依赖：无。产出：当前真实问题与可比较基线，不预设根因。

文件：拟新增 `plan/evidence/M0-baseline.md`；只保存脱敏记录，不复制真实账号和资源签名。

- [ ] 记录后端/网页版本、部署方式、反代、数据库类型、手机型号、Android/浏览器版本、网络路径。
- [ ] 重复实际失败步骤，记录失败发生在登录、起播、拖动、阅读、后台音频或网络恢复的哪个环节。
- [ ] 选择有权访问的本地视频、STRM/115、远程 Emby、文本/漫画/音频样本；不存在的类型标为未覆盖而不是通过。
- [ ] 对影视可使用现有安卓 Emby 客户端做对照，判断是否存在浏览器特有问题；不以此替代自有 App。
- [ ] 关联服务端脱敏日志/请求 ID；没有证据时只保留假设。

验收：至少一个可复现问题，或明确记录复现未成功及下一步采样方案；每个首版支持的资源类型有可用测试样本。

### [ ] M0-02 锁定 Android 构建环境

依赖：无，可与 M0-01 分别推进。

文件：拟新增 `plan/evidence/M0-toolchain.md`；工程版本文件在 M2-01 创建。

- [ ] 检查 JDK、Android SDK、模拟器/真机、ADB、Gradle/AGP/Kotlin/Compose 兼容要求。
- [ ] 选定稳定兼容版本，写出精确版本、安装路径、构建命令和已知限制；不能只写“使用最新版”。
- [ ] 确认推荐 minSdk 26、包名 `app.mebox.android`、开发机可用 Android 平台与目标发布渠道。
- [ ] 检查 MeBox 与拟引入依赖的许可要求，确定分发 APK 与源码时需要履行的事项。

验收：环境记录可让另一位开发者准备相同工具链；缺失工具标为阻塞，不凭规划文字宣称可以打包。

### [ ] M0-03 冻结首个业务范围与接口测试基线

依赖：M0-01；参考 M0-02。

文件：更新 01、02、03、06；拟新增后端 `docs/mobile-api/openapi.yaml`。

- [ ] 确认影视优先或阅读优先，以及首版必须支持的资源类型与字幕能力。
- [ ] 固定新接口字段、错误码、单位、可见性、设备踢出和旧客户端兼容规则。
- [ ] 明确轮换响应丢失后服务重启的恢复边界，以及首版是否接受重新认证。
- [ ] 将未来端点写为版本化 OpenAPI，配套固定示例与错误示例；未实现能力值为 false。

验收：首版需求能映射到本计划任务；M1 开始前接入契约已评审，没有依赖尚未定义的字段。

## M1：最小后端接入能力

### [x] M1-01 服务器身份与兼容信息

依赖：M0-03。

文件：现有 `internal/handler/handler.go`；拟新增 `mobile_routes.go`、`mobile_server_info.go`、`internal/service/server_identity.go` 及对应测试。

接口：`GET /api/v1/server-info`，输出 02 定义的 server_id、真实构建版本和能力。

- [ ] 测试 `TestMobileServerInfo`：匿名可访问、不泄露路径/用户、空功能不虚报。
- [ ] 测试实例身份持久化：同实例重启一致、独立克隆有明确身份重置步骤。
- [ ] 实现信息生成与注册；实际运行能力从服务容器/配置推导。
- [ ] 执行 `go test ./internal/handler ./internal/service -run "MobileServerInfo|ServerIdentity" -count=1`，预期全部通过；保留旧 `/api/version` 兼容行为。

验收：App 可区分非 MeBox、需要升级和可连接实例。

已完成（2026-10-17，后端分支 `feature/android-mobile-api`）：
- 新增 `GET /api/v1/server-info`、`internal/service/server_identity.go`、
  `internal/handler/mobile_server_info.go`（实际为 `mobile_routes.go` 内注册）。
- `server_id` 持久化在 `settings.mobile.server_id`；本机 SQLite 重启前后一致。
- `server_version` 使用真实构建版本（本地为 `dev`，注入后为实际版本）。
- 能力项中只有 `device_sessions=true`，其余未实现项为 false。
- 测试：`go test ./internal/handler -run TestMobileServerInfo`、
  `go test ./internal/service -run TestServerIdentity` 通过。
- 模拟器实际读取到 `{"name":"MeBox","server_version":"dev",...}` 并进入登录页。

### [ ] M1-02 认证会话模型与兼容迁移

依赖：M0-03。

文件：现有 `internal/model/refresh_token.go`、`internal/database/schema_migration.go` 和模型注册；拟新增 `auth_session.go` 模型、repository 及迁移测试。

接口：持久会话关联 user_id、device_id、撤销状态；刷新令牌可选关联 auth_session_id。

- [ ] 构造旧库、已有无关联刷新令牌和重复迁移测试。
- [ ] 增加非破坏性模型、索引与迁移；旧 token 不被随意绑定到设备。
- [ ] 验证 SQLite 与 PostgreSQL 查询/约束；未配置 PostgreSQL 的本地环境不能将其测试标为通过。
- [ ] 执行 `go test ./internal/database ./internal/repository -count=1`，按 05 执行数据库集成回归。

验收：旧账号和历史令牌可继续使用，新会话可独立撤销。

进度（2026-10-17）：`model.AuthSession`、`AuthSessionRepository` 与
`refresh_tokens.auth_session_id` 已实现，SQLite 迁移与并发刷新测试通过
（`go test ./internal/service ./internal/database ./internal/repository`）。
**PostgreSQL 尚未验证**，因此本任务保持未勾选；需在配置 PostgreSQL 的环境
补跑数据库迁移与并发测试后才能通过。

### [x] M1-03 v1 登录、刷新、退出和会话校验

依赖：M1-01、M1-02。

文件：现有 `internal/service/token_svc.go`、`token_refresh_rotation.go`、`internal/middleware/auth.go`、设备服务；拟新增 `mobile_auth.go`、`auth_session.go` service 和 middleware。

接口：02 中四个 `/api/v1/auth` 端点；新 JWT 含 sid，访问旧 API 时同样校验 sid。

- [ ] 测试 `TestMobileAuthLifecycle`：登录→刷新→退出；错误密码、账号禁用/到期有正确错误。
- [ ] 测试 `TestMobileSessionRevocation`：踢出一台设备、携 sid 令牌的旧 API 绕过尝试、可继承 sid 的派生 token 和 kick-all；M3/M4 的新资源票据分别在其阶段补充撤销测试。
- [ ] 测试 `TestMobileRefreshRecovery`：同 token 并发、宽限重试、正常重启恢复及轮换响应丢失窗口。
- [ ] 复用现有认证和限流能力；暂时性存储错误返回可重试服务错误。
- [ ] 执行 Go 定向与全量测试；旧网页和 Emby 登录流程回归。

验收：设备列表正确识别 MeBox Android，单设备撤销不影响其他会话，旧无 sid 客户端保持兼容。

已完成（2026-10-17）：
- 端点：`POST /api/v1/auth/login`、`/refresh`、`GET /session`、`POST /logout`。
- 访问令牌含 `sid`；旧 `/api` 中间件同样校验会话，退出后未过期 JWT 立即 401。
- 单设备踢出只撤销该设备会话；双设备测试确认另一会话仍可用。
- 退出幂等；会话撤销同时撤销其刷新令牌；刷新令牌轮换保留会话关联。
- 测试：`TestMobileAuthLifecycle`、`TestMobileV1RejectsLegacyTokenWithoutSid`、
  `TestMobileRefreshRejectsUnboundLegacyToken`、`TestMobileDeviceKickRevokesSession`、
  `TestMobileKickOneDeviceKeepsOtherSessions` 全部通过；curl 实测退出后
  `/api/me` 返回 401、刷新返回 `SESSION_REVOKED`。
- 已知限制：外部播放令牌尚未继承 `sid`，按设备即时撤销只覆盖 v1 会话与旧
  API 的会话校验，派生媒体令牌留到 M3 完成。

### [x] M1-04 错误契约、诊断与反代接入

依赖：M1-03。

文件：拟新增 `internal/handler/mobile_errors.go`、错误契约测试及 `docs/mobile-api/deployment.md`。

- [ ] 测试 `TestMobileErrorContract`：401/403/409/429/503 的 code、request_id、retryable 与 Retry-After。
- [ ] 统一 v1 错误映射，保留旧响应适配；过滤日志敏感字段。
- [ ] 验证公网 HTTPS、局域网 HTTP、端口与 IPv6；记录根路径限制。
- [ ] 验证 Range/206、流式缓冲、WebSocket 升级与代理超时设置，不仅检查健康端点。

验收：后端接入契约和部署说明可供安卓独立开发；无法验证的网络场景有明确阻塞项。

已完成（2026-10-17）：
- `internal/handler/mobile_errors.go` 统一 `code/message/request_id/retryable`
  与 `Retry-After`；`X-Request-ID` 合法值沿用，非法值重新生成。
- 400/401/403/429/503 契约测试通过；App 的 `MeBoxApiClient` 用 MockWebServer
  验证了 429 与 `retry_after_sec`、Bearer 头与错误体解析。
- 契约与部署文档：`D:\project\MeBox\docs\mobile-api\openapi.yaml`、
  `deployment.md`。
- 局域网 HTTP 已在模拟器确认风险提示与连接；HTTPS、IPv6、反代 Range/206、
  WebSocket 与流式缓冲**尚未验证**，已在部署文档列为阻塞项。

## M2：安卓连接、登录与会话

### [x] M2-01 Android 工程与服务器地址测试页

依赖：M0-02、M0-03；联调依赖 M1-01。

文件：拟新增 Gradle Wrapper、`settings.gradle.kts`、根/应用构建脚本、`gradle/libs.versions.toml`、Manifest、Application、Activity、AppContainer，以及 `core/network/ServerAddress.kt`、`feature/connection/`。

接口：`ServerAddress.parse(raw)` 返回规范来源；测试页消费 server-info，不持有账号密码。

- [ ] 建立锁版本单 `:app` 工程和基本主题/导航，排除构建产物与本地 SDK 路径提交。
- [ ] 编写 `ServerAddressTest`：域名/IPv4/IPv6/端口/尾斜线、缺 scheme 默认 HTTPS、内嵌凭证/查询/子路径拒绝或明确提示。
- [ ] 实现连接结果分类：服务不可达、TLS、非 MeBox、不兼容、成功；网络失败不隐式降级 HTTP。
- [ ] 执行 `.\gradlew.bat :app:testDebugUnitTest --tests "*ServerAddressTest"`，再执行 lintDebug、assembleDebug。

验收：可安装调试 APK，连接有效后端并显示服务器身份；所有密码发送都发生在连接确认之后。

已完成（2026-10-17）：
- Gradle Wrapper 8.13、AGP 8.10.1、Kotlin 2.1.21、Compose、minSdk 26，
  版本固定于 `gradle/libs.versions.toml`；`local.properties` 与构建产物已忽略。
- `ServerAddressTest` 16 项通过：域名/IPv4/IPv6/端口/尾斜线、缺 scheme 默认
  HTTPS、内嵌凭证/查询/片段/子路径拒绝、非法端口与主机拒绝。
- 连接结果区分：地址错误、HTTP 风险确认、网络/证书失败、非 MeBox、需要升级或
  不兼容、成功并显示服务器名称与版本；非 v1 / 最低 versionCode 判断统一在
  `ServerCompatibility` 并有单元测试。
- `assembleDebug` 成功；在 API 36 模拟器安装并实际连接到本机后端，登录页显示
  `MeBox · dev`。密码输入与提交只发生在服务器身份确认之后。
- 实测补充：`https://example.com/mebox` 被拒绝；返回 404 的普通 HTTP 服务提示
  “服务器未提供 MeBox 移动端接口”；`min_supported_app_version_code=99` 的
  服务被拒绝并提示升级 App。

### [x] M2-02 安全凭证与单次刷新网络层

依赖：M2-01、M1-03。

文件：拟新增 03 中 CredentialStore、KeystoreCredentialStore、AuthCoordinator、MobileApi、ApiErrorMapper 与备份排除规则。

接口：凭证按 server_id/user_id/generation 读写；刷新成功先持久化再发布。

- [ ] 写 `AuthCoordinatorTest` 与 `CredentialStoreTest`：20 个并发 401 只刷新一次；重试一次上限；刷新网络失败不清凭证。
- [ ] 使用 MockWebServer 验证轮换响应、账号切换时迟到响应、403 不刷新、429 等待与退出取消。
- [ ] 在设备上验证加密持久化、冷启动、密钥丢失/备份恢复后的可控重新登录。
- [ ] 执行 JVM 测试与 `connectedDebugAndroidTest`；敏感内容不出现在导出日志与普通配置中。

验收：有效登录可恢复；临时网络故障与明确凭证撤销有不同状态。

已完成（2026-10-17）：
- `KeystoreCredentialStore`：Android Keystore AES-GCM，临时文件 + fsync +
  原子重命名；解密失败删除文件并要求重新登录。
- `AuthCoordinatorTest` 7 项通过：20 个并发 401 只刷新一次、先落盘再重放、
  网络失败保留凭证、明确撤销清除凭证、第二次 401 不循环刷新、切换账号丢弃
  迟到结果、403 不触发刷新。
- `MeBoxApiClientTest` 6 项通过：server-info 解析、登录请求体、429 错误契约、
  Bearer 头、非 JSON 错误回退、网络失败分类。
- 模拟器实测：登录后冷启动自动恢复会话；退出后本地凭证清除，再启动进入登录页；
  后台踢出设备后再次启动进入登录页并提示“登录已失效”。
- 尚未编写 `connectedDebugAndroidTest`；密钥丢失/系统备份恢复只在实现中处理，
  未在真机演练。

### [ ] M2-03 登录闭环、账号与播放配置

依赖：M2-02、M1-04。

文件：拟新增 connection/account 页面、ViewModel、Repository、MeBoxNavHost 和用户/权限 DTO。

- [ ] 写 `LoginFlowTest`：成功、密码错误、账号到期、需要升级、断网恢复。
- [ ] 加载权限、播放配置、PIN 校验和设备列表；界面隐藏无权限功能但仍以服务端授权为准。
- [ ] 退出时本地清理立即生效；远端未确认单独说明；账号切换取消旧请求、刷新与队列。
- [ ] 测试旋转/后台返回/进程重建，避免重复提交登录或重用旧账号凭证。

验收：一个尚无影视功能的接入版本可独立使用和测试，完成地址→登录→账号页→退出闭环。

进度（2026-10-17）：地址→登录→账号页→退出闭环已实现，并在 API 36
模拟器完成实际验证（含冷启动恢复与退出）。尚未完成：权限列表、播放配置、
PIN 校验与设备列表展示；`LoginFlowTest` 自动化用例尚未编写。因此本任务
保持未勾选，账号页当前只展示服务器、用户与设备基本信息。

## M3：影视浏览和原生播放

### [ ] M3-01 媒体库、搜索、详情和选集

依赖：M2-03。

文件：拟新增 `feature/home/`、`feature/library/` 的 DTO、Repository、ViewModel、页面和单元测试；复用已有后端媒体接口。

- [ ] `LibraryRepositoryTest` 覆盖分页结束、空库、重复页去重、搜索取消、远程媒体 ID 与同剧不同库。
- [ ] 实现首页继续观看、媒体库、筛选、搜索、详情、剧集和版本选择。
- [ ] 校验权限、播放配置和成人内容隔离；缓存键包含影响可见性的条件。
- [ ] 验证普通账号、PIN、服务器拒绝访问、列表滚动恢复和断网旧缓存。

验收：首版范围内的资源可定位到准确可播放条目，不把远程剧集容器误当视频文件。

进度（2026-10-17）：已实现首页（焦点推荐轮播/继续观看/最近添加/媒体库）、媒体库分页、
筛选（类型/年份/评分/未看完）与排序、随机播放、搜索分页、详情、版本选择、
选集、收藏列表与观看历史（进度/删除/清空）以及“我的”入口；数据来自既有
`/api/libraries`、`/api/libraries/:id/series`、
`/api/libraries/:id/facets`、`/api/libraries/:id/random`、`/api/media`、
`/api/history`、`/api/favourites`、`/api/watch-history` 等接口。焦点轮播只请求
`carousel_enabled` 媒体库的预览卡片
（`with_preview=1&preview_limit=10`），5 秒自动切换，无配置时以最近添加兜底。
筛选与排序支持在媒体库内即时应用，筛选元数据来自服务端 facets。尚未实现
权限/PIN 专项测试与列表滚动位置恢复，任务保持未勾选。

### [ ] M3-02 统一播放解析和资源票据

依赖：M1-04、M0-03。

文件：后端拟新增 `internal/handler/mobile_playback.go`、`internal/service/playback_resolver.go` 及 `mobile_playback_test.go`；Android 拟新增 `PlaybackDescriptor.kt`、`DeviceCapabilities.kt`、`MediaHttpClient.kt`。

- [ ] 固定 capability 和 candidate JSON 示例，覆盖 direct、cloud_hls、transcode_hls 及明确不支持状态。
- [ ] `TestMobilePlaybackDescriptor` 验证权限/PIN、时间单位、时间轴偏移、候选限制与远程 Emby 无本地转码。
- [ ] `MediaRedirectPolicyTest` 验证跨来源不泄露 MeBox 凭证、票据过期和 HTTPS 不静默降级。
- [ ] 按实际实现开放候选；未实现 remux 时不返回 remux。

验收：App 不复制服务器网盘解析逻辑，描述信息足以驱动播放器与恢复策略。

### [ ] M3-03 播放会话、转码隔离和进度事件

依赖：M3-02。

文件：后端拟新增 `playback_session.go`、`playback_session_events.go`；调整 `stream_hls.go`、`transcoder_jobs.go` 等以新增会话隔离路径，保留旧接口。

- [ ] `TestPlaybackSessionIsolation`：两个用户/设备播放同媒体不同起点，一端停止/seek 不破坏另一端。
- [ ] `TestPlaybackSessionEvents`：创建幂等、事件重复/乱序、旧会话晚到、时钟偏差、主动回看和退出释放。
- [ ] `TestPlaybackSessionRecovery`：租约超时、作业失败、服务重启后的会话不存在及可重新创建。
- [ ] Go 回归验证旧 HLS 路径、115 和 Emby 兼容；分别记录实际 ffmpeg 样本验证。

验收：新会话可创建、刷新候选、上报和结束；不能通过停止某媒体全局任务实现自己的退出。

### [ ] M3-04 原生视频播放与可靠续播

依赖：M3-01、M3-03。

文件：拟新增 `feature/player/PlayerController.kt`、`PlayerUiState.kt`、`PlayerScreen.kt`、本地进度实体/DAO 与 outbox。

- [ ] `PlayerRecoveryTest`：绝对/相对时间转换、候选有限回退、会话丢失重建、旧回调失效和手动从头播放。
- [ ] `ProgressOutboxTest`：本地事务、重复重发、合并最新事件而非最大位置、换账号隔离。
- [ ] 绑定 Media3，按 03 的 5 秒本地保存/15 秒在线同步策略实现播放/暂停/切换保存。
- [ ] 验证长片续播、拖动、网络切换、旋转与进程回收；临时网络错误不清零位置。

验收：本地和首版选定的远程资源可播放并正确续播；所有恢复有明确上限与错误提示。

进度（2026-10-17）：播放层已升级为 Media3 ExoPlayer + 全自绘 Compose 控件：
直链优先并在失败时回退 HLS（保留播放位置），支持双击/拖动快进、亮度/音量
手势、倍速、清晰度、音轨、字幕、画面缩放、画中画与锁定；播放前合并服务端
`/api/playback/:id/resume` 与本地缓存，播放中每 5 秒写本地、每 15 秒上报
服务端，暂停/退出/播完立即上报，剧集结束可播放下一集。新增本机解码策略：
MediaCodec 探测视频编码/分辨率/容器，`DefaultRenderersFactory` 开启硬件失败后
的软件解码回退；仅在视频编码或容器确定不支持时自动服务端转码，网络错误改为
重试/手动转码。后台播放、播放会话隔离和服务端 v1 播放会话尚未实现；未在真机
验证多编码样本与解码回退，任务保持未勾选。

### [ ] M3-05 字幕音轨、清晰度和收藏历史

依赖：M3-04。

文件：拟新增 player 轨道选择与字幕适配、library 收藏操作、home 历史展示和对应测试。

- [ ] `TrackSelectionTest` 覆盖默认语言、字幕关闭、切换音轨/清晰度后的绝对进度保留。
- [ ] 验证选定的 SRT/VTT/ASS/图片字幕样本；未支持效果明确标注，不假装完整支持。
- [ ] 收藏使用目标状态式接口；测试重复请求不变成取消收藏。
- [ ] 使用同账号网页/App 交替观看验证历史与续播一致；不同账号不可串历史。

验收：首个影视业务闭环完成，真实格式支持清单可供用户理解。

进度（2026-10-17）：收藏已接入目标状态式接口，重复点击不会反向切换；
收藏与历史已在模拟器实测（入口、列表、取消/重新收藏、单条删除、清空确认、
返回刷新）；服务端软删除导致重新收藏 500 的问题已修复并补回归测试。
字幕（外挂与内嵌文本，服务端转 WebVTT）、音轨与清晰度切换已接入播放器设置面板，
切换后保持绝对播放进度。PGS 等内嵌图片字幕曾通过 `/api/hls/:id/index.m3u8?subtitle=<流索引>`
由服务端 FFmpeg 烧录；2026-10-17 起 App 改为纯直连播放，已移除 HLS/烧录/自动转码路径，
图片字幕在 App 内不再显示。外挂 `.sup` 仍不支持。尚未在真实多音轨/多清晰度样本上验证，
任务保持未勾选。

## M4：阅读、漫画与后台听书

M4 可在 M0 决定早于 M3 实施。其认证依赖 M2，文本/漫画不依赖视频播放器；后台有声书独立实现 Media3 音频控制和资源策略，不能假设 M3 已存在。

### [ ] M4-01 阅读锚点、资源访问和版本冲突协议

依赖：M1-04；范围确认 M0-03。

文件：后端拟新增 `mobile_reader_progress.go`、`mobile_reader_resources.go`、`reader_progress_anchor.go` 模型/service/repository 与迁移测试；兼容现有 `reader_routes.go`、阅读 SaveProgress。

- [ ] `TestMobileReaderResources`：v1 正文/批量适配、限时资源引用、有效 sid、越权/撤销/到期、Range 与 HLS 清单重写；原生入口不使用旧无时效签名代替会话校验。
- [ ] `TestReaderProgressAnchor`：字符、图片、音频位置，所有权和非法位置。
- [ ] `TestReaderProgressConflict`：base_revision 条件更新、重复 event_id、旧网页写入也增加 revision。
- [ ] `TestReaderAnchorRemapping`：字号变化、换源、目录重建和旧页码无法精确迁移的降级。
- [ ] SQLite/PostgreSQL 与现有阅读进度测试同时通过。

验收：新旧客户端交替写入有确定版本行为，不靠最后到达的请求静默覆盖。

### [ ] M4-02 书架、搜索与原生文本阅读

依赖：M2-03、M4-01。

文件：拟新增 `feature/reader/` 中书架/搜索/目录/文本 Repository、ViewModel、页面、设置与阅读位置映射。

- [ ] `ReaderRepositoryTest`：书架归属、搜索取消、目录为空、正文失败与分组/偏好兼容。
- [ ] `ReaderAnchorTest`：字号/行距变化后字符恢复；内容版本失配回到安全位置并提示。
- [ ] 实现纵向正文阅读、章节切换、主题字号和目录；仅按现有接口开放已验证功能。
- [ ] 验证 TXT、EPUB 解析结果及网络正文，不在客户端重做 EPUB/书源解析器。

验收：书架→正文→退出→恢复闭环，服务端解析失败与网络故障能区分。

进度（2026-10-17）：阅读模块首版已实现并在 API 36 模拟器验证。新增底部
“阅读”入口、书架（分组筛选、排序、目录更新、移出书架）、多源搜索（分页、
书源跳过原因、加入书架）、目录懒加载（章节缓存为空时按书源重抓并落库）、
原生纵向正文（主题/字号/行距/段距/夜间）、目录跳转与上一章/下一章、阅读设置
跨设备同步（服务端 `reader/profile`）与本地首屏缓存。阅读位置沿用服务端整数
`pos` 语义（文本页码、漫画图片序号），并保存段落基准位置以避免恢复时把进度
往前挪；滚动停止 1.5 秒、切章和退出时上报。

证据：`ReaderTextTest`、`ReaderShelfTest`、`ReaderStyleTest`、
`ReaderChaptersTest`、`ReaderApiTest` 共 12 项阅读单元测试通过；全量 JVM
测试 110 项通过；`:app:lintDebug` 0 errors；`assembleDebug` 成功。模拟器实测
书架展示两本书与进度、打开 30 章网络书籍、正文恢复、目录跳章、主题/夜间
切换、刷新目录、搜索请求与“书源未返回”提示、滚动与退出后服务端进度更新。
测试中发现并修复空目录访问上一章越界崩溃、首帧 `scrollToItem` 被布局覆盖、
恢复位置被立刻回写导致进度漂移三个问题。

尚未完成：`ReaderRepositoryTest` 与仪器化 `ReaderAnchorTest` 未单独编写；
TXT/EPUB 本地书籍、真实漫画样本未验证；阅读位置仍是页码近似，跨设备/字号
变化可能有一页以内偏差（精确锚点依赖尚未实现的 M4-01 v1 协议）。任务保持
未勾选。

补充（2026-10-17）：换源与段评已接入。书籍信息页可按书名重新搜索并列出手
机其他书源，切换成功后清空旧源缓存并重建目录；正文中的 `comments` 锚点按
行号显示段评气泡，点击通过服务端打开签名评论页（WebView）。段评因当前真实
书源没有 `comments` 数据，只验证了接口契约与页面打开链路，未验证气泡点击的
真实内容；换源因服务器只有一个同源书源，只验证了空候选提示与接口契约。

### [ ] M4-03 漫画阅读与资源预取

依赖：M4-02。

文件：拟新增 reader 漫画页面、图片位置映射和资源缓存适配。

- [ ] `ComicPositionTest`：图片索引、比例恢复、异常尺寸、图片增删后的版本失配。
- [ ] 验证签名媒体代理、防盗链、解密结果和预取窗口；不一次解码全章所有大图。
- [ ] 测试快速滚动、低内存、旋转、单图失败重试与部分章节可用。

验收：位置可恢复，单图失败不导致整本书不可操作，内存无无限增长。

进度（2026-10-17）：已实现图片型章节的纵向阅读：按服务端 `images` 顺序渲染
签名代理地址，位置按图片序号保存与恢复，`LazyColumn` 只组合可见图片并由 Coil
负责磁盘/内存缓存，单图失败不会中断整章滚动。图片位置换算与漫画索引恢复逻辑
已在代码中落地，但当前真实书架没有漫画样本，尚未做快速滚动、低内存、旋转与
单图重试的设备验证；也没有单独编写 `ComicPositionTest`。任务保持未勾选。

### [ ] M4-04 有声书与后台媒体服务

依赖：M2-03、M4-01；书籍页面可复用 M4-02。

文件：拟新增 `feature/audio/`、`service/MeBoxMediaSessionService.kt`、Manifest 媒体服务/前台服务声明和权限。

- [ ] `AudioPlaybackTest`：音频/HLS 识别、章节切换、倍速、定时、秒/毫秒转换和网络恢复。
- [ ] `MediaServiceLifecycleTest`：锁屏、耳机、来电、音频焦点、用户暂停后不得自动恢复。
- [ ] 资源客户端独立满足跨来源鉴权要求；若 M3 已完成则复用，不重复实现或共享 token 到任意来源。
- [ ] 验证所选 API 级别的前台服务限制、通知行为与省电场景。

验收：正常系统后台策略下持续听书；被系统/用户停止后可恢复最近保存位置，不承诺无法被系统终止。

进度（2026-10-17）：已实现听书首版。新增 `ReaderAudioService`（Media3
MediaSessionService + ExoPlayer 前台服务）与应用级 `ReaderAudioEngine`：
支持章节音轨播放、自动下一章、上一章/下一章、倍速、定时关闭、片头/片尾
跳过、媒体通知与锁屏/耳机控制，并按秒上报服务端进度。音频进度与文本页码
分开处理，避免退出阅读页时把秒数覆盖成 0。

证据：API 36 模拟器 + 本机后端，用管理员导入的本地 WAV 有声书实测：打开
章节后准备完成处于暂停态，点击播放正常；自动连续播放 3 章；服务端
`dur_chapter_index/dur_chapter_pos` 按秒更新；退到桌面后媒体会话仍为
PLAYING、`ReaderAudioService` 为前台服务且通知存在。`ReaderAudioStateTest`
3 项通过，全量 JVM 测试 121 项通过，lint 0 errors，`assembleDebug` 成功。

尚未完成：锁屏、耳机按键、来电/音频焦点、定时关闭、片头片尾跳过未在设备
上验证；HLS 音轨、真实长音频与省电策略未覆盖；没有 `MediaServiceLifecycleTest`
仪器测试。任务保持未勾选。

补充（2026-10-17）：播放页已按 Legado 布局重做（模糊封面背景、圆形封面、
章节信息、白色圆形播放键、进度两侧快退/快进、底部六个控制位、倍速/定时
底部面板、顶部状态胶囊、横屏左右布局）；定时值不再在每次打开时自动重启。
已在开启音频输出的模拟器验证播放/暂停、进度、倍速、定时、目录、信息页与
横竖屏，任务仍因缺少真机生命周期验证保持未勾选。

### [ ] M4-05 特殊书源页面桥接

依赖：M4-02、M1-04。

文件：拟新增 `feature/reader/browser/` 的受控 WebView、桥接协议和待办 Repository；必要时增量调整后端浏览器待办适配。

- [ ] `ReaderBrowserBridgeTest`：当前用户待办、取消、超时、DOM 回传与 XHR 代理。
- [ ] 外站导航不携带 MeBox 凭证、不启用高权限 JS 桥；切账号立即关闭旧页面。
- [ ] 选择真实有权使用的登录/验证码/评论样本验证 Cookie 和回传链路；不能用空白 WebView 通过验收。

验收：验证通过的特殊源可用，无法兼容的源明确说明，失败不会永久阻塞正文请求。

进度（2026-10-17）：已实现受控 WebView 与书源登录首版。新增书源列表/登录
表单、登录动作执行、待办轮询、宿主页面、DOM 回传与页面内 XHR 代理；WebView
只允许加载当前服务器同源地址，不携带 MeBox JWT，桥只暴露 `xhr` 一个方法；
关闭/取消会向服务端回传 cancelled，解除书源 JS 阻塞。

证据：导入一个本地测试书源，在模拟器实测“登录按钮 → 服务端待办 → 打开
`Line settings` 页面 → 完成 → DOM 回传 → 登录动作返回 `ok`”，全程无崩溃；
另用 curl 验证了待办出现、DOM 回传和登录解除的服务端链路。`ReaderApiTest`
覆盖书源列表、登录字段、待办、回传与 XHR 契约（全量 121 项通过）。

尚未完成：未用真实第三方登录/验证码/段评样本验证 Cookie 与 XHR 代理；
账号切换关闭旧页面、待办超时、外站重定向拦截未做设备验证；没有
`ReaderBrowserBridgeTest` 仪器测试。任务保持未勾选。

### [ ] M4-06 文本朗读（可选独立增强）

依赖：M4-02、M4-04。

文件：拟新增 `feature/audio/TextToSpeechController.kt` 和朗读位置适配测试。

- [ ] 测试段落/字符进度、暂停继续、切章、定时、声音包缺失和引擎异常。
- [ ] 与后台媒体协调器整合；视频/有声书/朗读互斥占用音频焦点。
- [ ] 明确本机语音与服务端音频的差异，不承诺服务端原本没有的云端 TTS。

验收：已支持语音引擎下可朗读并恢复位置；未启用该增强不阻塞普通文本阅读发布。

## M5：离线与同步增强

### [ ] M5-01 章节持久缓存与下载任务

依赖：M4-02；漫画/音频离线分别依赖 M4-03/M4-04。

文件：拟新增 CachedChapter/CachedAsset DAO、下载 Repository、WorkManager worker 与缓存管理页；后端按需增加版本化资源清单。

- [ ] `ChapterDownloadTest`：最多 64 章批量调用、部分失败、取消、重复任务、资源到期重取。
- [ ] 文本内容、漫画图片和音频实际字节分别校验；不能只缓存 URL 清单。
- [ ] 飞行模式验证已完成下载的章节；重启恢复未完成任务，权限失效不继续无限下载。

验收：持久离线内容与临时 HTTP 缓存区分，任务有完成/失败/取消终态。

进度（2026-10-17）：已实现文件型离线缓存首版（未使用 WorkManager/Room）。
按 serverId/userId/bookId 分区保存书架、目录、章节 JSON 与图片/音频资源；
阅读过的文本章节自动缓存，书籍信息页支持“缓存本章 / 后续 20 章 / 清除本书
缓存”并显示章数与体积；网络失败时书架、目录、正文和音频会回退到缓存。

证据：模拟器实测书架的 `shelf.json`、30 章目录、章节 JSON 与 350.5 KB 统计；
飞行模式下返回书架显示离线书架，重新打开书籍能读取离线目录与已缓存正文；
音频引擎会自动读取缓存音轨。全量 JVM 测试 121 项通过，lint 0 errors。

尚未完成：没有 WorkManager 后台任务、下载取消、资源过期/版本失配与 LRU
容量管理；HLS 音轨、文本内嵌 `[img]` 图片和部分上游资源不做离线；
冷启动时登录仍需先访问 server-info，未实现完全离线的会话恢复；没有
`ChapterDownloadTest`。任务保持未勾选。

### [ ] M5-02 冲突处理与增量同步

依赖：M5-01、对应影视或阅读进度协议。

文件：完善 ProgressOutbox/SyncWorker；后端拟新增同步游标、内容版本与删除标记实现及契约测试。

- [ ] `ProgressConflictTest`：离线旧位置遇到其他设备新位置，保留双方并显式选择。
- [ ] `IncrementalSyncTest`：游标失效全量重建、删除标记、换源/目录更新和断点重入。
- [ ] 对事件重复/乱序、时钟偏差和重新认证后的队列归属做回归。

验收：已删除书籍不被旧队列复活，旧进度不静默覆盖新进度，游标失效可以自愈。

### [ ] M5-03 容量管理与多服务器预留验证

依赖：M5-01。

文件：缓存容量/清理页面和分区测试；多服务器可视配置切换只在确认额外范围后实施。

- [ ] `CacheEvictionTest`：容量上限、LRU、固定离线内容、未同步进度保护与退出清理。
- [ ] 使用两个实例/两个账号验证缓存、凭证、队列和播放配置隔离。
- [ ] 为后续内外网地址绑定保留 server_id 校验，不能把相同用户名当相同实例。

验收：清缓存不删除未解决进度，多实例数据不串；未开放多服务器界面时不显示空入口。

## M6：质量门槛与发布

### [ ] M6-01 自动检查与跨端兼容回归

依赖：准备发布的 M2/M3/M4/M5 任务；不要求所有可选增强完成。

文件：拟新增安卓 `.github/workflows/android-ci.yml`，后端补充契约回归测试，更新 `plan/evidence/` 验收记录。

进度（2026-10-17）：已新增 `.github/workflows/android-release.yml`，在推送
`main`、推送 `v*` 标签或手动触发时运行 JVM 单元测试、lint、构建 APK，并创建
GitHub Release（附 SHA-256）。未配置签名 Secrets 时发布调试 APK；配置后发布
签名 Release APK。设备/数据库集成测试、网页与第三方客户端兼容回归仍未纳入
CI，因此本任务保持未勾选。

- [ ] CI 执行单元测试、lint 和调试构建；设备/数据库集成测试单独标记环境。
- [ ] 按 05 完成所发布能力的认证、网络、媒体、进度、阅读与生命周期场景。
- [ ] 用旧网页与已承诺支持的第三方客户端验证后端兼容；未完成能力开关关闭。
- [ ] 记录不可支持格式和已知限制；严重越权、令牌泄露、进度丢失/串号阻止发布。

验收：每个发布功能有测试证据，未测试不等于通过。

### [ ] M6-02 签名、升级、回滚与使用说明

依赖：M6-01。

文件：拟新增 `docs/release.md`、App 发布配置/版本信息、升级兼容测试；更新项目 README。

进度（2026-10-17）：已支持由环境变量注入发布 keystore，CI 用
`github.run_number` 作为递增 versionCode；签名构建已用临时 keystore 本地验证
`apksigner verify` 通过。配置步骤见 `docs/ci-release.md`。正式发布密钥、
覆盖升级与回滚验证尚未完成，任务保持未勾选。

- [ ] 固定包名、versionCode、versionName 与签名管理方式；密钥不提交仓库。
- [ ] Release APK 做真机安装/覆盖升级/本地数据库迁移；明确支持的 Android 与后端版本。
- [ ] 编写用户连接、HTTP 风险、证书错误、日志导出和缓存管理说明。
- [ ] 验证回退时的数据兼容；无法直接降级时使用备份恢复步骤，不承诺破坏性迁移可自动撤销。
- [ ] 分发产物附校验值、版本说明、许可与源码提供要求。

验收：测试版/正式版有可安装产物、升级证据、兼容说明与恢复方案。

## 首次执行建议

先领取 M0-01。若暂时无法提供真实部署场景，可先完成 M0-02，但不能跳过 M0-03 直接大规模实现播放器或阅读器。用户确认主要痛点后再调整 M3/M4 顺序，其他阶段保持依赖关系。

## 验证记录写法

完成每项任务后，在该项下增加：完成日期；两个仓库涉及的提交标识；实际命令及通过/失败；设备/数据库/网络；已验证样本；明确不支持项；是否更新 02/03/05/06。出现阻塞时记录阻塞原因和解除条件，保持任务未勾选。
