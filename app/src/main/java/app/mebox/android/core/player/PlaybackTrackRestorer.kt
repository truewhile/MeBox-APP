package app.mebox.android.core.player

/** 跨内核恢复轨道时使用的语义身份；内核内的轨道 id 每次装载都会变。 */
data class PlaybackTrackRef(
    val label: String,
    val language: String?,
)

/** 用户对某一类轨道的选择。 */
sealed interface PlaybackTrackSelection {
    /** 交给内核或语言偏好自动选择。 */
    data object Auto : PlaybackTrackSelection

    /** 用户明确关闭了这类轨道（目前只用于字幕）。 */
    data object Off : PlaybackTrackSelection

    /** 用户明确选中的某条轨道。 */
    data class Track(val ref: PlaybackTrackRef) : PlaybackTrackSelection
}

/**
 * 换内核或内核内部重载媒体后，按语义找回用户原来选中的音轨/字幕轨。
 *
 * 不同内核的轨道 id 互不通用，通用占位名（Track 2、字幕 1）也不能当身份。
 * 匹配优先级：规范化名称完全一致 → 唯一的语言匹配 → 唯一的名称包含关系；
 * 始终只接受唯一结果，宁可不选也不猜错。
 */
object PlaybackTrackRestorer {

    fun refOf(track: EngineTrack): PlaybackTrackRef =
        PlaybackTrackRef(label = track.label, language = track.language)

    fun match(
        tracks: List<EngineTrack>,
        type: EngineTrackType,
        selection: PlaybackTrackSelection,
    ): EngineTrack? {
        val ref = (selection as? PlaybackTrackSelection.Track)?.ref ?: return null
        val candidates = tracks.filter { it.type == type }
        if (candidates.isEmpty()) return null
        val refLabel = meaningfulLabel(ref.label)
        val refLanguage = languageGroup(ref.language)

        if (refLabel != null) {
            val sameLabel = candidates.filter { meaningfulLabel(it.label) == refLabel }
            when {
                sameLabel.size == 1 -> return sameLabel.single()
                sameLabel.size > 1 && refLanguage != null ->
                    return sameLabel.filter { languageGroup(it.language) == refLanguage }.singleOrNull()
            }
        }

        if (refLanguage != null) {
            val sameLanguage = candidates.filter { languageGroup(it.language) == refLanguage }
            if (sameLanguage.size == 1) return sameLanguage.single()
            if (refLabel != null) {
                val contained = sameLanguage.filter { labelContains(it.label, refLabel) }
                if (contained.size == 1) return contained.single()
            }
        } else if (refLabel != null) {
            // 旧内核没有语言元数据时，只在唯一的名称包含关系上恢复。
            val contained = candidates.filter { labelContains(it.label, refLabel) }
            if (contained.size == 1) return contained.single()
        }

        return null
    }

    /** 规范化后的轨道名；通用占位名（Track 2、字幕 1）不携带身份信息。 */
    internal fun meaningfulLabel(label: String): String? {
        val normalized = label.trim().lowercase()
            // 外挂字幕常带文件后缀；去掉后再比较。
            .substringBeforeLast('.')
            .replace(TRACK_LABEL_SEPARATORS, "")
        if (normalized.isEmpty()) return null
        if (GENERIC_TRACK_LABEL.matches(normalized)) return null
        return normalized
    }

    /** 语言主标签：zh-Hans / chi 都归到 zh；其余按主标签比较，空值返回 null。 */
    internal fun languageGroup(language: String?): String? {
        val normalized = language?.trim()?.lowercase()?.replace('_', '-').orEmpty()
        if (normalized.isEmpty()) return null
        return when {
            normalized == "zh" || normalized.startsWith("zh-") ||
                normalized in CHINESE_LANGUAGE_CODES -> "zh"

            normalized == "en" || normalized.startsWith("en-") ||
                normalized in ENGLISH_LANGUAGE_CODES -> "en"

            normalized == "ja" || normalized.startsWith("ja-") ||
                normalized in JAPANESE_LANGUAGE_CODES -> "ja"

            else -> normalized.substringBefore('-')
        }
    }

    private fun labelContains(candidate: String, refLabel: String): Boolean {
        val label = meaningfulLabel(candidate) ?: return false
        return label.contains(refLabel) || refLabel.contains(label)
    }

    private val CHINESE_LANGUAGE_CODES = setOf("zho", "chi", "cmn", "yue")
    private val ENGLISH_LANGUAGE_CODES = setOf("eng")
    private val JAPANESE_LANGUAGE_CODES = setOf("jpn")
    private val TRACK_LABEL_SEPARATORS = Regex("[\\s_\\-·]+")
    private val GENERIC_TRACK_LABEL = Regex(
        "^(track|track\\d+|subtitle|sub|audio|video|字幕\\d*|音轨\\d*|画面\\d*|音声\\d*)$",
    )
}
