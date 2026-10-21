package app.mebox.android.core.media

import app.mebox.android.core.network.LibraryDto
import app.mebox.android.core.network.SeriesCardDto

/**
 * 媒体库卡片的封面选择。
 *
 * 优先用服务端生成的拼图封面（`cover_image_url`）：它和 Web / Emby 客户端
 * 看到的是同一张图（库内成员海报竖切）。只有旧服务端没有这个字段时才回退
 * 到 `cover_url`，再退到用预览卡片拼一张，避免媒体库卡片只剩纯色占位。
 */
object LibraryArtwork {

    fun cover(
        origin: String,
        library: LibraryDto,
        width: Int = 480,
        height: Int = 320,
    ): String? {
        MediaImageUrls.backdrop(origin, library.coverImageUrl, width, height)?.let { return it }
        return cover(origin, library.coverUrl, library.cards, width, height)
    }

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
