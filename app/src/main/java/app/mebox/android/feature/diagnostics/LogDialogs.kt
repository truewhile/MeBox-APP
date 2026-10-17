package app.mebox.android.feature.diagnostics

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mebox.android.core.input.QrCodes
import app.mebox.android.core.log.DiagnosticsLog
import app.mebox.android.core.log.LogBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 返回「导出日志」动作：先让用户用系统文件选择器挑选文件夹，
 * 再在后台把 ZIP 写进去，最后用 Toast 告诉用户结果。
 *
 * 手机和电视共用；电视上同样会打开系统的 DocumentsUI。
 */
@Composable
fun rememberLogExport(): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                LogBundle.exportToFolder(context, treeUri)
            }
            val message = result.fold(
                onSuccess = { "日志已导出：$it" },
                onFailure = { "导出失败：${it.message ?: "未知错误"}" },
            )
            DiagnosticsLog.i("Logs", message)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    return remember(launcher) {
        {
            // 部分电视盒子没有 DocumentsUI；此时引导用户改用局域网下载。
            runCatching { launcher.launch(null) }.onFailure {
                Toast.makeText(
                    context,
                    "系统没有可用的文件夹选择器，请改用「局域网下载日志」",
                    Toast.LENGTH_LONG,
                ).show()
            }
            Unit
        }
    }
}

/**
 * 日志局域网下载对话框：二维码 + 地址 + 刷新。
 * 关闭对话框时调用方负责停止服务。
 */
@Composable
fun LogShareDialog(
    url: String?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bitmap = remember(url) {
        url?.let { QrCodes.bitmap(it, sizePx = 640) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        title = { Text("局域网下载日志") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .background(Color.White, RoundedCornerShape(14.dp))
                        .padding(10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "日志下载二维码",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            "未连接局域网",
                            color = Color(0xFF666666),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    url ?: "请先连接 Wi-Fi 或有线网络",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "手机或电脑和设备连接同一网络后，扫码或打开上面的地址即可下载包含日志和设备信息的 ZIP。" +
                        "关闭对话框会立即停止服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh) { Text("刷新地址") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
