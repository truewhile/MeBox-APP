# 弹幕流畅度优化实施计划

**Goal:** 在播放器进度间隔更新时保持弹幕逐帧移动，降低弹幕绘制的每帧开销。

**已确认设计：** 保留 Compose Canvas 和现有弹道；引入仅用于弹幕的平滑时钟，并复用文字排版结果。暂停、缓冲、倍速、拖动、换集和更换内核时重新同步，不修改解码与字幕下载逻辑。用户已在会话中确认实施。

**Tech Stack:** Kotlin、Compose、JUnit 4；不新增依赖。

## 1. 平滑时钟与回归测试

- 创建 `core/media/DanmakuPlaybackClock.kt` 和对应 JVM 测试。
- 接口：`positionMs(frameNanos: Long, sampledPositionMs: Long, playing: Boolean, speed: Float, discontinuity: Int = 0): Long`。
- 使用单调帧时间推进弹幕；播放器进度更新时小误差限速校准、大误差及回退立即重新同步。
- 同一播放进度长时间不更新时，最多外推 500ms，避免视频停滞后弹幕一直前进。
- 暂停、缓冲和倍速变化重新锚定。显式拖动通过 `discontinuity` 版本重新同步；换集/内核切换时重建时钟，即使拖动距离较小也不会被误当作采样误差。
- 先运行复现间隔采样导致停顿的失败测试，再实现并运行覆盖 60/120Hz、暂停恢复、缓冲、倍速、seek、长时间停滞与误差校准的测试。

## 2. 绘制热路径

- 修改 `core/media/Danmaku.kt`，增加直接遍历入口。
- 接口：`forEachActive(positionMs: Long, action: DanmakuFrameConsumer)`；SAM 回调的坐标使用原生 `float` 参数，避免坐标装箱。
- 保留 `activeAt` 兼容接口，通过相同遍历实现；Canvas 使用回调避免逐帧创建结果列表和每条弹幕位置对象。
- 增加时间边界、混合模式、前后 seek 一致性的回归测试。
- 修改 `feature/media/player/DanmakuOverlay.kt`：帧回调计算平滑位置，Canvas 仅读取位置；用容量 1024（与原测量缓存容量一致）的 LRU 复用 `TextLayoutResult`，仅缓存未命中时创建样式及排版。
- 密度、字体与测量器变化时重建缓存；固定单行排版，保留现有颜色、阴影、不透明度和弹道行为。

## 3. 播放页接入

- 修改 `feature/media/player/PlayerScreen.kt`，传入播放/缓冲状态、当前倍速、内核实例与 seek 版本。
- `seekTo` 和内核媒体重载时推进 seek 版本；媒体或内核变化使时钟重建。
- 停止播放时不再按帧改变 Canvas 位置，减少无效重绘。

## 4. 验证

- 定向运行 `:app:testDebugUnitTest --tests '*Danmaku*'`，确认失败再转为通过。
- 执行 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。
- 检查差异，确保未修改视频解码和字幕实现。
- 当前 `adb devices` 无设备：不能声明实测帧率或与哔哩哔哩等效；真机还需检查密集弹幕、暂停缓冲、seek、倍速和两种内核。

## 实施与验证结果（2026-10-18）

- 已完成平滑时钟、直接遍历、1024 项文字排版缓存及播放页接入。
- 新增 17 个回归用例（14 个时钟、3 个弹道）；使用原始进度采样的基线实现时，连续移动测试失败，平滑实现后通过。
- 复查发现异步短距离 seek 的最终采样可能误入小误差校准；增加两个失败用例复现，再通过等待首个变化采样的状态修复。暂停时保留进度采样，以便接收异步 seek 的最终落点，相同位置不会触发 Canvas 重绘。
- 最终命令：`gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug -PmeboxVersionCode=15 --console=plain`；执行成功。
- 全量 261 个单元测试，0 失败、0 忽略；Lint 无错误，项目仍有警告；Debug APK 的 versionCode 为 15。
- `javap` 确认绘制回调签名为 `draw(DanmakuComment, float, float)`，坐标不经过泛型装箱。
- APK 输出：`app/build/outputs/apk/debug/app-universal-debug.apk` 和各 CPU 架构分包。
- `git diff --check` 通过；本次未修改视频解码、播放器内核或字幕下载实现。
- 没有设备运行或帧耗时数据；上述 60/120Hz 为模拟帧时间单元测试，不是真机帧率结果。
