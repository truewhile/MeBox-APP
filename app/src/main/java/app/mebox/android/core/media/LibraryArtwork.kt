package app.mebox.android.core.media

import app.mebox.android.core.network.LibraryDto
import app.mebox.android.core.network.SeriesCardDto

/**
 * 媒体库卡片的封面选择。
 *
 * 服务端只有管理员显式设置过封面时才会返回 `cover_url`；大多数媒体库的
 * `cover_url` 是空的。Web 端此时会用库内作品的剧照拼出封面
 * （`web/src/pages/librariesPageModel.ts` 的 `getLibraryArtworks`），App 走同一套
 * 兜底规则，否则媒体库卡片只剩下纯色占位。
 */
object LibraryArtwork {

    fun cover(
        origin: String,
        library: LibraryDto,
        width: Int = 480,
        height: Int = 320,
    ): String? = cover(origin, library.coverUrl, library.cards, width, height)

    fun cover(
        origin: String,
        coverUrl: String,
        cards: List<SeriesCardDto>,
        width: Int = 480,
        height: Int = 320,
    ): String? {
        MediaImageUrls.backdrop(origin, coverUrl, width, height)?.let { return it }
        val card = cards.firstOrNull {
            it.rep.backdropUrl.isNotBlank() || it.rep.posterUrl.isNotBlank()
        } ?: return null
        return MediaImageUrls.backdrop(
            origin = origin,
            remote = card.rep.backdropUrl.ifBlank { card.rep.posterUrl },
            width = width,
            height = height,
        )
    }
}
