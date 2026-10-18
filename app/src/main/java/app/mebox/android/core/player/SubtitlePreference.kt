package app.mebox.android.core.player

/**
 * 字幕自动加载与优先语言。
 *
 * 纯 Kotlin，不依赖 Android/Compose，播放页、LibVLC 内核和单元测试共用
 * 同一套选择规则。
 */
enum class SubtitlePriority(
    val key: String,
    val label: String,
    val description: String,
) {
    FOLLOW_SOURCE(
        key = "auto",
        label = "跟随片源",
        description = "不指定语言：优先用片源默认选中的字幕，否则取第一条",
    ),
    CHINESE(
        key = "zh",
        label = "中文优先",
        description = "优先简体中文，其次繁体中文；没有中文时取第一条字幕",
    ),
    ENGLISH(
        key = "en",
        label = "英文优先",
        description = "优先英文字幕；没有英文时取第一条字幕",
    ),
    JAPANESE(
        key = "ja",
        label = "日文优先",
        description = "优先日文字幕；没有日文时取第一条字幕",
    ),
    ;

    companion object {
        val default: SubtitlePriority = CHINESE

        /** 读取持久化值时容错：未知或空值一律回落到默认偏好。 */
        fun fromKey(key: String?): SubtitlePriority =
            entries.firstOrNull { it.key == key } ?: default
    }
}

/** 用户在本机保存的字幕偏好。 */
data class SubtitlePreference(
    val autoLoad: Boolean = true,
    val priority: SubtitlePriority = SubtitlePriority.default,
)

/**
 * 从候选字幕里按偏好挑一条。
 *
 * 返回值语义：
 * - [choose]：能播就选一条——指定语言没有匹配时退回第一条；
 * - [findMatch]：只返回语言匹配的频道，用于不想覆盖内核默认选择的场景。
 */
object SubtitleSelector {

    fun choose(tracks: List<EngineTrack>, preference: SubtitlePreference): EngineTrack? =
        chooseBest(
            candidates = tracks,
            languageOf = { it.language },
            labelOf = { it.label },
            isSelectedOf = { it.selected },
            preference = preference,
        )

    fun choose(subtitles: List<EngineSubtitle>, preference: SubtitlePreference): EngineSubtitle? =
        chooseBest(
            candidates = subtitles,
            languageOf = { it.language },
            labelOf = { it.label },
            isSelectedOf = { false },
            preference = preference,
        )

    fun findMatch(tracks: List<EngineTrack>, preference: SubtitlePreference): EngineTrack? {
        if (!preference.autoLoad || preference.priority == SubtitlePriority.FOLLOW_SOURCE) return null
        return tracks.maxByOrNull { track ->
            score(track.language, track.label, preference.priority)
        }?.takeIf { score(it.language, it.label, preference.priority) > 0 }
    }

    private fun <T> chooseBest(
        candidates: List<T>,
        languageOf: (T) -> String?,
        labelOf: (T) -> String,
        isSelectedOf: (T) -> Boolean,
        preference: SubtitlePreference,
    ): T? {
        if (!preference.autoLoad || candidates.isEmpty()) return null
        if (preference.priority == SubtitlePriority.FOLLOW_SOURCE) {
            return candidates.firstOrNull(isSelectedOf) ?: candidates.first()
        }
        val best = candidates.maxByOrNull { candidate ->
            score(languageOf(candidate), labelOf(candidate), preference.priority)
        } ?: return candidates.first()
        return best.takeIf { score(languageOf(it), labelOf(it), preference.priority) > 0 }
            ?: candidates.first()
    }

    /**
     * 语言匹配打分；0 表示不匹配。
     *
     * 语言标签优先（ISO 639-1/639-2），没有语言元数据时再看轨道名，
     * 因为不少外挂字幕是 `简体中文.ass` 这类只有文件名的情况。
     */
    private fun score(
        language: String?,
        label: String,
        priority: SubtitlePriority,
    ): Int {
        val lang = normalizeLanguage(language)
        val text = label.lowercase()
        val matched = when (priority) {
            SubtitlePriority.CHINESE ->
                lang.isChinese() || text.containsAny(CHINESE_HINTS)
            SubtitlePriority.ENGLISH ->
                lang.isEnglish() || text.containsAny(ENGLISH_HINTS)
            SubtitlePriority.JAPANESE ->
                lang.isJapanese() || text.containsAny(JAPANESE_HINTS)
            SubtitlePriority.FOLLOW_SOURCE -> false
        }
        if (!matched) return 0

        // 中文再区分简繁：简体优先，未标注其次，繁体最后。
        return when (priority) {
            SubtitlePriority.CHINESE -> when {
                text.containsAny(SIMPLIFIED_HINTS) -> 13
                text.containsAny(TRADITIONAL_HINTS) -> 9
                else -> 10
            }
            else -> 10
        }
    }

    private fun normalizeLanguage(language: String?): String =
        language.orEmpty().trim().lowercase().replace('_', '-')

    private fun String.isChinese(): Boolean =
        this == "zho" || this == "chi" || this == "cmn" || this == "yue" ||
            startsWith("zh-") || this == "zh"

    private fun String.isEnglish(): Boolean =
        this == "eng" || startsWith("en-") || this == "en"

    private fun String.isJapanese(): Boolean =
        this == "jpn" || startsWith("ja-") || this == "ja"

    private fun String.containsAny(hints: List<String>): Boolean =
        hints.any { contains(it) }

    private val CHINESE_HINTS = listOf(
        "中文", "中字", "中英", "简中", "繁中", "简体", "繁体", "繁體", "双语",
        "chinese", "chs", "cht", "chi", "zho",
    )
    private val SIMPLIFIED_HINTS = listOf("简体", "简中", "简日", "简繁", "chs", "simplified")
    private val TRADITIONAL_HINTS = listOf("繁体", "繁體", "繁中", "繁日", "cht", "traditional")
    private val ENGLISH_HINTS = listOf("英文", "英语", "english", "eng")
    private val JAPANESE_HINTS = listOf("日文", "日语", "日本語", "japanese", "jpn")
}
