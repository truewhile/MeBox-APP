package app.mebox.android.core.network

import app.mebox.android.BuildConfig

/**
 * App 统一的 User-Agent。
 *
 * 115 直链会绑定「换取直链时」的 User-Agent（见服务端
 * `cloud115.ResolveWithUA`），所以播放地址解析（OkHttp）与播放器拉流
 * （ExoPlayer / LibVLC）必须使用同一个 UA，否则会在播放时被 CDN 403。
 * 统一之后，`/api/playback/{id}/info` 预置的直链才能直接交给播放器使用；
 * 同时服务端日志也能区分 App 与网页端。
 */
val APP_USER_AGENT: String = "MeBox-Android/${BuildConfig.VERSION_NAME}"
