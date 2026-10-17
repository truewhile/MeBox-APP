package app.mebox.android.core.media

/**
 * 播放位置与时间轴的纯逻辑。
 *
 * 单独抽出来是为了能在 JVM 单元测试里验证续播规则：服务端进度是权威值，
 * 本地缓存只用来补上「最后一次上报失败」的缺口；离结尾很近时重新从头播放，
 * 避免一进播放器就立刻结束。
 */
object PlaybackPlan {

    /** 距离结尾小于该值时视为已看完，重新从头播放。 */
    const val FINISHED_MARGIN_MS: Long = 20_000L

    /**
     * 计算起播位置。
     *
     * - 服务端标记已完成：从头播放；
     * - 服务端进度为 0：使用本地缓存（离线/上次上报失败的兜底）；
     * - 两者都有：取较大值，因为本地通常比服务端更新；
     * - 超过总时长或离结尾过近：从头播放。
     */
    fun startPosition(
        serverPositionMs: Long,
        cachedPositionMs: Long,
        durationMs: Long,
        completed: Boolean = false,
    ): Long {
        if (completed) return 0L
        val candidate = maxOf(serverPositionMs, cachedPositionMs).coerceAtLeast(0L)
        if (candidate == 0L) return 0L
        if (durationMs > 0L) {
            if (candidate >= durationMs - FINISHED_MARGIN_MS) return 0L
            return candidate.coerceAtMost(durationMs)
        }
        return candidate
    }

    /** 拖动/双击后的目标位置，始终限制在 [0, duration]。 */
    fun seekTarget(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
        val target = (positionMs + deltaMs).coerceAtLeast(0L)
        return if (durationMs > 0L) target.coerceAtMost(durationMs) else target
    }

    fun progressFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    /** `h:mm:ss`（不足一小时时 `m:ss`）。 */
    fun formatTime(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    fun formatSpeed(speed: Float): String {
        val rounded = (Math.round(speed * 100f) / 100f)
        return if (rounded == rounded.toInt().toFloat()) {
            "${rounded.toInt()}×"
        } else {
            "${"%.2f".format(rounded).trimEnd('0').trimEnd('.')}×"
        }
    }
}
