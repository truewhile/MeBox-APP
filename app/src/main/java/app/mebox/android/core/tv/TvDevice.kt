package app.mebox.android.core.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** 当前界面是否应该走 10-foot（电视/遥控器）布局。 */
fun Context.isTelevisionDevice(): Boolean {
    val uiMode = getSystemService(UiModeManager::class.java)
        ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    val packageManager = packageManager
    val leanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    val television = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
    return televisionUiMode(uiMode, leanback, television)
}

/**
 * 把系统特征收敛成纯函数，方便单元测试；电视盒子不一定同时声明全部特征。
 */
internal fun televisionUiMode(uiMode: Boolean, leanback: Boolean, television: Boolean): Boolean =
    uiMode || leanback || television
