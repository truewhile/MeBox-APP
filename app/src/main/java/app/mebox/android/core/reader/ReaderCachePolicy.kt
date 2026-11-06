package app.mebox.android.core.reader

import app.mebox.android.core.network.ReaderChapterDto

/** ALL is a management filter, never a kind assigned to a chapter. */
enum class ReaderCacheKind { AUTOMATIC, DOWNLOAD, ALL }

data class ReaderBookStorage(
    val bookId: String,
    val name: String,
    val automaticBytes: Long,
    val downloadBytes: Long,
    val automaticChapters: Int,
    val downloadChapters: Int,
) {
    val totalBytes: Long get() = automaticBytes + downloadBytes
}

/** Shared disk/preload policy. Positions refer to the TOC list, not the server's index field. */
object ReaderCachePolicy {
    const val AUTOMATIC_MAX_BYTES = 512L * 1024 * 1024
    const val MIN_AUTOMATIC_FREE_BYTES = 128L * 1024 * 1024
    const val WINDOW_RADIUS = 5

    fun window(chapters: List<ReaderChapterDto>, current: Int): Set<Int> {
        if (current !in chapters.indices) return emptySet()
        val before = chapters.indices.filter { it < current && !chapters[it].isVolume }.takeLast(WINDOW_RADIUS)
        val after = chapters.indices.filter { it > current && !chapters[it].isVolume }.take(WINDOW_RADIUS)
        return (before + listOfNotNull(current.takeUnless { chapters[it].isVolume }) + after).toSet()
    }
}
