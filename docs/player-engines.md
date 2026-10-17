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
- 字体依赖设备上可用的字体集合；ASS 里指定的非常用字体找不到时会被替换，
  但颜色、位置、描边等样式仍然生效。

## 相关文件

- `core/player/PlayerEngineKind.kt`：枚举、持久化键与默认值（纯 Kotlin，带单元测试）
- `core/player/VideoPlayerEngine.kt`：统一接口与轨道/媒体模型
- `core/player/ExoVideoPlayerEngine.kt`
- `core/player/VlcVideoPlayerEngine.kt`
- `core/player/VideoPlayerEngineFactory.kt`
- `feature/media/player/PlayerScreen.kt`：只依赖统一接口
- `feature/AppScreens.kt`：我的 → 播放设置
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
