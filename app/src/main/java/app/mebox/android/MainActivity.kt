package app.mebox.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import app.mebox.android.core.tv.isTelevisionDevice
import app.mebox.android.core.ui.MeBoxTheme
import app.mebox.android.feature.MainViewModel
import app.mebox.android.feature.MeBoxRoot
import app.mebox.android.feature.tv.TvRoot

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory((application as MeBoxApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 电视和手机共用同一个 Activity：电视走 10-foot 界面，手机保持原界面。
        // 调试时可以用 adb 强制电视界面：
        // adb shell am start -n app.mebox.android/.MainActivity \
        //   --ez app.mebox.android.EXTRA_TV_UI true
        val television = isTelevisionDevice() ||
            intent?.getBooleanExtra(EXTRA_TV_UI, false) == true
        if (television) {
            // 电视上观看时通常不会触摸屏幕，避免系统自动熄屏打断播放。
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        setContent {
            MeBoxTheme(darkTheme = television || isSystemInDarkTheme()) {
                if (television) {
                    TvRoot(viewModel)
                } else {
                    MeBoxRoot(viewModel)
                }
            }
        }
    }

    override fun onStop() {
        // 用户切到其他应用/锁屏时事件回调不一定还有机会执行，先把听书位置
        // 同步落盘；这样即使后台服务随后被系统回收，回到 App 也能接着听。
        (application as? MeBoxApplication)
            ?.container
            ?.readerAudio
            ?.persistCheckpoint(synchronous = true)
        super.onStop()
    }

    private companion object {
        const val EXTRA_TV_UI = "app.mebox.android.EXTRA_TV_UI"
    }
}
