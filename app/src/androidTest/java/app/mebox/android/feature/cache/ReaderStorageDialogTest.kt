package app.mebox.android.feature.cache

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mebox.android.core.reader.ReaderBookStorage
import app.mebox.android.core.reader.ReaderCacheKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderStorageDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun perBookActionsRequireConfirmationAndPassExactKind() {
        var selected: Pair<String, ReaderCacheKind>? = null
        compose.setContent {
            MaterialTheme {
                ReaderStorageContent(
                    ReaderStorageUiState(books = listOf(ReaderBookStorage("book-1", "测试有声书", 1024, 2048, 3, 4))),
                    onRefresh = {}, onClear = { id, kind -> selected = id to kind }, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("测试有声书").assertIsDisplayed()
        compose.onNodeWithText("清除自动缓存").performScrollTo().performClick()
        compose.onNodeWithText("确认清除自动缓存？").assertIsDisplayed()
        assertNull(selected)
        compose.onNodeWithText("取消").performClick()
        assertNull(selected)
        compose.onNodeWithText("删除下载").performScrollTo().performClick()
        compose.onNodeWithText("确认删除下载").performClick()
        assertEquals("book-1" to ReaderCacheKind.DOWNLOAD, selected)
        compose.onNodeWithText("清空全部").performScrollTo().performClick()
        compose.onNodeWithText("确认清空全部").performClick()
        assertEquals("book-1" to ReaderCacheKind.ALL, selected)
    }

    @Test fun loadingErrorEmptyAndBusyStatesAreUsable() {
        var state by mutableStateOf(ReaderStorageUiState(loading = true))
        var refreshed = false
        compose.setContent {
            MaterialTheme { ReaderStorageContent(state, { refreshed = true }, { _, _ -> }, {}) }
        }
        compose.onNodeWithText("刷新").assertIsNotEnabled()
        compose.runOnIdle { state = ReaderStorageUiState(error = "读取失败") }
        compose.onNodeWithText("重试").performScrollTo().performClick()
        assertTrue(refreshed)
        compose.runOnIdle { state = ReaderStorageUiState() }
        compose.onNodeWithText("暂无阅读缓存或下载。阅读时的自动缓存和手动下载会显示在这里。")
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            state = ReaderStorageUiState(busy = true,
                books = listOf(ReaderBookStorage("b", "下载保护", 10, 20, 1, 2)))
        }
        compose.onNodeWithText("删除下载").performScrollTo().assertIsNotEnabled()
    }
}
