# 阅读可靠性与离线入口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付可恢复本机阅读进度、可信缓存状态和受限离线书架。

**Architecture:** 沿用 Compose + ReaderViewModel，增加文件持久进度存储与串行同步器；缓存根据资源实际完整性判定。Offline 是独立顶层状态，只向阅读模块提供本机访问。

**Tech Stack:** Kotlin 2.1、Compose、kotlinx.serialization、coroutines、OkHttp、JUnit4。

## Global Constraints

- 仅修改 MeBox-APP；不修改后端，不新增依赖，不提交或推送本批代码。
- minSdk 26，compileSdk/targetSdk 36。
- 音频沿用现有引擎；本批离线入口仅开放文本和漫画，HLS 不计完整缓存。
- 在线下载仍是界面生命周期任务，系统终止后续传/配额/Wi-Fi 策略不在本批。

### Task 1: 本地进度和串行同步

Files: 新建 `core/reader/ReaderProgressStore.kt`、`ReaderProgressSync.kt` 和对应 JVM 测试；修改 `di/AppContainer.kt`、`feature/MainViewModel.kt`、`feature/reader/ReaderViewModel.kt`。

- [x] 测试先行：`ReaderProgressStore(root, json)` 保存 `ReaderProgress(bookId, chapterIndex, position, chapterTitle, chapterUrl, updatedAtMs, pending)`，重建 store 后仍可读取；同名书籍跨账号隔离。
- [x] 测试 `acknowledge(scope, event)` 只确认当前事件，旧事件回执不能删除新事件；本地回退位置与章节 URL 改动的恢复。
- [x] 执行 `./gradlew.bat :app:testDebugUnitTest --tests "*ReaderProgress*"` 验证失败，再实现持久化、合并和串行同步；同步前比较服务端 durChapterTime。
- [x] 在翻页/切章/退出先保存并更新书架，再异步同步；打开书籍采用有效本地位置；全量测试确认兼容现有听书检查点。

### Task 2: 可信缓存和可取消下载

Files: 修改 `core/reader/ReaderCacheStore.kt`、`feature/reader/ReaderViewModel.kt`、`ReaderReadingScreen.kt`；新增 `ReaderCacheStoreTest.kt`。

- [x] 用真实临时目录 + MockWebServer 写测试：一张图片成功另一张 500 应为 PARTIAL，不能进入 cachedIndexes；重试后 COMPLETE；删除资产后重新变 PARTIAL。
- [x] 写空正文/HLS/缺失音轨/跨账号/取消下载测试，执行测试验证失败。
- [x] 增加 File 根目录构造，`status`/`partialIndexes`，资源仅接受私有目录实际文件；unique temp + atomic replace，下载取消时取消 HTTP Call 和清理 temp。
- [x] 下载流程逐章判定完整性，保存失败索引；在信息面板显示部分数量、取消和重试按钮；异常与取消恢复 busy 状态。
- [x] 执行 `./gradlew.bat :app:testDebugUnitTest --tests "*ReaderCacheStoreTest"`。
- [x] 回归测试补齐退出登录/删除书籍与在途下载的竞态：清理提升账号与书籍代次并取消 Call，迟到结果不得重建缓存。

### Task 3: 受限离线书架

Files: 新建 `feature/reader/OfflineReaderHost.kt`；修改 `MainViewModel.kt`、`AppScreens.kt`、`feature/tv/TvRoot.kt`、`ReaderViewModel.kt`、`ReaderShelfScreens.kt`。

- [x] 测试 `cache.offlineShelf(scope)` 只包含有完整章节的文本/漫画，并排除音频和无缓存书籍。
- [x] ReaderViewModel 增加 offlineOnly：不加载 profile/groups，不启动同步器，目录正文优先本地且直接返回，所有网络操作入口受限。
- [x] MainViewModel 新增 Offline(scope, address)，在 Loading/Connection 提供进入入口；只用已确认服务器快照和保留凭证验证入口条件。
- [x] 手机与电视呈现共用离线宿主，显示受限说明和返回连接按钮，不加载远程封面；保留字体主题和阅读翻章。
- [x] 入口可用性在工作线程计算，离线工厂在进入时构建，组合期间不读凭证与磁盘；阅读 ViewModel 键跟随会话和进度代次。

### Task 4: 验证与交付

- [x] `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`。
- [x] `git diff --check` 并逐文件审查凭证边界、取消传播、旧回执、磁盘异常。
- [x] 记录真实测试数量/构建结果/未做真机验证，列明后续批次；更新本计划复选框。

## 验证记录（2026-10-20）

- `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`：BUILD SUCCESSFUL。
- 单元测试共 390 个，0 失败；本批新增套件：`ReaderCacheStoreTest` 10、`ReaderProgressStoreTest` 7、`ReaderProgressSyncTest` 4、`ReaderOfflineRepositoryTest` 1，`ReaderApiTest` 追加 1 个 `no-cache` 断言（共 11）。
- `.\gradlew.bat :app:lintDebug`：BUILD SUCCESSFUL，0 error、84 warning、2 hint；新增文件无 lint 命中，warning 全部为既有风格项（UseKtx、ApplySharedPref、ConfigurationScreenWidthHeight 等）。
- 产物：`app/build/outputs/apk/debug/`（universal / arm64-v8a / armeabi-v7a / x86 / x86_64）。
- `git diff --check`：无空白错误。
- 未验证：真机/模拟器界面、飞行模式、进程被杀后的恢复、多设备同时改进度。这些需要设备环境，未在本批声称通过。
- 未纳入本批：影视续播冲突选择、观影档案/PIN、片段跳过、TXT/EPUB 导入、替换规则、账号与设备管理、翻页与漫画增强、播放列表、统计、外部播放器、多服务器、TTS、书签、DLNA/影视离线/HLS 离线。
