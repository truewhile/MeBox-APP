package app.mebox.android.core.reader

import app.mebox.android.core.log.DiagnosticsLog
import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.network.ApiResult
import app.mebox.android.core.network.ReaderBookDto
import app.mebox.android.core.network.ReaderChapterContentDto
import app.mebox.android.core.network.ReaderChapterDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** 当前章 + 后续五个可读章。卷标题不占窗口，接近书尾时自然缩小。 */
internal fun readerPreloadIndexes(chapters: List<ReaderChapterDto>, current: Int): List<Int> {
    if (current !in chapters.indices) return emptyList()
    return (current..chapters.lastIndex).asSequence()
        .filter { !chapters[it].isVolume }.take(6).toList()
}

/**
 * 阅读页与后台听书共享的滚动预加载器（每个账号的 ReaderRepository 一份）。
 *
 * 当前章由前台优先加载，随后才启动预取。以最多两个并发解析窗口内的正文/
 * 媒体地址，同时按章节顺序下载已就绪的图片和普通音轨；首屏与起播不等待下载。
 * 完整缓存可直接复用；带票的在线媒体只在内存短存，不把书源请求头写进磁盘。
 * HLS / 转码音轨只预取地址，不把播放清单冒充完整离线音频。
 */
class ReaderChapterPreloader(
    private val cache: ReaderCacheStore,
    private val account: AccountScope,
    private val origin: String,
    private val loadContent: suspend (String, Int) -> ApiResult<ReaderChapterContentDto>,
    private val enabled: Boolean = true,
    private val isSessionCurrent: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class BookKey(val id: String, val source: String, val url: String)
    private data class Session(
        val book: BookKey,
        val chapters: List<ReaderChapterDto>,
        val generation: ReaderCacheGeneration,
    )
    private data class Entry(val content: ReaderChapterContentDto, val savedAt: Long)

    private val lock = Any()
    private val storageOwner = "preloader:${System.identityHashCode(this)}"
    private val accountGeneration = cache.generation(account).account
    private var session: Session? = null
    private var request = 0L
    private var job: Job? = null
    private val entries = mutableMapOf<Int, Entry>()

    private fun key(book: ReaderBookDto) = BookKey(book.id, book.origin, book.bookUrl)
    private fun active() = enabled && isSessionCurrent() &&
        cache.generation(account).account == accountGeneration

    /** 切章优先读完整本机资源，其次读刚预取的在线结果，不等待后台下载完成。 */
    suspend fun get(book: ReaderBookDto, index: Int): ReaderChapterContentDto? {
        if (!active()) return null
        val generation = cache.generation(account, book.id)
        val memory = synchronized(lock) {
            val current = session
            if (current?.book == key(book) && current.generation == generation) {
                entries[index]?.takeIf { clock() - it.savedAt in 0 until ONLINE_TTL_MS }?.content
            } else null
        }
        val local = withContext(ioDispatcher) {
            if (cache.hasChapter(account, book.id, index)) cache.read(account, book.id, index) else null
        }
        if (!active() || cache.generation(account, book.id) != generation) return null
        return local ?: memory
    }

    /** 每次切章取消过期任务，保留重叠窗口；返回 Job 方便测试等待预加载完成。 */
    fun preload(
        scope: CoroutineScope,
        book: ReaderBookDto,
        chapters: List<ReaderChapterDto>,
        current: Int,
        content: ReaderChapterContentDto,
    ): Job? {
        if (!active()) return null
        val targets = readerPreloadIndexes(chapters, current)
        if (targets.isEmpty()) return null
        val next = Session(key(book), chapters.toList(), cache.generation(account, book.id))
        return synchronized(lock) {
            job?.cancel()
            if (session != next) entries.clear()
            session = next
            entries.keys.retainAll(targets.toSet())
            entries[current] = Entry(content, clock())
            val token = ++request
            scope.launch {
                // 维护窗口会撤销旧写入；保留本轮返回的新代次，但绝不能越过用户清理产生的代次。
                val updated = withContext(ioDispatcher) {
                    if (!valid(token, next)) return@withContext null
                    val generation = cache.maintainWindow(account, book, chapters, current, next.generation, owner = storageOwner)
                    next.copy(generation = generation)
                } ?: return@launch
                synchronized(lock) {
                    if (request == token && session == next) session = updated
                }
                if (!valid(token, updated)) return@launch
                // 地址先到内存，媒体下载慢也不阻塞换章时复用已解析的结果。
                val semaphore = Semaphore(2)
                coroutineScope {
                    val resolved = targets.map { index ->
                        async {
                            semaphore.withPermit {
                                try {
                                    if (!valid(token, updated)) return@withPermit null
                                    val ready = get(book, index) ?: when (val result = loadContent(book.id, index)) {
                                        is ApiResult.Success -> result.value
                                        else -> null
                                    }
                                    ensureActive()
                                    if (ready != null && valid(token, updated)) {
                                        synchronized(lock) {
                                            val previous = entries[index]
                                            if (request == token && (previous?.content != ready ||
                                                    clock() - previous.savedAt !in 0 until ONLINE_TTL_MS)) {
                                                entries[index] = Entry(ready, clock())
                                            }
                                        }
                                        index to ready
                                    } else null
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    DiagnosticsLog.w("ReaderPreload", "章节预取失败 index=$index", error)
                                    null
                                }
                            }
                        }
                    }
                    // 不等最慢那章：当前章和较近章节一就绪就下载，后面的解析并发继续。
                    for (deferred in resolved) {
                        val (index, ready) = deferred.await() ?: continue
                        ensureActive()
                        if (!valid(token, updated)) return@coroutineScope
                        try {
                            if (withContext(ioDispatcher) { cache.hasChapter(account, book.id, index) }) continue
                            ReaderMediaHeaders.registerChapter(
                                ready.mediaDirect, ready.directHeaders, ready.tracks, ready.images, ready.content,
                            )
                            cache.save(account, book.id, index, ready, origin, updated.generation, kind = ReaderCacheKind.AUTOMATIC)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            // 一章失败不影响后续章，也不把后台预取错误显示成当前章播放失败。
                            DiagnosticsLog.w("ReaderPreload", "章节资源预加载失败 index=$index", error)
                        }
                    }
                }
            }.also { job = it }
        }
    }

    private fun valid(token: Long, expected: Session): Boolean =
        active() && cache.generation(account, expected.book.id) == expected.generation &&
            synchronized(lock) { request == token && session == expected }

    /** 换源、清缓存、停止听书或离开普通阅读时丢弃在途任务及在线票。 */
    fun cancel() = synchronized(lock) {
        request++
        job?.cancel()
        job = null
        session = null
        entries.clear()
        cache.releaseCurrent(storageOwner)
    }

    private companion object {
        const val ONLINE_TTL_MS = 5 * 60 * 1_000L
    }
}
