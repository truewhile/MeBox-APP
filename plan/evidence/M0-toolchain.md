# M0 工具链记录

日期：2026-10-17  
结论：Android 工程可构建、可安装、可在模拟器运行；发布签名链路已用临时密钥验证，尚未使用正式密钥与真机。

## 1. 构建环境

| 组件 | 版本 / 路径 |
| --- | --- |
| 操作系统 | Windows 10.0.26200 |
| Go | go1.26.6 windows/amd64 |
| JDK | OpenJDK 21.0.12.1 LTS（Microsoft Build） |
| Android SDK | `D:\Android\Sdk` |
| SDK Platforms | android-36、android-37.0 |
| Build Tools | 36.1.0、37.0.0 |
| platform-tools | 含 `adb.exe` |
| Gradle | 8.13（Wrapper，`gradle/wrapper/gradle-wrapper.properties`） |
| AGP | 8.10.1 |
| Kotlin | 2.1.21（含 Compose Compiler 插件） |
| Compose BOM | 2025.05.01 |
| Media3 | 1.7.1（exoplayer / exoplayer-hls / ui） |
| Coil | 2.7.0（coil-compose） |
| compileSdk / targetSdk | 36 / 36 |
| minSdk | 26 |
| 包名 | `app.mebox.android` |
| versionCode / versionName | 1 / 0.1.0 |

## 2. 实际执行命令与结果

工作目录 `D:\project\MeBox-APP`：

- `.\gradlew.bat :app:testDebugUnitTest --tests "*ServerAddressTest"` → 16 项通过。
- `.\gradlew.bat :app:testDebugUnitTest` → 45 项通过（地址解析 16、认证协调 7、
  v1 网络契约 6、服务器兼容 3、错误文案 5、图片地址 4、播放地址 4）。
- `.\gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL，产物
  `app/build/outputs/apk/debug/app-debug.apk`。
- `.\gradlew.bat :app:lintDebug` → 0 errors，28 warnings（依赖版本提示、
  KTX 建议与 Coil 组合本地 API 提示等）。

启动的模拟器：

- AVD：`MeBox_API36`（x86_64, android-36, google_apis），
  `ANDROID_AVD_HOME=D:\Android\avd`，无窗口模式。
- 安装与启动：`adb install -r app-debug.apk`、`adb shell am start -n app.mebox.android/.MainActivity`。
- 实际验证：地址→HTTP 风险确认→登录 admin→账号页；冷启动自动恢复会话；
  退出后回到连接页，再次冷启动进入登录页。`logcat` 无 `AndroidRuntime` 异常。
- 第二轮测试补充：子路径被拒绝、错误密码提示、后台踢出后强制重新登录、
  非 MeBox 服务器提示、要求更高 versionCode 的服务器被拒绝、横竖屏切换保持
  账号状态；`logcat` 中未出现密码、`Bearer ` 或 `refresh_token`。
  测试中发现并修复：`min_supported_app_version_code` 此前未参与连接判断，
  现已由 `ServerCompatibility` 统一判断并补单元测试。

第三轮（影视模块）模拟器验证：

- 首页显示继续观看、最近添加与媒体库；海报经 `/api/img` 图片代理加载。
- 影视页显示媒体库网格；进入动漫库后按剧集卡片分页展示，集数角标正常。
- 详情页显示背景图、简介、选集与收藏按钮；收藏可切换为“已收藏”。
- 搜索 `Devadasy` 返回对应媒体。
- 原生播放：远程 Emby MKV 直链起播，进度条从 00:35 推进；退出后重新进入
  从上次位置继续（约 01:29），进度写入服务端（position 70922ms / duration
  4192808ms）。
- 播放器失败后 HLS 回退逻辑已实现，本轮样本未触发转码路径。
- 启动器图标已替换为后端 `web/public/brand/logo-192.png` 生成的
  adaptive/legacy 图标，模拟器应用列表显示正常。

## 3. 已知限制

- 未配置 release 签名；未做覆盖升级与数据库迁移测试。
- 未运行 `connectedDebugAndroidTest`（本次未编写仪器测试）。
- 模拟器 API 36 不代表所有手机；真机播放、后台音频、省电策略未验证。
- 影视模块只用远程 Emby 样本验证起播/续播；本地转码、115、STRM 与多种
  字幕/音轨格式未覆盖，不能据此宣称原生播放器完整支持。
- 播放进度直接复用既有 `/api/history`，尚未实现 M3 的播放会话隔离与 v1
  候选协商。
- 未在最老支持版本 API 26 上运行（该 AVD 存在，本轮未启动）。
- 依赖版本按当前锁定组合固定，不使用动态 `+`。

## 4. 许可

- 后端 MeBox 仓库为 GPL-3.0；分发修改后的后端需履行 GPL 源码义务。
- App 当前依赖（AndroidX、OkHttp、Retrofit、kotlinx.serialization）为
  Apache-2.0；分发 APK 时需附相应许可与声明。
- 正式发布前仍需复核最终依赖清单与发布渠道要求（见 M6-02）。

## 5. GitHub Actions 工作流验证

工作流：`.github/workflows/android-release.yml`；说明见 `docs/ci-release.md`。

- `actionlint` 校验通过（exit 0）。
- 本地模拟无签名分支：`assembleRelease -PmeboxVersionCode=42` 生成
  `app-release-unsigned.apk`；CI 在该情况下改为构建并发布调试 APK。
- 本地模拟签名分支：用临时 keystore 与四个 `MEBOX_*` 环境变量执行
  `assembleRelease -PmeboxVersionCode=43`，生成 `app-release.apk`；
  `apksigner verify --print-certs` 验证签名证书成功。
- `:app:printVersionName` 输出 `0.1.0`，供工作流生成标签与文件名。
- 临时 keystore 只存在于系统临时目录，未写入仓库。
- 限制：尚未在 GitHub 托管 Runner 上实际运行；未验证 Secrets、Release 权限、
  覆盖升级和回滚。
