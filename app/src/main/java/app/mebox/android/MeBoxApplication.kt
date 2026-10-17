package app.mebox.android

import android.app.Application
import app.mebox.android.core.log.DiagnosticsLog
import app.mebox.android.di.AppContainer

class MeBoxApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 先安装日志与崩溃捕获，再创建依赖；启动阶段的异常也能留下记录。
        DiagnosticsLog.install(this)
        container = AppContainer(this)
    }
}
