package app.mebox.android.core.reader

import app.mebox.android.core.network.ReaderSourceDto

/**
 * 搜索范围：对齐网页端「搜索范围」弹窗（legado `SearchScopeDialog`）。
 *
 * 空集合表示「全部启用书源」，与后端 `/api/reader/search` 的 `source_ids`
 * 空数组语义一致，这样以后新增的书源会自动纳入搜索。保存的 ID 里如果包含
 * 已删除或已停用的书源会被忽略；全部失效时退回「全部启用书源」。
 */
object ReaderSearchScope {

    const val ALL_LABEL = "全部书源"

    data class Group(val name: String, val sources: List<ReaderSourceDto>)

    /** 当前启用书源的 ID，顺序与服务端下发顺序一致。 */
    fun enabledIds(sources: List<ReaderSourceDto>): List<String> =
        sources.filter { it.enabled }.map { it.id }

    /**
     * 已选范围里仍然有效的书源 ID。
     * 书源列表尚未加载时无法判断有效性，原样返回已保存的 ID。
     */
    fun effectiveIds(saved: Set<String>, sources: List<ReaderSourceDto>): List<String> {
        if (saved.isEmpty()) return emptyList()
        val enabled = enabledIds(sources)
        if (enabled.isEmpty()) return saved.toList()
        return enabled.filter { it in saved }
    }

    /** 是否等同于「全部启用书源」：没保存过，或保存的书源全部失效。 */
    fun isAll(saved: Set<String>, sources: List<ReaderSourceDto>): Boolean {
        if (saved.isEmpty()) return true
        val enabled = enabledIds(sources)
        if (enabled.isEmpty()) return false
        return enabled.none { it in saved }
    }

    /** 搜索页上的范围文案：「全部书源」或「已选 N 个书源」。 */
    fun label(saved: Set<String>, sources: List<ReaderSourceDto>): String =
        if (isAll(saved, sources)) {
            ALL_LABEL
        } else {
            "已选 ${effectiveIds(saved, sources).size} 个书源"
        }

    /** 传给 `source_ids` 的值；「全部」时返回 null，让后端按默认范围搜索。 */
    fun requestIds(saved: Set<String>, sources: List<ReaderSourceDto>): List<String>? =
        if (isAll(saved, sources)) null else effectiveIds(saved, sources)

    /**
     * 打开弹层时的初始勾选；null 表示「全部启用书源」。
     * 已保存的子集在书源列表加载完成前也原样保留，避免闪一下「全部」。
     */
    fun initialSelection(saved: Set<String>, sources: List<ReaderSourceDto>): Set<String>? {
        if (saved.isEmpty() || isAll(saved, sources)) return null
        return effectiveIds(saved, sources).toSet()
    }

    /**
     * 确认时把勾选收敛成可持久化的集合。
     * 覆盖全部启用书源时存空集合，保留「新增书源自动纳入」的语义。
     */
    fun confirmSelection(selected: Set<String>, sources: List<ReaderSourceDto>): Set<String> {
        val enabled = enabledIds(sources)
        val picked = selected.filter { it in enabled }.toSet()
        return if (enabled.isEmpty() || picked.size >= enabled.size) emptySet() else picked
    }

    /** 按分组聚合并保持书源原顺序（服务端已按 customOrder 排好）。 */
    fun groups(sources: List<ReaderSourceDto>, keyword: String = ""): List<Group> {
        val needle = keyword.trim().lowercase()
        val visible = if (needle.isEmpty()) {
            sources
        } else {
            sources.filter { source ->
                source.name.lowercase().contains(needle) ||
                    source.group.lowercase().contains(needle)
            }
        }
        val buckets = LinkedHashMap<String, MutableList<ReaderSourceDto>>()
        visible.forEach { source ->
            val name = source.group.trim().ifBlank { "未分组" }
            buckets.getOrPut(name) { mutableListOf() } += source
        }
        return buckets.map { (name, items) -> Group(name, items) }
    }

    /** 整组勾选 / 取消；组内已停用的书源不参与。 */
    fun toggleGroup(selected: Set<String>, group: Group): Set<String> {
        val ids = group.sources.filter { it.enabled }.map { it.id }
        if (ids.isEmpty()) return selected
        val next = selected.toMutableSet()
        if (ids.all { it in next }) {
            ids.forEach(next::remove)
        } else {
            next.addAll(ids)
        }
        return next
    }

    /** 全选 / 清空只作用于当前可见的已启用书源。 */
    fun toggleVisible(selected: Set<String>, visible: List<ReaderSourceDto>, select: Boolean): Set<String> {
        val ids = enabledIds(visible)
        val next = selected.toMutableSet()
        if (select) next.addAll(ids) else next.removeAll(ids.toSet())
        return next
    }
}
