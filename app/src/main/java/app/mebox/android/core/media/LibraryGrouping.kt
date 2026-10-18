package app.mebox.android.core.media

import app.mebox.android.core.network.LibraryDto
import app.mebox.android.core.network.LibraryTagDto

/**
 * 媒体库标签栏的一项。
 *
 * [name] 为 `null` 表示「全部」；其余项与用户自定义标签同名。标签只做
 * 成员筛选，展示顺序仍由置顶与媒体库自身的 `sort_order` 决定。
 */
data class LibraryTagTab(
    val name: String?,
    val label: String,
    val count: Int,
)

/**
 * 媒体库的置顶排序与标签分组。
 *
 * 规则与网页端保持一致：
 * - 置顶库整体排在最前，组内保持服务端返回的手动顺序（`sort_order`）；
 * - 未置顶的库保持原有顺序；
 * - 标签只决定哪些库参与展示，不改变库的先后。
 */
object LibraryGrouping {

    /** 置顶库整体在前，其余保持服务端返回顺序。 */
    fun orderByPinned(
        libraries: List<LibraryDto>,
        pinnedIds: List<String>,
    ): List<LibraryDto> {
        if (libraries.size < 2 || pinnedIds.isEmpty()) return libraries
        val pinned = pinnedIds.toHashSet()
        val head = ArrayList<LibraryDto>(libraries.size)
        val tail = ArrayList<LibraryDto>(libraries.size)
        for (library in libraries) {
            if (library.id in pinned) head += library else tail += library
        }
        if (head.isEmpty() || tail.isEmpty()) return libraries
        return head + tail
    }

    /**
     * 标签栏模型：首项固定为「全部」，其后按服务端保存的标签顺序追加。
     * 计数只统计当前实际存在（可见）的媒体库，避免标签里残留旧 ID 时
     * 显示一个永远点不出内容的数字。
     */
    fun tabs(
        libraries: List<LibraryDto>,
        tags: List<LibraryTagDto>,
    ): List<LibraryTagTab> {
        val all = LibraryTagTab(name = null, label = "全部", count = libraries.size)
        if (tags.isEmpty()) return listOf(all)
        val available = libraries.mapTo(HashSet()) { it.id }
        val tabs = ArrayList<LibraryTagTab>(tags.size + 1)
        tabs += all
        for (tag in tags) {
            tabs += LibraryTagTab(
                name = tag.name,
                label = tag.name,
                count = tag.libraryIds.count { it in available },
            )
        }
        return tabs
    }

    /** 按标签筛选媒体库；[tagName] 为空或「全部」时原样返回。 */
    fun filterByTag(
        libraries: List<LibraryDto>,
        tags: List<LibraryTagDto>,
        tagName: String?,
    ): List<LibraryDto> {
        if (tagName.isNullOrBlank()) return libraries
        val tag = tags.firstOrNull { it.name == tagName } ?: return emptyList()
        val ids = tag.libraryIds.toHashSet()
        if (ids.isEmpty()) return emptyList()
        return libraries.filter { it.id in ids }
    }

    /** 选中的标签已被删除时回落到「全部」。 */
    fun resolveTag(
        tags: List<LibraryTagDto>,
        selected: String?,
    ): String? {
        if (selected.isNullOrBlank()) return null
        return selected.takeIf { name -> tags.any { it.name == name } }
    }
}
