package app.mebox.android.core.media

/**
 * 字幕轨的可读名称。
 *
 * 服务端对外挂字幕只给出文件名的语言后缀（`chs`、`chs[BilibiliID_danmu]`），
 * LibVLC 又只用「Track 1」这类编号命名外挂字幕轨；两者直接摆到设置面板里
 * 非常难懂。这里统一折算成中文名，并保留「外挂 / 内嵌」「弹幕」「ASS 样式」
 * 这些真正有用的信息。
 */
object SubtitleLabel {

    private val languages: Map<String, String> = buildMap {
        listOf("zh", "chi", "zho", "cn").forEach { put(it, "中文") }
        listOf("chs", "sc", "zh-cn", "zh-hans", "zh-sg")
            .forEach { put(it, "简体中文") }
        listOf("cht", "tc", "zh-tw", "zh-hk", "zh-mo", "zh-hant")
            .forEach { put(it, "繁体中文") }
        listOf("jpn", "ja", "jp").forEach { put(it, "日本語") }
        listOf("eng", "en").forEach { put(it, "English") }
        listOf("kor", "ko").forEach { put(it, "한국어") }
        put("yue", "粤语")
    }

    /** 容器/内核里常见的英文语言名，用于内嵌字幕轨。 */
    private val englishNames: Map<String, String> = mapOf(
        "chinese" to "中文",
        "mandarin" to "中文",
        "cantonese" to "粤语",
        "japanese" to "日本語",
        "english" to "English",
        "korean" to "한국어",
    )

    private val nativeTrackPattern = Regex("""Track\s+(\d+)\s*(?:-\s*\[([^\]]+)])?""", RegexOption.IGNORE_CASE)
    private val parenthesisPattern = Regex("""[（(][^）)]*[）)]""")

    /**
     * 语言标记 → 可读名称。
     *
     * 接受 `chs`、`zh-Hans`、`chs[BilibiliID_danmu]` 这类带附加信息的标记；
     * 认不出来时返回 null，交给调用方决定是否直接展示原始值。
     */
    fun languageName(raw: String): String? {
        val token = normalize(raw)
        if (token.isEmpty()) return null
        languages[token]?.let { return it }
        val head = token.substringBefore('[').substringBefore('.').substringBefore('@').trim()
        return languages[head]
    }

    /** 是否像弹幕文件（`xxx[BilibiliID_danmu].ass`、`弹幕.ass`）。 */
    fun isDanmaku(vararg parts: String): Boolean = parts.any { part ->
        val text = part.lowercase()
        text.contains("danmu") || text.contains("danmaku") || text.contains("弹幕")
    }

    /**
     * 字幕面板里展示的名称。
     *
     * @param rawLabel 服务端给的 label（可能是 `chs`、`简体中文`、`chi（默认）`）
     * @param lang 服务端给的语言标记
     * @param source 服务端的字幕来源：`external` / `embedded`
     * @param assStyled 当前内核能否还原 ASS 原始样式
     */
    fun display(
        rawLabel: String,
        lang: String,
        source: String?,
        assStyled: Boolean,
    ): String {
        val raw = rawLabel.trim()
        val base = baseName(raw, lang)
        // 原始 label 里的「（默认）」「（强制）」这类标记要保留；
        // 只有在名称被改写时才需要单独搬过来，原样返回时已经包含在内。
        val markers = if (raw.isNotEmpty() && raw != base) {
            parenthesisPattern.findAll(raw).joinToString("") { it.value }
        } else {
            ""
        }
        val danmaku = isDanmaku(rawLabel, lang) && !base.contains("弹幕")
        val suffix = when (source) {
            "embedded" -> "（内嵌）"
            "external" -> "（外挂）"
            else -> ""
        }
        return buildString {
            append(base)
            append(markers)
            if (danmaku) append(" · 弹幕")
            append(suffix)
            if (assStyled) append(" · ASS 样式")
        }
    }

    /**
     * 内核对内嵌字幕轨的原始描述 → 可读名称。
     *
     * LibVLC 对内嵌字幕轨的命名形如 `Track 1 - [Chinese]` / `Track 2`，
     * 直接展示没有意义；能读出语言就显示语言，读不出来就按序号编号。
     */
    fun nativeTrackName(description: String?, index: Int): String {
        val text = description?.trim().orEmpty()
        val match = nativeTrackPattern.find(text)
        if (match == null || match.range.first != 0) {
            return text.ifBlank { "内嵌字幕 ${index + 1}" }
        }
        val language = match.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
        val readable = language?.let { englishNames[it.lowercase()] ?: it }
        return if (readable != null) "内嵌字幕（$readable）" else "内嵌字幕 ${index + 1}"
    }

    private fun baseName(rawLabel: String, lang: String): String {
        val label = rawLabel.trim()
        // 「简体中文（默认）」这类本身就带可读名称的 label 原样保留。
        if (label.replace(parenthesisPattern, "").any(::isCjk)) return label
        // 「chs[bilibiliid_danmu]」「chi（默认）」先取前缀语言标记。
        val head = label.substringBefore('[').substringBefore('（').substringBefore('(').trim()
        languageName(head)?.let { return it }
        languageName(lang)?.let { return it }
        if (label.isEmpty() || label.equals("und", true) || label.equals("unknown", true)) {
            return "字幕"
        }
        return label
    }

    private fun normalize(raw: String): String =
        raw.trim().lowercase().replace('_', '-')

    private fun isCjk(ch: Char): Boolean = ch in '\u4e00'..'\u9fff'
}
