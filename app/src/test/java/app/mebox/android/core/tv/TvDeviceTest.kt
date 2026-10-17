package app.mebox.android.core.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvDeviceTest {

    @Test
    fun anyTelevisionFeatureSelectsTenFootUi() {
        assertTrue(televisionUiMode(uiMode = true, leanback = false, television = false))
        assertTrue(televisionUiMode(uiMode = false, leanback = true, television = false))
        assertTrue(televisionUiMode(uiMode = false, leanback = false, television = true))
    }

    @Test
    fun ordinaryPhoneKeepsMobileUi() {
        assertFalse(televisionUiMode(uiMode = false, leanback = false, television = false))
    }
}
