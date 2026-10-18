package app.mebox.android.core.reader

/**
 * 听书请求的生命周期。音轨请求/服务连接与播放器缓冲是两个阶段，
 * 不能用 prepare() 返回或 autoplay 推断音频已经开始播放。
 * 所有调用与 ReaderAudioEngine 一样在主线程串行执行。
 */
internal class ReaderAudioPlayback {
    var requestId: Long = 0
        private set
    var preparing: Boolean = false
        private set
    var playWhenReady: Boolean = false
        private set
    var playing: Boolean = false
        private set
    var failed: Boolean = false
        private set
    private var active = false
    private var buffering = false

    val loading: Boolean get() = preparing || buffering

    fun begin(autoPlay: Boolean): Long {
        requestId += 1
        active = true
        preparing = true
        buffering = false
        playing = false
        failed = false
        playWhenReady = autoPlay
        return requestId
    }

    fun isCurrent(request: Long): Boolean = active && request == requestId

    fun setPlayWhenReady(value: Boolean) {
        playWhenReady = value
        if (!value) playing = false
    }

    fun mediaInstalled(request: Long): Boolean {
        if (!isCurrent(request) || !preparing) return false
        preparing = false
        // 控制器的远程缓冲事件可能稍后才到，装载到 READY 之间持续显示加载。
        buffering = true
        return true
    }

    fun playerChanged(buffering: Boolean, playing: Boolean, playWhenReady: Boolean) {
        // 新章解析期间收到的还是旧媒体的暂停/空闲事件，不能清除新请求或覆盖意图。
        if (!active || preparing || failed) return
        this.buffering = buffering
        this.playing = playing
        this.playWhenReady = playWhenReady
    }

    fun fail(request: Long): Boolean {
        if (!isCurrent(request)) return false
        preparing = false
        buffering = false
        playing = false
        playWhenReady = false
        failed = true
        return true
    }

    fun reset() {
        requestId += 1
        active = false
        preparing = false
        buffering = false
        playing = false
        playWhenReady = false
        failed = false
    }
}
