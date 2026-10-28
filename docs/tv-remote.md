# 电视（Android TV）适配说明

这份文档说明 MeBox-APP 如何在同一份 APK 中同时支持手机和电视，以及遥控器、
手机扫码输入和多架构打包的实现方式。

## 1. 设备识别

入口在 `MainActivity`：使用 `UiModeManager.currentModeType` 与
`PackageManager.FEATURE_LEANBACK` / `FEATURE_TELEVISION` 判断当前是否为电视。
任一命中即进入 `TvRoot`，否则保持手机界面 `MeBoxRoot`。

Manifest 同时声明：

- `android.software.leanback`（`required=false`）
- `android.hardware.touchscreen`（`required=false`）
- `LEANBACK_LAUNCHER` 与 `LAUNCHER` 两个入口
- `android:banner="@drawable/tv_banner"`

因此电视桌面会显示应用，手机安装同一个包也不会被应用商店按触摸屏过滤。

## 2. 遥控器交互

电视界面使用 Compose 焦点系统，没有依赖 Leanback 库：

- 所有可操作项通过 `TvFocusableSurface` 统一处理焦点、放大、强调色描边和
  OK/Enter 确认；
- `LazyRow` / `LazyVerticalGrid` / `LazyColumn` 会自动把获得焦点的子项滚动到
  可见区域；
- 左侧导航固定，第一次进入时焦点在「首页」，按右键进入内容区；
- 弹窗和播放设置面板打开时会主动把焦点放到主操作/第一项，关闭后焦点回到
  播放器。

播放页按键映射：

| 按键 | 行为 |
| --- | --- |
| OK / Enter | 播放 / 暂停；播放结束后重播；错误页重试 |
| 左 / 右 | 快退 / 快进 10 秒 |
| 上 / 下 | 显示 / 隐藏控制栏 |
| 菜单（MENU）/ INFO | 打开播放设置 |
| 媒体播放暂停 / 耳机键 | 播放 / 暂停 |
| 媒体快进 / 快退 | 快进 / 快退 10 秒 |
| 媒体下一集 | 播放下一集 |
| 返回 | 退出播放 / 返回上一级 |

阅读页按键映射：

| 按键 | 行为 |
| --- | --- |
| 上 / 下 | 按像素滚动正文 |
| 左 / 右 | 上一章 / 下一章 |
| OK | 隐藏 / 显示顶栏和底部提示 |
| 菜单（MENU）/ INFO | 打开目录 |
| 返回 | 退出阅读 |

## 3. 手机扫码输入

电视端不要求用户用遥控器输入长文本。`PhoneInputServer` 会在电视界面打开时
启动一个只监听局域网的 HTTP 服务：

1. 枚举 `wlan` / `eth` 等网卡的站点本地 IPv4 地址；
2. 绑定系统分配的随机端口；
3. 生成 12 位随机 token，二维码内容为
   `http://<电视IP>:<端口>/t/<token>`；
4. 手机扫码后打开一个自包含的移动端网页，页面上有三个表单：
   - 连接服务器（服务器地址）
   - 账号登录（用户名 + 密码）
   - 搜索 / 推送关键词
5. 表单 POST 到 `/t/<token>/submit`，服务把内容转成 `PhoneInputEvent`
   写入内存 `SharedFlow`：
   - `MainViewModel` 处理服务器地址和登录；
   - 电视界面处理搜索关键词并自动切换到影视/阅读搜索结果；
6. 退出电视界面时立即关闭 Socket、取消协程并清空 token。

播放页的弹幕面板也复用这个入口：面板里直接铺一张二维码（和地址、刷新按钮
在同一块卡片里），不需要再点一次按钮弹出二级弹窗。手机扫码提交的关键词直接
作为 `/api/danmaku/:id?kw=` 的搜索词，返回的候选弹幕库仍在电视的弹幕面板里
用遥控器选择；面板关闭前二维码一直可见，遥控器焦点落在「刷新二维码」上即可
在换网后换发 token。

安全边界：

- 只接受带正确 token 的路径，其它路径返回 404；
- 请求体限制 16 KB，Socket 超时 5 秒；
- 不记录密码、不写入 SharedPreferences；
- 密码只留在当前登录的内存事件里，登录完成后不保留；
- 只适用于同一局域网，不能从公网访问；
- 如果家里有不可信设备，退出电视界面后服务会立即停止。

电视界面里的「刷新二维码」会重新绑定端口并换发 token，用于网络切换或
IP 变化后的恢复。

### 3.1 扫码下载诊断日志

电视端排查问题时不需要数据线。日志入口有两处：

- 连接页 / 登录页的「扫码下载日志」：服务器连不上或登录失败时也能用；
- 登录后「我的 → 诊断与日志 → 局域网下载日志」。

两处都会启动临时日志服务 `LogShareServer`，二维码内容为
`http://<电视IP>:<端口>/d/<token>`。手机扫码后打开下载页，可选择下载
单个日志文件或 ZIP；ZIP 内包含全部日志和设备/版本摘要。关闭弹窗或离开
当前页面后服务立即停止，token 同时失效。

日志本身写入应用私有目录 `files/logs`，单文件 512 KB、最多保留 4 份历史
文件；全局崩溃也会被捕获并写入，方便定位电视端偶发闪退。

## 4. 多架构打包

`app/build.gradle.kts` 使用 ABI split：

```kotlin
splits {
    abi {
        isEnable = true
        reset()
        include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        isUniversalApk = true
    }
}
```

LibVLC 的 AAR 自带四套原生库，拆分后每个用户只需下载自己设备对应的 so：

| ABI | 典型设备 | 调试包体积（参考） |
| --- | --- | --- |
| `arm64-v8a` | 主流手机、大多数电视盒子 | 约 73 MB |
| `armeabi-v7a` | 老款设备 | 约 61 MB |
| `x86_64` | Android TV 模拟器 | 约 78 MB |
| `x86` | 少数 32 位 x86 设备 | 约 69 MB |
| `universal` | 不确定架构时 | 约 212 MB |

安装时选择对应 APK；不确定时使用 universal 包。

## 5. 本地验证建议

1. 用 Android Studio 创建 Android TV (1080p) 模拟器，选择 `x86_64` 镜像；
2. `adb install -r app/build/outputs/apk/debug/app-x86_64-debug.apk`；
3. 没有 TV 模拟器时，也可以在手机上强制电视界面验证布局：

   ```bash
   adb shell am start -n app.mebox.android/.MainActivity \
     --ez app.mebox.android.EXTRA_TV_UI true
   ```

4. 用方向键检查首页、影视库、详情、播放和阅读的焦点移动；
5. 手机与电脑/电视模拟器连接同一局域网，扫码打开网页并推送搜索词；
6. 在播放页测试 OK、左右、上下、菜单和返回键；
7. 用 `adb shell dumpsys package app.mebox.android | findstr leanback` 或电视桌面
   确认 leanback 入口可见。

## 6. 已知限制

- 电视端暂未接入语音输入、手机虚拟遥控器和投屏协议；
- 手机输入页依赖电视和手机在同一局域网；访客 Wi-Fi 开启 AP 隔离时二维码
  可打开但无法连接电视；
- 电视端阅读暂不提供听书播放器，音频书会提示回到手机；
- 播放设置面板中的滑杆类选项（弹幕透明度等）可用方向键微调，但不如手机触摸
  直观；
- 未在真实电视盒子/遥控器上做仪器测试，发布前仍需人工验证。
