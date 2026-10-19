package app.mebox.android.core.media

import app.mebox.android.core.network.WatchHistoryDto

/**
 * 详情页「继续播放」要接哪一条记录。
 *
 * 服务端的 `/api/watch-history` 按 `watched_at` 倒序返回，因此一部剧里
 * 真正最新的那条就是列表中第一个落在本剧集集合里的记录。把这段判断抽成
 * 纯函数，避免详情页被代表条目的 `/playback/{id}/resume` 绑死：代表条目
 * 可能是 OVA 或特别篇，它自己的续播进度并不代表用户最近看的那一集。
 */
object MediaContinue {

    /**
     * 从观看历史里挑出本剧最新的一条记录。
     *
     * [episodeIds] 应包含详情页代表条目和 `/media/{id}/episodes` 返回的全部
     * 剧集 ID；只用 ID 匹配，避免同名剧集或不同季的标题干扰。
     * [history] 必须按最新在前排序（服务端契约），空列表或没有命中时返回 null。
     */
    fun latest(
        history: List<WatchHistoryDto>,
        episodeIds: Collection<String>,
    ): WatchHistoryDto? {
        if (history.isEmpty() || episodeIds.isEmpty()) return null
        val ids = episodeIds.filter { it.isNotBlank() }.toSet()
        if (ids.isEmpty()) return null
        return history.firstOrNull { it.mediaId in ids }
    }
}
