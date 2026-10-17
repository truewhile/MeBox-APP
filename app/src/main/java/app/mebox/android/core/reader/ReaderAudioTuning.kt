package app.mebox.android.core.reader

import kotlin.math.roundToInt

/**
 * 听书播放的可调范围与片头/片尾跳过规则。
 *
 * 边界和判断都放在纯函数里，播放引擎与播放器界面共用同一套口径，
 * 也便于在 JVM 单元测试中直接验证。
 */
object ReaderAudioTuning {

    /** 倍速范围，与 ExoPlayer 的音频处理能力以及服务端偏好字段保持一致。 */
    const val SPEED_MIN = 0.5f
    const val SPEED_MAX = 3f
    const val SPEED_STEP = 0.05f

    /** 定时关闭：0 表示不启用。 */
    const val TIMER_MIN_MINUTES = 1
    const val TIMER_MAX_MINUTES = 180

    /** 片头/片尾最多可跳过的时间，以及拖动步长。 */
    const val CREDITS_MAX_SECONDS = 600
    const val CREDITS_STEP_SECONDS = 5

    /** 把任意倍速吸附到 [SPEED_STEP] 的整数档，并限制在上下界内。 */
    fun snapSpeed(speed: Float): Float {
        if (!speed.isFinite()) return 1f
        val clamped = speed.coerceIn(SPEED_MIN, SPEED_MAX)
        val steps = ((clamped - SPEED_MIN) / SPEED_STEP).roundToInt()
        return ((SPEED_MIN + steps * SPEED_STEP) * 100f).roundToInt() / 100f
    }

    /** 把任意时长收敛到 0..[TIMER_MAX_MINUTES]，0 表示取消定时。 */
    fun clampTimerMinutes(minutes: Int): Int = minutes.coerceIn(0, TIMER_MAX_MINUTES)

    /** 写入服务端偏好时保留两位小数，避免出现 1.0500000715 这类浮点尾巴。 */
    fun speedProfileValue(speed: Float): Double = (snapSpeed(speed) * 100f).roundToInt() / 100.0

    /** 把片头/片尾秒数收敛到 0..[CREDITS_MAX_SECONDS]。 */
    fun clampCreditsSeconds(seconds: Int): Int = seconds.coerceIn(0, CREDITS_MAX_SECONDS)

    /** 拖动滑杆时把片头/片尾吸附到 [CREDITS_STEP_SECONDS] 的整数档。 */
    fun snapCreditsSeconds(seconds: Int): Int {
        val clamped = seconds.coerceIn(0, CREDITS_MAX_SECONDS)
        return (clamped / CREDITS_STEP_SECONDS.toFloat()).roundToInt() * CREDITS_STEP_SECONDS
    }

    /**
     * 计算进入某章时应落到的位置。
     *
     * 只有「请求位置还落在片头里」才向后跳，用户从更靠后的进度继续听时保持原位置；
     * 已知时长时，如果片头比整章还长则不做跳过，避免直接跳到结尾。
     */
    fun startPositionMs(
        openCreditsSeconds: Int,
        requestedMs: Long,
        durationMs: Long,
    ): Long {
        val open = clampCreditsSeconds(openCreditsSeconds) * 1_000L
        val requested = requestedMs.coerceAtLeast(0L)
        if (open <= 0L || requested >= open) return requested
        if (durationMs > 0L && open >= durationMs) return requested
        return open
    }

    /**
     * 是否已经播到片尾跳过区。
     *
     * 片头+片尾会占满整章时不做片尾跳过；位置未知或时长为 0 时也返回 false，
     * 等播放器给出真实进度后再判断。
     */
    fun isEndCreditsReached(
        openCreditsSeconds: Int,
        closeCreditsSeconds: Int,
        positionMs: Long,
        durationMs: Long,
    ): Boolean {
        val open = clampCreditsSeconds(openCreditsSeconds) * 1_000L
        val close = clampCreditsSeconds(closeCreditsSeconds) * 1_000L
        if (close <= 0L || positionMs <= 0L || durationMs <= 0L) return false
        if (durationMs <= open + close) return false
        return positionMs >= durationMs - close
    }
}
