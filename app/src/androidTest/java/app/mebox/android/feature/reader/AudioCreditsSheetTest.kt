package app.mebox.android.feature.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioCreditsSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun typedSecondsAndSliderStayInSyncAndSaveExactValues() {
        var saved: Pair<Int, Int>? = null
        compose.setContent {
            MaterialTheme {
                AudioCreditsSheet(30, 20, false, null, { open, close -> saved = open to close }, {})
            }
        }

        compose.onNodeWithText("片头跳过秒数").performTextReplacement("31")
        compose.onNodeWithContentDescription("片头跳过秒数滑杆")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(32f) }
        compose.onNodeWithText("片头跳过秒数").assertTextContains("32")
        compose.onNodeWithText("片尾跳过秒数").performTextReplacement("17")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(32 to 17, saved) }
    }

    @Test fun invalidInputCannotBeSavedAndZeroAndUpperBoundAreAccepted() {
        var saved: Pair<Int, Int>? = null
        compose.setContent {
            MaterialTheme {
                AudioCreditsSheet(30, 20, false, null, { open, close -> saved = open to close }, {})
            }
        }

        listOf("", "601", "-1", "1.5", "999999999999999").forEach { invalid ->
            compose.onNodeWithText("片头跳过秒数").performScrollTo().performTextReplacement(invalid)
            compose.onNodeWithText("保存").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle { assertNull(saved) }
        }
        compose.onNodeWithText("片头跳过秒数").performScrollTo().performTextReplacement("0")
        compose.onNodeWithText("片尾跳过秒数").performScrollTo().performTextReplacement("600")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0 to 600, saved) }
    }

    @Test fun savingDisablesEditingAndActions() {
        compose.setContent {
            MaterialTheme { AudioCreditsSheet(31, 17, true, null, { _, _ -> }, {}) }
        }
        compose.onNodeWithText("片头跳过秒数").assertIsNotEnabled()
        compose.onNodeWithText("片尾跳过秒数").assertIsNotEnabled()
        compose.onNodeWithContentDescription("片头跳过秒数滑杆").assertIsNotEnabled()
        compose.onNodeWithContentDescription("片尾跳过秒数滑杆").assertIsNotEnabled()
        compose.onNodeWithText("清除").performScrollTo().assertIsNotEnabled()
    }
}
