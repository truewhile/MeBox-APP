package app.mebox.android.feature.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mebox.android.feature.AppUiState
import app.mebox.android.feature.MainViewModel
import app.mebox.android.feature.tv.TvReaderReadingScreen

/** 独立于已认证在线账号的受限宿主；不会创建媒体列表或账号管理页面。 */
@Composable
fun OfflineReaderHost(account: AppUiState.Offline, mainViewModel: MainViewModel, television: Boolean = false) {
    val factory = mainViewModel.offlineReaderFactory(account)
    if (factory == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("本机登录信息已不可用，请重新连接服务器")
            TextButton(onClick = mainViewModel::leaveOfflineReader) { Text("返回连接") }
        }
        return
    }
    val reader: ReaderViewModel = viewModel(
        key = "offline:${account.scope.serverId}:${account.scope.userId}:${mainViewModel.readerSessionKey(account.scope)}", factory = factory,
    )
    val state by reader.state.collectAsStateWithLifecycle()
    val reading = state.screen == ReaderScreen.Reading
    BackHandler {
        if (reading) reader.closeReading() else mainViewModel.leaveOfflineReader()
    }
    if (reading) {
        if (television) TvReaderReadingScreen(state, account.address.origin, reader)
        else ReaderReadingScreen(state, account.address.origin, reader)
        return
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("离线书架", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = mainViewModel::leaveOfflineReader) { Text("返回连接") }
        }
        Text("仅可阅读本机完整缓存的文本和漫画；搜索、账号管理与听书需恢复在线。",
            style = MaterialTheme.typography.bodySmall)
        state.progressNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(16.dp))
        when {
            state.shelfLoading -> CircularProgressIndicator()
            state.books.isEmpty() -> Text("暂无可离线阅读的书籍，请先联网缓存章节。")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.books, key = { it.id }) { book ->
                    OutlinedButton(onClick = { reader.openBook(book) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(book.name, style = MaterialTheme.typography.titleMedium)
                            Text(book.author.ifBlank { "未知作者" }, style = MaterialTheme.typography.bodySmall)
                            Text(book.durChapterTitle.ifBlank { "点击继续阅读" }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
