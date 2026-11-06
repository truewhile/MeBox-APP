package app.mebox.android.core.reader

/** 同书普通预加载与本地软解文件合并展示；不重复计算 file: 直读资源。 */
fun mergeReaderBookStorage(vararg groups: List<ReaderBookStorage>): List<ReaderBookStorage> =
    groups.flatMap { it }.groupBy { it.bookId }.map { (id, rows) ->
        ReaderBookStorage(
            bookId = id,
            name = rows.firstOrNull { it.name.isNotBlank() && it.name != id }?.name ?: rows.first().name,
            automaticBytes = rows.sumOf { it.automaticBytes },
            downloadBytes = rows.sumOf { it.downloadBytes },
            automaticChapters = rows.sumOf { it.automaticChapters },
            downloadChapters = rows.sumOf { it.downloadChapters },
        )
    }.filter { it.totalBytes > 0 }.sortedWith(compareByDescending<ReaderBookStorage> { it.totalBytes }.thenBy { it.bookId })
