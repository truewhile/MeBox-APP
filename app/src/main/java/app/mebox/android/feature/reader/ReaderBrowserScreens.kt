package app.mebox.android.feature.reader

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.mebox.android.core.log.DiagnosticsLog
import app.mebox.android.core.network.ApiResult
import app.mebox.android.core.network.ReaderBrowserPageDto
import app.mebox.android.core.network.ReaderLoginFieldDto
import app.mebox.android.core.network.ReaderSearchOriginDto
import app.mebox.android.core.ui.MeBoxButton
import app.mebox.android.core.ui.MeBoxChip
import app.mebox.android.core.ui.MeBoxOutlinedButton
import app.mebox.android.core.ui.MeBoxTextField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 书源 JS 的宿主浏览器页面。
 *
 * 页面由服务端带书源 Cookie 抓取并改写成同源签名代理地址，因此 WebView 里
 * 不需要携带 MeBox 的 JWT。页面自己的 fetch/XHR 经 JavaScript 桥转交
 * [ReaderViewModel.browserXhr] 由服务端补书源凭据；点击「完成」时把 DOM
 * 回传给服务端，解除书源 JS 的阻塞。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ReaderBrowserPageDialog(
    page: ReaderBrowserPageDto,
    origin: String,
    viewModel: ReaderViewModel,
) {
    val scope = rememberCoroutineScope()
    // page.id / reloadToken 变化时必须重建 WebView：AndroidView 的 factory 只在
    // 节点首次组合时执行，沿用旧节点不会重新 loadUrl——「切换线路」之后再点
    // 「书源设置」就会一直停在上一个页面（或空白），看起来像页面打不开。
    var reloadToken by remember(page.id) { mutableIntStateOf(0) }
    var webView by remember(page.id, reloadToken) { mutableStateOf<WebView?>(null) }
    var currentUrl by remember(page.id, reloadToken) {
        mutableStateOf(page.targetUrl.ifBlank { origin })
    }
    var submitting by remember(page.id, reloadToken) { mutableStateOf(false) }
    val pageUrl = remember(page.id, origin, page.targetUrl) {
        resolvePageUrl(origin, page.pageUrl, page.targetUrl)
    }
    var loading by remember(page.id, reloadToken) { mutableStateOf(pageUrl.isNotBlank()) }
    // 页面加载失败（DNS / TLS / 4xx / 5xx）必须给用户看原因。
    // 旧实现没有任何错误回调，WebView 失败就是一片白，无法判断是没登录还是页面坏了。
    var error by remember(page.id, reloadToken) {
        mutableStateOf(
            if (pageUrl.isBlank()) "服务端没有返回页面地址，请重试或重新打开" else "",
        )
    }
    // 站外跳转被拦截等说明：不影响「完成」，只做提示。
    var notice by remember(page.id, reloadToken) { mutableStateOf("") }

    // 只记 id/标题与是否有地址，不记带签名的完整 URL。
    LaunchedEffect(page.id, reloadToken) {
        DiagnosticsLog.i(
            "Reader",
            "承载书源页面 id=${page.id} title=${page.title} mode=${page.mode} urlBlank=${pageUrl.isBlank()}",
        )
    }

    Dialog(
        onDismissRequest = {
            viewModel.closeBrowserPage(cancelled = page.mode == "wait")
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        page.title.ifBlank { "书源页面" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    )
                    if (loading && error.isEmpty()) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    if (page.mode == "wait") {
                        if (submitting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Button(
                                // 页面没加载出来时回传的 DOM 是空的，书源解析必然失败，
                                // 直接禁用并让用户先「重试」。
                                enabled = error.isEmpty(),
                                onClick = {
                                    submitting = true
                                    webView?.evaluateJavascript(
                                        "(function(){return document.documentElement.outerHTML})()",
                                    ) { value ->
                                        val html = runCatching {
                                            JSONTokener(value).nextValue() as? String
                                        }.getOrNull().orEmpty()
                                        viewModel.submitBrowserResult(page, html, currentUrl)
                                        submitting = false
                                    }
                                },
                            ) {
                                Text("完成")
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    IconButton(onClick = {
                        viewModel.closeBrowserPage(cancelled = page.mode == "wait")
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }

                if (error.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { reloadToken += 1 }) { Text("重试") }
                    }
                } else if (notice.isNotEmpty()) {
                    Text(
                        notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    if (pageUrl.isBlank()) {
                        Text(
                            "没有可显示的页面地址",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        key(page.id, reloadToken) {
                            AndroidView(
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.databaseEnabled = true
                                        settings.allowFileAccess = false
                                        settings.allowContentAccess = false
                                        settings.loadWithOverviewMode = true
                                        settings.useWideViewPort = true
                                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                                        addJavascriptInterface(
                                            BrowserBridge(page.id, viewModel, scope, this),
                                            BRIDGE_NAME,
                                        )
                                        webViewClient = object : WebViewClient() {
                                            override fun onPageStarted(
                                                view: WebView,
                                                url: String?,
                                                favicon: android.graphics.Bitmap?,
                                            ) {
                                                url?.let { currentUrl = it }
                                                loading = true
                                                error = ""
                                                view.evaluateJavascript(BRIDGE_SCRIPT, null)
                                            }

                                            override fun onPageFinished(view: WebView, url: String?) {
                                                url?.let { currentUrl = it }
                                                loading = false
                                                view.evaluateJavascript(BRIDGE_SCRIPT, null)
                                            }

                                            override fun onReceivedError(
                                                view: WebView,
                                                request: WebResourceRequest,
                                                err: WebResourceError,
                                            ) {
                                                // 子资源失败不影响页面本身，只看主文档。
                                                if (!request.isForMainFrame) return
                                                loading = false
                                                error = "页面加载失败：${err.description}（错误码 ${err.errorCode}）"
                                                DiagnosticsLog.w(
                                                    "Reader",
                                                    "承载页加载失败 id=${page.id} code=${err.errorCode} desc=${err.description}",
                                                )
                                            }

                                            override fun onReceivedHttpError(
                                                view: WebView,
                                                request: WebResourceRequest,
                                                resp: WebResourceResponse,
                                            ) {
                                                if (!request.isForMainFrame) return
                                                loading = false
                                                error = "页面返回 HTTP ${resp.statusCode}，书源登录态可能已失效"
                                                DiagnosticsLog.w(
                                                    "Reader",
                                                    "承载页 HTTP 错误 id=${page.id} status=${resp.statusCode}",
                                                )
                                            }

                                            override fun onReceivedSslError(
                                                view: WebView,
                                                handler: SslErrorHandler,
                                                sslError: SslError,
                                            ) {
                                                // 不忽略证书错误：忽略会把书源 Cookie 暴露给中间人。
                                                handler.cancel()
                                                loading = false
                                                error = "HTTPS 证书校验失败（${sslError.primaryError}），请检查服务器地址"
                                                DiagnosticsLog.w(
                                                    "Reader",
                                                    "承载页证书错误 id=${page.id} primary=${sslError.primaryError}",
                                                )
                                            }

                                            override fun shouldOverrideUrlLoading(
                                                view: WebView,
                                                request: WebResourceRequest,
                                            ): Boolean {
                                                val target = request.url.toString()
                                                // 只允许服务端同源页面/资源；外站导航在这里拦下，
                                                // 避免承载页把用户带到未知站点并携带页面状态。
                                                if (target.startsWith(origin) ||
                                                    target.startsWith("about:") ||
                                                    target.startsWith("data:")
                                                ) {
                                                    return false
                                                }
                                                // 拦截也要说出来：页面里的「保存」如果提交到站外，
                                                // 静默拦下会表现成「点了没反应」。
                                                notice = "已阻止跳转到站外地址：${request.url.host ?: target}"
                                                return true
                                            }
                                        }
                                        loadUrl(pageUrl)
                                        webView = this
                                    }
                                },
                                onRelease = { view ->
                                    view.removeJavascriptInterface(BRIDGE_NAME)
                                    if (webView === view) webView = null
                                    view.destroy()
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 把服务端下发的承载地址解析成 WebView 可加载的绝对地址。
 *
 * 正常情况 pageUrl 是 `/api/reader/browser/page?id=…&s=…`（同源相对地址）；
 * 老版本服务端不返回 page_url 时退回 target_url，至少还能打开原始页面。
 */
private fun resolvePageUrl(origin: String, pageUrl: String, targetUrl: String = ""): String {
    if (pageUrl.startsWith("http://") || pageUrl.startsWith("https://")) return pageUrl
    if (pageUrl.isNotBlank()) {
        return origin.trimEnd('/') + (if (pageUrl.startsWith("/")) pageUrl else "/$pageUrl")
    }
    return targetUrl
}

private const val BRIDGE_NAME = "MeBoxAndroid"

private const val BRIDGE_SCRIPT = """
(function(){
  if (window.__meboxAndroidBridgeInstalled) return;
  window.__meboxAndroidBridgeInstalled = true;
  window.addEventListener('message', function(e){
    var d = e && e.data;
    if (!d || d.__mebox_proxy__ !== true) return;
    try {
      $BRIDGE_NAME.xhr(d.reqId, d.url, d.method || 'GET',
        JSON.stringify(d.headers || {}), d.body || '');
    } catch (err) {}
  });
})();
"""

private class BrowserBridge(
    private val pageId: String,
    private val viewModel: ReaderViewModel,
    private val scope: CoroutineScope,
    private val webView: WebView,
) {
    @JavascriptInterface
    fun xhr(reqId: String, url: String, method: String, headersJson: String, body: String) {
        val headers = runCatching {
            val obj = JSONObject(headersJson.ifBlank { "{}" })
            buildMap {
                obj.keys().forEach { key -> put(key, obj.optString(key)) }
            }
        }.getOrDefault(emptyMap())
        scope.launch {
            val result = viewModel.browserXhr(pageId, url, method, headers, body)
            val payload = JSONObject().apply {
                put("__mebox_proxy_res__", true)
                put("reqId", reqId)
                when (result) {
                    is ApiResult.Success -> {
                        put("status", result.value.status)
                        put("contentType", result.value.contentType)
                        put("body", result.value.body)
                        put("base64", result.value.base64)
                    }
                    else -> {
                        put("status", 0)
                        put("contentType", "text/plain")
                        put("body", "")
                        put("base64", false)
                    }
                }
            }
            webView.post {
                webView.evaluateJavascript("window.postMessage($payload, '*');", null)
            }
        }
    }
}

/** 书源登录表单：字段由服务端 loginUi 解析，页面类登录通过宿主浏览器完成。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSourceLoginSheet(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val login = state.sourceLogin
    // 源变量是书源 JSON 里的 bookSourceUrl 变量，形如 {"线路":"https://..."}；
    // 服务端已保存的值随登录信息一起下发，这里做本地编辑副本。
    var variableText by remember(login?.variable) { mutableStateOf(login?.variable.orEmpty()) }
    ModalBottomSheet(
        onDismissRequest = viewModel::closeSourceLogin,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                "书源登录",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                state.currentSource?.name
                    ?: login?.sourceName
                    ?: "当前书源",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (login?.loggedIn == true) "已登录" else "未登录",
                style = MaterialTheme.typography.labelMedium,
                color = if (login?.loggedIn == true) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            if (state.sourceLoginLoading) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }

            if (login != null) {
                val fields = login.fields.orEmpty()
                if (fields.isEmpty() && login.hasLoginJs) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "该源通过登录页完成登录，点击下方按钮打开书源页面。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                fields.forEach { field ->
                    LoginField(
                        field = field,
                        value = state.sourceLoginFields[field.name]
                            ?: login.values[field.name]
                            ?: field.default,
                        busy = state.sourceLoginBusy,
                        onChange = { value -> viewModel.onSourceLoginFieldChange(field.name, value) },
                        onAction = { viewModel.submitSourceLogin(field.action) },
                    )
                }
                if (login.variableComment.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        login.variableComment,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "源变量（bookSourceUrl 变量的 JSON）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = variableText,
                    onValueChange = { variableText = it },
                    minLines = 3,
                    maxLines = 6,
                    placeholder = { Text("{\"线路\":\"https://v1.example.com\"}", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                MeBoxOutlinedButton(
                    onClick = { viewModel.saveSourceVariable(variableText) },
                    enabled = !state.sourceLoginBusy,
                ) {
                    Text("保存变量")
                }
            }

            state.sourceLoginError?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            state.sourceLoginNotice?.let { notice ->
                Spacer(Modifier.height(8.dp))
                Text(notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MeBoxButton(
                    onClick = { viewModel.submitSourceLogin(null) },
                    enabled = !state.sourceLoginBusy && login != null,
                ) {
                    if (state.sourceLoginBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("确认登录")
                    }
                }
                if (login?.loggedIn == true) {
                    MeBoxOutlinedButton(
                        onClick = viewModel::logoutSource,
                        enabled = !state.sourceLoginBusy,
                    ) {
                        Text("退出登录")
                    }
                }
                MeBoxOutlinedButton(
                    onClick = viewModel::saveSourceLoginInfo,
                    enabled = !state.sourceLoginBusy && login != null,
                ) {
                    Text("仅保存信息")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LoginField(
    field: ReaderLoginFieldDto,
    value: String,
    busy: Boolean,
    onChange: (String) -> Unit,
    onAction: () -> Unit,
) {
    when (field.type) {
        "button" -> {
            Spacer(Modifier.height(8.dp))
            MeBoxOutlinedButton(onClick = onAction, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(field.viewName.ifBlank { field.name.ifBlank { "执行" } })
            }
        }
        "toggle" -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    field.viewName.ifBlank { field.name },
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = value == "true" || value == "1",
                    onCheckedChange = { checked -> onChange(if (checked) "true" else "false") },
                    enabled = !busy,
                )
            }
        }
        "select" -> {
            val options = field.chars.orEmpty()
            if (options.isNotEmpty()) {
                Text(
                    field.viewName.ifBlank { field.name },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { option ->
                        MeBoxChip(
                            text = option,
                            selected = value == option,
                            enabled = !busy,
                            onClick = { onChange(option) },
                        )
                    }
                }
            }
        }
        else -> {
            MeBoxTextField(
                value = value,
                onValueChange = onChange,
                label = field.viewName.ifBlank { field.name },
                enabled = !busy,
                visualTransformation = if (field.type == "password") {
                    PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** 换源候选列表：按书名重新搜索，列出可切换的书源。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderOriginPickerDialog(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = viewModel::closeOriginPicker,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text("换源", style = MaterialTheme.typography.titleMedium)
            Text(
                state.book?.name.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            when {
                state.originLoading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                state.originCandidates.isEmpty() -> Text(
                    state.originError ?: "没有找到其他可用书源",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(
                        state.originCandidates,
                        key = { "${it.origin}\u0000${it.bookUrl}" },
                    ) { origin ->
                        OriginRow(
                            origin = origin,
                            enabled = !state.originApplying,
                            onClick = { viewModel.applyOrigin(origin) },
                        )
                    }
                }
            }
            if (state.originApplying) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在切换书源…", style = MaterialTheme.typography.bodySmall)
                }
            }
            state.originError?.takeIf { state.originCandidates.isNotEmpty() }?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OriginRow(
    origin: ReaderSearchOriginDto,
    enabled: Boolean,
    onClick: () -> Unit,
) {    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                origin.originName.ifBlank { origin.origin },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (origin.latestChapter.isNotBlank()) {
                Text(
                    "最新：${origin.latestChapter}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
