package app.mebox.android.core.player.vr

/**
 * 全景视频的投影方式。
 *
 * 服务端只返回一条直链，不会标注片源是不是 360/180；这里既允许用户手动指定，
 * 也提供按片名/文件名猜测的「自动」模式。保持纯 Kotlin，便于单元测试。
 */
enum class VrProjection(val key: String, val label: String, val shortLabel: String) {
    AUTO("auto", "自动识别", "自动"),
    EQUIRECT_360("360", "360° 全景（等距柱状）", "360°"),
    HEMISPHERE_180("180", "180° 半球", "180°"),
    ;

    companion object {
        fun fromKey(key: String?): VrProjection =
            entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/** 片源的立体排布方式，只影响纹理采样，不影响屏幕上的双眼布局。 */
enum class VrStereo(val key: String, val label: String, val shortLabel: String) {
    AUTO("auto", "自动识别", "自动"),
    MONO("mono", "单目（普通全景）", "单目"),
    SIDE_BY_SIDE("sbs", "左右并排（SBS）", "左右"),
    TOP_BOTTOM("tb", "上下并排（TB / OU）", "上下"),
    ;

    companion object {
        fun fromKey(key: String?): VrStereo =
            entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/** 屏幕上如何摆放两只眼睛的画面。 */
enum class VrDisplayMode(val key: String, val label: String, val shortLabel: String) {
    AUTO("auto", "自动（立体片源分屏）", "自动"),
    FULLSCREEN("fullscreen", "手机全屏", "全屏"),
    HEADSET("headset", "头显双眼分屏", "双眼"),
    ;

    companion object {
        fun fromKey(key: String?): VrDisplayMode =
            entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/**
 * 一次播放的 VR 参数。
 *
 * [AUTO] 只存在于用户选择里；传给渲染器前必须经过 [resolve]，
 * 得到投影/立体/显示方式都是具体值的实例。
 */
data class VrSettings(
    val projection: VrProjection = VrProjection.AUTO,
    val stereo: VrStereo = VrStereo.AUTO,
    val display: VrDisplayMode = VrDisplayMode.AUTO,
    /** 纸盒眼镜的桶形畸变预校正；手机全屏观看时通常关闭。 */
    val lensCorrection: Boolean = false,
) {
    val isResolved: Boolean
        get() = projection != VrProjection.AUTO &&
            stereo != VrStereo.AUTO &&
            display != VrDisplayMode.AUTO

    /**
     * 把「自动」展开成具体参数。
     *
     * 识别只看片名、标题和地址：服务端没有球面元数据，容器里的 `sv3d/st3d`
     * 目前也没有通过 Media3 暴露出来，猜错时用户可以在一级面板里改。
     */
    fun resolve(vararg hints: String?): VrSettings {
        val text = hints.filterNotNull().joinToString(" ").lowercase()
        val resolvedProjection = when (projection) {
            VrProjection.AUTO -> VrAutoDetect.projection(text)
            else -> projection
        }
        val resolvedStereo = when (stereo) {
            VrStereo.AUTO -> VrAutoDetect.stereo(text)
            else -> stereo
        }
        val resolvedDisplay = when (display) {
            VrDisplayMode.AUTO ->
                if (resolvedStereo == VrStereo.MONO) VrDisplayMode.FULLSCREEN else VrDisplayMode.HEADSET
            else -> display
        }
        return VrSettings(
            projection = resolvedProjection,
            stereo = resolvedStereo,
            display = resolvedDisplay,
            lensCorrection = lensCorrection,
        )
    }

    /** 设置页/更多面板里的简短描述。 */
    fun summary(): String {
        val resolved = resolve()
        val stereoText = if (resolved.stereo == VrStereo.MONO) "" else " · ${resolved.stereo.shortLabel}"
        return "${resolved.projection.shortLabel}$stereoText · ${resolved.display.shortLabel}"
    }
}

/** 从片名/地址里猜 360/180 与立体排布。 */
object VrAutoDetect {

    private val hemi180 = Regex("(?<!\\d)180(?!\\d|[pi])")
    private val full360 = Regex("(?<!\\d)360(?!\\d|[pi])")

    private val sbsHints = listOf(
        "sbs", "side-by-side", "side by side", "sidebyside",
        "left-right", "left right", "lr", "左右", "并排",
    )
    private val tbHints = listOf(
        "tb", "ou", "top-bottom", "top bottom", "topbottom",
        "over-under", "over under", "上下", "overunder",
    )
    private val monoHints = listOf("mono", "2d", "单目", "非立体")

    fun projection(text: String): VrProjection {
        val normalized = text.lowercase()
        return when {
            hemi180.containsMatchIn(normalized) -> VrProjection.HEMISPHERE_180
            full360.containsMatchIn(normalized) -> VrProjection.EQUIRECT_360
            // 「VR」「全景」一般指整球；没有更多信息时按最常见的 360 处理。
            else -> VrProjection.EQUIRECT_360
        }
    }

    fun stereo(text: String): VrStereo {
        val normalized = text.lowercase()
        if (monoHints.any { containsWord(normalized, it) }) return VrStereo.MONO
        if (tbHints.any { containsWord(normalized, it) }) return VrStereo.TOP_BOTTOM
        if (sbsHints.any { containsWord(normalized, it) }) return VrStereo.SIDE_BY_SIDE
        // 只写 3D / VR 的片源大多是左右排布；宁可猜左右，用户也能手动改。
        if (containsWord(normalized, "3d") || containsWord(normalized, "vr")) {
            return VrStereo.SIDE_BY_SIDE
        }
        return VrStereo.MONO
    }

    /**
     * 词边界匹配。文件名常用 `_`、`.`、`-` 分隔，正则的 `\b` 在这些字符上
     * 行为不稳定，这里自己判断前后是否仍是字母/数字。
     */
    private fun containsWord(text: String, word: String): Boolean {
        // 中文没有空格分词，直接按子串匹配；英文才需要词边界。
        if (word.any { it.code > 0x2E80 }) return text.contains(word)
        var index = text.indexOf(word)
        while (index >= 0) {
            val before = text.getOrNull(index - 1)
            val after = text.getOrNull(index + word.length)
            if (!before.isWordChar() && !after.isWordChar()) return true
            index = text.indexOf(word, index + 1)
        }
        return false
    }

    private fun Char?.isWordChar(): Boolean {
        if (this == null) return false
        // 下划线、点、横线都是文件名分隔符，不算单词的一部分。
        return isLetterOrDigit()
    }
}
