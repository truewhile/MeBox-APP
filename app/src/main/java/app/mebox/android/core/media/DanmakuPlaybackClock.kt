package app.mebox.android.core.media

import kotlin.math.abs

/**
 * 把播放器间隔更新的进度转换为连续的弹幕时间，不改变播放器本身的时间。
 *
 * 帧间使用单调时间和播放倍速推进；小采样误差通过最多 10% 的速度修正逐渐收敛，
 * 不在每次收到采样时后退。暂停/缓冲、倍速变化、seek 和长时间无帧时重新同步。
 * 播放器连续 500ms 不更新进度时停止外推，防止未及时上报的停滞导致无限漂移。
 */
class DanmakuPlaybackClock {
    private var initialized = false
    private var lastFrameNanos = 0L
    private var lastSampleFrameNanos = 0L
    private var lastSampleMs = 0L
    private var lastPlaying = false
    private var lastSpeed = 1f
    private var lastDiscontinuity = 0
    private var awaitingDiscontinuitySample = false
    private var displayMs = 0.0
    private var correctionMs = 0.0

    fun positionMs(
        frameNanos: Long,
        sampledPositionMs: Long,
        playing: Boolean,
        speed: Float,
        discontinuity: Int = 0,
    ): Long {
        val sample = sampledPositionMs.coerceAtLeast(0L)
        val rate = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        val frameDelta = frameNanos - lastFrameNanos
        if (!initialized || !playing || playing != lastPlaying || rate != lastSpeed ||
            discontinuity != lastDiscontinuity || frameDelta < 0L || frameDelta > MAX_GAP_NANOS
        ) {
            // seek 是异步的：版本变化时仍读到旧进度，就等待首个新采样再重新同步。
            // 暂停/缓冲等状态变化不能提前丢掉这次待完成的同步。
            if (initialized && discontinuity != lastDiscontinuity) {
                awaitingDiscontinuitySample = sample == lastSampleMs
            } else if (sample != lastSampleMs) {
                awaitingDiscontinuitySample = false
            }
            initialized = true
            lastFrameNanos = frameNanos
            lastSampleFrameNanos = frameNanos
            lastSampleMs = sample
            lastPlaying = playing
            lastSpeed = rate
            lastDiscontinuity = discontinuity
            displayMs = sample.toDouble()
            correctionMs = 0.0
            return sample
        }

        val sampleChanged = sample != lastSampleMs
        // 进度没有更新时，只计算外推期限内的那部分帧时间；到期后原地停住。
        val advanceNanos = if (sampleChanged) {
            frameDelta
        } else {
            val remaining = (MAX_GAP_NANOS - (lastFrameNanos - lastSampleFrameNanos))
                .coerceAtLeast(0L)
            frameDelta.coerceAtMost(remaining)
        }
        val advanceMs = advanceNanos / 1_000_000.0 * rate
        val predictedMs = displayMs + advanceMs

        if (sampleChanged) {
            val errorMs = sample - predictedMs
            if (awaitingDiscontinuitySample || sample < lastSampleMs ||
                abs(errorMs) > MAX_DRIFT_MS * rate.coerceAtLeast(1f)
            ) {
                // 大跳转或回退不是采样抖动，应立即跟随播放器。
                displayMs = sample.toDouble()
                correctionMs = 0.0
            } else {
                correctionMs = errorMs
                val correction = correctionMs.coerceIn(-advanceMs * CORRECTION_RATE, advanceMs * CORRECTION_RATE)
                displayMs = predictedMs + correction
                correctionMs -= correction
            }
            awaitingDiscontinuitySample = false
            lastSampleMs = sample
            lastSampleFrameNanos = frameNanos
        } else {
            val correction = correctionMs.coerceIn(-advanceMs * CORRECTION_RATE, advanceMs * CORRECTION_RATE)
            displayMs = predictedMs + correction
            correctionMs -= correction
        }
        lastFrameNanos = frameNanos
        return displayMs.toLong().coerceAtLeast(0L)
    }

    private companion object {
        const val MAX_GAP_NANOS = 500_000_000L
        const val MAX_DRIFT_MS = 750.0
        const val CORRECTION_RATE = 0.1
    }
}
