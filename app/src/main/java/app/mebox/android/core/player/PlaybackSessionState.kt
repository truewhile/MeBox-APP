package app.mebox.android.core.player

/**
 * 播放内核内部的“用户播放意图”。
 *
 * 与内核此刻是否真的在播放分开：缓冲、内部换地址、换解码方式都不改变意图，
 * 只有用户的播放/暂停操作、新媒体的自动播放设置或显式重试才会更新它。
 */
internal class PlaybackIntent(initialPlayWhenReady: Boolean = true) {

    var playWhenReady: Boolean = initialPlayWhenReady
        private set

    fun onPlay() {
        playWhenReady = true
    }

    fun onPause() {
        playWhenReady = false
    }

    /** 装载新媒体时按调用方要求设置是否自动起播。 */
    fun onMediaLoaded(autoPlay: Boolean) {
        playWhenReady = autoPlay
    }
}

/**
 * 一次播放页会话内的状态：用户播放意图 + 切换内核时的续播位置。
 *
 * 只记录用户希望怎样播放，不根据缓冲或错误推断。暂停着切换内核，新内核仍
 * 保持暂停；自动回退沿用原意图；显式重试才把意图改回播放。
 *
 * 纯 Kotlin，便于在 JVM 单元测试里验证续播与意图规则。
 */
class PlaybackSessionState(
    initialPlayWhenReady: Boolean = true,
    initialPositionMs: Long = 0L,
) {

    var playWhenReady: Boolean = initialPlayWhenReady
        private set

    /** 最近一次已知的有效位置；0 是合法值。 */
    var lastKnownPositionMs: Long = initialPositionMs
        private set

    private var pendingResumePositionMs: Long? = null

    fun onUserPlay() {
        playWhenReady = true
    }

    fun onUserPause() {
        playWhenReady = false
    }

    /** 显式重试：用户要求继续播放。 */
    fun onExplicitRetry() {
        playWhenReady = true
    }

    /** 新的一集或新地址装载时按调用方要求设置自动播放。 */
    fun onMediaLoaded(autoPlay: Boolean) {
        playWhenReady = autoPlay
    }

    /** 记录引擎上报的有效位置。 */
    fun recordPosition(positionMs: Long) {
        if (positionMs >= 0L) lastKnownPositionMs = positionMs
    }

    /**
     * 切换内核前固定续播点。
     *
     * 引擎在缓冲或出错时可能报 0，这时沿用 [lastKnownPositionMs]，避免回到开头。
     */
    fun beginHandoff(currentPositionMs: Long) {
        if (currentPositionMs > 0L) lastKnownPositionMs = currentPositionMs
        pendingResumePositionMs = lastKnownPositionMs
    }

    /**
     * 新内核装载媒体时读取续播点。
     *
     * 读取后清空挂起值，避免新内核尚未就绪时上报的 0 位置覆盖它。
     */
    fun consumeResumePosition(): Long {
        val pending = pendingResumePositionMs ?: return lastKnownPositionMs
        pendingResumePositionMs = null
        lastKnownPositionMs = pending
        return pending
    }
}
