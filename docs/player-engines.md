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

### LibVLC 的硬解 → 软解重试

LibVLC 第一次收到 `EncounteredError` 时，不直接判失败：把当前媒体改成
FFmpeg 软件解码（`:avcodec-hw=none` + `setHWDecoderEnabled(false, false)`）
并从原位置重载一次。手机硬解只覆盖 8-bit H.264/HEVC，Hi10P、10-bit HEVC
这类编码经常在硬解阶段失败，而 VLC 的自动回退并不可靠。

失败日志同时记录 `read/demux/decoded` 统计（`IMedia.Stats`）：

- `read=0 / demux=0`：基本是网络/地址问题（CDN 拒绝、链接过期等）；
- `read>0 / demux>0 / decoded=0`：媒体读到了但解不出来，属于解码问题。

只对当前媒体降级，换集或换地址后会重新从硬解开始尝试。

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

ExoPlayer 的触发条件是 `ERROR_CODE_IO_BAD_HTTP_STATUS`；LibVLC 在
`EncounteredError` 且统计为 `read=0 / demux=0`（一个字节都没读到）时触发。
日志里分别能看到「改走服务端中转」的提示。

## 解码失败自动换内核

手机上常见的 10-bit H.264（High 10 / Hi10P，VCB-Studio 等压制组常用）没有硬件
解码器，Android 自带的软解也只到 8-bit，ExoPlayer 会报 `ERROR_CODE_DECODING_FAILED`
（日志里是 `avc1.6E0033` 这类 High 10 profile、`format_supported=NO_EXCEEDS_CAPABILITIES`）。

播放页在系统内核下收到解码类错误（解码失败、格式超出能力、容器无法解析）时，会
自动切换到 LibVLC，从原位置续播，并用提示条说明原因。自动切换只作用于本次播放，
不写回 `AppPreferences.playerEngine`；用户手动在「更多 → 播放内核」里切换时才会
记住选择。每次播放最多自动切换一次，LibVLC 也失败时仍显示原来的错误层。

相关判断在 `PlayerEngineKind.shouldAutoFallback` / `automaticFallback`，有单元测试。


## 外挂字幕样式

服务端的 `/api/subtitles/:id` 会把 ASS/SSA 压平成 WebVTT（`assToVTT` 会剥掉
`{\...}` 标签），所以 App 额外使用原始 ASS：

- App 对 `codec=ass/ssa` 或 `delivery=ass` 的轨道额外生成
  `/api/subtitles/:id/ass?path=...` 地址（`PlaybackUrls.assSubtitleUrl`）。
- ExoPlayer 用 `MimeTypes.TEXT_SSA` 加载该地址，由 Media3 的 `SsaParser`
  还原行内颜色、粗体、斜体、下划线和位置（不支持自定义字体、描边与复杂特效）。
- LibVLC 内核把该地址作为 subtitle slave 加入，由 libass 按文件自带的字体、
  颜色、描边、定位和淡入淡出完整渲染；有多条 ASS 时默认选中第一条。
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

## 相关文件

- `core/player/PlayerEngineKind.kt`：枚举、持久化键与默认值（纯 Kotlin，带单元测试）
- `core/player/SubtitlePreference.kt`：字幕自动加载/优先语言与匹配规则（纯 Kotlin，带单元测试）
- `core/player/VideoPlayerEngine.kt`：统一接口与轨道/媒体模型
- `core/player/ExoVideoPlayerEngine.kt`
- `core/player/VlcVideoPlayerEngine.kt`
- `core/player/VideoPlayerEngineFactory.kt`
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

`compileDebugKotlin`、`testDebugUnitTest`、`lintDebug`、`assembleRelease` 均通过。
播放内核的真机行为（尤其是 LibVLC 的 HLS、外挂 WebVTT 字幕、倍速与音频直通）
尚未在真实设备上验证，发布前需要人工过一遍。
