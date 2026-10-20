package app.mebox.android.core.reader

import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.network.ApiResult
import app.mebox.android.core.network.ReaderBookDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一次只发送一个事件；调用方在 IO 执行，取消/失败不丢待同步记录。 */
class ReaderProgressSync(
    private val store: ReaderProgressStore,
    private val scope: AccountScope,
    private val loadBooks: suspend () -> ApiResult<List<ReaderBookDto>>,
    private val send: suspend (ReaderProgress) -> ApiResult<Unit>,
) {
    private val mutex = Mutex()

    suspend fun flush() = mutex.withLock {
        if (store.pending(scope).isEmpty()) return@withLock
        val books = (loadBooks() as? ApiResult.Success)?.value?.associateBy { it.id }
            ?: return@withLock
        for (event in store.pending(scope)) {
            val book = books[event.bookId] ?: continue
            if (book.durChapterTime > event.updatedAtMs) {
                store.discard(scope, event)
                continue
            }
            // 一个 flush 等待书架或上一章请求时，本地位置可能又变了。
            if (store.load(scope, event.bookId)?.eventId != event.eventId) continue
            when (send(event)) {
                is ApiResult.Success -> store.acknowledge(scope, event)
                else -> return@withLock
            }
        }
    }
}
