# 播放内核

播放页不直接依赖某个播放器，而是通过 `app.mebox.android.core.player.VideoPlayerEngine`
接口驱动。用户可以在「我的 → 播放设置」选择默认内核，也可以在播放页
「更多 → 播放内核」临时切换；切换时会记录当前位置，新内核从同一点继续播放。

选择结果保存在 `AppPreferences.playerEngine`（本机偏好，不写服务端）。

## 已接入

| 内核 | 依赖 | 说明 |
| --- | --- | --- |
| ExoPlayer（默认） | `androidx.media3:media3-exoplayer:1.7.1` | 系统硬件解码优先，解码器初始化失败时回退系统软解；清晰度/音轨/字幕/画面缩放支持最完整 |
| LibVLC | `org.videolan.android:libvlc-all:3.6.5` | 内置 FFmpeg，补足 RMVB、WMV、DTS 等系统不支持的格式；软解为主，功耗更高 |

LibVLC 是 LGPL-2.1，通过 Maven Central 分发，不需要自行编译 native 库。

### LibVLC 的画面框（黑边居中）

LibVLC 3.6 的画面输出由库里的 `VideoHelper.updateVideoSurfaces()` 自己算：
它按视频宽高比把 `SurfaceView` 的宽高写成绝对像素，但并不修正这块输出
区域相对宿主的落点。宿主比画面框宽时（手机横向 + 16:9 片源），画面就会
贴着左上角、右边留一整条黑边，看起来像“没有置中”，软解和硬解都一样。

App 不再把定位交给这段逻辑：

- `VlcVideoPlayerEngine` 从 `currentVideoTrack` 的宽高、SAR 和旋转算出显示
  宽高比（`VideoSurfaceBox.kt`，纯 Kotlin，带单元测试）；
- 按「适应屏幕 / 裁剪铺满 / 拉伸铺满」把 `VLCVideoLayout` 本身缩放到画面
  框并用 `Gravity.CENTER` 居中；宿主尺寸变化、轨道/视频输出就绪时重算；
- 画面视图和宿主同比例后，VLC 算出来的输出区域正好等于画面框，两侧黑边
  必然等宽；`VLCVideoLayout` 挂窗口时会把 LayoutParams 改回 MATCH_PARENT，
  所以额外挂了一个布局监听兜底。

### LibVLC 的硬解 → 软解重试

LibVLC 第一次收到 `EncounteredError` 时，不直接判失败：把当前媒体改成
FFmpeg 软件解码（`:avcodec-hw=none` + `setHWDecoderEnabled(false, false)`）
并从原位置重载一次。手机硬解只覆盖 8-bit H.264/HEVC，Hi10P、10-bit HEVC
这类编码经常在硬解阶段失败，而 VLC 的自动回退并不可靠。

失败日志同时记录 `read/demux/decoded` 统计（`IMedia.Stats`）和 `progress`：

- `progress=false` 且读取、解复用统计均为零：尚未观察到有效进展，优先按网络处理；
- 已解析出轨道、识别出时长、缓冲推进或读取字节增加：都算有效进展，不能单凭
  `read=0 / demux=0` 断定网络不通。VLC 在起播前可能尚未更新这些统计；
- 有有效进展但仍打不开时，自动模式才尝试软件解码；明确的网络失败不再空转重试软解。

只对当前媒体降级，换集或换地址后会重新从硬解开始尝试。

### 起播看门狗

LibVLC 打开文件失败时并不总会抛 `EncounteredError`，所以仍保留起播看门狗，
但不再固定十秒就重载：

- 每秒观察一次，读取字节增长、解析轨道、时长就绪和缓冲增长都会更新进展；
- 连续 20 秒无进展，或单轮启动总计超过 90 秒，才进入有次数限制的回退；
- `Playing` 后作废看门狗；用户暂停时停止计时，恢复未完成的起播时重新计时；
- 同一轮失败只允许排队一次回退，换集/退出后旧的定时器和重载不能操作新媒体；
- 保留 115 CDN 已有的 HTTPS → HTTP 兼容重试，但只允许已知 `.115cdn.net`
  域名，不降级带访问令牌的服务器地址，也不改写任意第三方 HTTPS URL；
- native 日志的查询参数值会脱敏，保留最新 HTTP 状态、Range 和错误，避免旧日志
  重复嵌套或被单条日志长度限制截掉关键结尾。

### 2026-10-18：域名播放与外挂字幕阻塞

当日日志中，同一 MKV 使用域名服务器时已经获得 CDN 的 HTTP 206 并解析了轨道，
随后 VLC 在输入线程逐条打开服务器上的远程 ASS（含弹幕 ASS）。这些 HTTPS 请求
的冷连接耗时约 5.5 秒，而旧的十秒看门狗又将尚未刷新的零字节统计误认为网络失败，
导致 HTTPS、HTTP、服务端中转连续重载。内网字幕请求只需十几毫秒，所以差异被掩盖。
这与 High 10 片源需要从系统解码回退到 FFmpeg 是两个叠加的问题。

修复后的视频与字幕生命周期分开：

- 初始媒体不附加远程字幕，先等待 `Playing`；
- `PlaybackSubtitleLoader` 按语言偏好优先下载默认字幕，后续字幕串行后台下载；
- `SubtitleDownloader` 复用 App 的 OkHttp 连接池、代理与 TLS 行为，保持原始 ASS
  字节，VLC 的 libass 只读取下载完成的本地文件，样式无需压平；
- 单条字幕总请求期限 20 秒、最大 8 MiB；失败不重载视频，其它字幕继续尝试；
- 用户手动切换/关闭字幕后，迟到的下载不会重新覆盖选择；换集与退出取消下载并
  清理当前会话的临时字幕，暂停恢复不会重复下载或附加同一批字幕。

这些行为有 JVM 回归测试；实际设备上的视频输出、动态字幕轨和公网链路仍需验收。

## 解码方式（硬解 / 软解）

「我的 → 播放设置 → 解码方式」可以手动指定内核走硬件还是软件解码，
保存在 `AppPreferences.decoderMode`，下次起播生效；电视端「我的」也有同一组选项：

- **自动（默认）**：优先硬件解码；失败或起播卡住时按内核规则回退软件解码。
- **硬件解码**：尽量强制硬件解码，不自动回退软件；适合省电和 4K，
  但 10-bit H.264（Hi10P）这类编码可能直接失败。
- **软件解码**：跳过硬件解码器。LibVLC 用内置 FFmpeg；ExoPlayer 用 Android
  自带的软件解码器（没有 FFmpeg 扩展，Hi10P 仍可能不支持，此时自动换内核
  会继续走 LibVLC + FFmpeg）。

实现落点：

- ExoPlayer：通过 `DefaultRenderersFactory.setMediaCodecSelector` 过滤
  `softwareOnly` / `hardwareAccelerated` 的解码器；强制硬件时同时关闭
  decoder fallback。
- LibVLC：软件模式使用 `:avcodec-hw=none` + `setHWDecoderEnabled(false, false)`；
  硬件模式使用 `setHWDecoderEnabled(true, true)`（force），不自动回退。

## 直连 403 时改走服务端中转

115 直链是绑定 UA 的签名地址。如果客户端直连 CDN 收到 403/404（常见于
客户端网络/地区被 CDN 拒绝、或直链被风控），两个内核都会自动改走服务端的
同源转发地址再试一次：

- 地址来自 `/api/playback/{id}/info` 的 `stream_url`，追加 `proxy=1`，
  例如 `/api/stream/{id}?token=...&proxy=1`；
- 服务端用 `ProxyMediaDirect` 从 CDN 拉流，按 Range 原样转发，客户端只
  面对自己的 MeBox 服务器；
- 只在直连失败后兜底，正常播放仍走 CDN，避免把网盘流量压到服务器上；
- 每次播放最多尝试一次，服务端中转也失败时仍显示原来的错误页。

ExoPlayer 的触发条件是 `ERROR_CODE_IO_BAD_HTTP_STATUS`；LibVLC 在打开失败或
起播超时时，结合读取字节、轨道、时长、缓冲进展决定网络回退或软解重试，
不能只用 `read=0 / demux=0` 判定。日志里能看到「改走服务端中转」的提示。

## 解码失败自动换内核

手机上常见的 10-bit H.264（High 10 / Hi10P，VCB-Studio 等压制组常用）没有硬件
解码器，Android 自带的软解也只到 8-bit，ExoPlayer 会报 `ERROR_CODE_DECODING_FAILED`
（日志里是 `avc1.6E0033` 这类 High 10 profile、`format_supported=NO_EXCEEDS_CAPABILITIES`）。

播放页在系统内核下收到解码类错误（解码失败、格式超出能力、容器无法解析）时，会
自动切换到 LibVLC，从原位置续播，并用提示条说明原因。自动切换只作用于本次播放，
不写回 `AppPreferences.playerEngine`；用户手动在「更多 → 播放内核」里切换时才会
记住选择。每次播放最多自动切换一次，LibVLC 也失败时仍显示原来的错误层。

相关判断在 `PlayerEngineKind.shouldAutoFallback` / `automaticFallback`，有单元测试。

## VR / 全景

播放页「更多 → VR / 全景」可以在普通播放与全景播放之间切换，支持：

- 投影：360°（等距柱状）与 180° 半球；
- 立体格式：单目、左右并排（SBS）、上下并排（TB / OU）；
- 显示方式：自动（立体片源自动分屏）、手机全屏、头显双眼分屏；
- 头部追踪：优先 `TYPE_GAME_ROTATION_VECTOR`，没有时退回 `TYPE_ROTATION_VECTOR`；
  两者都没有则只能手指拖动；
- 纸盒镜头畸变校正与「重新居中」（只重置偏航，俯仰/横滚继续跟随重力）。

实现不依赖第三方 VR SDK：

1. 两个内核都把解码帧输出到 `SurfaceTexture`。ExoPlayer 用
   `player.setVideoSurface(Surface)`；LibVLC 用
   `mediaPlayer.vlcVout.setVideoSurface(SurfaceTexture)` 后 `attachViews()`。
2. `VrVideoRenderer` 用全屏四边形做片元级射线：屏幕坐标 → 相机方向 →
   世界方向 → 等距柱状 UV。没有球面网格，所以没有接缝，切换投影只改 uniform。
3. 立体源在片元着色器里按左/右眼采样纹理的对应半幅；显示屏上是否分屏由
   `VrDisplayMode` 决定。头显模式把 GL viewport 一分为二，并可选镜头畸变。
4. 传感器部分直接使用 9 元旋转矩阵，不经过欧拉角：手机竖立或戴在头显里时
   俯仰接近 ±90°，`SensorManager.getOrientation` 会进入万向锁。显示旋转
   （90°/180°/270°）用 `remapCoordinateSystem` 映射到屏幕坐标。
5. 投影/立体/显示方式/镜头校正保存在 `AppPreferences`；是否进入 VR 只对本次
   播放有效，不跨片源保留。

片源识别按标题/文件名猜测 360/180 与 SBS/TB，例如 `VR180`、`360`、`SBS`、
`OU`、`左右`、`上下`；识别只用于「自动」选项，用户手动选择后不会被覆盖。
服务端和容器目前没有可靠的球面元数据输入。

已知限制：

- LibVLC 的字幕由 VLC 渲染在视频输出上，进入 VR 后会随球面弯曲；系统内核使用
  `VrVideoView` 里的两个 `SubtitleView`，左右眼各一份。
- VR 下暂时隐藏弹幕层。
- 镜头校正使用固定经验系数，不读取具体头显的镜片参数。
- 手机全屏模式只显示左眼画面；没有立体偏移。

## 外挂字幕样式

服务端的 `/api/subtitles/:id` 会把 ASS/SSA 压平成 WebVTT（`assToVTT` 会剥掉
`{\...}` 标签），所以 App 额外使用原始 ASS：

- App 对 `codec=ass/ssa` 或 `delivery=ass` 的轨道额外生成
  `/api/subtitles/:id/ass?path=...` 地址（`PlaybackUrls.assSubtitleUrl`）。
- ExoPlayer 用 `MimeTypes.TEXT_SSA` 加载该地址，由 Media3 的 `SsaParser`
  还原行内颜色、粗体、斜体、下划线和位置（不支持自定义字体、描边与复杂特效）。
- LibVLC 在视频起播后用共享 OkHttp 客户端下载原始 ASS，把本地文件作为
  subtitle slave 加入，由 libass 按原样式渲染；有多条字幕时先下载偏好匹配的一条。
- 字幕面板在系统内核下会提示并给出「切换到 LibVLC 渲染 ASS 样式」入口。
- 中日双语字幕属于同一时刻的两条 cue，Media3 的 `SubtitleView` 会逐条按各自
  位置绘制，两条都落在默认底部坐标时会互相重叠。App 用
  `core/player/SubtitleCueStacker.kt` 把位置相同（行位置、锚点、对齐方式一致）
  的并发 cue 合并成一条多行 cue，再由 `SubtitleView` 正常排版；位置不同的
  顶部注释等 cue 不受影响。
- 字体依赖设备上可用的字体集合；ASS 里指定的非常用字体找不到时会被替换，
  但颜色、位置、描边等样式仍然生效。

## 字幕自动加载

「我的 → 字幕」可以配置两项本机偏好（保存在 `AppPreferences`，不写服务端）：

- **自动加载字幕**：开播时自动选中一条字幕；关闭后起播不选字幕，需要时在
  播放页「字幕」面板手动打开。
- **优先语言**：跟随片源 / 中文优先 / 英文优先 / 日文优先。中文优先会先找
  简体、再找未标注简繁的中文、最后找繁体；指定语言没有匹配时退回第一条，
  保证“有字幕就能看到”。

匹配规则在 `core/player/SubtitlePreference.kt`（纯 Kotlin，带单元测试）：

- 优先看轨道语言标签（`zh`/`zho`/`chi`/`cmn`、`en`/`eng`、`ja`/`jpn`，含
  `zh-Hans`、`zh-TW` 这类带地区后缀的值）；
- 没有语言元数据时再看轨道名，覆盖「简体中文」「English SDH」「日本語」
  这类只有文件名/标题的外挂字幕。

两个内核的落点：

- ExoPlayer：播放页在首次收到字幕轨时按偏好调用 `selectTextTrack`；用户
  手动选过字幕后本次播放不再自动覆盖。
- LibVLC：装载媒体时按同一偏好决定哪条外挂字幕作为默认字幕；「跟随片源」
  保持旧行为，优先选一条 ASS，交给 libass 渲染原始样式。关闭自动加载时
  不选任何外挂字幕，并关闭内嵌字幕轨。

### 字幕名称

字幕面板里的名称统一在 `core/media/SubtitleLabel.kt` 生成：

- 服务端外挂字幕只给文件名后缀（`chs`、`chs[BilibiliID_danmu]`），这里折成
  「简体中文（外挂）」「简体中文 · 弹幕（外挂）」；`chi` 这类未区分简繁的
  语言标记显示为「中文」。
- 保留服务端 label 里「（默认）」「（强制）」这类标记；ASS/SSA 追加
  「· ASS 样式」。
- LibVLC 3 没有给外挂字幕起名的接口，轨道名只有「Track 2」。内核记录
  `addSlave` 的顺序，等 VLC 报出轨道后用上面的名称覆盖；内嵌轨则由
  「Track 1 - [Chinese]」改成「内嵌字幕（中文）」。外挂轨数量与 VLC 报出的
  轨道数对不上（某条加载失败）时只美化内嵌轨，避免名字错位。

## 相关文件

- `core/player/PlayerEngineKind.kt`：枚举、持久化键与默认值（纯 Kotlin，带单元测试）
- `core/player/SubtitlePreference.kt`：字幕自动加载/优先语言与匹配规则（纯 Kotlin，带单元测试）
- `core/player/VideoPlayerEngine.kt`：统一接口与轨道/媒体模型
- `core/player/VideoSurfaceBox.kt`：LibVLC 画面框的宽高比与落点计算（纯 Kotlin，带单元测试）
- `core/media/SubtitleLabel.kt`：字幕轨可读名称（纯 Kotlin，带单元测试）
- `core/player/ExoVideoPlayerEngine.kt`
- `core/player/VlcVideoPlayerEngine.kt`
- `core/player/VideoPlayerEngineFactory.kt`
- `core/player/PlaybackStartupWatchdog.kt`：按进展判断起播超时（纯 Kotlin，带单元测试）
- `core/player/PlaybackSubtitleLoader.kt`：视频起播后的字幕会话、选择与取消（带单元测试）
- `core/player/SubtitleDownloader.kt`：有期限和大小限制的原始字幕下载（带单元测试）
- `core/player/PlaybackNetworkFallback.kt`：CDN 回退白名单与日志脱敏（带单元测试）
- `core/player/vr/VrMode.kt`：投影/立体/显示方式、自动识别与参数展开（纯 Kotlin，带单元测试）
- `core/player/vr/VrOrientation.kt`：传感器矩阵到着色器相机矩阵、重定位与手势旋转（纯 Kotlin，带单元测试）
- `core/player/vr/VrVideoRenderer.kt`：OpenGL 全景射线渲染与左右眼采样
- `core/player/vr/VrVideoView.kt`：GLSurfaceView、陀螺仪、左右眼字幕与生命周期
- `feature/media/player/PlayerScreen.kt`：只依赖统一接口
- `feature/AppScreens.kt`：我的 → 播放设置 / 字幕
- `feature/tv/TvMediaScreens.kt`：电视端「我的」的同名字幕设置
- `feature/media/player/PlayerControls.kt`：播放页内切换入口

## 未接入的候选

- `media3-exoplayer-ffmpeg`：官方没有在 Maven Central 发布预编译产物，需要
  用 NDK 自行编译 FFmpeg 扩展并作为本地模块引入，构建链成本高。
- IJKPlayer：最后版本 0.8.8（2020 年），只存在于已下线的 JCenter 及其镜像，
  不再适配新系统 / 新编码，不建议引入。
- MPV：`dev.jdtech.mpv:libmpv` 在 Maven Central 有发布，但需要一套独立的
  JNI 初始化与画面绑定逻辑，且字幕/轨道接口与现有模型差异较大，可后续按需补充。

## 新增内核的步骤

1. 在 `PlayerEngineKind` 增加枚举项（key、名称、说明）。
2. 实现 `VideoPlayerEngine`：
   - `attach(host)` 把渲染视图挂到传入的 `FrameLayout`；
   - `setMedia` 支持在画面挂载前缓存；
   - 通过 `listener` 回调播放状态、缓冲、轨道、倍速、错误和结束；
   - 轨道 id 只需在自己的实例内稳定。
3. 在 `createVideoPlayerEngine` 增加分支。
4. 设置页和播放页会自动列出新的枚举项，无需改 UI。
5. 为 `PlayerEngineKind.fromKey` 的兼容行为补单元测试。

## 验证状态

此前内核接入阶段已通过 `assembleRelease`。2026-10-18 本次域名起播修复重新执行
`testDebugUnitTest`、`lintDebug`、`assembleDebug`，均通过：244 个单元测试无失败，
Lint 为 0 错误、64 个警告、2 个提示，生成 versionCode 14 的 Debug APK。
本次没有重新验证 Release 构建。

播放内核的真机行为（尤其是域名/内网同片源对比、High 10 软解、起播后动态外挂
ASS/WebVTT 字幕、手动关闭字幕和换集取消）仍需在真实设备上验证。
