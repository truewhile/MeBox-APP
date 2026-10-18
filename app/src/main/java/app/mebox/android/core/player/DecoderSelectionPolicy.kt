package app.mebox.android.core.player

/** 解码器候选的过滤方式。 */
internal enum class DecoderConstraint {
    /** 不额外过滤，交给内核或系统选择。 */
    NONE,

    /** 只允许硬件解码器，且仅限视频轨；音频仍允许系统软件解码器。 */
    HARDWARE_VIDEO_ONLY,

    /** 所有轨道只允许软件解码器。 */
    SOFTWARE_ONLY,
}

/**
 * 把用户选择的解码方式翻译成解码器候选过滤规则。
 *
 * 强制硬件只约束视频：部分设备的音频编码（AAC、AC-3 等）没有对应的硬件
 * 解码器，如果连音频也一起过滤，会表现为“有画面没声音”。
 */
internal object DecoderSelectionPolicy {

    fun isVideoMimeType(mimeType: String): Boolean =
        mimeType.startsWith("video/", ignoreCase = true)

    fun constraintFor(mode: DecoderMode, isVideo: Boolean): DecoderConstraint = when (mode) {
        DecoderMode.AUTO -> DecoderConstraint.NONE
        DecoderMode.SOFTWARE -> DecoderConstraint.SOFTWARE_ONLY
        DecoderMode.HARDWARE ->
            if (isVideo) DecoderConstraint.HARDWARE_VIDEO_ONLY else DecoderConstraint.NONE
    }
}
