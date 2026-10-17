# 02 MeBox 后端改造方案

日期：2026-10-17  
状态：新增协议的设计建议；执行对应任务时冻结为 OpenAPI 与契约测试。  
后端根目录：`D:\project\MeBox`。

## 1. 改造方式

保留既有 `/api` 和 Emby/Jellyfin 入口。新增 `/api/v1` 移动端稳定契约，新 handler 调用现有 service/repository，按职责增加适配器。

首版继续复用已满足需求的媒体库、搜索、详情、字幕、收藏和阅读接口，不要求一次将整个后台迁移到 v1。App 网络层按“既有接口”和“v1 接口”区分响应模型。

所有下面列出的 v1 端点均为拟新增，不是现有可调用 API。

## 2. 现有接口与推荐复用方式

### 2.1 认证、用户与播放配置

- `POST /api/auth/login`：现有用户名/密码登录，返回 user、tokens。
- `POST /api/auth/refresh`：现有刷新端点，从 JSON 读取 refresh_token。
- `GET /api/me`、`GET /api/auth/permissions`：用户与权限。
- `GET /api/me/devices` 及 kick 接口：已有设备管理入口。
- `GET /api/play-profiles` 与 `POST /api/play-profiles/:id/verify-pin`：播放配置与 PIN 校验。
- 既有播放配置通过 `X-Play-Profile-ID` 和 `X-Play-Profile-PIN-Token` 传递；移动端不能自行绕过。

旧登录返回 user/tokens，旧刷新返回 code/message/data，且 data 中访问令牌字段名是 token；两者形状不同，不能共用未经适配的 DTO。认证采用 v1 包装现有认证服务，以统一返回形状、明确设备绑定和错误语义；旧网页继续使用旧端点。

### 2.2 影视

- `GET /api/libraries`、`GET /api/libraries/:id/media`：媒体库与分页内容。
- `GET /api/libraries/:id/series`、`GET /api/media/:id/episodes`：剧集聚合与选集；具体页面按现有返回形状适配。
- `GET /api/media/:id`、`GET /api/media`：详情与搜索。
- `GET /api/playback/:id/info`：通用播放信息。
- `GET /api/media/:id/playback`：当前偏向 115 的播放信息，不等价于统一播放协议。
- `GET /api/stream/:id`、`GET /api/hls/:id/index.m3u8`：直连与本地 HLS。
- `GET /api/cloud115/media/:id/master.m3u8`：115 HLS。
- `GET /api/media/:id/subtitles`、`GET /api/subtitles/:id`：字幕列表与资源。
- `GET /api/playback/:id/resume`、`POST /api/history`：续播与带会话信息的进度。
- `POST /api/media/:id/favorite`、`DELETE /api/media/:id/favorite`：目标状态式收藏；优先于切换式 `/favourites/:id` 做可重试写入。

### 2.3 阅读

- `/api/reader/sources`：书源。
- `/api/reader/search`：聚合搜索。
- `/api/reader/books`：书架。
- `/api/reader/books/:id/chapters`、`content`、`content-batch`：目录和正文；批量正文目前最多 64 章。
- `/api/reader/books/:id/progress`：现有章节索引 + pos 进度。
- `/api/reader/profile`、`book-groups`：用户阅读偏好和分组。
- `/api/reader/browser/pending`、`result`、`xhr`：特殊页面待办与服务端桥接。

服务端正文缓存不等于手机离线缓存；不要把批量返回的媒体地址清单当成资源文件已经下载。

## 3. 新协议的全局约束

- 路径前缀 `/api/v1`；JSON 字段使用 snake_case。
- 时间点使用 RFC 3339 UTC 字符串；时长和播放位置使用整数毫秒；超时/等待字段明确以秒结尾。
- resource_id、server_id、device_id、auth_session_id 和 playback_session_id 是不同身份，不复用成同一个字段。
- 服务端持久保存随机 server_id。普通重启、升级与同实例数据恢复保持不变；将备份克隆成独立服务时显式重新生成，防止两个实例共享身份。
- `X-Request-ID` 用于诊断；非法或过长客户端值由服务器重新生成，不能把它当权限凭证。
- `Idempotency-Key` 用于约定的创建/写入端点；去重键限定在用户、端点与操作参数内，重复键但参数不同返回 409。
- 集合正常为空返回 `[]`；未知字段允许客户端忽略，已公布字段不得静默改变类型。
- 能力开关由真实运行状态与接口实现共同决定；仅 handler 存在但功能不可用时不能宣称支持。

## 4. 服务器识别

拟新增 `GET /api/v1/server-info`，无需登录，只暴露最小连接信息。

字段：name、server_id、server_version、api_version、min_supported_app_version_code、capabilities。

capabilities 首批键：device_sessions、playback_sessions、reader_progress_v1、reader_content_batch、local_transcode。每项均为布尔值，不承诺读取媒体格式等同设备支持。

- `server_version` 来自真实构建版本，不继续硬编码。
- 功能运行失败与版本不兼容分开处理。
- 返回 `Cache-Control: no-store`；不暴露媒体路径、账号列表或内部配置。
- 旧服务没有该端点时，App 可显示“需要升级服务器”；开发验证模式允许旧 API 探测，但不能自动谎报拥有设备会话等能力。
- 首版只承诺根路径部署。对反代子路径返回明确兼容提示，不在客户端偷偷丢弃路径。

拟新增文件：`internal/handler/mobile_server_info.go`、`mobile_routes.go`、`internal/service/server_identity.go`；在现有 `handler.go` 注册。持久身份优先复用配置/设置存储模式，不新建独立数据库。

## 5. 认证与设备会话

### 5.1 新端点

- `POST /api/v1/auth/login`：username、password、device_id、device_name、client_name、client_version。
- `POST /api/v1/auth/refresh`：refresh_token；设备身份由会话关联校验，不仅相信请求里的 device_id。
- `GET /api/v1/auth/session`：当前用户和认证会话最小信息，用于恢复与诊断。
- `POST /api/v1/auth/logout`：撤销当前认证会话，重复退出返回成功。

登录/刷新返回 auth_session_id、user、tokens；tokens 保留 access_token、refresh_token、expires_in、token_type。

### 5.2 服务端语义

- 设备 ID 为 App 安装生成的随机标识，不使用 IMEI、MAC 或可追踪硬件标识。
- 新访问令牌增加可选 sid，会话绑定 user_id、device_id、客户端和撤销状态。
- 刷新令牌记录增加 auth_session_id，与既有无关联令牌兼容；旧数据不强制绑定到伪造设备。
- 新 v1 认证要求 sid；访问旧 `/api` 的新令牌也必须检查 sid 对应会话，防止被踢后绕过到旧端点。
- 旧 token 没有 sid 时保留既有路径与权限逻辑；不能要求现有全部客户端同步升级。
- 踢出一台设备只撤销该设备关联会话和新协议派生资源访问；kick-all/改密策略与现有服务整合并测试。
- 新令牌派生的外部播放 token 继承 sid，并校验会话；旧阅读 HMAC 链接无设备/时效信息，不能承诺逐设备即时撤销。M4 的新移动阅读资源入口使用受认证会话保护的限时资源引用，既有网页旧签名作为独立兼容路径记录限制。
- JWT 有效期与会话可用性分开；仅验证 JWT 签名不足以实现即时设备踢出。
- 暂时性数据库或基础设施错误返回 503 + retryable，不伪装成账号不存在或凭证已失效。
- 登录类接口继续限流；客户端名称/设备 ID 仅用于识别，不代替认证，也不允许绕过并发账号限制。

### 5.3 轮换与恢复边界

复用已有“同 token 并发共享刷新”和短宽限复用机制，先解决安卓客户端单次刷新、原子保存和常规服务器重启后的恢复。

已知边界：当前轮换复用结果在内存。若新令牌已签发并撤销旧令牌，但客户端没收到响应，随后服务器重启，旧令牌可能无法恢复。首版必须测试并明确提示重新认证，不能把该边界隐藏为普通网络故障；如产品要求此窗口也自动恢复，需要额外设计带加密短期结果存储的持久幂等轮换，另设任务和安全审查。

拟新增：`internal/model/auth_session.go`、`internal/repository/auth_session_repository.go`、`internal/service/auth_session.go`、`internal/handler/mobile_auth.go`、`internal/middleware/auth_session.go`。

现有改动入口：`auth.go`、`token_svc.go`、`token_refresh_rotation.go`、`model/refresh_token.go`、`middleware/auth.go`、设备服务及模型注册/迁移。迁移必须加字段与索引而非破坏性清库。

## 6. 统一播放会话

### 6.1 创建与返回

拟新增 `POST /api/v1/playback/sessions`。

请求字段：media_id、profile_id、client_capabilities、preferred_audio_language、preferred_subtitle_language、start_position_ms。创建可携带 Idempotency-Key，网络重试不重复占用转码槽位。

client_capabilities 包含容器、音视频解码、分辨率和字幕呈现能力，由实际设备探测结果生成；格式具体枚举在 M3-02 的契约测试中冻结。

响应字段：playback_session_id、media_id、resume_position_ms、duration_ms、candidates、audio_tracks、subtitle_tracks、heartbeat_interval_sec。

每个 candidate 明确：candidate_id、mode、url、mime_type、required_headers、expires_at、timeline_offset_ms、seekable、fallback_reason。

mode 限定 direct、remux、cloud_hls、transcode_hls；未实现/不可用的模式不返回。`required_headers` 只能给相应资源请求使用，不允许客户端泛化为所有域名的默认头。

### 6.2 资源和鉴权

- 流媒体优先使用可带 Authorization 的受保护请求；只把 MeBox 身份头发送给已确认的服务器来源。
- 媒体 URL 需要跨组件使用时签发限时、限资源、绑定会话的票据；覆盖播放列表、分片和字幕，不把刷新令牌放进 URL。
- 票据定义到期/撤销/资源不存在的不同错误。到期后 App 重新请求候选，并按原绝对播放位置恢复。
- 原有阅读签名路径继续兼容；新移动阅读正文与资源按第 7.2 节使用带 Authorization 的会话受保护入口，和无时效旧签名路径明确区分。
- 302 跨来源时不转发 MeBox token；第三方必需请求头由资源适配器按来源白名单给出。

### 6.3 会话生命周期与恢复

- `POST /api/v1/playback/sessions/:id/events`：event_id、sequence、event_type、candidate_id、position_ms、duration_ms。
- event_type 为 started、progress、paused、resumed、stopped、failed；服务端按用户/会话校验并去重。
- `POST /api/v1/playback/sessions/:id/resolve`：preferred_mode、position_ms、audio_track_id、subtitle_track_id、quality_id；用于刷新或切换候选。
- `DELETE /api/v1/playback/sessions/:id`：幂等释放当前会话，其他会话不受影响。
- 服务端给新播放会话分配可比较的顺序，不仅依赖不同手机的时间戳；同会话使用 sequence 排序。
- 空闲租约超时回收；App 未完成 DELETE 或进程被杀不永久保留 ffmpeg。
- 服务器重启后播放会话不存在，App 创建新会话并恢复本地位置；不能无限重试旧会话。
- 每次返回的 HLS 候选明确 timeline_offset_ms，播放器位置与绝对媒体进度统一转换。

### 6.4 本地转码隔离

先让新协议作业和缓存按播放会话/候选代次隔离；不得直接复用旧媒体 ID 路径处理不同 seek/质量/音轨。

相同媒体、起点、码率、字幕和音轨的共享可以后续优化，需引用计数和严格作业键。旧 `/api/hls/:id` 保留旧调用方式，新会话停止不能调用会影响所有用户的媒体级 StopJob。

现有可复用入口：`playback_extra.go`、`cloud115_playback.go`、`streaming.go`、`service/stream_hls.go`、`transcoder*.go`、远程 Emby 服务。新增适配文件建议为 `mobile_playback.go`、`playback_session.go`、`playback_resolver.go`、`playback_session_events.go`，不继续堆进单个大型 handler。

## 7. 进度一致性

### 7.1 影视

新事件接口最终调用现有 PlaybackService/HistoryRepository 保存权威进度。保留现有乱序保护，增加服务端会话顺序、事件幂等和播放配置影响范围的验证。

在线写入只接受当前或更新会话的合法事件。离线旧会话补传若已有更新进度，返回冲突而非覆盖；App 可提示用户选择本地或服务端，显式选择会创建新会话/新写入。

用户主动回看是合法操作，不能用“位置只增不减”代替事件排序。

### 7.2 阅读

拟新增 `GET /api/v1/reader/books/:id/content` 与 `POST /api/v1/reader/books/:id/content-batch`，包装现有正文与批量服务，增加 content_version，将正文中的私有媒体地址改写为会话受保护的资源引用；批量仍最多 64 章。拟新增 `GET/HEAD /api/v1/reader/resources/:resource_id`，要求 Authorization 与有效 sid，资源引用绑定用户/书籍/会话且有过期时间，服务端处理 Range 与必要的播放列表重写。资源 ID 不是免认证凭证，原始上游地址/Cookie 不外发。

原生图片、音频和正文资源请求统一携带受限来源的认证头；引用过期后重新获取正文清单。App 不继续使用旧无时效签名链接作为新协议撤销能力的替代。特殊 WebView 待办仍遵循独立桥接兼容策略。

拟新增 `GET /api/v1/reader/books/:id/progress` 与 `PUT /api/v1/reader/books/:id/progress`。

写入字段：event_id、base_revision、chapter_identity、chapter_index_hint、chapter_title、content_version、position。

position 是带类型的联合结构：

- text：paragraph_index + char_offset。
- image：image_index + offset_ratio，比例范围 0—1。
- audio：position_ms。

读取/写入成功均返回 revision、updated_at 与完整锚点。PUT 以 base_revision 条件更新；版本已变化返回 409 和服务端当前版本，禁止静默覆盖。

章节身份优先依据稳定绝对章节 URL/本地逻辑章节键；chapter_index_hint 仅作兼容提示。换源、目录重建或内容版本变化时先重映射；找不到时回到章节开头并提示，不猜测字符偏移。

旧进度只具有页码时，不承诺无损迁移到字符位置；保留章节恢复能力。旧协议仍可读写既有字段，但只要确实发生旧进度变更，必须同步增加 revision，并使受影响的精细锚点失效，避免网页与 App 并用时版本检查形同虚设。

阅读主题/分组继续复用现有接口；M4 明确设备级和用户级偏好边界。音频毫秒与旧整秒字段转换只在兼容适配层处理。

## 8. 错误、事件与缓存协议

新 JSON 错误统一为 code、message、request_id、retryable，可选 retry_after_sec、details。HTTP 状态保留语义。

首批稳定 code：INVALID_ARGUMENT、INVALID_CREDENTIALS、TOKEN_EXPIRED、SESSION_REVOKED、ACCOUNT_DISABLED、ACCOUNT_EXPIRED、PERMISSION_DENIED、PROFILE_PIN_REQUIRED、RATE_LIMITED、UPSTREAM_UNAVAILABLE、TRANSCODE_BUSY、PLAYBACK_SESSION_NOT_FOUND、RESOURCE_EXPIRED、PROGRESS_CONFLICT、SERVICE_UNAVAILABLE。

- 401：凭证或认证会话问题；由 code 决定刷新或重新认证。
- 403：权限、账号状态或 PIN；不盲目刷新。
- 409：版本冲突或业务等待状态；客户端按 code 分流。
- 429：遵守 Retry-After；不能触发立即重试风暴。
- 5xx：优先保留会话与本地数据；仅对安全操作有限重试。

首版长连接只作刷新提示，App 回前台主动对账。后台不保持永久 WebSocket。M4 已提供正文 content_version 和临时资源引用；增量游标、删除标记和持久离线下载清单放到 M5，不能把未实现的全量刷新称为可靠增量同步。

## 9. 数据迁移、兼容和回滚

- 模型新增后接入 `internal/model` 的注册与现有数据库迁移机制。
- 同时验证 SQLite 和 PostgreSQL，包含旧库回填、重复执行和唯一约束。
- 无 sid 的历史令牌、旧进度和旧网页流程保留既有兼容路径。
- 新协议通过能力开关发布；关闭新入口后旧网页仍能使用。
- 回滚前先检查新字段是否向旧程序兼容；不把“可回滚”理解为自动反向执行破坏性迁移。
- 认证、播放会话、资源票据及权限均需契约和越权测试；详细用例见 05。
