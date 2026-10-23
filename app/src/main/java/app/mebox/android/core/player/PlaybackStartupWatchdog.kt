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

    /**
     * [noProgressTimeoutMs] 只在「本轮一个字节都没读到、也没有任何轨道进展」
     * 时生效，给调用方一个更短的兜底窗口：手上还有别的地址可换时（例如
     * 115 直链的明文 http），没有必要把整整 [idleTimeoutMs] 耗在转圈上。
     * 有过进展后回到正常的停滞判断，避免把慢但能用的链路判死。
     */
    fun hasTimedOut(nowMs: Long, noProgressTimeoutMs: Long = idleTimeoutMs): Boolean =
        nowMs - lastProgressMs >= idleTimeoutMs ||
            (!hasProgress && nowMs - startedAtMs >= noProgressTimeoutMs) ||
            nowMs - startedAtMs >= maxStartupMs
}
