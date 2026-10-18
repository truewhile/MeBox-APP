package app.mebox.android.core.player

/**
 * 起播超时按「多久没有进展」判断，而不是固定从 play() 倒数十秒。
 * VLC 在完成初始化前字节统计可能一直为零；轨道解析、缓冲推进也是有效进展。
 * 时间由调用方传入单调时钟，避免系统时间调整影响判断。
 */
internal class PlaybackStartupWatchdog(
    private val startedAtMs: Long,
    private val idleTimeoutMs: Long = 20_000,
    private val maxStartupMs: Long = 90_000,
) {
    private var lastProgressMs = startedAtMs
    private var maxReadBytes = 0L
    private var maxDemuxBytes = 0L
    var hasProgress: Boolean = false
        private set

    fun recordProgress(nowMs: Long) {
        hasProgress = true
        lastProgressMs = nowMs
    }

    fun observeBytes(nowMs: Long, readBytes: Long, demuxBytes: Long) {
        if (readBytes > maxReadBytes || demuxBytes > maxDemuxBytes) recordProgress(nowMs)
        maxReadBytes = maxOf(maxReadBytes, readBytes)
        maxDemuxBytes = maxOf(maxDemuxBytes, demuxBytes)
    }

    fun hasTimedOut(nowMs: Long): Boolean =
        nowMs - lastProgressMs >= idleTimeoutMs || nowMs - startedAtMs >= maxStartupMs
}
