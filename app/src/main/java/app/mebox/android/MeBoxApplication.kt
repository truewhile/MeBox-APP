package app.mebox.android

import android.app.Application
import app.mebox.android.di.AppContainer

class MeBoxApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
