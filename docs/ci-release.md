# 安卓 APK 自动构建与 GitHub Release

工作流：`.github/workflows/android-release.yml`  
触发条件：推送到 `main`、推送 `v*` 标签、手动 `workflow_dispatch`。

## 1. 工作流做什么

1. 使用 JDK 17 与 Android SDK 36 构建。
2. 运行 `:app:testDebugUnitTest` 与 `:app:lintDebug`；失败则不发布。
3. 构建 APK（按 CPU 架构拆分，同时生成 universal 包）：
   - 配置了发布签名 Secrets：`assembleRelease`，产物为
     `app-<abi>-release.apk` 与 `app-universal-release.apk`。
   - 未配置签名 Secrets：回退到 `assembleDebug`，产物为
     `app-<abi>-debug.apk` 与 `app-universal-debug.apk`。
   - ABI 包括 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`。
4. 以 `github.run_number` 作为 `versionCode`，避免每次构建版本号相同。
5. 为每个 APK 生成 SHA-256 校验文件。
6. 上传一个临时的 workflow artifact（保留 14 天）。
7. 创建 GitHub Release 并附加全部 APK 与 `.sha256`。

Release 标签：

- `main` 上的提交：`v<versionName>-build.<run_number>`，例如
  `v0.1.0-build.42`。
- 推送 `v*` 标签：直接使用该标签，例如 `v1.0.0`。

文档与 `plan/**` 变更不会自动触发发布。

## 2. 配置发布签名（推荐）

未配置签名时仍会发布调试 APK，但调试签名不适合正式分发，也不能用于
后续正式版本的覆盖升级。

### 2.1 生成 keystore（只做一次，妥善备份）

```bash
keytool -genkeypair -v \
  -keystore mebox-release.jks \
  -alias mebox \
  -keyalg RSA -keysize 2048 -validity 10000
```

密钥文件不得提交到仓库；`.gitignore` 已排除 `*.jks`、`*.keystore`、`*.p12`。

### 2.2 转为 Base64

Windows PowerShell：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("mebox-release.jks")) |
  Set-Content -Encoding ascii keystore.b64
```

Linux / macOS：

```bash
base64 -w 0 mebox-release.jks > keystore.b64
```

### 2.3 写入仓库 Secrets

仓库 **Settings → Secrets and variables → Actions → New repository secret**：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `keystore.b64` 的全部内容 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 口令 |
| `ANDROID_KEY_ALIAS` | 通常是 `mebox` |
| `ANDROID_KEY_PASSWORD` | key 口令 |

四个值必须同时存在；缺任意一个都会回退为调试 APK。

### 2.4 允许创建 Release

如果组织/仓库限制了 `GITHUB_TOKEN`，请在
**Settings → Actions → General → Workflow permissions** 中选择
**Read and write permissions**。工作流自身已声明 `contents: write`。

## 3. 本地等效验证

```powershell
# 单元测试与静态检查
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug

# 未签名 Release（会生成按 ABI 拆分的 app-<abi>-release-unsigned.apk
# 和包含全部架构的 app-universal-release-unsigned.apk）
.\gradlew.bat :app:assembleRelease

# 使用本地 keystore 验证签名构建
$env:MEBOX_KEYSTORE_FILE="D:\keys\mebox-release.jks"
$env:MEBOX_KEYSTORE_PASSWORD="..."
$env:MEBOX_KEY_ALIAS="mebox"
$env:MEBOX_KEY_PASSWORD="..."
.\gradlew.bat :app:assembleRelease -PmeboxVersionCode=43
```

电视盒子安装时选择对应架构；不确定时用 universal 包：

```bash
adb install -r app-arm64-v8a-release.apk
adb install -r app-universal-release.apk
```

验证签名：

```powershell
D:\Android\Sdk\build-tools\36.1.0\apksigner.bat verify --print-certs `
  app\build\outputs\apk\release\app-arm64-v8a-release.apk
```

## 4. 版本号规则

- `versionName` 来自 `app/build.gradle.kts`，当前为 `0.1.0`。
- CI 每次构建传入 `-PmeboxVersionCode=<run_number>`，使 `versionCode` 单调递增。
- 修改 `versionName` 后，后续 Release 标签会随之变化。

## 5. 已知限制

- 发布包按 ABI 拆分：`arm64-v8a` / `armeabi-v7a` 面向手机与电视盒子，
  `x86` / `x86_64` 面向模拟器和少数 Intel 设备；另有一个体积较大的
  `universal` 包。上传到应用商店时，商店会自动选择对应架构。
- 工作流只跑 JVM 单元测试和 lint，不启动模拟器，不覆盖登录、播放等仪器测试。
- 未配置签名 Secrets 时的 `debug` 包使用调试签名，不能作为正式发布版本。
- 工作流不会自动合并或修改代码；失败时先修复测试/lint 再推送。
- 发布后的 APK 仍需要人工安装验证关键流程（连接、登录、电视遥控器、播放）。
