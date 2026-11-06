package app.mebox.android.feature.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** 登录面板拥有自己的任务组；关掉面板后，旧请求不得继续刷新 UI 或重启页面轮询。 */
internal class ReaderSourceLoginSession(private val scope: CoroutineScope) {
    var job: Job? = null
        private set

    fun start(): Job {
        close()
        return Job(scope.coroutineContext[Job]).also { job = it }
    }

    fun close() {
        job?.cancel()
        job = null
    }
}
